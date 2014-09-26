(ns shale.sessions
  (:require [clj-webdriver.remote.driver :as remote-webdriver]
            [taoensso.carmine :as car :refer (wcar)])
  (:use [shale.webdriver :only [new-webdriver resume-webdriver to-async]]))

(def redis-conn  {:pool {} :spec {}})
(defmacro with-car*  [& body] `(car/wcar redis-conn ~@body))

(def redis-key-prefix "_shale")
(def session-set-key
  (str (interpose "/" [redis-key-prefix "sessions"])))
(def session-key-template
  (str (interpose "/" [redis-key-prefix "sessions" "%s"])))
(def session-tags-key-template
  (str (interpose "/" [redis-key-prefix "session" "%s" "tags"])))

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
                            reserved nil
                            tags nil
                            extra-desired-capabilities nil
                            reserve-after-create nil
                            force-create nil
                            current-url nil}
                       :as requirements}]
  (with-car*
    (def defaulted-reqs {})
    (def capabilities (merge {"browserName" browser-name}
                             extra-desired-capabilities))
    (def wd (new-webdriver (get-node) capabilities))
    (def session-id (remote-webdriver/session-id wd))
    (if current-url (to-async wd current-url) nil)
    (car/sadd session-set-key session-id)

    (def session-key (format session-key-template session-id))
    (def session-tags-key (format session-tags-key-template session-id))

    (doall
      (map (partial car/hset session-key)
           (select-keys defaulted-reqs
                        [:browser-name :node :reserved :current-url])))
    (car/del session-tags-key)
    (doall (map #(car/sadd session-tags-key %) (or tags []))))
  (view-model session-id))

(defn view-model [session-id]
  (with-car*
    (def session-key (format session-key-template session-id))
    (def session-tags-key (format session-tags-key session-id))
    (car/watch session-key)
    (car/watch session-tags-key)
    (if (car/exists session-key)
      (merge
        {:id session-id :tags (car/smembers session-tags-key)}
        (car/hgetall session-key))
      nil)))
