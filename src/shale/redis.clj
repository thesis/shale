(ns shale.redis
  (:require [taoensso.carmine :as car :refer (wcar) ]
            [schema.core :as s])
  (:use [shale.configurer :only [config]]))

(def redis-conn-opts {:pool {} :spec (config :redis)})
(defmacro with-car* [& body] `(car/wcar redis-conn-opts ~@body))

(defn hset-all [k m]
  (doseq [[a b] m]
    (car/hset k a b)))

(defn sset-all [k s]
  (car/del k)
  (doseq [a s]
    (car/sadd k a)))

;; key constants
(def redis-key-prefix "_shale")

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

(def node-set-key
  (apply str (interpose "/" [redis-key-prefix "nodes"])))

(def node-key-template
  (apply str (interpose "/" [redis-key-prefix "nodes" "%s"])))

(def node-tags-key-template
  (apply str (interpose "/" [redis-key-prefix "nodes" "%s" "tags"])))

(defn node-key [id]
  (format node-key-template id))

(defn node-tags-key [id]
  (format node-tags-key-template id))

;; in-database models
(def SessionInRedis
  "A session, as represented in redis."
  {(s/optional-key :tags)        [s/Str]
   (s/optional-key :reserved)     s/Bool
   (s/optional-key :current-url)  s/Str
   (s/optional-key :browser-name) s/Str
   (s/optional-key :node-id)      s/Str})

(def NodeInRedis
  "A node, as represented in redis."
  {(s/optional-key :url)          s/Str
   (s/optional-key :max-sessions) s/Int
   (s/optional-key :tags)        [s/Str]})
