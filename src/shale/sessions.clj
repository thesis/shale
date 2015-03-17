(ns shale.sessions
  (:require [clojure.set :refer [rename-keys]]
            [clojure.core.match :refer [match]]
            [clojure.walk :refer :all]
            [clj-webdriver.remote.driver :as remote-webdriver]
            [clj-webdriver.taxi :refer [current-url quit]]
            [taoensso.carmine :as car]
            [schema.core :as s]
            [camel-snake-kebab.core :refer :all]
            [taoensso.timbre :as timbre :refer [info warn error debug]]
            [slingshot.slingshot :refer [try+ throw+]]
            [shale.configurer :refer [config]]
            [shale.nodes :as nodes :refer [NodeInRedis NodeView]]
            [shale.utils :refer :all]
            [shale.redis :refer :all]
            [shale.webdriver :refer [new-webdriver resume-webdriver to-async]])
  (:import org.openqa.selenium.WebDriverException))

(def session-set-key
  (apply str (interpose "/" [redis-key-prefix "sessions"])))
(def session-key-template
  (apply str (interpose "/" [redis-key-prefix "sessions" "%s"])))
(def session-tags-key-template
  (apply str (interpose "/" [redis-key-prefix "sessions" "%s" "tags"])))
(defn session-key [session-id]
  (format session-key-template session-id))
(defn session-tags-key [session-id]
  (format session-tags-key-template session-id))

(def Capabilities {s/Any s/Any})

(def SessionInRedis
  "A session, as represented in redis."
  {(s/optional-key :tags)        [s/Str]
   (s/optional-key :reserved)     s/Bool
   (s/optional-key :current-url)  s/Str
   (s/optional-key :browser-name) s/Str
   (s/optional-key :node-id)      s/Str})

(def SessionView
  "A session, as presented to library users."
  {(s/optional-key :id)           s/Str
   (s/optional-key :tags)        [s/Str]
   (s/optional-key :reserved)     s/Bool
   (s/optional-key :current-url)  s/Str
   (s/optional-key :browser-name) s/Str
   (s/optional-key :node)         NodeView})

(def TagChange
  "Add/remove a session/node tag"
  {:resource (s/either (literal-pred :session) (literal-pred :node))
   :action   (s/either (literal-pred :add) (literal-pred :remove))
   :tag      s/Str})

