(ns shale.redis
  (:require [taoensso.carmine :as car :refer (wcar)])
  (:use [shale.configurer :only [config]]))

(def redis-conn  {:pool {} :spec (config :redis)})
(defmacro with-car*  [& body] `(car/wcar redis-conn ~@body))

(def redis-key-prefix "_shale")

(defn hset-all [k m]
  (doall #(car/hset (key %) (val %)) m))

(defn sset-all [k s]
  (car/del k)
  (doall (map #(car/sadd k %) s)))
