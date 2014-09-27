(ns shale.sessions
  (:require [clj-webdriver.remote.driver :as remote-webdriver]
            [taoensso.carmine :as car :refer (wcar)])
  (:use [shale.webdriver :only [new-webdriver resume-webdriver to-async]]
        clojure.walk
        [clj-webdriver.taxi :only [current-url quit]])
  (:import org.openqa.selenium.WebDriverException))

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

(defn prn-tee [obj]
  (prn obj)
  obj)

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
                            extra-desired-capabilities nil reserve-after-create nil
                            force-create nil
                            current-url nil}
                       :as requirements}]
  (let [defaulted-reqs
          (assoc
            (select-keys requirements [:browser-name :reserved :tags :current-url])
            :node
            (or node (get-node)))
          capabilities
          (merge {"browserName" browser-name}
                 extra-desired-capabilities)
          wd
          (new-webdriver (defaulted-reqs :node) capabilities)
          session-id
          (remote-webdriver/session-id wd)]
    (with-car*
      (if current-url (to-async wd current-url) nil)
        (car/sadd session-set-key session-id)
        (let [sess-key (session-key session-id)
              sess-tags-key (session-tags-key session-id)]
          (doall
            (map #(car/hset sess-key (key %1) (val %1))
                 (select-keys defaulted-reqs
                              [:browser-name :node :reserved :current-url])))
          (car/del sess-tags-key)
          (doall (map #(car/sadd sess-tags-key %) (or tags [])))))
      (view-model session-id)))

(defn resume-webdriver-from-id [session-id]
  (apply resume-webdriver
         (map-indexed (fn [index e] (if (= index 2) {"browserName" e} e))
             (map (view-model session-id) [:id :node :browser-name]))))

(defn destroy-session [session-id]
  (with-car*
    (car/watch session-set-key)
    (let [sess-key (session-key session-id)
          sess-tags-key (session-tags-key session-id)]
      (try
        (let [wd (resume-webdriver-from-id session-id)]
          (quit wd))
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
         (try
           (car/hset sess-key :current-url (current-url wd))
           (catch WebDriverException e
             (destroy-session session-id))))))
   true)

(defn refresh-sessions [session-ids]
  (with-car*
    (car/watch session-set-key)
    (doseq [session-id (or session-ids (with-car* (car/smembers session-set-key)))]
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

(defn view-models [session-ids]
  (map view-model (with-car* (or session-ids (car/smembers session-set-key)))))
