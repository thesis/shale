(ns shale.prod
  (:require [shale.webapp :as webapp]))

;;ignore println statements in prod
(set! *print-fn* (fn [& _]))

(webapp/init!)
