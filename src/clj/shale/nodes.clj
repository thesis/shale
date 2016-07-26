(ns shale.nodes
  (:require [clojure.set :refer [difference]]
            [taoensso.carmine :as car :refer (wcar)]
            [schema.core :as s]
            [clojure.walk :refer :all]
            [com.stuartsierra.component :as component]
            [shale.logging :as logging]
            [shale.node-providers :as node-providers]
            [shale.redis :as redis]
            [shale.utils :refer :all])
  (:import java.util.UUID
           [shale.node_providers DefaultNodeProvider AWSNodeProvider]))

(deftype ConfigNodeProvider [])

(def node-provider-from-config
  "Return a node pool given a config function.

  The config function should expect a single keyword argument and to look up
  config values.

  Note that, though this function isn't referentially transparent, it's
  memoized. This is a hack, and will be cleaned up when we start using
  components (https://github.com/cardforcoin/shale/issues/58)."
  (memoize
    (fn [config-fn]
      (if (nil? (config-fn :node-pool-impl))
        (if (nil? (config-fn :node-pool-cloud-config))
          (node-providers/DefaultNodeProvider. (or (config-fn :node-list)
                                                ["http://localhost:5555/wd/hub"]))
          (if (= ((config-fn :node-pool-cloud-config) :provider) :aws)
            (node-providers/AWSNodeProvider. (config-fn :node-pool-cloud-config))
            (throw (ex-info (str "Issue with cloud config: AWS is "
                                 "the only currently supported "
                                 "provider.")
                            {:user-visible true :status 500}))))
        (do
          (extend ConfigNodeProvider
            node-providers/INodeProvider
            (config-fn :node-pool-impl))
          (ConfigNodeProvider.))))))

(s/defrecord NodePool
  [redis-conn
   logger
   node-provider
   default-session-limit]
  component/Lifecycle
  (start [cmp]
    (logging/info "Starting the node pool...")
    cmp)
  (stop [cmp]
    (logging/info "Stopping the node pool...")
    (assoc cmp :node-provider nil)))

(defn new-node-pool [config]
  (map->NodePool {:node-provider (node-provider-from-config config)
                  :default-session-limit (or (:node-max-sessions config) 3)}))

(s/defn node-ids :- [s/Str] [pool :- NodePool]
  (car/wcar (:redis-conn pool)
    (car/smembers (redis/model-ids-key redis/NodeInRedis))))

(s/defschema NodeView
  "A node, as presented to library users."
  {(s/optional-key :id)           s/Str
   (s/optional-key :url)          s/Str
   (s/optional-key :tags)        [s/Str]
   (s/optional-key :max-sessions) s/Int})

(s/defn ->NodeView :- NodeView
  [id :- s/Str
   from-redis :- redis/NodeInRedis]
  (->> from-redis
       (merge {:id id})
       keywordize-keys))

(s/defn view-model :- NodeView
  "Given a node pool, get a view model from Redis."
  [pool :- NodePool
   id   :- s/Str]
  (let [m (redis/model (:redis-conn pool) redis/NodeInRedis id)]
    (->NodeView id m)))

(s/defn view-models :- [NodeView]
  "Get all view models from a node pool."
  [pool :- NodePool]
  (map #(view-model pool %) (node-ids pool)))

(s/defn view-model-from-url :- NodeView
  [pool :- NodePool
   url  :- s/Str]
  (first (filter #(= (% :url) url) (view-models pool))))

(s/defn ^:always-validate view-model-exists? :- s/Bool
  [pool :- NodePool
   id   :- s/Str]
  (redis/model-exists? (:redis-conn pool) redis/NodeInRedis id))

(s/defn modify-node :- NodeView
  "Modify a node's url or tags in Redis."
  [pool id {:keys [url tags]
            :or {:url nil
                 :tags nil}}]
  (last
    (car/wcar (:redis-conn pool)
      (let [node-key (redis/model-key redis/NodeInRedis id)
            node-tags-key (redis/node-tags-key id)]
        (if url (->> url
                     str
                     (car/hset node-key :url)))
        (if tags (redis/sset-all node-tags-key tags))
        (car/return (view-model pool id))))))

(s/defn create-node :- NodeView
  "Create a node in a given pool."
  [pool {:keys [url tags]
         :or {:tags []}}]
  (last
    (car/wcar (:redis-conn pool)
      (let [id (gen-uuid)
            node-key (redis/model-key redis/NodeInRedis id)]
        (car/sadd (redis/model-ids-key redis/NodeInRedis) id)
        (modify-node pool id {:url url :tags tags})
        (car/return (view-model pool id))))))

(s/defn destroy-node
  [pool :- NodePool
   id   :- s/Str]
  (car/wcar (:redis-conn pool)
    (car/watch (redis/model-ids-key redis/NodeInRedis))
    (try
      (let [url (:url (view-model pool id))]
        (if (some #{url} (node-providers/get-nodes (:node-provider pool)))
          (node-providers/remove-node (:node-provider pool) url)))
      (finally
        (redis/delete-model! redis/NodeInRedis id)
        (car/del (redis/node-tags-key id)))))
  true)

(defn ^:private to-set [s]
  (into #{} s))

(def ^:private refresh-nodes-lock {})

(s/defn refresh-nodes
  "Syncs the node list with the backing node provider."
  [pool :- NodePool]
  (locking refresh-nodes-lock
    (logging/debug "Refreshing nodes...")
    (let [nodes (->> (:node-provider pool)
                     node-providers/get-nodes
                     to-set)
          registered-nodes (->> (view-models pool)
                                (map :url)
                                to-set)]
      (logging/debug "Live nodes:")
      (logging/debug nodes)
      (logging/debug "Nodes in Redis:")
      (logging/debug registered-nodes)
      (doall
        (concat
          (map #(create-node pool {:url %})
               (filter identity
                       (difference nodes registered-nodes)))
          (map #(destroy-node pool (:id (view-model-from-url pool %)))
               (filter identity
                       (difference registered-nodes nodes))))))
    true))

(def NodeRequirements
  {(s/optional-key :url)   s/Str
   (s/optional-key :tags) [s/Str]
   (s/optional-key :id)    s/Str})

(s/defn ^:always-validate raw-sessions-with-node [pool    :- NodePool
                                                  node-id :- s/Str]
  (let [redis-conn (:redis-conn pool)]
    (->> (redis/models redis-conn
                       redis/SessionInRedis
                       :include-soft-deleted? true)
         (filter #(= node-id (:node-id %))))))

(s/defn ^:always-validate raw-session-count [pool    :- NodePool
                                             node-id :- s/Str]
  (count (raw-sessions-with-node pool node-id)))

(s/defn nodes-under-capacity
  "Nodes with available capacity."
  [pool :- NodePool]
  (let [session-limit (:default-session-limit pool)]
    (filter #(< (raw-session-count pool (:id %)) session-limit)
            (view-models pool))))

(s/defn matches-requirements :- s/Bool
  [model        :- NodeView
   requirements :- NodeRequirements]
  (and
    (if (contains? requirements :id)
      (apply = (map :id [requirements model]))
      true)
    (if (contains? requirements :url)
      (apply = (map :url [requirements model]))
      true)
    (if (contains? requirements :tags)
      (apply clojure.set/subset?
             (map :tags [requirements model]))
      true)))

(s/defn get-node :- (s/maybe NodeView)
  [pool         :- NodePool
   requirements :- NodeRequirements]
  (try
    (rand-nth
      (filter #(matches-requirements % requirements)
              (nodes-under-capacity pool)))
    (catch IndexOutOfBoundsException e)))
