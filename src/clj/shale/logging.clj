(ns shale.logging
  (:require [clojure.pprint :refer [pprint]]
            [com.stuartsierra.component :as component]
            [schema.core :as s]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.tools.logging]))

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

(defmacro trace [obj]
  `(timbre/trace ~obj))

(defmacro debug [obj]
  `(timbre/debug ~obj))

(defmacro info [obj]
  `(timbre/info ~obj))

(defmacro infof [& args]
  `(timbre/infof ~@args))

(defmacro warn [obj]
  `(timbre/warn ~obj))

(defmacro warnf [& args]
  `(timbre/warnf ~@args))

(defmacro error [obj]
  `(timbre/error ~obj))

(defmacro errorf [& args]
  `(timbre/errorf ~@args))

(s/defn ^:always-validate new-logger :- Logger
  []
  (map->Logger {}))
