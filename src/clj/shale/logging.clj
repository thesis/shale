(ns shale.logging
  (:require [clojure.pprint :refer [pprint]]
            [com.stuartsierra.component :as component]
            [schema.core :as s]
            [taoensso.timbre :as timbre]))

(s/defrecord Logger
  [config :- (s/maybe {s/Any s/Any})
   logging-config :- (s/maybe {s/Any s/Any})
   old-logging-config :- (s/maybe {s/Any s/Any})]

  component/Lifecycle
  (start [cmp]
    (println "Configuring logging...")
    (let [logging-config (or (get config :logging) {})
          old-logging-config (timbre/merge-config! {})]
      (pprint logging-config)
      (timbre/merge-config! logging-config)
      (-> cmp
          (assoc :logging-config logging-config)
          (assoc :old-logging-config old-logging-config))))
  (stop [cmp]
    (println "Resetting old logging config...")
    (timbre/set-config! (:old-logging-config cmp))
    (-> cmp
        (assoc :logging-config nil)
        (assoc :old-logging-config nil))))

(defn trace [obj]
  (timbre/trace obj))

(defn debug [obj]
  (timbre/debug obj))

(defn info [obj]
  (timbre/info obj))

(defn warn [obj]
  (timbre/warn obj))

(defn error [obj]
  (timbre/error obj))

(s/defn ^:always-validate new-logger :- Logger
  []
  (map->Logger {}))
