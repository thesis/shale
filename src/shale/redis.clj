(ns shale.redis
  (:require [taoensso.carmine :as car :refer (wcar)])
  (:use carica.core))

(def redis-conn  {:pool {} :spec {}})
(defmacro with-car*  [& body] `(car/wcar redis-conn ~@body))

(def redis-key-prefix "_shale")

(defn hset-all [k m]
  (doall #(car/hset (key %) (val %)) m))

(defn sset-all [k s]
  (car/del k)
  (doall (map #(car/sadd k %) s)))
