(ns shale.resources
  (:require [liberator.dev :as dev]
            shale.sessions
            clojure.walk)
  (:use [liberator.core :only  [defresource]]
        [compojure.core :only  [context ANY routes]]
        [hiccup.page :only [html5]]))

(defn json-keys [m]
  (let [f (fn [[k v]]
        (if (keyword? k)
            [(clojure.string/replace (name k) "-" "_") v]
            [k v]))]
    (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defresource sessions
  :handle-ok (fn [context] (json-keys (shale.sessions/view-models nil)))
  :available-media-types  ["application/json"]
  :allowed-methods  [:get :post])

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
    (ANY "/sessions" [] sessions))

   (dev/wrap-trace :ui :header)))
