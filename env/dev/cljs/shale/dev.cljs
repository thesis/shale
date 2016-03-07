(ns ^:figwheel-no-load shale.dev
  (:require [shale.webapp :as webapp]
            [figwheel.client :as figwheel :include-macros true]))

(enable-console-print!)

(figwheel/watch-and-reload
  :websocket-url "ws://localhost:3449/figwheel-ws"
  :jsload-callback webapp/mount-root)

(webapp/init!)
