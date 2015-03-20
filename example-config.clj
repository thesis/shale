;; shale service configuration. Rename to 'config" and place somewhere on the
;; class path (eg, resources/)

;; either provide a fn map suitable to implement shale.nodes/INodePool to inject
;; custom node management
{:node-pool-impl {:get-node (fn [this requirements]
                              (prn "custom node pool!")
                              "http://localhost:5555/wd/hub")
                  :add-node (fn [this requirements] nil)
                  :remove-node (fn [this url] nil)
                  :can-add-node (fn [this] false)
                  :can-remove-node (fn [this] false)}
 ;; or, configure it to use a cloud provider- AWS is currently supported.
 ;; this option uses https://github.com/mcohen01/amazonica#ec2, so make sure
 ;; you've supplied AWS credentials in your environment variables or elsewhere
 ;; (https://github.com/mcohen01/amazonica#authentication)
 :node-pool-cloud-config {:provider :aws
                          :ami "ami-12345678"
                          :tags {:service :shale}
                          :use-private-dns true}
 ;; if you just need a static list of nodes, provide that instead
 :node-list ["http://localhost:5555/wd/hub" "http://anotherhost:5555/wd/hub"]
 ;; the default max number of sessions to create per node.
 :node-max-sessions 3
 ;; optionally provide redis connection details suitable for use by carmine
 ;; eg http://ptaoussanis.github.io/carmine/taoensso.carmine.html#var-wcar
 ;; if not provided, the defaults will be used
 :redis {:host "localhost"
         :port 6379
         :db 0}
 ;; webdriver timeout, in milliseconds. this prevents shale from hanging when
 ;; a node has a memory or network issue. if not set, defaults to 1000. 0 means
 ;; block forever.
 :webdriver-timeout 1000
 ;; timeout starting a new webdriver, in milliseconds. typically this should
 ;; be high if you're using slow machines or a browser with a longer startup
 ;; time, like chrome. defaults to 5000. 0 means block forever.
 :start-webdriver-timeout 5000}
