(ns shale.nodes
  (:require [clojure.set :refer [difference]]
            [taoensso.carmine :as car :refer (wcar)]
            [schema.core :as s]
            [shale.redis :refer :all]
            [shale.utils :refer :all]
            [clojure.walk :refer :all]
            [shale.configurer :refer [config]]
            [shale.node-pools :as node-pools]
            [io.aviso.ansi :refer [bold-red bold-green]] )
  (:import java.util.UUID
           [shale.node_pools DefaultNodePool AWSNodePool]))

(deftype ConfigNodePool [])


(def node-pool
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
          (node-pools/DefaultNodePool. (or (config-fn :node-list)
                                           ["http://localhost:5555/wd/hub"]))
          (if (= ((config-fn :node-pool-cloud-config) :provider) :aws)
            (node-pools/AWSNodePool. (config :node-pool-cloud-config))
            (throw (ex-info (str "Issue with cloud config: AWS is "
                                 "the only currently supported "
                                 "provider.")
                            {:user-visible true :status 500}))))
        (do
          (extend ConfigNodePool
            node-pools/INodePool
            (config-fn :node-pool-impl))
          (ConfigNodePool.))))))

(def default-session-limit
  (or (config :node-max-sessions) 3))

(s/defn node-ids :- [s/Str] []
  (with-car* (car/smembers node-set-key)))

(def NodeView
  "A node, as presented to library users."
  {(s/optional-key :id)           s/Str
   (s/optional-key :url)          s/Str
   (s/optional-key :tags)        [s/Str]
   (s/optional-key :max-sessions) s/Int})

(s/defn ->NodeView [id :- s/Str
                    from-redis :- NodeInRedis]
  (->> from-redis
       (merge {:id id})
       keywordize-keys))

(s/defn view-model :- NodeView
  [id :- s/Str]
  (->NodeView id (model NodeInRedis id)))

(s/defn view-models :- [NodeView] []
  (map view-model (node-ids)))

(s/defn view-model-from-url :- NodeView
  [url :- s/Str]
  (first (filter #(= (% :url) url) (view-models))))

(s/defn modify-node :- NodeView
  "Modify a node's url or tags in Redis. Any provided url that's host isn't an
  IP address will be resolved before storing."
  [id {:keys [url tags]
       :or {:url nil
            :tags nil}}]
  (last
    (with-car*
      (let [node-key (node-key id)
            node-tags-key (node-tags-key id)]
        (if url (->> url
                     host-resolved-url
                     str
                     (car/hset node-key :url)))
        (if tags (sset-all node-tags-key tags))
        (car/return (view-model id))))))

(s/defn create-node :- NodeView
  [{:keys [url tags]
    :or {:tags []}}]
  (last
    (with-car*
      (let [id (gen-uuid)
            node-key (node-key id)]
        (car/sadd node-set-key id)
        (modify-node id {:url url :tags tags})
        (car/return (view-model id))))))

(s/defn destroy-node [id :- s/Str]
  (with-car*
    (car/watch node-set-key)
    (try
      (let [url (get (view-model id) :url)]
        (if (some #{url} (node-pools/get-nodes (node-pool config)))
          (node-pools/remove-node (node-pool config) url)))
      (finally
        (car/srem node-set-key id)
        (car/del (node-key id))
        (car/del (node-tags-key id)))))
  true)

(defn ^:private to-set [s]
  (into #{} s))

(def ^:private  refresh-nodes-lock {})

(defn refresh-nodes
  "Syncs the node list with the backing node pool."
  []
  (locking refresh-nodes-lock
    (let [nodes (to-set (node-pools/get-nodes (node-pool config)))
          registered-nodes (to-set (map #(get % :url) (view-models)))]
      (doall
        (concat
          (map #(create-node {:url %})
               (filter identity
                       (difference nodes registered-nodes)))
          (map #(destroy-node ((view-model-from-url %) :id))
               (filter identity
                       (difference registered-nodes nodes))))))
    true))

(def NodeRequirements
  {(s/optional-key :url)   s/Str
   (s/optional-key :tags) [s/Str]
   (s/optional-key :id)    s/Str})

(s/defn session-count [node-id :- s/Str]
  (->> (models SessionInRedis)
       (filter #(= node-id (:node-id %)))
       count))

(s/defn nodes-under-capacity []
  (filter #(< (session-count (:id %)) default-session-limit)
          (view-models)))

(s/defn matches-requirements :- s/Bool
  [model :- NodeView
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
  [requirements :- NodeRequirements]
  (try
    (rand-nth
      (filter #(matches-requirements % requirements)
              (nodes-under-capacity)))
    (catch IndexOutOfBoundsException e)))
