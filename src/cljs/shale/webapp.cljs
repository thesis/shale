(ns shale.webapp
    (:require [reagent.core :as reagent :refer [atom]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [ajax.core :refer [GET DELETE PUT]]))

;; -------------------------
;; Data

(defn delete-session [session-id]
  (DELETE (str "/sessions/" session-id)))

(defn get-nodes [success-fn]
  (GET "/nodes" {:handler success-fn}))

(defn get-sessions [success-fn]
  (GET "/sessions" {:handler success-fn}))

(defn set-session-reservation [session-id reserved finally-fn]
  (PUT (str "/sessions/" session-id)
       {:format :json
        :params {"reserved" reserved}
        :finally finally-fn}))

;; -------------------------
;; Components

(defn a-href-text [text]
  [:a {:href text} text])

(defn node-component [node]
  (let [url (get node "url")
        id (get node "id")]
    ^{:key id} [:div.btn-group
      [:a.node.btn.btn-default {:href (str "/manage/node/" id)}
        [:i.fa.fa-share-alt] url]]))

(defn node-list-component []
  (let [nodes (atom [])
        load-nodes (fn [] (get-nodes #(reset! nodes %)))]
    (load-nodes)
    (js/setInterval load-nodes 5000)
    (fn []
      [:ul.node-list
       (for [node @nodes]
         [:li [node-component node]])])))

(defn browser-icon-component [browser]
  (case browser
    "chrome" [:i.fa.fa-chrome {:title browser}]
    "firefox" [:i.fa.fa-firefox {:title browser}]
    [:i.fa.fa-laptop {:title browser}]))

(defn reserve-button-component [session reserving reserve-fn]
  (let [id (get session "id")
        reserved (get session "reserved")
        title (if reserved "Unreserve session" "Reserve session")]
    [:button.btn.btn-default {:title title
                              :on-click #(reserve-fn (not (boolean reserved)))}
     (if (some #{id} reserving)
      [:i.fa.fa-spinner.fa-spin]
       (if reserved
         [:i.fa.fa-lock]
         [:i.fa.fa-unlock]))]))

(defn destroy-button-component [session deleting delete-fn]
  (let [id (get session "id")]
    [:button.btn.btn-default {:title "Destroy session"
                              :on-click delete-fn}
      (if (some #{id} deleting)
        [:i.fa.fa-spinner.fa-spin]
        [:i.fa.fa-remove])]))

(defn session-component [session]
  (let [deleting (atom [])
        reserving (atom [])]
    (fn [session]
      (let [id (get session "id")
            browser (get session "browser_name")
            delete-fn (fn []
                        (swap! deleting #(conj % id))
                        (delete-session id))
            remove-from-reserving! (fn []
                                     (swap! reserving
                                            #(filter (complement #{id}) %)))
            reserve-fn (fn [reserve]
                         (swap! reserving #(conj % id))
                         (set-session-reservation
                           id reserve remove-from-reserving!))]
        ^{:key id} [:div.btn-group
          [:a.session.btn.btn-default {:href (str "/manage/session/" id)}
            [browser-icon-component browser]
            [:span id]]
          [reserve-button-component session @reserving reserve-fn]
          [destroy-button-component session @deleting delete-fn]]))))

(defn session-list-component []
  (let [sessions (atom [])
        load-sessions (fn [] (get-sessions #(reset! sessions %)))]
    (load-sessions)
    (js/setInterval load-sessions 5000)
    (fn []
      [:ul.session-list
       (for [session @sessions]
         [:li [session-component session]])])))

;; -------------------------
;; Pages

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

(defn management-page []
  [:div [:h2.text-center "Shale Management Console"]
    [:div.col-md-3
      [:h3 "Nodes"]
      [node-list-component]]
    [:div.col-md-5
      [:h3 "Sessions"]
      [session-list-component]]])

(defn node-page [id]
  [:div [:h2.text-center "Shale Management Console"]
    [:div.col-md-6.col-md-offset-3
      [:h3.text-center (str "Node " id)]]])

(defn session-page [id]
  [:div [:h2.text-center "Shale Management Console"]
    [:div.col-md-6.col-md-offset-3
      [:h3.text-center (str "Session " id)]]])

(defn current-page []
  (session/get :current-page))

;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page [#'home-page]))

(secretary/defroute "/manage" []
  (session/put! :current-page [#'management-page]))

(secretary/defroute "/manage/node/:id" [id]
  (session/put! :current-page [#'node-page id]))

(secretary/defroute "/manage/session/:id" {id :id}
  (session/put! :current-page [#'session-page id]))

(secretary/defroute "/docs" []
  (session/put! :current-page [#'docs-page]))

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
