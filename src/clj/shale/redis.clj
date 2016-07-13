(ns shale.redis
  (:require [clojure.walk :refer [keywordize-keys]]
            [taoensso.carmine :as car :refer (wcar)]
            [schema.core :as s]
            [shale.utils :refer :all]))

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

(def session-node-key-template
  (apply str (interpose "/" [redis-key-prefix "sessions" "%s" "node"])))

(def session-capabilities-key-template
  (apply str (interpose "/" [redis-key-prefix "sessions" "%s" "capabilities"])))

(defn session-key [session-id]
  (format session-key-template session-id))

(defn session-tags-key [session-id]
  (format session-tags-key-template session-id))

(defn session-node-key [session-id]
  (format session-node-key-template session-id))

(defn session-capabilities-key [session-id]
  (format session-capabilities-key-template session-id))

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

(def soft-delete-sub-key
  (apply str (interpose "/" [redis-key-prefix "deleting"])))

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
   :redis-key \"_shale/Model\"}

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
  {(s/optional-key :id)             s/Str
   (s/optional-key :webdriver-id)   (s/maybe s/Str)
   (s/optional-key :tags)           #{s/Str}
   (s/optional-key :reserved)       s/Bool
   (s/optional-key :current-url)    (s/maybe s/Str)
   (s/optional-key :browser-name)   s/Str
   (s/optional-key :node)           {:id s/Str
                                     s/Any s/Any}
   (s/optional-key :capabilities)   {s/Keyword s/Any}})

(defmodel NodeInRedis
  "A node, as represented in redis."
  :model-name "nodes"
  {(s/optional-key :id)             s/Str
   (s/optional-key :url)            s/Str
   (s/optional-key :max-sessions)   s/Int
   (s/optional-key :tags)         #{s/Str}})

(s/defschema IPAddress
  (s/pred is-ip?))

(defmodel ProxyInRedis
  "A proxy, as represented in redis."
  :model-name "proxies"
  {(s/optional-key :public-ip) IPAddress
   :type                       (s/enum :socks5 :http)
   :private-host-and-port      s/Str
   (s/optional-key :active)    s/Bool})

;; model fetching
(defn model-key [model-schema id]
  (clojure.string/join "/" [(:redis-key (meta model-schema)) id]))

(defn model-ids-key [model-schema]
  (:redis-key (meta model-schema)))

(defn ^:private model-ids [redis-conn model-schema]
  (wcar redis-conn
    (car/smembers (model-ids-key model-schema))))

(defn model-exists? [redis-conn model-schema id]
  (not (nil? (some #{id} (model-ids redis-conn model-schema)))))

(defn is-map-type?
  "Unfortunately, distinguishing between maps and records isn't the default
  behavior of map?."
  [m]
  (and (map? m) (not (record? m))))

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
  [redis-conn model-schema id]
  (let [set-keys (->> model-schema
                      (keys-with-vals-matching-pred set?)
                      (map (comp name :k)))
        list-keys (->> model-schema
                       (keys-with-vals-matching-pred sequential?)
                       (map (comp name :k)))
        map-keys (->> model-schema
                      (keys-with-vals-matching-pred is-map-type?)
                      (map (comp name :k)))
        k (model-key model-schema id)]
    (last
      (wcar redis-conn
        (car/watch k)
        (car/return
          (let [base (vector->map (wcar redis-conn
                                    (car/hgetall k)))
                sets (for [set-k set-keys]
                       {set-k (->> [k set-k]
                                   (clojure.string/join "/")
                                   car/smembers
                                   (wcar redis-conn)
                                   (into #{}))})
                lists (for [list-k list-keys]
                       {list-k (list
                                 (wcar redis-conn
                                   (-> (clojure.string/join "/" [k list-k])
                                       (car/lrange 0 -1))))})
                maps (for [map-k map-keys]
                       {map-k (->> [k map-k]
                                   (clojure.string/join "/")
                                   car/hgetall
                                   (wcar redis-conn)
                                   (apply hash-map))})]
            (->> (concat sets lists maps)
                 (list* base)
                 (reduce merge)
                 ; remove internal keys
                 (filter #(not (.startsWith (key %) redis-key-prefix)))
                 (into {})
                 keywordize-keys
                 (merge {:id id}))))))))

(defn soft-delete-model!
  "Add a flag to a Redis model to signify a \"soft delete\".

  Soft-deleted models won't show up when listing a model unless specified."
  [redis-conn model-schema id]
  (let [m-key (model-key model-schema id)
        ids-key (model-ids-key model-schema)]
    (wcar redis-conn
      (car/watch ids-key)
      (car/hset m-key soft-delete-sub-key true))))

(defn delete-model!
  "Hard delete a model from Redis."
  [redis-conn model-schema id]
  (let [m-key (model-key model-schema id)
        ids-key (model-ids-key model-schema)]
    (wcar redis-conn
      (car/watch ids-key)
      ; delete any associated keys first
      (doall
        (for [k (->> model-schema
                     (keys-with-vals-matching-pred coll?)
                     (map :k))]
          (->> [m-key k]
               (clojure.string/join "/")
               car/del
               (car/wcar redis-conn))))
      ; delete the base model data
      (car/del m-key)
      (car/srem ids-key id)))
  true)

(defn models [redis-conn model-schema & {:keys [include-soft-deleted?]
                                         :or {include-soft-deleted? false}}]
  (wcar redis-conn
    (car/return
      (->> (model-ids redis-conn model-schema)
           (map #(model redis-conn model-schema %))
           (filter #(or (not (get % soft-delete-sub-key))
                        include-soft-deleted?))
           (filter identity)))))
