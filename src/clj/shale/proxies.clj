(ns shale.proxies
  (:require [com.stuartsierra.component :as component]
            [schema.core :as s]
            [taoensso.carmine :as car]
            [shale.logging :as logging]
            [shale.redis :as redis]
            [shale.sessions :as sessions]
            [shale.utils :refer [gen-uuid]])
  (:import shale.sessions.SessionPool))

; create a proxy pool object that gets the initial proxies from config
; - requires config + redis conn + session pool
; eventually, health checks that we run periodically to flag something as active / not active?
;; eh, that requires shale to have access to the proxies

(s/defrecord ProxyPool
  [config
   redis-conn
   session-pool :- SessionPool
   logger]
  component/Lifecycle
  (start [cmp]
    (logging/info "Starting proxy pool...")
    ;; TODO if proxies in config aren't in Redis, put them there
    ;; if they are, don't modify eg availability
    cmp)
  (stop [cmp]
    (logging/info "Stopping proxy pool...")
    cmp))

(defn new-proxy-pool []
  (map->ProxyPool {}))

(def SharedProxySchema
  {(s/optional-key :public-ip) (s/maybe redis/IPAddress)
   :type                       (s/enum :socks5 :http)
   :private-ip-and-port        s/Str})

(s/defschema ProxySpec
  "Spec for creating a new proxy record"
  SharedProxySchema)

(s/defschema ProxyView
  "A proxy, as presented to library users"
  (merge SharedProxySchema
         {:id     s/Str
          :active s/Bool}))

(s/defn ^:always-validate create-proxy! :- (s/maybe ProxyView)
  [pool :- ProxyPool
   spec :- ProxySpec]
  (logging/info (format "Recording new proxy: %s"
                        spec))
  (let [id (gen-uuid)
        public-ip (get spec :public-ip)
        prox (merge spec {:active true
                          :id id
                          :public-ip public-ip})]
    (last
      (car/wcar (:redis-conn pool)
        (car/sadd (redis/model-ids-key redis/ProxyInRedis) id)
        (car/return
          (redis/hset-all
            (redis/model-key redis/ProxyInRedis id)
            prox))))))

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

(s/defn model->view-model :- ProxyView
  [model :- redis/ProxyInRedis]
  (update-in model [:type] keyword))

(s/defn ^:always-validate view-model-exists? :- s/Bool
  [pool :- ProxyPool
   id   :- s/Str]
  (redis/model-exists? (:redis-conn pool) redis/SessionInRedis id))

(s/defn ^:always-validate view-models :- [ProxyView]
  [pool :- ProxyPool]
  (let [redis-conn (:redis-conn pool)]
    (car/wcar redis-conn
      (car/return
        (map model->view-model
             (redis/models redis-conn redis/ProxyInRedis))))))
