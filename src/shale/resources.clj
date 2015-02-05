(ns shale.resources
  (:require shale.sessions
            clojure.walk
            [taoensso.timbre :as timbre :refer [error]]
            [clj-json [core :as json]]
            [clojure.java.io :as io]
            [camel-snake-kebab.core :refer :all]
            [camel-snake-kebab.extras :refer  [transform-keys]])
  (:use [liberator.core :only  [defresource]]
        [compojure.core :only  [context ANY routes]]
        [hiccup.page :only [html5]]
        [clojure.set :only [rename-keys]]
        shale.utils)
  (:import [java.net URL]))

(defn json-keys [m]
  (transform-keys ->snake_case_string m))

(defn clojure-keys [m]
  (transform-keys ->kebab-case-keyword m))

(defn name-keys [m]
  (transform-keys name m))

(defn truth-from-str-vals [m]
  (map-walk (fn [[k v]]
              [k (if (#{"True" "1" 1 "true" true} v) true false)])
            m))

(defn jsonify [m]
  (json/generate-string
    (json-keys m)))

(defn is-json-content? [context]
  (if (#{:put :post} (get-in context [:request :request-method]))
    (or
     (re-matches #"application/json(?:;.*)?"
                 (or (get-in context [:request :headers "content-type"]) ""))
     [false {:message "Unsupported Content-Type"}])
    true))

(defn is-json-or-unspecified? [context]
  (or (nil? (get-in context [:request :headers "content-type"]))
      (is-json-content? context)))

(defn body-as-string [context]
  (if-let [body (get-in context [:request :body])]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn parse-json [context key]
  (when (#{:put :post} (get-in context [:request :request-method]))
    (try
      (if-let [body (body-as-string context)]
        (let [data (json/parse-string body)]
          [false {key data}])
        {:message "Empty body."})
      (catch Exception e
        (error e)
        {:message (format "Malformed JSON.")}))))

(defn build-session-url [request id]
  (URL. (format "%s://%s:%s%s/%s"
                (name (:scheme request))
                (:server-name request)
                (:server-port request)
                (:uri request)
                (str id))))

(defn a-href-text [text]
  [:a {:href text} text])

(defn handle-exception [context]
  (let [exc (:exception context)
        _ (error exc)
        info (ex-data exc)
        message (if (:user-visible info)
                  (.getMessage exc)
                  "Internal server error.")]
    (jsonify {:error message})))

(defn ->sessions-request [context]
  (rename-keys
    (clojure-keys
      (merge (get context ::data)
        (name-keys
          (truth-from-str-vals
            (get-in context [:request :params])))))
    {:reserve :reserve-after-create}))

(defresource sessions-resource [params]
  :allowed-methods  [:get :post]
  :available-media-types  ["application/json"]
  :known-content-type? is-json-or-unspecified?
  :malformed? #(parse-json % ::data)
  :handle-ok (fn [context]
               (jsonify (shale.sessions/view-models nil)))
  :handle-exception handle-exception
  :post! (fn [context]
           {::session (shale.sessions/get-or-create-session
                        (->sessions-request context))})
  :handle-created (fn [context]
                    (jsonify (get context ::session))))

(defresource session-resource [id]
  :allowed-methods [:get :put :delete]
  :available-media-types ["application/json"]
  :known-content-type? is-json-or-unspecified?
  :malformed? #(parse-json % ::data)
  :handle-ok (fn [context]
               (jsonify (get context ::session)))
  :handle-exception handle-exception
  :delete! (fn [context]
             (shale.sessions/destroy-session id))
  :put! (fn [context]
          {::session
           (shale.sessions/modify-session id (clojure-keys
                                               (get context ::data)))})
  :exists? (fn [context]
             (let [session (shale.sessions/view-model id)]
               (if-not (nil? session)
                 {::session session}))))

(defresource sessions-refresh-resource [id]
  :allowed-methods [:post]
  :available-media-types ["application/json"]
  :handle-exception handle-exception
  :post! (fn [context]
           (shale.sessions/refresh-sessions (if (nil? id) id [id]))))

(defresource nodes-resource [params]
  :allowed-methods  [:get]
  :available-media-types  ["application/json"]
  :known-content-type? is-json-or-unspecified?
  :malformed? #(parse-json % ::data)
  :handle-ok (fn [context]
               (jsonify (shale.nodes/view-models nil)))
  :handle-exception handle-exception)

(defresource nodes-refresh-resource []
  :allowed-methods [:post]
  :available-media-types ["application/json"]
  :handle-exception handle-exception
  :post! (fn [context]
           (shale.nodes/refresh-nodes)))

(defresource node-resource [id]
  :allowed-methods [:get :put :delete]
  :available-media-types ["application/json"]
  :known-content-type? is-json-or-unspecified?
  :malformed? #(parse-json % ::data)
  :handle-ok (fn [context]
               (jsonify (get context ::node)))
  :handle-exception handle-exception
  :delete! (fn [context]
             (shale.nodes/destroy-node id))
  :put! (fn [context]
          {::node
           (shale.nodes/modify-node id (clojure-keys
                                         (get context ::data)))})
  :exists? (fn [context]
             (let [node (shale.nodes/view-model id)]
               (if-not (nil? node)
                 {::node node}))))

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
                        "A session identified by id. Accepts GET, PUT, & DELETE."]
                       [:li (a-href-text "/sessions/refresh")
                        "POST to refresh all sessions."]
                       ]])))))

(defn assemble-routes []
  (routes
    (ANY "/" [] index)
    (ANY "/sessions" {params :params} sessions-resource)
    (ANY "/sessions/refresh" [] (sessions-refresh-resource nil))
    (ANY ["/sessions/:id", :id #"(?:[a-zA-Z0-9]{4,}-)*[a-zA-Z0-9]{4,}"]
      [id]
      (session-resource id))
    (ANY ["/sessions/:id/refresh", :id #"(?:[a-zA-Z0-9]{4,}-)*[a-zA-Z0-9]{4,}"]
      [id]
      (sessions-refresh-resource id))
    (ANY "/nodes" {params :params} nodes-resource)
    (ANY "/nodes/refresh" [] (nodes-refresh-resource))
    (ANY ["/nodes/:id", :id #"(?:[a-zA-Z0-9\-])+"]
      [id]
      (node-resource id))))
