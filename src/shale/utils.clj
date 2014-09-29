(ns shale.utils)

(defn prn-tee
  ([obj] (prn-tee nil obj ))
  ([prefix obj] (if prefix (prn prefix)) (prn obj) obj))

(defn assoc-fn [m k f]
  (assoc m k (f (get m k))))

