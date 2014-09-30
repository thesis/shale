(defprotocol NodePool
  "Basic interface for choosing and managing Selenium nodes per session.
   Implementing this allows dynamic node domains- eg, by retrieving them from
   a cloud provider's API."

  (get-node [this requirements] "")
  (add-node [this requirements] "")
  (remove-node [this url] ""))

(deftype DefaultNodePool [nodes] NodePool
  "A simple node pool the chooses randomly from an initial list."
  (get-node [this requirements]
    (rand-nth nodes))

  (add-node [this requirements]
    (throw (ex-info "Unable to add new nodes to the default node pool."
                    {:user-visible true :status 500})))

  (remove-node [this requirements]
    (throw (ex-info "Unable to remove nodes with the default node pool."
                    {:user-visible true :status 500})))
  )
