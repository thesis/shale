(ns shale.webapp
    (:require [reagent.core :as reagent :refer [atom]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [ajax.core :refer [GET]]))

;; -------------------------
;; Views

(defn a-href-text [text]
  [:a {:href text} text])

(defn home-page []
  [:div [:h2 "Shale"]
    [:nav
      [:ul
        [:li [:a {:href "/manage"} "Management Console"]]
        [:li [:a {:href "/docs"} "API Docs"]]]]])

(defn docs-page []
  [:div [:h2 "Shale"]
    [:ul
      [:li (a-href-text "/sessions")
        "Active Selenium sessions."]
      [:li (a-href-text "/sessions/:id")
        (str "A session identified by id."
             "Accepts GET, PUT, & DELETE.")]
      [:li (a-href-text "/sessions/refresh")
        "POST to refresh all sessions."]]])

(defn node-list-component []
  (let [nodes (atom [])
        load-nodes (fn [] (GET "/nodes" {:handler #(reset! nodes %)}))]
    (load-nodes)
    (js/setInterval load-nodes 5000)
    (fn []
      [:ul.node-list
       (for [node @nodes]
         [:li.node (get node "url")])])))

(defn browser-icon-component [browser]
  (case browser
    "chrome" [:i.fa.fa-chrome {:title browser}]
    "firefox" [:i.fa.fa-firefox {:title browser}]
    [:i.fa.fa-laptop {:title browser}]))

(defn session-component [session]
  (let [id (get session "id")
        browser (get session "browser_name")]
    [:div.btn-group
      [:a.session.btn.btn-default {:href (str "/manage/session/" id)}
        [browser-icon-component browser]
        [:span id]]
      [:button.btn.btn-default {:title "Destroy session"}
        [:i.fa.fa-remove]]]))

(defn session-list-component []
  (let [sessions (atom [])
        load-sessions (fn [] (GET "/sessions" {:handler #(reset! sessions %)}))]
    (load-sessions)
    (js/setInterval load-sessions 5000)
    (fn []
      [:ul.session-list
       (for [session @sessions]
         [:li (session-component session)])])))

(defn management-page []
  [:div [:h2.text-center "Shale Management Console"]
    [:div.col-md-4
      [:h3 "Nodes"]
      [node-list-component]]
    [:div.col-md-4
      [:h3 "Sessions"]
      [session-list-component]]])

(defn current-page []
  [:div [(session/get :current-page)]])

;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page #'home-page))

(secretary/defroute "/manage" []
  (session/put! :current-page #'management-page))

(secretary/defroute "/docs" []
  (session/put! :current-page #'docs-page))

;; -------------------------
;; Initialize app

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (accountant/configure-navigation!
    {:nav-handler
     (fn [path]
       (secretary/dispatch! path))
     :path-exists?
     (fn [path]
       (secretary/locate-route path))})
  (accountant/dispatch-current!)
  (mount-root))
