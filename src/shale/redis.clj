(ns shale.redis
  (:require [taoensso.carmine :as car :refer (wcar) ]
            [schema.core :as s]
            [shale.utils :refer :all]
            [shale.configurer :refer [config]]))

(def redis-conn-opts {:pool {} :spec (config :redis)})
(defmacro with-car* [& body] `(car/wcar redis-conn-opts ~@body))

(defn hset-all [k m]
  (doseq [[a b] m]
    (car/hset k a b)))

(defn sset-all [k s]
  (car/del k)
  (doseq [a s]
    (car/sadd k a)))

;; in-database models
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

;; model schemas
(defmacro defmodel
  "A wrapper around schema's `defschema` to attach additional model metadata.

  For example,
  ```
  > (defmodel Model
      \"My Redis model.\"
      {(s/optional-key :name) s/Str})
  > (meta Model)
  {:doc \"My Redis model.\"
   :name \"Model\"
   :model-name \"Model\"
   :redis-key \"_shake/Model\"}

  > (defmodel FancyModel
      \"My fancy model.\"
      :model-name \"fancy\"
      {(s/optional-key :field) s/Int})
  > (meta FancyModel)
  {:doc \"My fancy model.\"
   :name \"Fancy\"
   :model-name \"fancy\"
   :redis-key \"_shale/fancy\"}
  ```"
  [name & forms]
  (let [optional-args (butlast forms)
        docstring (if (string? (first optional-args))
                    (first optional-args))
        options (s/validate (s/either (s/eq nil)
                                      (s/pred #(even? (count %))))
                            (if (nil? docstring)
                              optional-args
                              (rest optional-args)))
        schema (last forms)
        option-map (->> options
                        (partition 2)
                        (map vec)
                        (into {}))
        meta-options (update-in option-map
                                [:model-name]
                                #(or % name))
        default-redis-key (->> [redis-key-prefix (:model-name meta-options)]
                               (clojure.string/join "/"))]
    `(def ~name
       ~(or docstring "")
       (merge-meta (schema.core/schema-with-name ~schema '~name)
                   (merge {:redis-key ~default-redis-key}
                          ~meta-options)))))

(defmodel SessionInRedis
  "A session, as represented in redis."
  :model-name "sessions"
  {(s/optional-key :webdriver-id) s/Str
   (s/optional-key :tags)        [s/Str]
   (s/optional-key :reserved)     s/Bool
   (s/optional-key :current-url)  s/Str
   (s/optional-key :browser-name) s/Str
   (s/optional-key :node-id)      s/Str})

(defmodel NodeInRedis
  "A node, as represented in redis."
  :model-name "nodes"
  {(s/optional-key :url)          s/Str
   (s/optional-key :max-sessions) s/Int
   (s/optional-key :tags)       #{s/Str}})

;; model fetching
(defn model-key [model-schema id]
  (clojure.string/join "/" [(:redis-key (meta model-schema)) id]))

(defn model-ids-key [model-schema]
  (:redis-key (meta model-schema)))

(defn model-ids [model-schema]
  (with-car*
    (car/smembers (model-ids-key model-schema))))

(defn model-exists? [model-schema id]
  (not (nil? (some #{id} (model-ids model-schema)))))

(defn model
  "Return a model from a Redis key given a particular schema.

  Models understand and serialize most clojure primitives, including shallow
  sequentials, maps, and sets. Int, strings, etc are stored on the hash at the
  model's key, while the rest are stored at keys that share the model's key as
  a prefix. For example,

  ```
  > (defmodel Person
      {(s/optional-key :age)          s/Int
       (s/optional-key :nicknames)   #{s/Str}})
  ;; stored as {\"age\" \"10\"} at \"_shale/Person/<id>\" and #{\"oli\" \"joe\"}
  ;; at \"_shale/Person/<id>/nicknames\".
  ```"
  [model-schema id]
  (let [set-keys (->> model-schema
                      (keys-with-vals-matching-pred set?)
                      (map :k))
        list-keys (->> model-schema
                       (keys-with-vals-matching-pred sequential?)
                       (map :k))
        map-keys (->> model-schema
                      (keys-with-vals-matching-pred map?)
                      (map :k))
        regular-keys (->> model-schema
                          (keys-with-vals-matching-pred
                            #(not-any? identity (juxt sequential? map? set?)))
                          (map :k))
        k (model-key model-schema id)]
    (last
      (with-car*
        (car/watch k)
        (car/return
          (let [base (vector->map (with-car* (car/hgetall k)))
                sets (for [set-k set-keys]
                       {set-k (->> [k set-k]
                                   (clojure.string/join "/")
                                   car/smembers
                                   with-car*
                                   (into #{}))})
                lists (for [list-k list-keys]
                       {list-k (list (with-car* (car/lrange list-k 0 -1)))})
                maps (for [map-k map-keys]
                       {map-k (hash-map (with-car* (car/smembers map-k)))})]
            (if-not (= base {})
              (reduce merge (list* base (concat sets []))))))))))

(defn delete-model!
  "Delete a model from Redis."
  [model-schema id]
  (let [m-key (model-key model-schema id)
        ids-key (model-ids-key model-schema)]
    (with-car*
      (car/watch ids-key)
      ; delete any associated keys first
      (doall
        (for [k (->> model-schema
                     (keys-with-vals-matching-pred coll?)
                     (map :k))]
          (->> [m-key k]
               (clojure.string/join "/")
               car/del
               with-car*)))
      ; delete the base model data
      (car/del m-key)
      (car/srem ids-key id)))
  true)

(defn models [model-schema]
  (with-car*
    (car/return
      (->> (model-ids model-schema)
           (map #(model model-schema %))
           (filter identity)))))
