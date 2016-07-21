(ns shale.sessions
  (:require [clojure.set :refer [rename-keys]]
            [clojure.core.match :refer [match]]
            [clojure.walk :refer :all]
            [clj-webdriver.remote.driver :as remote-webdriver]
            [clj-webdriver.taxi :refer [current-url quit]]
            [clj-http.client :as client]
            [cemerick.url :refer [url]]
            [taoensso.carmine :as car]
            [schema.core :as s]
            [camel-snake-kebab.core :refer :all]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [taoensso.timbre :as timbre :refer [info warn error debug]]
            [slingshot.slingshot :refer [try+ throw+]]
            [shale.nodes :as nodes :refer [NodeView]]
            [shale.utils :refer :all]
            [shale.redis :refer :all]
            [shale.selenium :as selenium]
            [shale.webdriver :refer [new-webdriver
                                     resume-webdriver
                                     to-async
                                     webdriver-capabilities]]
            [com.stuartsierra.component :as component]
            [io.aviso.ansi :refer [bold-red bold-green bold-blue]])
  (:import org.openqa.selenium.WebDriverException
           org.openqa.selenium.remote.UnreachableBrowserException
           org.xbill.DNS.Type
           org.apache.http.conn.ConnectTimeoutException
           java.net.ConnectException
           java.net.SocketTimeoutException
           java.util.concurrent.ExecutionException))

(s/defrecord SessionPool
  [redis-conn node-pool webdriver-timeout start-webdriver-timeout]
  component/Lifecycle
  (start [cmp]
    (info "Starting session pool...")
    cmp)
  (stop [cmp]
    (info "Stopping session pool...")
    cmp))

(defn new-session-pool [config]
  (map->SessionPool {:start-webdriver-timeout
                     (or (:start-webdriver-timeout config) 1000)
                     :webdriver-timeout
                     (or (:webdriver-timeout config) 1000)}))

(s/defschema Capabilities {s/Any s/Any})

(s/defschema SessionView
  "A session, as presented to library users."
  {(s/required-key :id)             s/Str
   (s/required-key :webdriver-id)   s/Str
   (s/required-key :tags)         #{s/Str}
   (s/required-key :reserved)       s/Bool
   (s/required-key :current-url)   (s/maybe s/Str)
   (s/required-key :browser-name)   s/Str
   (s/required-key :node)           NodeView
   (s/required-key :capabilities)   {s/Keyword s/Any}})

(s/defschema TagChange
  "Add/remove a session/node tag"
  {:resource (s/either (literal-pred :session) (literal-pred :node))
   :action   (s/either (literal-pred :add) (literal-pred :remove))
   :tag      s/Str})

