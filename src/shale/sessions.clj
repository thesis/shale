(ns shale.sessions
  (:require [clj-webdriver.remote.driver :as remote-webdriver]
            [taoensso.carmine :as car :refer (wcar)]
            [clojure.string :as string]
            [org.bovinegenius  [exploding-fish :as uri]])
  (:use shale.utils
        shale.nodes
        carica.core
        clojure.walk
        [shale.webdriver :only [new-webdriver resume-webdriver to-async]]
        [clj-webdriver.taxi :only [current-url quit]]
        [clj-dns.core :only [dns-lookup]]
        [clojure.set :only [rename-keys]])
  (:import org.openqa.selenium.WebDriverException
           org.xbill.DNS.Type
           [shale.nodes DefaultNodePool]))

(def redis-conn  {:pool {} :spec {}})
(defmacro with-car*  [& body] `(car/wcar redis-conn ~@body))

(def redis-key-prefix "_shale")
(def session-set-key
  (apply str (interpose "/" [redis-key-prefix "sessions"])))
(def session-key-template
  (apply str (interpose "/" [redis-key-prefix "sessions" "%s"])))
(def session-tags-key-template
  (apply str (interpose "/" [redis-key-prefix "session" "%s" "tags"])))
(defn session-key [session-id]
  (format session-key-template session-id))
(defn session-tags-key [session-id]
  (format session-tags-key-template session-id))

(deftype ConfigNodePool [])
(def node-pool (if (nil? (config :node-pool-config))
                 (DefaultNodePool. (or (config :node-list)
                                       ["http://localhost:5555/wd/hub"]))
                 (do
                   (extend ConfigNodePool
                     INodePool
                       (config :node-pool-config))
                   (ConfigNodePool.))))

(defn ^:private is-ip? [s]
  (re-matches #"(?:\d{1,3}\.){3}\d{1,3}" s))

(defn resolve-host [host]
  (if (is-ip? host)
    host
    (let [resolved (first ((dns-lookup host Type/A) :answers))]
      (if resolved
        (string/replace (str (.getAddress resolved)) "/" "")
        (throw
          (ex-info (format "Unable to resolve host %s." host)
                   {:user-visible true :status 500}))))))

(defn host-resolved-url [url]
  (let [u (if (string? url) (uri/uri url) url)]
    (assoc u :host (resolve-host (uri/host u)))))

(defn ^:private matches-requirements [session-model requirements]
  (let [exact-match-keys [:browser-name :reserved]]
    (and
      (every? #(apply clojure.set/subset? %)
              [(map #(hash-set (% :tags))
                       [requirements session-model])
               (map #(into #{} (select-keys % exact-match-keys))
                    [requirements session-model])])
      (apply = (map host-resolved-url
                    (filter identity
                            (map #(get % :node)
                                 [session-model requirements])))))))

(declare view-model view-models resume-webdriver-from-id destroy-session)

(defn session-ids []
  (with-car* (car/smembers session-set-key)))

(defn modify-session [session-id {:keys [browser-name
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
  (if (some #{session-id} (session-ids))
    (last
      (with-car*
        (if (if current-url
                (try
                  (to-async
                    (resume-webdriver-from-id session-id)
                    current-url)
                  true
                  (catch WebDriverException e
                    (destroy-session session-id)
                    nil))
                true)
            (let [sess-key (session-key session-id)
                  sess-tags-key (session-tags-key session-id)]
              (doall
                (map #(car/hset sess-key (key %1) (val %1))
                     (select-keys modifications (for [[k v] modifications
                                                      :when (and
                                                              (not (= k :tags))
                                                              (not (nil? v)))]
                                                     k))))
              (car/del sess-tags-key)
              (doall (map #(car/sadd sess-tags-key %) (or tags []))))
            nil)
        (car/return (view-model session-id))))
    nil))

(defn create-session [{:keys [browser-name
                              node
                              reserved
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
  (let [resolved-node-reqs
        (assoc-fn (merge {:node (get-node node-pool requirements)
                          :tags tags
                          :reserved reserved}
                         (select-keys requirements
                                      [:browser-name :reserved :tags :current-url]))
                  :node
                  (comp str host-resolved-url))
        defaulted-reqs
        (assoc-fn resolved-node-reqs
                  :reserved
                  (fn [v] (or v (resolved-node-reqs :reserve-after-create) false)))
        capabilities
        (merge {"browserName" browser-name}
               extra-desired-capabilities)
        wd
        (new-webdriver (defaulted-reqs :node) capabilities)
        session-id
        (remote-webdriver/session-id wd)]
    (last
      (with-car*
        (car/sadd session-set-key session-id)
        (car/return (modify-session session-id defaulted-reqs))))))

(defn get-or-create-session [{:keys [browser-name
                                     node
                                     reserved
                                     tags
                                     extra-desired-capabilities
                                     reserve-after-create
                                     force-create
                                     current-url]
                              :or {browser-name "firefox"
                                   node nil
                                   reserved false
                                   tags []
                                   extra-desired-capabilities nil
                                   reserve-after-create nil
                                   force-create nil
                                   current-url nil}
                              :as requirements}]
  (with-car*
    (car/return
      (or
        (and force-create (create-session
                            (rename-keys requirements
                                         {:reserve-after-create :reserved})))
        (if-let [candidate (first (filter #(matches-requirements % requirements)
                                          (view-models nil)))]
          (if (or reserve-after-create current-url)
            (modify-session (get candidate :id)
                            (rename-keys
                              (select-keys requirements
                                           [:reserve-after-create
                                            :current-url
                                            :tags])
                              {:reserve-after-create :reserved}))
            candidate))
        (create-session requirements)))))

(defn resume-webdriver-from-id [session-id]
  (if-let [model (view-model session-id)]
    (apply resume-webdriver
           (map-indexed (fn [index e] (if (= index 2) {"browserName" e} e))
               (map model [:id :node :browser-name])))))

(defn destroy-session [session-id]
  (with-car*
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

(defn view-models [ids]
  (with-car*
    (car/return
      (map view-model (session-ids)))))