(def Requirement
  "A schema for a session requirement."
  (any-pair
    :session-tag   s/Str
    :node-tag      s/Str
    :reserved      s/Bool
    :session-id    s/Str
    :node-id       s/Str
    :browser-name  s/Str
    :current-url   s/Str
    :not           (s/recursive #'Requirement)
    :and           [(s/recursive #'Requirement)]
    :or            [(s/recursive #'Requirement)]))

(defn maybe-bigdec [x]
  (try (bigdec x) (catch NumberFormatException e nil)))

(def DecString
  "A schema for a string that is convertable to bigdec."
  (s/pred #(boolean (maybe-bigdec %)) 'decimal-string))

(def Score
  "A schema for a session score rule."
  {:weight  DecString
   :require Requirement})

(def OldRequirements
  {(s/optional-key :browser-name)  s/Str
   (s/optional-key :reserved)      s/Bool
   (s/optional-key :tags)          [s/Str]
   (s/optional-key :node)          {:url s/Str}})

(defn first-checked-schema
  "Return the first schema which validates a value. Useful as a dispatch
  function for multimethods."
  [value schemas]
  (doto (->> schemas
             (filter (fn [schema]
                       (try
                         (s/validate schema value)
                         schema
                         (catch RuntimeException e))))
             first)))

(defmacro defmultischema
  "Dispatches multimethods based on whether their args match the provided
  schemas.

  Each schema is validated against a vector of a method's args. The first that
  validates successfully is used as the dispatch value.

  For example,
  ```
  (defmultischema test-method :number [s/Num] :string [s/Str])
  (defmethod test-method :number [n]
    (prn \"number\"))
  (defmethod test-method :string [s]
    (prn \"string\"))
  ```"
  [multi-name & schemas]
  (if-not (even? (count schemas))
    (throw+ (str "defmultischema expects an even number of args (for "
                 "value / schema pairs)")))
  `(defmulti ~multi-name
     (fn [& args#]
       (let [schema-to-value# (->> ~(vec schemas)
                                   (partition 2)
                                   (map (comp vec reverse))
                                   (into {}))
             schemas# (keys schema-to-value#)
             matching-schema# (get schema-to-value#
                                   (first-checked-schema (vec args#) schemas#))]
         (if (nil? matching-schema#)
           (throw+ (str "No matching schema for multimethod "
                        (name '~multi-name)
                        " with args "
                        (vec args#)))
           matching-schema#)
         ))))

(defmultischema matches-requirement
  :old (s/pair s/Any "session" OldRequirements "requirements")
  :new (s/pair s/Any "session" Requirement "requirement"))

(s/defmethod matches-requirement :old :- s/Bool
  [session-model :- {(s/optional-key :id) s/Str
                     (s/optional-key :reserved) s/Bool
                     (s/optional-key :tags) [s/Str]
                     (s/optional-key :browser-name) s/Str
                     (s/optional-key :current-url) s/Str
                     (s/optional-key :node) {(s/optional-key :id) s/Str
                                             (s/optional-key :url) s/Str
                                             (s/optional-key :tags) [s/Str]}}
   requirements :- OldRequirements]
  (matches-requirement
    {:session-id (:id session-model)
     :session (merge
                (select-keys session-model
                           [:tags :reserved :browser-name :current-url])
                (if-let [node-id (get-in session-model [:node :id])]
                  {:node-id node-id}))
     :node (select-keys (:node session-model) [:tags :url])}
    [:and
     (vec
       (concat
         (map #(vec [:session-tag %])
              (get requirements :tags))
         (->> [:browser-name :reserved]
              (map #(vec [% (get requirements %)]))
              (filter identity)
              (into []))
         (if-let [node-id
                  (get-in requirements [:node :id])]
           [[:node-id node-id]])
         (map #(vec [:node-tag %])
              (get-in requirements [:node :tags]))))]))

(s/defmethod matches-requirement :new :- s/Bool
  [session-model :- {(s/optional-key :session-id) s/Str
                     (s/optional-key :session) SessionInRedis
                     (s/optional-key :node) NodeInRedis}
   requirement :- Requirement]
  (let [[req-type arg] requirement
        s session-model]
    (match req-type
      :session-tag  (some #{arg} (get-in s [:session :tags]))
      :node-tag     (some #{arg} (get-in s [:node :tags]))
      :session-id   (= arg (get-in s [:session-id]))
      :node-id      (= arg (get-in s [:session :node-id]))
      :reserved     (= arg (get-in s [:session :reserved]))
      :browser-name (= arg (get-in s [:session :browser-name]))
      :current-url  (= arg (get-in s [:session :current-url]))
      :not          (not     (matches-requirement s arg))
      :and          (every? #(matches-requirement s %) arg)
      :or           (some   #(matches-requirement s %) arg))))

(declare view-model view-models resume-webdriver-from-id destroy-session)

(defn session-ids []
  (with-car* (car/smembers session-set-key)))

(defn webdriver-timeout []
  (or
    (config :webdriver-timeout)
    1000))

(defn start-webdriver-timeout []
  (or
    (config :start-webdriver-timeout)
    5000))

(defn start-webdriver!
  "Create a webdriver. Optionally specify a timeout in milliseconds.

  Throws an exception on timeout, but blocks forever by default."
  [node capabilities & {:keys [timeout]}]
  (let [future-wd (future (new-webdriver node capabilities))
        wd (if (or (nil? timeout) (= 0 timeout))
               (deref future-wd)
               (deref future-wd timeout ::timeout))]
    (if (= wd ::timeout)
        (do
          (future-cancel future-wd)
          ;; TODO send to riemann
          (warn (format
                  "Timeout starting new webdriver on node %s"
                  node))
          (throw
            (ex-info "Timeout starting a new webdriver."
                     {:timeout timeout
                      :node-url node})))
        wd)))

(defn resume-webdriver-from-id
  "Resume and return a web driver from a session id. Optionally include a
  timeout, in milliseconds.

  Throws an exception on timeout, but blocks forever by default."
  [session-id & {:keys [timeout]}]
  (if-let [model (view-model session-id)]
    (let [node-url (get-in model [:node :url])
          resume-args (map-indexed
                        (fn [index e] (if (= index 2) {"browserName" e} e))
                        [(model :id)
                         node-url
                         :browser-name])
          future-wd (future (apply resume-webdriver resume-args))
          wd (if (or (nil? timeout) (= 0 timeout))
               (deref future-wd)
               (deref future-wd timeout ::timeout))]
      (if (= wd ::timeout)
        (do
          (future-cancel future-wd)
          ;; TODO send to riemann
          (warn (format
                  "Timeout resuming session %s after %d ms against node %s"
                  session-id
                  timeout
                  node-url))
          (throw
            (ex-info "Timeout resuming session."
                     {:session-id session-id
                      :timeout timeout
                      :node-url node-url})))
        wd))
    (throw
      (ex-info "Unknown session id." {:session-id session-id}))))

(defn webdriver-go-to-url [wd url]
  "Asynchronously point a webdriver to a url.
  Return nil or :webdriver-is-dead."
  (try (do (to-async wd url) nil)
    (catch WebDriverException e :webdriver-is-dead)))

(defn session-go-to-url [session-id url]
  "Asynchronously point a session to a url.
  Return nil or :webdriver-is-dead."
  (webdriver-go-to-url (resume-webdriver-from-id session-id) url))

(defn session-go-to-url-or-destroy-session [session-id url]
  "Asynchronously point a session to a url. Destroy the session if the
  webdriver is dead. Return true if everything seems okay."
  (let [okay (not (session-go-to-url session-id current-url))]
    (if-not okay (destroy-session session-id))
    okay))

(def ModifyArg
  "Modification to a session"
  (any-pair
    :change-tag TagChange
    :go-to-url s/Str
    :reserve s/Bool))

(defn save-session-tags-to-redis [session-id tags]
  (with-car* (sset-all (session-tags-key session-id) tags)))

(defn save-session-diff-to-redis [session-id session]
  "For any key present in the session arg, write that value to redis."
  (with-car*
    (hset-all
      (session-key session-id)
      (select-keys session
        [:reserved :current-url :browser-name :node]))
    (if (contains? session :tags)
      (save-session-tags-to-redis session-id (session :tags)))))

(defn modify-session
  [session-id {:keys [browser-name
                      node
                      reserved
                      tags
                      current-url]
               :or {browser-name nil
                    node nil
                    reserved nil
                    tags nil
                    current-url nil}
               :as modifications}]
  (info (format "Modifing session %s, %s" session-id (str modifications)))
  (when (some #{session-id} (session-ids))
    (if (or (not current-url)
            (session-go-to-url-or-destroy-session session-id current-url))
      (save-session-diff-to-redis
        session-id
        (select-keys modifications
          [:reserved :current-url :browser-name :node :tags])))
    (view-model session-id)))

(def CreateArg
  "Session-creation args"
  {(s/optional-key :browser-name) s/Str
   (s/optional-key :capabilities) {}
   (s/optional-key :node-id) s/Str})

(defn create-session
  [{:keys [browser-name
           node
           tags
           extra-desired-capabilities
           reserve-after-create
           current-url]
    :or {browser-name "firefox"
         node nil
         reserved false
         tags []
         extra-desired-capabilities nil
         reserve-after-create nil
         current-url nil}
    :as requirements}]
  (s/validate (s/maybe NodeInRedis) node)
  (info (format "Creating a new session.\nRequirements: %s"
                (str requirements)))
  (let [merged-reqs
        (merge {:node (select-keys (nodes/get-node {}) [:url :id])
                :tags tags}
               (select-keys requirements
                            [:browser-name
                             :tags
                             :current-url
                             :reserved
                             :reserve-after-create]))
        resolved-node-reqs
        (if (get-in merged-reqs [:node :url])
          (assoc-in-fn merged-reqs
                       [:node :url]
                       (comp str host-resolved-url))
          merged-reqs)
        defaulted-reqs
        (assoc-fn resolved-node-reqs
                  :reserved
                  (fn [v]
                    (or
                      (first
                        (filter #(not (nil? %))
                                [(resolved-node-reqs :reserve-after-create) v]))
                        false)))
        capabilities
        (into {}
              (map (fn [[k v]] [(->camelCaseString k) v])
                   (merge {:browser-name browser-name}
                          extra-desired-capabilities)))
        node-url (get-in defaulted-reqs [:node :url])
        _ (if (nil? node-url)
            (throw
              (ex-info "No suitable node found!"
                       {:user-visible true :status 500})))
        wd
        (start-webdriver!
          node-url
          capabilities
          :timeout (start-webdriver-timeout))
        session-id
        (remote-webdriver/session-id wd)]
    (last
      (with-car*
        (car/sadd session-set-key session-id)
        (car/return (modify-session session-id defaulted-reqs))))))

(def OldGetOrCreateArg
  {(s/optional-key :browser-name)  s/Str
   (s/optional-key :reserved)      s/Bool
   (s/optional-key :tags)          [s/Str]
   (s/optional-key :node)          {:url s/Str}
   (s/optional-key :current-url)   s/Str
   (s/optional-key :reserve-after-create)       s/Bool
   (s/optional-key :extra-desired-capabilities) Capabilities
   (s/optional-key :force-create)               s/Bool})

(s/def get-or-create-defaults :- OldGetOrCreateArg
  {:browser-name "firefox"
   :tags []
   :reserved false})

(def GetOrCreateArg
  "The arg to get-or-create-session."
  {(s/optional-key :require) Requirement  ; filter criteria
   (s/optional-key :score) [Score]        ; sort ranking
   (s/optional-key :create) CreateArg     ; how to create, if creating
   (s/optional-key :modify) [ModifyArg]}) ; modifications to perform always

(s/defn get-or-create-session [arg]
  (let [arg (s/validate OldGetOrCreateArg (merge get-or-create-defaults arg))]
    (with-car*
      (car/return
        (or
          (if (arg :force-create)
            (-> arg
                (rename-keys {:reserve-after-create :reserve-after-create})
                create-session)
            )
          (if-let [candidate (->> (view-models)
                                  (filter
                                    (fn [session-model]
                                      (matches-requirement
                                        session-model
                                        (dissoc arg :reserve-after-create))))
                                  first)]
            (if (or (arg :reserve-after-create) (arg :current-url))
              (modify-session (get candidate :id)
                              (rename-keys
                                (select-keys arg
                                             [:reserve-after-create
                                              :current-url
                                              :tags])
                                {:reserve-after-create :reserved}))
              candidate))
          (create-session arg))))))

(defn destroy-session [session-id]
  (with-car*
    (info (format "Destroying sessions %s..." session-id))
    (car/watch session-set-key)
    (let [sess-key (session-key session-id)
          sess-tags-key (session-tags-key session-id)]
      (try+
        (if-let [wd (resume-webdriver-from-id
                      session-id
                      :timeout (webdriver-timeout))]
          (quit wd))
        (catch WebDriverException e)
        (catch :timeout e))
      (car/srem session-set-key session-id)
      (car/del sess-key)
      (car/del sess-tags-key)))
  true)

(defn refresh-session [session-id]
   (with-car*
     (debug (format "Refreshing session %s..." session-id))
     (car/watch session-set-key)
     (let [sess-key (format session-key-template session-id)]
       (car/watch sess-key)
       (try+
         (if-let [wd (resume-webdriver-from-id
                       session-id
                       :timeout (webdriver-timeout))]
           (car/hset sess-key :current-url (current-url wd))
           (destroy-session session-id))
         (catch WebDriverException e
           (destroy-session session-id))
         (catch :timeout e
           (destroy-session session-id)))))
   true)

(defn refresh-sessions [ids]
  (with-car*
    (debug "Refreshing sessions...")
    (car/watch session-set-key)
    (doall
      (pmap refresh-session
            (or ids (session-ids)))))
  true)

(defn view-model [session-id]
  (let [[contents tags]
        (subvec
          (with-car*
            (let [sess-key (session-key session-id)
                  sess-tags-key (session-tags-key session-id)]
              (car/watch sess-key)
              (car/watch sess-tags-key)
              (if (with-car* (car/exists session-key))
                (do
                  (car/hgetall sess-key)
                  (car/smembers sess-tags-key))
                [nil nil])))
          2
          4)]
    (if (= (count contents) 0)
      nil
      (assoc
        (keywordize-keys
          (apply hash-map contents)) :tags tags :id session-id))))

(defn view-models []
  (with-car*
    (car/return
      (map view-model (session-ids)))))
