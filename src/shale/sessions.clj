(ns shale.sessions
  (:require [clj-webdriver.remote.driver :as remote-webdriver]
            [taoensso.carmine :as car :refer (wcar)])
  (:use [shale.webdriver :only [new-webdriver resume-webdriver to-async]]
        clojure.walk))

(def redis-conn  {:pool {} :spec {}})
(defmacro with-car*  [& body] `(car/wcar redis-conn ~@body))

(def redis-key-prefix "_shale")
(def session-set-key
  (apply str (interpose "/" [redis-key-prefix "sessions"])))
(def session-key-template
  (apply str (interpose "/" [redis-key-prefix "sessions" "%s"])))
(def session-tags-key-template
  (apply str (interpose "/" [redis-key-prefix "session" "%s" "tags"])))

(defn get-node []
  "http://localhost:5555/wd/hub")

(declare view-model)

(defn create-session [{:keys [browser-name
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
    (def defaulted-reqs
      (assoc
        (select-keys requirements [:browser-name :reserved :tags])
        :node
        (or node (get-node))))
    (def capabilities (merge {"browserName" browser-name}
                             extra-desired-capabilities))
    (def wd (new-webdriver (get-node) capabilities))
    (def session-id (remote-webdriver/session-id wd))
    (if current-url (to-async wd current-url) nil)
    (car/sadd session-set-key session-id)

    (def session-key (format session-key-template session-id))
    (def session-tags-key (format session-tags-key-template session-id))

    (doall
      (map #(car/hset session-key (key %1) (val %1))
           (select-keys defaulted-reqs
                        [:browser-name :node :reserved :current-url])))
    (car/del session-tags-key)
    (doall (map #(car/sadd session-tags-key %) (or tags []))))
  (view-model session-id))

(defn view-model [session-id]
  (let [[contents tags]
        (subvec
          (with-car*
            (let [session-key (format session-key-template session-id)
                  session-tags-key (format session-tags-key session-id)]
              (car/watch session-key)
              (car/watch session-tags-key)
              (if (car/exists session-key)
                (do
                  (car/hgetall session-key)
                  (car/smembers session-tags-key))
                nil))) 3 5)]
    (assoc (keywordize-keys (apply hash-map contents)) :tags tags)))

(defn view-models [session-ids]
  (with-car*
    (let [session-ids (or session-ids (car/smembers session-set-key))]
      (map view-model session-ids))))
