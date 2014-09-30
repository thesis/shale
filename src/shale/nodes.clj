(ns shale.nodes)

(defprotocol INodePool
  "Basic interface for choosing and managing Selenium nodes per session.
   Implementing this allows dynamic node domains- eg, by retrieving them from
   a cloud provider's API."

  (get-node [this requirements]
    "Get a node from the pool. Takes the same requirement map as
     get-or-create-session.")
  (add-node [this requirements]
    "Add a node to the pool that fulfills the requirement map.")
  (remove-node [this url]
    "Remove a node from the pool specific by url."))

(deftype DefaultNodePool [nodes]
  INodePool
  ;;"A simple node pool that chooses randomly from an initial list.
  (get-node [this requirements]
    (rand-nth nodes))

  (add-node [this requirements]
    (throw (ex-info "Unable to add new nodes to the default node pool."
                    {:user-visible true :status 500})))

  (remove-node [this requirements]
    (throw (ex-info "Unable to remove nodes with the default node pool."
                    {:user-visible true :status 500}))))
