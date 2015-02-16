(ns shale.sessions
  (:require [clojure.set :refer [rename-keys]]
            [clojure.string :as string]
            [clojure.walk :refer :all]
            [clojure.core.match :refer [match]]
            [clj-dns.core :refer [dns-lookup]]
            [clj-webdriver.remote.driver :as remote-webdriver]
            [clj-webdriver.taxi :refer [current-url quit]]
            [taoensso.carmine :as car]
            [org.bovinegenius  [exploding-fish :as uri]]
            [schema.core :as s]
            [camel-snake-kebab.core :refer :all]
            [taoensso.timbre :as timblre :refer [info warn error debug]]
            [shale.nodes :as nodes :refer [Node]]
            [shale.utils :refer :all]
            [shale.redis :refer :all]
            [shale.webdriver :refer [new-webdriver resume-webdriver to-async]])
  (:import org.openqa.selenium.WebDriverException
           org.xbill.DNS.Type))

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

(defn is-ip? [s]
  (re-matches #"(?:\d{1,3}\.){3}\d{1,3}" s))

(def Session
  "A schema for a session spec."
  {:id                            s/Str
   (s/optional-key :tags)        [s/Str]
   (s/optional-key :reserved)     s/Bool
   (s/optional-key :current-url)  s/Str
   (s/optional-key :browser-name) s/Str
   (s/optional-key :node)         Node})

(def Capabilities {s/Any s/Any})

(def Requirement
  "A schema for a session requirement."
  (s/either
    (s/pair :session-tag  "type"  s/Str        "tag")
    (s/pair :node-tag     "type"  s/Str        "tag")
    (s/pair :reserved     "type"  s/Str        "reserved")
    (s/pair :session-id   "type"  s/Str        "session id")
    (s/pair :node-id      "type"  s/Str        "node id")
    (s/pair :browser-name "type"  s/Str        "browser")
    (s/pair :current-url  "type"  s/Str        "url")
    (s/pair :not          "type"  Requirement  "requirement")
    (s/pair :and          "type" [Requirement] "requirements")
    (s/pair :or           "type" [Requirement] "requirements")))

(defn maybe-bigdec [x]
  (try (bigdec x) (catch NumberFormatException e nil)))

(def DecString
  "A schema for a string that is convertable to bigdec."
  (s/pred #(boolean (maybe-bigdec %)) 'decimal-string))

(def Score
  "A schema for a session score rule."
  {:weight  DecString
   :require Requirement})

(defn resolve-host [host]
  (info (format "Resolving host %s..." host))
  (if (is-ip? host)
    host
    (if-let [resolved (first ((dns-lookup host Type/A) :answers))]
        (string/replace (str (.getAddress resolved)) "/" "")
        (if-let [resolved
                 (try
                   (java.net.InetAddress/getByName host)
                   (catch java.net.UnknownHostException e nil))]
          (.getHostAddress resolved)
          (do
            (let [message (format "Unable to resolve host %s" host)]
              (warn message)
              (throw
                (ex-info message
                         {:user-visible true :status 500}))))))))

(defn host-resolved-url [url]
  (let [u (if (string? url) (uri/uri url) url)]
    (assoc u :host (resolve-host (uri/host u)))))

(def OldRequirements
  {(s/optional-key :browser-name)  s/Str
   (s/optional-key :reserved)      s/Bool
   (s/optional-key :tags)          [s/Str]
   (s/optional-key :node)          {:url s/Str}})

(defn matches-requirements [session-model requirements]
  (s/validate OldRequirements requirements)
  (let [exact-match-keys [:browser-name :reserved]
        resolved-nodes (map (comp str host-resolved-url)
                            (filter identity
                                    (map #(get-in % [:node :url])
                                         [session-model requirements])))]
    (info "Checking session requirements..."
         session-model
         requirements)
    (info "Resolved node hosts..." resolved-nodes)
    (and
      (every? #(apply clojure.set/subset? %)
              [(map #(into #{} (% :tags))
                       [requirements session-model])
               (map #(into #{} (select-keys % exact-match-keys))
                    [requirements session-model])])
      (if (> (count resolved-nodes) 0)
        (apply = resolved-nodes)
        true))))

(defn matches-requirement [requirement session-model]
  (let [arg (second requirement)]
    (match (first requirement)
      :session-tag  (some #{arg} (session-model :tags))
      :node-tag     (some #{arg} (get-in session-model [:node :tags]))
      :session-id   (= arg (session-model :id))
      :node-id      (= arg (get-in session-model [:node :id]))
      :reserved     (= arg (session-model :reserved))
      :browser-name (= arg (session-model :browser-name))
      :current-url  (= arg (session-model :current-url))
      :not          (not     (matches-requirement arg session-model))
      :and          (every? #(matches-requirement % session-model) arg)
      :or           (some   #(matches-requirement % session-model) arg))))

(declare view-model view-models resume-webdriver-from-id destroy-session)

(defn session-ids []
  (with-car* (car/smembers session-set-key)))

(defn webdriver-go-to-url [wd url]
  "Asynchronously point a webdriver to a url.
  Return nil or :webdriver-is-dead."
  (try (do (to-async wd url) nil)
    (catch WebDriverException e :webdriver-is-dead)))

(defn session-go-to-url [session-id url]
  "Asynchronously point a session to a url.
  Return nil or :webdriver-is-dead."
  (webdriver-go-to-url (resume-webdriver-from-id session-id) url))

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
  (s/validate (s/maybe Node ) node)
  (info (format "Modifing session %s, %s" session-id (str modifications)))
  (if (some #{session-id} (session-ids))
    (last
      (with-car*
        (if (if current-url
              (let [okay (not (session-go-to-url session-id current-url))]
                (when (not okay) (destroy-session session-id))
                okay)
              true)
            (let [sess-key (session-key session-id)
                  sess-tags-key (session-tags-key session-id)]
              (doall
                (map #(car/hset sess-key (key %1) (val %1))
                     (select-keys modifications
                                  [:reserved :current-url :browser-name :node])))
              (car/del sess-tags-key)
              (doall (map #(car/sadd sess-tags-key %) (or tags []))))
            nil)
        (car/return (view-model session-id))))
    nil))

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
  (s/validate (s/maybe Node ) node)
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
        (new-webdriver node-url capabilities)
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

(def get-or-create-defaults
  {:browser-name "firefox"
   :tags []
   :reserved false})
(s/validate OldGetOrCreateArg get-or-create-defaults)

(defn get-or-create-session [arg]
  (let [arg (s/validate OldGetOrCreateArg (merge get-or-create-defaults arg))]
    (with-car*
      (car/return
        (or
          (if (arg :force-create)
            (create-session
              (rename-keys arg
                           {:reserve-after-create :reserved})))
          (if-let [candidate (->> (view-models)
                                  (filter #(matches-requirements % (dissoc arg :reserve-after-create)))
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

(defn resume-webdriver-from-id [session-id]
  (if-let [model (view-model session-id)]
    (apply resume-webdriver

           (map-indexed (fn [index e] (if (= index 2) {"browserName" e} e))
                        [(model :id)
                         (get-in model [:node :url])
                         :browser-name]))))

(defn destroy-session [session-id]
  (with-car*
    (info (format "Destroying sessions %s..." session-id))
    (car/watch session-set-key)
    (let [sess-key (session-key session-id)
          sess-tags-key (session-tags-key session-id)]
      (try
        (let [wd (resume-webdriver-from-id session-id)]
          (if wd
            (quit wd)))
        (catch WebDriverException e))
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
       (let [wd (resume-webdriver-from-id session-id)]
         (if (not (nil? wd))
           (try
             (car/hset sess-key :current-url (current-url wd))
             (catch WebDriverException e
               (destroy-session session-id)))
           (destroy-session session-id)))))
   true)

(defn refresh-sessions [ids]
  (with-car*
    (debug "Refreshing sessions...")
    (car/watch session-set-key)
    (doseq [session-id (or ids (session-ids))]
      (refresh-session session-id)))
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
