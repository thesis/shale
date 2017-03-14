(ns shale.riemann
  (require [riemann.client :as riemann]
           [shale.logging :as logging]
           [com.stuartsierra.component :as component]))

(defrecord Riemann [config client]
  component/Lifecycle
  (start [cmp]
    (logging/info "Starting the riemann client...")
    (assoc cmp :client (riemann/tcp-client (:riemann config))))
  (stop [cmp]
    (logging/info "Stopping the riemann client...")
    (assoc cmp :client nil)))

(defn new-riemann [config]
  (map->Riemann {:config config}))

(defn send-event [riemann m]
  (let [cl (:client riemann)
        ev (assoc m :service "shale")]
    (logging/debug (str "Sended to riemann: " ev))
    @(riemann/send-event cl ev)))

;; Macro for defining riemann sender where symbs are symbols that
;; represent keys appended to event where values are taken as their
;; lexical bindings.
;;
;; If we have
;; 
;; (defriemann-sender notify [obj id name]
;;   {:state "ok"
;;    :metric (/ time 1000)
;;    :number 1})
;;
;; Then
;; 
;; (let [id 13
;;       time 2000]
;;   (notify))
;;
;; sends {:service "shale"
;;        :id "13"
;;        :state "ok"
;;        :metric 2
;;        :number "1"}

(defmacro defriemann-sender [name riemann-container [& symbs] & [mapping]]
  (let [maybe-pair (fn [symb] `(when ~symb {~(keyword symb) ~symb}))
        event      (if (empty? symbs)
                     mapping
                     `(merge ~@(map maybe-pair symbs)
                             ~mapping))]
    `(defmacro ~name []
       `(send-event (:riemann ~'~riemann-container) ~'~event))))
