(ns shale.redis
  (:require [taoensso.carmine :as car :refer (wcar)])
  (:use [shale.configurer :only [config]]))

(def redis-conn-opts {:pool {} :spec (config :redis)})
(defmacro with-car* [& body] `(car/wcar redis-conn-opts ~@body))

(def redis-key-prefix "_shale")

(defn hset-all [k m]
  (doseq [[a b] m]
    (car/hset k a b)))

(defn sset-all [k s]
  (car/del k)
  (doseq [a s]
    (car/sadd k a)))
