(ns shale.utils
  (:require [clojure.walk]
            [clojure.string :as string]
            [clojure.pprint :refer [pprint]]
            [clj-dns.core :refer [dns-lookup]]
            [org.bovinegenius  [exploding-fish :as uri]]
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

(defn resolve-host [host]
  (logging/debug (format "Resolving host %s..." host))
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
              (logging/warn message)
              (throw
                (ex-info message {:user-visible true}))))))))

(defn host-resolved-url [url]
  (let [u (if (string? url) (uri/uri url) url)]
    (assoc u :host (resolve-host (uri/host u)))))

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
