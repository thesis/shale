

(defprotocol NodePool
  "Basic interface for choosing and managing Selenium nodes per session.
   Implementing this allows dynamic node domains- eg, by retrieving them from
   a cloud provider's API."

  (get-node [this requirements] "")
  (add-node [this requirements] "")
  (remove-node [this url] ""))

(deftype DefaultNodePool [options] NodePool
  ""
  ()
  )
