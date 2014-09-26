(ns shale.resources
  (:require [liberator.dev :as dev])
  (:use [liberator.core :only  [defresource]]
        [compojure.core :only  [context ANY routes]]
        [hiccup.page :only [html5]]))

(defresource sessions
  :handle-ok "[]"
  :available-media-types  ["application/json"])

(defresource index
  :available-media-types ["text/html" "application/json"]
  :handle-ok (fn [context]
               (let [media-type (get-in context [:representation :media-type])]
                 (condp = media-type
                   "text/html"
                   (html5 [:head [:title "Shale"]]
                     [:body
                       [:h1 "Shale - Selenium Manager / Hub Replacement"]
                       [:ul
                       [:li [:a {:href "/sessions"} "Active Selenium sessions."]]]])))))

(defn assemble-routes []
  (->
   (routes
    (ANY "/" [] index)
    (ANY "/hello-world" [] hello-world)
    (ANY "/sessions" [] sessions))

   (dev/wrap-trace :ui :header)))
