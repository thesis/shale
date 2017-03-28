(ns shale.utils
  (:require [clojure.walk]
            [clojure.string :as string]
            [clojure.pprint :refer [pprint]]
            [clj-dns.core :refer [dns-lookup]]
            [environ.core :as env]
            [org.bovinegenius [exploding-fish :as uri]]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+]]
            [shale.logging :as logging])
  (:import org.xbill.DNS.Type
           java.io.StringWriter))

(defn prn-tee
  ([obj] (prn-tee nil obj ))
  ([prefix obj] (if prefix (prn prefix)) (prn obj) obj))

(defn pretty [x]
  (let [w (StringWriter.)]
    (pprint x w)
    (.toString w)))

(defn assoc-fn [m k f]
  (assoc m k (f (get m k))))

(defn assoc-in-fn [m ks f]
  (assoc-in m ks (f (get-in m ks))))

(defn map-walk [f m]
  (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m))

(defn is-ip? [s]
  (re-matches #"(?:\d{1,3}\.){3}\d{1,3}" s))

(s/defn any-instance?
  [ps :- [Class]
   v  :- s/Any]
  (some (map #(instance? % v)) ps))

(defn vector->map [v]
  (->> v
       (s/validate (s/pred #(even? (count %))))
       (partition 2)
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
  `(s/pair (s/eq ~kw) "keyword" ~schema ~kw))

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

(defn first-checked-schema
  "Return the first schema which validates a value. Useful as a dispatch
  function for multimethods."
  [value schemas]
  (doto (->> schemas
             (filter (fn [schema]
                       (try
                         (s/validate schema value)
                         schema
                         (catch RuntimeException e))))
             first)))

(defmacro defmultischema
  "Dispatches multimethods based on whether their args match the provided
  schemas.

  Each schema is validated against a vector of a method's args. The first that
  validates successfully is used as the dispatch value.

  For example,
  ```
  (defmultischema test-method :number [s/Num] :string [s/Str])
  (defmethod test-method :number [n]
    (prn \"number\"))
  (defmethod test-method :string [s]
    (prn \"string\"))
  ```"
  [multi-name & schemas]
  (if-not (even? (count schemas))
    (throw+ (str "defmultischema expects an even number of args (for "
                 "value / schema pairs)")))
  `(defmulti ~multi-name
     (fn [& args#]
       (let [schema-to-value# (->> ~(vec schemas)
                                   (partition 2)
                                   (map (comp vec reverse))
                                   (into {}))
             schemas# (keys schema-to-value#)
             matching-schema# (get schema-to-value#
                                   (first-checked-schema (vec args#) schemas#))]
         (if (nil? matching-schema#)
           (throw+ (str "No matching schema for multimethod "
                        (name '~multi-name)
                        " with args "
                        (vec args#)))
           matching-schema#)))))

(defn maybe-resolve-env-keyword
  "If k is a keyword with the namespace :env, look it up and return it, else k"
  [k]
  (if (and k (keyword? k) (namespace k) (= "env" (namespace k)))
    (get env/env (keyword (name k)))
    k))
