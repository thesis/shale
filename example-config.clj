;; shale service configuration. Rename to 'config" and place somewhere on the
;; class path (eg, resources/)

;; either provide a fn map suitable to implement shale.nodes/INodePool to inject
;; custom node management
{:node-pool-config {:get-node (fn [this requirements]
                                (prn "custom node pool!")
                                "http://localhost:5555/wd/hub")
                    :add-node (fn [this requirements] nil)
                    :remove-node (fn [this url] nil)
                    :can-add-node (fn [this] false)
                    :can-remove-node (fn [this] false)}
 ;; or, if you just need a static list of nodes, provide that
 :node-list ["http://localhost:5555/wd/hub" "http://localhost:5554/wd/hub"] }
