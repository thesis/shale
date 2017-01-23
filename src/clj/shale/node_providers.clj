(ns shale.node-providers
  (:require [amazonica.aws.ec2 :as ec2]
            [clj-kube.core :as kube])
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

(deftype DefaultNodeProvider [nodes]
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

(defn ^:private describe-instances-or-throw []
  (mapcat #(get % :instances) (get (ec2/describe-instances) :reservations)))

(defn ^:private instances-running-shale []
  (filter #(and
             (= (get-in % [:state :name]) "running")
             (some (fn [i] (= (get i :key) "shale"))
                   (get % :tags)))
          (describe-instances-or-throw)))

(defn ^:private instance->node-url [instance use-private-dns]
  (format "http://%s:5555/wd/hub"
          (get instance
               (if use-private-dns :private-dns-name :public-dns-name))))

(deftype AWSNodeProvider [options]
  INodeProvider

  (get-nodes [this]
    (map #(instance->node-url % (get options :use-private-dns))
         (instances-running-shale)))
  (add-node [this url]
    (throw (ex-info "Adding nodes is not yet implemented."
                    {:user-visible true :status 500})))

  (remove-node [this url])

  (can-add-node [this] false)
  (can-remove-node [this] false))

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

(deftype KubeNodeProvider [api-url label port-name namespace]
  ;;; Uses pods with the given label. Label is a map containing a single key & value. port-name is the name of the container port to connect on
  INodeProvider
  (get-nodes [this]
    (assert (map? label))
    (assert (= 1 (count label)))
    (let [label-key (-> label first key)
          label-value (-> label first val)]
      (->> (kube/list-pods api-url)
           :items
           (filter (fn [pod]
                     (-> pod :metadata :labels (get label-key) (= label-value))))
           (map (fn [pod]
                  (selenium-url pod port-name))))))
  (add-node [this url]
    (throw (ex-info "Adding nodes is not yet implemented."
                    {:user-visible true :status 500})))

  (remove-node [this url])

  (can-add-node [this] false)
  (can-remove-node [this] false))
