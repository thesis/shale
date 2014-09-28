(ns shale.resources
  (:require [liberator.dev :as dev]
            shale.sessions
            clojure.walk
            (clj-json  [core :as json]))
  (:use [liberator.core :only  [defresource]]
        [compojure.core :only  [context ANY routes]]
        [hiccup.page :only [html5]])
  (:import [java.net URL]))

(defn json-keys [m]
  (let [f (fn [[k v]]
        (if (keyword? k)
            [(clojure.string/replace (name k) "-" "_") v]
            [k v]))]
    (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn build-session-url [request id]
  (URL. (format "%s://%s:%s%s/%s"
                (name (:scheme request))
                (:server-name request)
                (:server-port request)
                (:uri request)
                (str id))))

(defn a-href-text [text]
  [:a {:href text} text])

(defresource sessions-resource
  :allowed-methods  [:get :post]
  :available-media-types  ["application/json"]
  :handle-ok (fn [context]
               (json/generate-string
                 (json-keys (shale.sessions/view-models nil))))
  :post! (fn [context] )
  :post-redirect true
  :location #(build-session-url (get % :request) (get % ::id)))

(defresource session-resource [id]
  :allowed-methods [:get :put :delete]
  :available-media-types ["application/json"]
  :handle-ok (fn [context]
               (json/generate-string
                 (json-keys (::session context))))
  :delete! (fn [context]
             (shale.sessions/destroy-session id))
  :exists? (fn [context]
             (let [session (shale.sessions/view-model id)]
               (if-not (nil? session)
                 {::session session}))))

(defresource sessions-refresh-resource [id]
  :allowed-methods [:post]
  :available-media-types ["application/json"]
  :post! (fn [context]
           (shale.sessions/refresh-sessions (if (nil? id) id [id]))))

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
                       [:li (a-href-text "/sessions") "Active Selenium sessions."]
                       [:li (a-href-text "/sessions/:id")
                        "A session identified by id. Accepts GET, PUT, & DELETE."]]])))))

(defn assemble-routes []
  (->
   (routes
    (ANY "/" [] index)
    (ANY "/sessions" [] sessions-resource)
    (ANY "/sessions/refresh" [] (sessions-refresh-resource nil))
    (ANY ["/sessions/:id", :id #"(?:[a-zA-Z0-9]{4,}-)*[a-zA-Z0-9]{4,}"]
         [id]
         (session-resource id))
    (ANY ["/sessions/:id/refresh", :id #"(?:[a-zA-Z0-9]{4,}-)*[a-zA-Z0-9]{4,}"]
         [id]
         (sessions-refresh-resource id))
    )
   (dev/wrap-trace :ui :header)))