(s/defschema Requirement
  "A schema for a session requirement."
  (any-pair
    :id            s/Str
    :session-id    s/Str
    :session-tag   s/Str
    :node-tag      s/Str
    :reserved      s/Bool
    :node-id       s/Str
    :browser-name  s/Str
    :current-url   s/Str
    :not           (s/recursive #'Requirement)
    :and           [(s/recursive #'Requirement)]
    :or            [(s/recursive #'Requirement)]))

(defn maybe-bigdec [x]
  (try (bigdec x) (catch NumberFormatException e nil)))

(s/defschema DecString
  "A schema for a string that is convertable to bigdec."
  (s/pred #(boolean (maybe-bigdec %)) 'decimal-string))

(s/defschema Score
  "A schema for a session score rule."
  {:weight  DecString
   :require Requirement})

(s/defschema OldRequirements
  {(s/optional-key :browser-name)  s/Str
   (s/optional-key :reserved)      s/Bool
   (s/optional-key :tags)          [s/Str]
   (s/optional-key :node)          {(s/optional-key :id)    s/Str
                                    (s/optional-key :url)   s/Str
                                    (s/optional-key :tags) [s/Str]}})

(s/defschema OldSessionViewModel
  {:id s/Str
   (s/optional-key :webdriver-id) s/Str
   (s/optional-key :reserved) s/Bool
   (s/optional-key :tags) #{s/Str}
   (s/optional-key :browser-name) s/Str
   (s/optional-key :current-url) (s/maybe s/Str)
   (s/optional-key :node) {(s/optional-key :id) s/Str
                           (s/optional-key :url) s/Str
                           (s/optional-key :tags) [s/Str]}
   (s/optional-key :capabilities) {s/Keyword s/Any}})

(s/defschema SessionAndNode
  {(s/optional-key :id)      s/Str
   (s/optional-key :session) SessionInRedis
   (s/optional-key :node)    NodeInRedis})

(s/defn ^:always-validate old->new-session-model :- SessionAndNode
  [session-model :- OldSessionViewModel]
  (merge
    (select-keys session-model [:id])
    {:session (select-keys
                session-model
                [:tags :reserved :browser-name :current-url :webdriver-id])
     :node (select-keys (:node session-model) [:tags :url :id])}))

(defmultischema matches-requirement
  :old (s/pair s/Any "session" OldRequirements "requirements")
  :new (s/pair s/Any "session" Requirement "requirement"))

(s/defmethod matches-requirement :old :- s/Bool
  [session-model :- OldSessionViewModel
   requirements :- OldRequirements]
  (debug
    (format "Testing session %s against requirements %s."
            session-model
            requirements))
  (matches-requirement
    (old->new-session-model session-model)
    [:and
     (vec
       (concat
         (map #(vec [:session-tag %])
              (get requirements :tags))
         (->> [:browser-name :reserved]
              (map #(vec [% (get requirements %)]))
              (filter (comp not nil? second))
              (into []))
         (if-let [node-id
                  (get-in requirements [:node :id])]
           [[:node-id node-id]])
         (map #(vec [:node-tag %])
              (get-in requirements [:node :tags]))))]))

(s/defmethod matches-requirement :new :- s/Bool
  [session-model :- SessionAndNode
   requirement :- Requirement]
  (debug
    (format "Testing session %s against requirement %s."
            session-model
            requirement))
  (let [[req-type arg] requirement
        s session-model]
    (match req-type
           :session-tag  (some #{arg} (get-in s [:session :tags]))
           :node-tag     (some #{arg} (get-in s [:node :tags]))
           :id           (= arg (get-in s [:id]))
           :webdriver-id (= arg (get-in s [:session :webdriver-id]))
           :node-id      (= arg (get-in s [:node :id]))
           :reserved     (= arg (get-in s [:session :reserved]))
           :browser-name (= arg (get-in s [:session :browser-name]))
           :current-url  (= arg (get-in s [:session :current-url]))
           :not          (not     (matches-requirement s arg))
           :and          (every? #(matches-requirement s %) arg)
           :or           (some   #(matches-requirement s %) arg))))

(declare view-model view-model-exists? view-models view-model-ids
         resume-webdriver-from-id destroy-session)

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
  "Resume and return a web driver from a session id (versus a webdriver id).
  Optionally include a timeout, in milliseconds.

  Throws an exception on timeout, but blocks forever by default."
  [pool id & {:keys [timeout]}]
  (if-let [model (view-model pool id)]
    (let [webdriver-id (:webdriver-id model)
          node-url (get-in model [:node :url])
          resume-args (map-indexed
                        (fn [index e] (if (= index 2) {"browserName" e} e))
                        [webdriver-id
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
                  webdriver-id
                  timeout
                  node-url))
          (throw
            (ex-info "Timeout resuming webdriver."
                     {:webdriver-id webdriver-id
                      :timeout timeout
                      :node-url node-url})))
        wd))
    (throw
      (ex-info "Unknown session id." {:session-id id}))))

(defn webdriver-go-to-url [wd url]
  "Asynchronously point a webdriver to a url.
  Return nil or :webdriver-is-dead."
  (try (do (to-async wd url) nil)
    (catch WebDriverException e :webdriver-is-dead)))

(s/defn ^:always-validate session-go-to-url
  [pool :- SessionPool
   id   :- s/Str
   url  :- s/Str]
  "Asynchronously point a session to a url.
  Return nil or :webdriver-is-dead."
  (let [webdriver-id (:webdriver-id (view-model pool id))]
    (webdriver-go-to-url (resume-webdriver-from-id pool id) url)))

(defn session-go-to-url-or-destroy-session [pool id url]
  "Asynchronously point a session to a url. Destroy the session if the
  webdriver is dead. Return true if everything seems okay."
  (let [okay (not (session-go-to-url pool id current-url))]
    (if-not okay (destroy-session pool id))
    okay))

(def ModifyArg
  "Modification to a session"
  (any-pair
    :change-tag TagChange
    :go-to-url s/Str
    :reserve s/Bool))

(s/defn ^:always-validate save-session-tags-to-redis
  [pool :- SessionPool
   id :- s/Str
   tags]
  (car/wcar (:redis-conn pool)
    (sset-all (session-tags-key id) tags)))

(s/defn ^:always-validate save-session-node-to-redis
  [pool :- SessionPool
   id :- s/Str
   node :- {s/Any s/Any}]
  (car/wcar (:redis-conn pool)
    (hset-all (session-node-key id) node)))

(s/defn ^:always-validate save-session-capabilities-to-redis
  [pool :- SessionPool
   id :- s/Str
   capabilities :- {s/Keyword s/Any}]
  (car/wcar (:redis-conn pool)
    (hset-all (session-capabilities-key id) capabilities)))

(s/defn ^:always-validate save-session-diff-to-redis
  "For any key present in the session arg, write that value to redis."
  [pool :- SessionPool
   id   :- s/Str
   session]
  (car/wcar (:redis-conn pool)
    (hset-all
      (session-key id)
      (select-keys session
        [:webdriver-id :reserved :current-url :browser-name]))
    (if (contains? session :tags)
      (save-session-tags-to-redis pool id (:tags session)))
    (if (contains? session :node)
      (save-session-node-to-redis pool id (:node session)))
    (if (contains? session :capabilities)
      (save-session-capabilities-to-redis pool id (:capabilities session)))))

(defn modify-session
  [pool id {:keys [browser-name
                   node
                   reserved
                   tags
                   current-url
                   webdriver-id
                   capabilities]
            :or {browser-name nil
                 node nil
                 reserved nil
                 tags nil
                 current-url nil
                 webdriver-id nil
                 capabilities {}}
            :as modifications}]
  (s/validate SessionPool pool)
  (s/validate s/Str id)
  (info (format "Modifying session %s, %s" id modifications))
  (when (view-model-exists? pool id)
    (if (or (not current-url)
            (session-go-to-url-or-destroy-session id current-url))
      (save-session-diff-to-redis
        pool
        id
        (select-keys modifications
          [:reserved :current-url :webdriver-id :browser-name :node :tags
           :capabilities])))
    (view-model pool id)))

(def CreateArg
  "Session-creation args"
  {(s/optional-key :browser-name) s/Str
   (s/optional-key :capabilities) {}
   (s/optional-key :node-id) s/Str})

(defn create-session
  [pool {:keys [browser-name
                node
                tags
                extra-desired-capabilities
                reserved
                current-url]
         :or {browser-name "firefox"
              node nil
              reserved false
              tags []
              extra-desired-capabilities nil
              current-url nil}
         :as requirements}]
  (s/validate SessionPool pool)
  (s/validate (s/maybe NodeView) node)
  (info (format "Creating a new session.\nRequirements: %s"
                (str requirements)))

  (if (= 0 (count (nodes/nodes-under-capacity (:node-pool pool))))
    (throw
      (ex-info "All nodes are over capacity!"
               {:user-visible true :status 503})))
  (let [node-req (merge (select-keys node [:url :tags :id])
                        (if (contains? node :url)
                          (assoc-in-fn
                            node
                            [:url]
                            (comp str host-resolved-url))
                          (or node {})))
        node (nodes/get-node (:node-pool pool) node-req)
        merged-reqs
        (merge {:node (select-keys node [:url :id])
                :tags tags}
               (select-keys requirements
                            [:browser-name
                             :tags
                             :current-url
                             :reserved]))
        defaulted-reqs
        (if (get-in merged-reqs [:node :url])
          (assoc-in-fn merged-reqs
                       [:node :url]
                       (comp str host-resolved-url))
          merged-reqs)
        capabilities
        (transform-keys ->camelCaseString
                        (merge {:browser-name browser-name}
                               extra-desired-capabilities))
        node-url (get-in defaulted-reqs [:node :url])
        _ (if (nil? node-url)
            (throw
              (ex-info "No suitable node found!"
                       {:user-visible true :status 503})))
        id (gen-uuid)
        wd (start-webdriver!
          node-url
          capabilities
          :timeout (:start-webdriver-timeout pool))
        webdriver-id
        (remote-webdriver/session-id wd)
        actual-capabilities
        (webdriver-capabilities wd)]
    (last
      (car/wcar (:redis-conn pool)
        (car/sadd session-set-key id)
        (car/return
          (modify-session pool
                          id
                          (merge {:webdriver-id webdriver-id
                                  :capabilities actual-capabilities}
                                 defaulted-reqs)))))))

(def OldGetOrCreateArg
  {(s/optional-key :browser-name)               s/Str
   (s/optional-key :reserved)                   s/Bool
   (s/optional-key :tags)                       [s/Str]
   (s/optional-key :node)                       {(s/optional-key :url)   s/Str
                                                 (s/optional-key :id)    s/Str
                                                 (s/optional-key :tags) [s/Str]}
   (s/optional-key :current-url)                s/Str
   (s/optional-key :reserve)                    s/Bool
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

(s/defn ^:always-validate get-or-create-session
  [pool :- SessionPool
   arg]
  (debug (format "Getting or creating a new session.\nRequirements %s" arg))
  (let [arg (s/validate OldGetOrCreateArg (merge get-or-create-defaults arg))]
    (car/wcar (:redis-conn pool)
      (car/return
        (or
          (if-not (:force-create arg)
            (if-let [candidate (->> (view-models pool)
                                    (filter
                                      (fn [session-model]
                                        (matches-requirement
                                          session-model
                                          (dissoc arg :reserve))))
                                    first)]
              (if (or (:reserve arg)
                      (:current-url arg))
                (modify-session pool
                                (get candidate :id)
                                (rename-keys
                                  (select-keys arg
                                               [:reserve
                                                :current-url
                                                :tags])
                                  {:reserve :reserved}))
                candidate)))
          (let [reserved (or (:reserve arg)
                             (:reserved arg))
                create-req (-> arg
                               (dissoc :reserve)
                               (assoc :reserved reserved))]
            (create-session pool create-req)))))))

(s/defn ^:always-validate destroy-webdriver!
  [pool         :- SessionPool
   webdriver-id :- s/Str
   node-url     :- s/Str
   immediately  :- s/Bool
   callback     :- (s/maybe (s/pred fn? "callback"))]
  (let [timeout (:webdriver-timeout pool)
        session-url (->> webdriver-id
                         (format "./session/%s")
                         (url node-url)
                         str)
        deferred (future
                   (try+
                     (client/delete session-url
                                    {:socket-timeout timeout
                                     :conn-timeout timeout})
                     (info (format "Destroyed webdriver %s on node %s."
                                   webdriver-id
                                   node-url))
                     (catch [:status 404] _
                       (error
                         (format (str "Got a 404 attempting to delete"
                                      " webdriver %s from node %s.")
                                 webdriver-id
                                 node-url)))
                     (catch [:status 500] _
                       (error
                         (format (str "Got a 500 attempting to delete"
                                      " webdriver %s from node %s.")
                                 webdriver-id
                                 node-url)))

                     (catch #(any-instance?
                               [ConnectTimeoutException
                                SocketTimeoutException
                                UnreachableBrowserException]) e
                       (error
                         (format (str "Timeout connecting to node %s"
                                      " to delete webdriver %s.")
                                 node-url
                                 webdriver-id)))
                     (catch ConnectException e
                       (error
                         (format (str "Error connecting to node %s"
                                      " to delete session %s.")
                                 node-url
                                 webdriver-id)))
                     (finally
                       (when-not (nil? callback)
                         (callback)))))]
    (when immediately
      (deref deferred))))

(defn destroy-session
  [pool id & {:keys [immediately] :or [immediately true]}]
  (s/validate SessionPool pool)
  (s/validate s/Str id)
  (car/wcar (:redis-conn pool)
    (info (format "Destroying session %s..." id))
    (car/watch session-set-key)
    (when (view-model-exists? pool id)
      (let [session (view-model pool id)
            webdriver-id (:webdriver-id session)
            node-url (get-in session [:node :url])
            _ (soft-delete-model! (:redis-conn pool) SessionInRedis id)
            hard-delete #(delete-model! (:redis-conn pool) SessionInRedis id)]
        (destroy-webdriver! pool
                            webdriver-id
                            node-url
                            (boolean immediately)
                            hard-delete))))
  true)

(def view-model-defaults {:tags #{}
                          :browser-name nil
                          :reserved false
                          :node {}
                          :current-url nil
                          :webdriver-id nil})

(s/defn ^:always-validate model->view-model :- SessionView
  [model :- (s/maybe SessionInRedis)]
  (some->> model
           (merge view-model-defaults)))

(s/defn ^:always-validate view-model-exists? :- s/Bool
  [pool :- SessionPool
   id   :- s/Str]
  (model-exists? (:redis-conn pool) SessionInRedis id))

(s/defn ^:always-validate view-model :- SessionView
  [pool :- SessionPool
   id   :- s/Str]
  (-> (model (:redis-conn pool) SessionInRedis id)
      model->view-model))

(s/defn ^:always-validate view-models :- [SessionView]
  [pool :- SessionPool]
  (let [redis-conn (:redis-conn pool)]
    (car/wcar redis-conn
      (car/return
        (map model->view-model (models redis-conn SessionInRedis))))))

(s/defn ^:always-validate view-model-ids :- [s/Str]
  [pool :- SessionPool]
  (map :id (view-models pool)))

(s/defn ^:always-validate view-model-by-webdriver-id :- SessionView
  "Return a view model with the corresponding webdriver id."
  [pool         :- SessionPool
   webdriver-id :- s/Str]
  (->> (view-models pool)
       (filter #(= (:webdriver-id %) webdriver-id))
       first))

(s/defn ^:always-validate refresh-session
  [pool :- SessionPool
   id   :- s/Str]
  (car/wcar (:redis-conn pool)
    (debug (format "Refreshing session %s..." id))
    (car/watch session-set-key)
    (let [sess-key (format session-key-template id)]
      (car/watch sess-key)
      (try+
        (if-let [wd (resume-webdriver-from-id
                      pool
                      id
                      :timeout (:webdriver-timeout pool))]
          (car/hset sess-key :current-url (current-url wd))
          (destroy-session pool id))
        (catch (any-instance? [WebDriverException
                               UnreachableBrowserException
                               ExecutionException
                               ConnectTimeoutException
                               SocketTimeoutException
                               ConnectException] %)  e
          (destroy-session pool id))
        (catch :timeout e
          (destroy-session pool id)))))
  true)

(def ^:private unmanaged-session-lock {})

(s/defn ^:always-validate destroy-unmanaged-sessions!
  "Destroy any webdrivers on managed nodes without backing Redis records."
  [pool :- SessionPool]
  (locking unmanaged-session-lock
    (let [redis-conn (:redis-conn pool)
          node-pool (:node-pool pool)]
      (car/wcar redis-conn
        (debug "Destroying unmanaged sessions...")
        (car/watch session-set-key)

        (let [known-webdrivers (->> (view-models pool)
                                    (map :webdriver-id)
                                    (into #{}))]
          (doall
            (->> (nodes/view-models node-pool)
                 (map :url)
                 (filter identity)
                 (pmap #(vector % (selenium/session-ids-from-node %)))
                 (map #(map (fn [id] [(first %) id]) (second %)))
                 (apply concat)
                 (pmap #(when-not (known-webdrivers (second %))
                          (destroy-webdriver! pool
                                              (second %)
                                              (first %)
                                              false
                                              nil))))))))))

(s/defn ^:always-validate refresh-sessions
  [pool :- SessionPool
   ids  :- [s/Str]]
  (let [redis-conn (:redis-conn pool)]
    (car/wcar redis-conn
              (debug "Refreshing sessions...")
              (car/watch session-set-key)
              (doall
                (pmap (partial refresh-session pool)
                      (or ids (view-model-ids pool))))
              (destroy-unmanaged-sessions! pool)))
  true)
