(ns shale.utils)

(defn prn-tee
  ([obj] (prn-tee nil obj ))
  ([prefix obj] (if prefix (prn prefix)) (prn obj) obj))

(defn assoc-fn [m k f]
  (assoc m k (f (get m k))))

(defn assoc-in-fn [m ks f]
  (assoc-in m ks (f (get-in m ks))))

(defn map-walk [f m]
  (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m))

(defn update-all
  "Like update-in, but for multiple key paths.

  (update-all {:a 1 :b 2 :c 3} [[:a] [:b]] inc) -> {:a 2 :b 3 :c 3}"
  [v paths f]
  (loop [m v
         ps paths]
    (if (> (count ps) 0)
      (recur (update-in m (first ps) f) (rest ps))
      m)))
