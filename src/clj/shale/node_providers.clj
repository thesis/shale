(ns shale.node-providers
  (:require [amazonica.aws.ec2 :as ec2]
            [clj-kube.core :as kube]
            [environ.core :as env])
  (:import java.io.FileNotFoundException))

(try
  (require '[amazonica.aws.ec2])
  (catch FileNotFoundException e))

(defprotocol INodeProvider
  "Basic interface for choosing and managing Selenium nodes per session.
   Implementing this allows dynamic node domains- eg, by retrieving them from
   a cloud provider's API."

  (get-nodes [this])

  (add-node [this url]
    "Add a new node to the pool. If a url is provided, stick with that. Otherwise
    attempt to create a new node.")
  (remove-node [this url]
    "Remove a node from the pool specific by url.")
  (can-add-node [this]
    "Whether this pool supports adding new nodes.")
  (can-remove-node [this]
    "Whether this pool supports removing nodes."))

(defrecord DefaultNodeProvider [nodes]
  INodeProvider
  ;;A simple node pool that chooses randomly from an initial list.
  (get-nodes [this]
    nodes)

  (add-node [this requirements]
    (throw (ex-info "Unable to add new nodes to the default node pool."
                    {:user-visible true :status 500})))

  (remove-node [this url]
    (throw (ex-info "Unable to remove nodes with the default node pool."
                    {:user-visible true :status 500})))

  (can-add-node [this] false)
  (can-remove-node [this] false))

;; (s/fdef new-default-node-provider :args (s/cat :nodes (s/coll-of string?)))
(defn new-default-node-provider [nodes]
  (->DefaultNodeProvider nodes))

(defn- describe-instances-or-throw []
  (mapcat #(get % :instances) (get (ec2/describe-instances) :reservations)))

(defn- instances-running-shale []
  (filter #(and
             (= (get-in % [:state :name]) "running")
             (some (fn [i] (= (get i :key) "shale"))
                   (get % :tags)))
          (describe-instances-or-throw)))

(defn- instance->node-url [instance use-private-dns]
  (format "http://%s:5555/wd/hub"
          (get instance
               (if use-private-dns :private-dns-name :public-dns-name))))

(defrecord AWSNodeProvider [use-private-dns]
  INodeProvider

  (get-nodes [this]
    (map #(instance->node-url % use-private-dns)
         (instances-running-shale)))
  (add-node [this url]
    (throw (ex-info "Adding nodes is not yet implemented."
                    {:user-visible true :status 500})))

  (remove-node [this url])

  (can-add-node [this] false)
  (can-remove-node [this] false))

(defn new-aws-node-provider [{:keys [use-private-dns]
                              :as options}]
  (map->AWSNodeProvider options))

(defn selenium-url
  [pod port-name]
  (let [port (->> pod
                  :spec
                  :containers
                  (mapcat :ports)
                  (filter (fn [p]
                            (-> p :name (= port-name))))
                  first)
        _ (assert port)
        port-num (:containerPort port)]
    (str "http://" (-> pod :status :podIP) ":" port-num)))

(defrecord KubeNodeProvider [api-url]
  INodeProvider
  (get-nodes [this]
    (let [label (:kube/label this)
          _ (assert (map? label))
          _ (assert (= 1 (count label)))
          label-key (-> label first key)
          label-value (-> label first val)]
      (->> (kube/list-pods api-url {:namespace (or (:kube/namespace this) "default")})
           :items
           (filter (fn [pod]
                     (-> pod :metadata :labels (get label-key) (= label-value))))
           (map (fn [pod]
                  (selenium-url pod (:kube/port-name this)))))))
  (add-node [this url]
    (throw (ex-info "Adding nodes is not yet implemented."
                    {:user-visible true :status 500})))

  (remove-node [this url])

  (can-add-node [this] false)
  (can-remove-node [this] false))

;; (s/def :kube/api-url string?)
;; (s/def :kube/label (s/map-of keyword? string?))
;; (s/def :kube/port-name string?)
;; (s/def :kube/namespace string?)

;; (s/fdef new-kube-node-provider :args (s/keys :req-un [:kube/api-url :kube/label :kube/port-name] :opt-un [:kube/namespace]))
(defn new-kube-node-provider [{:keys [api-url label port-name]
                               :as options}]
  "Use pods with the given kubernetes label. Label is a map containing a single key & value. port-name is the named container port to connect on"
  (assert (= :kube (:provider options)))
  (assert api-url)
  (let [api-url (if (and (keyword? api-url) (= "env" (namespace api-url)))
                  (do
                    (assert (get env/env (keyword (name api-url))))
                    (str "https://" (get env/env (keyword (name api-url)))))
                  api-url)
        options (assoc options :api-url api-url)]
    (map->KubeNodeProvider options)))
