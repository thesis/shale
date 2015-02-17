(ns shale.utils
  (:require [clojure.walk]
            [clojure.string :as string]
            [clj-dns.core :refer [dns-lookup]]
            [org.bovinegenius  [exploding-fish :as uri]]
            [taoensso.timbre :as timblre :refer [warn info]])
  (:import org.xbill.DNS.Type))

(defn prn-tee
  ([obj] (prn-tee nil obj ))
  ([prefix obj] (if prefix (prn prefix)) (prn obj) obj))

(defn assoc-fn [m k f]
  (assoc m k (f (get m k))))

(defn assoc-in-fn [m ks f]
  (assoc-in m ks (f (get-in m ks))))

(defn map-walk [f m]
  (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m))

(defn is-ip? [s]
  (re-matches #"(?:\d{1,3}\.){3}\d{1,3}" s))

(defn resolve-host [host]
  (info (format "Resolving host %s..." host))
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
              (warn message)
              (throw
                (ex-info message {:user-visible true}))))))))

(defn host-resolved-url [url]
  (let [u (if (string? url) (uri/uri url) url)]
    (assoc u :host (resolve-host (uri/host u)))))
