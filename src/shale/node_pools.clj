(ns shale.node-pools
  (:import java.io.FileNotFoundException))

(try
  (require '[amazonica.aws.ec2])
  (catch FileNotFoundException e))

(defprotocol INodePool
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

(deftype DefaultNodePool [nodes]
  INodePool
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
  (let [describe-instances
            (ns-resolve 'amazonica.aws.ec2 'describe-instances)]
        (if describe-instances
          (mapcat #(get % :instances) (get (describe-instances) :reservations))
          (throw
            (ex-info
              (str "Unable to configure connect to the AWS API- make sure "
                   "amazonica is listed in your dependencies.")
              {:user-visible true :status 500})))))

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

(deftype AWSNodePool [options]
    INodePool

    (get-nodes [this]
      (map #(instance->node-url % (get options :use-private-dns))
           (instances-running-shale)))
    (add-node [this url]
      (throw (ex-info "Adding nodes is not yet implemented."
                      {:user-visible true :status 500})))

    (remove-node [this url])

    (can-add-node [this] false)
    (can-remove-node [this] false))
