(ns shale.resources
  (:require [liberator.dev :as dev]
            shale.sessions
            clojure.walk
            (clj-json [core :as json])
            [clojure.java.io :as io])
  (:use [liberator.core :only  [defresource]]
        [compojure.core :only  [context ANY routes]]
        [hiccup.page :only [html5]]
        [clojure.set :only [rename-keys]]
        shale.utils)
  (:import [java.net URL]))

(defn map-walk [f m]
  (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m))

(defn json-keys [m]
  (map-walk (fn [[k v]]
               (if (keyword? k)
                 [(clojure.string/replace (name k) "-" "_") v]
                 [k v]))
            m))

(defn clojure-keys [m]
  (map-walk (fn [[k v]]
               (if (keyword? k)
                 [k v]
                 [(keyword (clojure.string/replace k "_" "-"))  v]))
            m))

(defn name-keys [m]
  (map-walk (fn [[k v]]
              [(name k) v])
            m))

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
     (= (get-in context [:request :headers "content-type"]) "application/json")
     [false {:message "Unsupported Content-Type"}])
    true))

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
        (.printStackTrace e)
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

(defresource sessions-resource [params]
  :allowed-methods  [:get :post]
  :available-media-types  ["application/json"]
  :known-content-type? is-json-content?
  :malformed? #(parse-json % ::data)
  :handle-ok (fn [context]
               (jsonify (shale.sessions/view-models nil)))
  :post! (fn [context]
           {::session
            (shale.sessions/get-or-create-session
              (rename-keys (clojure-keys
                             (merge (get context ::data)
                                    (name-keys
                                      (truth-from-str-vals
                                        (params :params)))))
                                    {:reserve :reserve-after-create}))})
  :handle-created (fn [context]
                    (jsonify (get context ::session))))

(defresource session-resource [id]
  :allowed-methods [:get :put :delete]
  :available-media-types ["application/json"]
  :known-content-type? is-json-content?
  :malformed? #(parse-json % ::data)
  :handle-ok (fn [context]
               (jsonify (get context ::session)))
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
                        "A session identified by id. Accepts GET, PUT, & DELETE."]
                       [:li (a-href-text "/sessions/refresh")
                        "POST to refresh all sessions."]
                       ]])))))

(defn assemble-routes []
  (->
   (routes
    (ANY "/" [] index)
    (ANY "/sessions" {params :params} sessions-resource)
    (ANY "/sessions/refresh" [] (sessions-refresh-resource nil))
    (ANY ["/sessions/:id", :id #"(?:[a-zA-Z0-9]{4,}-)*[a-zA-Z0-9]{4,}"]
         [id]
         (session-resource id))
    (ANY ["/sessions/:id/refresh", :id #"(?:[a-zA-Z0-9]{4,}-)*[a-zA-Z0-9]{4,}"]
         [id]
         (sessions-refresh-resource id)))
   (dev/wrap-trace :ui :header)))
