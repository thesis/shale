(ns shale.proxies
  (:require [clojure.core.match :refer [match]]
            [clojure.set :refer [rename-keys]]
            [clojure.walk :refer [postwalk]]
            [com.stuartsierra.component :as component]
            [schema.core :as s]
            [taoensso.carmine :as car]
            [shale.logging :as logging]
            [shale.redis :as redis]
            [shale.utils :refer [gen-uuid any-pair]]))

(declare modify-proxy! view-model-exists? view-model view-models ProxySpec
         create-proxy!)

(s/defrecord ProxyPool
  [config
   redis-conn
   logger]
  component/Lifecycle
  (start [cmp]
    (logging/info "Starting proxy pool...")
    ;; if proxies in config aren't in Redis, put them there
    ;; if they are, don't modify eg availability
    (let [proxies (or (:proxy-list config) [])
          _ (do
              (logging/info "Configuring proxy pool...")
              (doall (map #(s/validate ProxySpec %) proxies)))
          existing (->> (view-models  cmp)
                        (map #(select-keys % [:host :port]))
                        (into #{}))
          new-proxies (->> proxies
                           (filter
                             (fn [p]
                               (not
                                 (.contains existing
                                            (select-keys p [:host :port])))))
                           (into []))]
      (when (> (count new-proxies) 0)
        (logging/info "Creating proxies...")
        (doall
          (map (partial create-proxy! cmp) new-proxies))))
    cmp)
  (stop [cmp]
    (logging/info "Stopping proxy pool...")
    cmp))

(defn new-proxy-pool []
  (map->ProxyPool {}))

(def SharedProxySchema
  {:type (s/enum :socks5 :http)
   :host s/Str
   :port s/Int})

(s/defschema ProxySpec
  "Spec for creating a new proxy record"
  (merge SharedProxySchema
         {(s/optional-key :public-ip) (s/maybe redis/IPAddress)
          (s/optional-key :shared)    s/Bool
          (s/optional-key :active)    s/Bool
          (s/optional-key :tags)      #{s/Str}}))

(def proxy-spec-defaults
  {:shared true
   :active true
   :tags #{}})

(s/defschema ProxyView
  "A proxy, as presented to library users"
  (merge SharedProxySchema
         {:id        s/Str
          :public-ip (s/maybe redis/IPAddress)
          :active    s/Bool
          :shared    s/Bool
          :tags      #{s/Str}}))

(s/defschema ProxyRequirement
  (any-pair
    :id        s/Str
    :shared    s/Bool
    :active    s/Bool
    :host      s/Str
    :port      s/Int
    :type      (:type SharedProxySchema)
    :public-ip redis/IPAddress
    :tag       s/Str
    :nil?      (s/enum :public-ip)
    :not       (s/recursive #'ProxyRequirement)
    :and       [(s/recursive #'ProxyRequirement)]
    :or        [(s/recursive #'ProxyRequirement)]))

(s/defn ^:always-validate matches-requirement :- s/Bool
  [prox        :- ProxyView
   requirement :- ProxyRequirement]
  (logging/debug
    (format "Testing proxy %s against requirement %s."
            prox
            requirement))
  (let [[req-type arg] requirement
        p prox]
    (-> (match req-type
               (:or :id :shared :active :host :port :type)
                 (= arg (get p req-type))
               :tag  ((:tags p) arg)
               :nil? (nil? (get p arg))
               :not  (not     (matches-requirement p arg))
               :and  (every? #(matches-requirement p %) arg)
               :or   (some   #(matches-requirement p %) arg))
        boolean)))

(s/defn ^:always-validate create-proxy! :- (s/maybe ProxyView)
  [pool :- ProxyPool
   spec :- ProxySpec]
  (logging/info (format "Recording new proxy: %s"
                        spec))
  (let [id (gen-uuid)
        prox (merge spec {:active true
                          :shared (not (false? (:shared spec)))
                          :id id
                          :public-ip (:public-ip spec)})]
    (last
      (car/wcar (:redis-conn pool)
        (car/sadd (redis/model-ids-key redis/ProxyInRedis) id)
        (car/return
          (modify-proxy! pool id prox))))))

(s/defn ^:always-validate delete-proxy! :- s/Bool
  [pool :- ProxyPool
   id   :- s/Str]
  (let [redis-conn (:redis-conn pool)]
    (->
      (car/wcar redis-conn
        (logging/info (format "Deleting proxy record %s..." id))
        (car/watch (redis/model-ids-key redis/ProxyInRedis))
        (if (redis/model-exists? redis-conn redis/ProxyInRedis id)
          (redis/delete-model! redis-conn redis/ProxyInRedis id)
          false))
      (= "OK"))))

(s/defn ^:always-validate modify-proxy! :- ProxyView
  [pool          :- ProxyPool
   id            :- s/Str
   modifications :- {s/Keyword s/Any}]
  (when (view-model-exists? pool id)
    (let [k (redis/model-key redis/ProxyInRedis id)]
      (car/wcar (:redis-conn pool)
        (car/return
          (redis/hset-all k modifications))
        (when-let [tags (:tags modifications)]
          (redis/sset-all (clojure.string/join "/" [k "tags"])
                          tags))))
    (view-model pool id)))

(s/defn ^:always-validate model->view-model :- ProxyView
  [model :- redis/ProxyInRedis]
  (update-in model [:type] keyword))

(s/defn ^:always-validate view-model :- (s/maybe ProxyView)
  [pool :- ProxyPool
   id   :- s/Str]
  (model->view-model (redis/model (:redis-conn pool) redis/ProxyInRedis id)))

(s/defn ^:always-validate view-model-exists? :- s/Bool
  [pool :- ProxyPool
   id   :- s/Str]
  (redis/model-exists? (:redis-conn pool) redis/ProxyInRedis id))

(s/defn ^:always-validate view-models :- [ProxyView]
  [pool :- ProxyPool]
  (let [redis-conn (:redis-conn pool)]
    (car/wcar redis-conn
      (car/return
        (map model->view-model
             (redis/models redis-conn redis/ProxyInRedis))))))

(s/defn ^:always-validate get-proxy :- (s/maybe ProxyView)
  [pool        :- ProxyPool
   requirement :- (s/maybe ProxyRequirement)]
  (try
    (rand-nth
      (filter #(matches-requirement % (or requirement
                                          [:and [[:shared true] [:active true]]]))
              (view-models pool)))
    (catch IndexOutOfBoundsException e)))

; TODO DRY up this with logic in sessions

(s/defn ^:always-validate require->spec :- ProxySpec
  "Infer proxy creation options from a requirement.

  Ignores or'd and regated requirements."
  [requirement :- (s/maybe ProxyRequirement)]
  (let [reqs [requirement]
        nilled (postwalk #(if (and (sequential? %)
                                   (some #{(first %)} [:or :not]))
                            ::nil
                            %)
                         reqs)
        filtered (postwalk #(if (sequential? %)
                              (vec (filter (partial not= ::nil) %))
                              %)
                           nilled)
        flattened (->> filtered
                       (tree-seq sequential? identity)
                       (filter #(nil? (s/check ProxyRequirement %)))
                       (filter #(not= (first %) :and))
                       (map (partial apply hash-map))
                       (map #(if (contains? % :tag)
                               (update-in % [:tag] hash-set)
                               %))
                       (apply merge-with (comp vec concat)))]
    (merge proxy-spec-defaults
           (rename-keys flattened {:tag :tags}))))

(s/defn ^:always-validate get-or-create-proxy! :- ProxyView
  [pool :- ProxyPool
   requirement :- ProxyRequirement]
  (or (get-proxy pool requirement)
      (create-proxy! pool (require->spec requirement))))
