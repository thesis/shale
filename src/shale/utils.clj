(ns shale.utils
  (:require [clojure.walk]
            [clojure.string :as string]
            [clj-dns.core :refer [dns-lookup]]
            [org.bovinegenius  [exploding-fish :as uri]]
            [schema.core :as s]
            [taoensso.timbre :as timblre :refer [warn info]])
  (:import org.xbill.DNS.Type))

(defn prn-tee
  ([obj] (prn-tee nil obj ))
  ([prefix obj] (if prefix (prn prefix)) (prn obj) obj))

(defn assoc-fn [m k f]
  (assoc m k (f (get m k))))

(defn assoc-in-fn [m ks f]
  (assoc-in m ks (f (get-in m ks))))

(defn map-walk [f m]
  (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m))

(defn is-ip? [s]
  (re-matches #"(?:\d{1,3}\.){3}\d{1,3}" s))

(defn resolve-host [host]
  (info (format "Resolving host %s..." host))
  (if (is-ip? host)
    host
    (if-let [resolved (first ((dns-lookup host Type/A) :answers))]
        (string/replace (str (.getAddress resolved)) "/" "")
        (if-let [resolved
                 (try
                   (java.net.InetAddress/getByName host)
                   (catch java.net.UnknownHostException e nil))]
          (.getHostAddress resolved)
          (do
            (let [message (format "Unable to resolve host %s" host)]
              (warn message)
              (throw
                (ex-info message {:user-visible true}))))))))

(defn host-resolved-url [url]
  (let [u (if (string? url) (uri/uri url) url)]
    (assoc u :host (resolve-host (uri/host u)))))

(defn vector->map [v]
  (->> v
       (s/validate (s/pred #(even? (count %))))
       (partition 2 )
       (map vec )
       (into {})))

(defn keys-with-vals-matching-pred [pred m]
  (->> m
       (map #(if (pred (val %)) (key %)))
       (filter identity)))

(defn merge-meta
  "Return an object with an additional metadata map merged in."
  [obj new-meta]
  (with-meta obj (merge (meta obj) new-meta)))

(defn gen-uuid [] (str (java.util.UUID/randomUUID)))

;; schema utils

(defmacro literal-pred
  "Yields a predicate schema that only matches one value. Takes an optional
  name.

  For example,
  ```
  > (s/validate (literal-pred :my-keyword) :my-keyword)
  :my-keyword
  ```

  "
  [literal & rest]
  (let [pred-name (first (split-at 1 rest))]
    `(s/pred #(= ~literal %) ~@pred-name)))

(defmacro keyword-schema-pair
  "Yields a predicate that matches keyword / schema pairs like
  [:my-keyword \"My Value\"].

  For example,
  ```
  > (s/validate (keyword-schema-pair :my-keyword s/Str)
                [:my-keyword \"My Value\"])
  [:my-keyword \"My Value\"]
  ```
  "
  [kw schema]
  `(s/pair (literal-pred ~kw) "keyword" ~schema ~kw))

(defmacro any-pair
  "Syntactic sugar for a schema that validates a value against any of a number
  of literal keyword/schema pairs.

  For example,
  ```
  > (s/validate (any-pair :my-keyword 1 :your-keyword 2) [:your-keyword 2])
  [:your-keyword 2]
  ```
  "
  [& pairs]
  (s/validate (s/pred even? 'even?) (count pairs))
  (let [pairs (vec (map vec (partition 2 pairs)))]
    `(let [pred-pairs# (map #(keyword-schema-pair (first %) (second %))
                            ~pairs)]
       (apply s/either pred-pairs#))))

