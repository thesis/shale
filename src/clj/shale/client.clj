(ns shale.client
  (:require [clojure.set :refer [rename-keys]]
            [clojure.walk :as walk]
            [clj-http.client :as client]
            [cheshire [core :as json]]
            [clj-webdriver.taxi :as taxi]
            [slingshot.slingshot :refer [try+ throw+]]
            [taoensso.timbre :as timbre :refer [error]]
            [schema.core :as s]
            [camel-snake-kebab.core :refer [->snake_case_string]]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [shale.sessions :as sessions]
            [shale.webdriver :as webdriver])
  (:use [clj-webdriver.remote.driver :only [session-id]]
        shale.utils))

(def default-url-root "http://localhost:5000")

(def ^:private sessions-url
  (memoize
    (fn [url-root]
      (clojure.string/join "/" [url-root "sessions"]))))

(def ^:private nodes-url
  (memoize
    (fn [url-root]
      (clojure.string/join "/" [url-root "nodes"]) )))

(def ^:private proxies-url
  (memoize
    (fn [url-root]
      (clojure.string/join "/" [url-root "proxies"]) )))

(defn ^:private proxy-url [url-root id]
  (clojure.string/join "/" [(proxies-url url-root) id]))

(defn ^:private node-url [url-root id]
  (clojure.string/join "/" [(nodes-url url-root) id]))

(defn ^:private session-url [url-root id]
  (clojure.string/join "/" [(sessions-url url-root) id]))

(defn ^:private session-by-webdriver-url [url-root webdriver-id]
  (clojure.string/join "/" [(sessions-url url-root) "webdriver" webdriver-id]))

(s/defschema SessionIdentifier
  (s/either
    s/Str
    {:id s/Str}
    {:session-id s/Str}
    {:webdriver-id s/Str}))

(s/defn ^:always-validate map->session-url
  [url-root :- s/Str
   m-or-id :- SessionIdentifier]
  (if (map? m-or-id)
    (if (contains? m-or-id :webdriver-id)
      (session-by-webdriver-url url-root (:webdriver-id m-or-id))
      (session-url url-root (or (:session-id m-or-id) (:id m-or-id))))
    (session-url url-root m-or-id)))

(defn sessions
  ([] (sessions default-url-root))
  ([url-root]
   (let [response (try+
                    (client/get (sessions-url url-root))
                    (catch [:status 400] {:keys [body]} (error body) (throw+)))]
     (json/parse-string (response :body)))))

(defn nodes
  ([] (nodes default-url-root))
  ([url-root]
   (let [response (try+ (client/get (nodes-url url-root))
                        (catch [:status 400] {:keys [body]} (error body) (throw+)))]
     (json/parse-string (response :body)))))

(defn proxies
  ([] (proxies default-url-root))
  ([url-root]
   (let [response (try+ (client/get (proxies-url url-root))
                        (catch [:status 400] {:keys [body]}
                          (error body)
                          (throw+)))]
     (json/parse-string (response :body)))))

(defn create-proxy!
  ([requirements] (create-proxy! default-url-root
                                 requirements))
  ([url-root {:keys [proxy-type
                     host-and-port
                     public-ip
                     shared
                     active]
              :or {proxy-type "socks5"
                   shared true
                   active true
                   public-ip nil}
              :as requirements}]
   (let [body (->> (rename-keys requirements
                                {:host-and-port :private-host-and-port
                                 :proxy-type :type})
                   (transform-keys ->snake_case_string))
         response (try+
                    (client/post (proxies-url url-root)
                                 {:body (json/generate-string body)
                                  :content-type :json
                                  :accept :json})
                    (catch [:status 400] {:keys [body]}
                      (error body)
                      (throw+)))]
     (json/parse-string (response :body)))))

(defn modify-proxy!
  ([id options] (modify-proxy! default-url-root id options))
  ([url-root id {:keys [shared active]
                 :or {shared nil
                      active nil}
                 :as modifications}]

   (let [url (proxy-url url-root id)
         body (->> modifications
                   (filter #(not (nil? (val %))))
                   (into {})
                   (transform-keys ->snake_case_string))
         response (try+
                    (client/patch url
                                  {:body (json/generate-string body)
                                   :content-type :json
                                   :accept :json})
                    (catch [:status 400] {:keys [body]}
                      (error body)
                      (throw+)))]
     (json/parse-string (response :body)))))

(defn session
  ([id] (session default-url-root id))
  ([url-root id]
   (let [url (map->session-url url-root id)
         response (client/get url)]
     (json/parse-string (response :body)))))

(defn get-or-create-session!
  ([requirements] (get-or-create-session! default-url-root requirements))
  ([url-root {:keys [browser-name
                     node
                     prox
                     tags
                     reserve
                     reserved
                     force-create]
              :or {browser-name "phantomjs"
                   node nil
                   prox nil
                   reserve false
                   reserved nil
                   force-create false
                   tags #{}}
              :as requirements}]

   (let [and-reqs (fn [reqs]
                    (if (sequential? reqs)
                      (cond
                        (keyword? (first reqs)) reqs
                        (= (count reqs) 0) nil
                        :else [:and reqs])))
         tags-req (if (> (count tags) 0)
                    [:and (vec (map #(vec [:tag %]) tags))])
         node-reqs (->> [(into [] (dissoc node :url))
                         (map #(vec [[:tag %]]) (:tags node))]
                        (apply concat)
                        vec)
         node-req (if (> (count node-reqs) 0)
                    [:and node-reqs])
         prox-reqs (->> (into [] prox)
                        vec)
         prox-req (and-reqs prox-reqs)
         _ (prn "CLIENT: PROX REQS" prox-req)
         reqs (->> [(if browser-name [[:browser-name browser-name]])
                    (if tags-req [tags-req])
                    (if (some? reserved) [[:reserved reserved]])
                    (if node-req [[:node node-req]])
                    (if prox-req [[:proxy prox-req]])]
                  (apply concat)
                  vec)
         req (and-reqs reqs)
         _ (prn "CLIENT SESSION REQS" req)
         create-req (merge {:browser-name browser-name
                            :tags tags}
                           (if reserve {:reserved reserve})
                           (if node-req {:node-require node-req})
                           (if prox-req {:proxy-require prox-req}))
         modifications (->> [(if reserve [[:reserve true]])]
                            (apply concat)
                            vec)
         arg (merge (if modifications {:modify modifications})
                    {:create create-req}
                    (if (and (not force-create) req) {:require req}))
         body (walk/postwalk
                #(cond
                   (= % :socks5) "socks5"
                   (keyword? %) (->snake_case_string %)
                   :else %)
                arg)
         response (try+
                    (client/post (sessions-url url-root)
                                 {:body (json/generate-string body)
                                  :content-type :json
                                  :accept :json})
                    (catch [:status 400] {:keys [body]} (error body) (throw+)))]
     (json/parse-string (response :body)))))

(s/defn ^:always-validate modify-session!
  ([id :- SessionIdentifier
    modifications :- [sessions/ModifyArg]]
   (modify-session! default-url-root id modifications))
  ([url-root :- s/Str
    id :- SessionIdentifier
    modifications :- [sessions/ModifyArg]]
   (let [url (map->session-url url-root id)
         body (->> modifications
                   (map #(transform-keys ->snake_case_string %))
                   vec)
         response (try+
                    (client/patch url
                                  {:body (json/generate-string body)
                                   :content-type :json
                                   :accept :json})
                    (catch [:status 400] {:keys [body]}
                      (error body)
                      (throw+)))]
     (json/parse-string (response :body)))))

(defn reserve-session!
  ([id] (reserve-session! default-url-root id))
  ([url-root id]
   (modify-session! url-root id [[:reserve true]])))

(defn release-session!
  ([id] (release-session! default-url-root id))
  ([url-root id]
   (modify-session! url-root id [[:reserve false]])))

(s/defn destroy-session!
  ([id] (destroy-session! default-url-root id))
  ([url-root
    id]
   (let [url (map->session-url url-root id)]
     (client/delete url {:query-params {"immediately" "true"}}))
   nil))

(defn destroy-sessions!
  ([] (destroy-sessions! default-url-root))
  ([url-root]
   (client/delete (sessions-url url-root) {:query-params {"immediately" "true"}})
   nil))

(defn refresh-sessions!
  ([] (refresh-sessions! default-url-root))
  ([url-root]
   (let [response (try+
                    (client/post
                      (clojure.string/join "/"
                                           [(sessions-url url-root) "refresh"])
                      {})
                    (catch [:status 400] {:keys [body]} (error body) (throw+)))]
     (json/parse-string (response :body)))))

(defn get-or-create-webdriver!
  ([requirements] (get-or-create-webdriver! default-url-root requirements))
  ([url-root {:keys [browser-name
                     node
                     reserved
                     tags
                     reserve
                     force-create]
              :or {browser-name "phantomjs"
                   node nil
                   reserved nil
                   reserve nil
                   force-create nil
                   tags []}
              :as requirements}]
   (let [session (get-or-create-session! url-root requirements)]
     (webdriver/resume-webdriver
       (get session "webdriver_id")
       (get-in session ["node" "url"])
       {"platform" "ANY"
        "browserName" (get session "browser_name")}))))

(defn release-webdriver!
  ([driver] (release-webdriver! default-url-root driver))
  ([url-root driver]
   (if-let [session-id (session-id driver)]
     (release-session! url-root {:webdriver-id session-id}))))

(defmacro with-webdriver*
    "Given tags and a browser name to get or create a new webdriver session,
     execute the forms in `body`, then unreserve the webdriver session.

       Example :
       ========

       ;;
       ;; Log into Github
       ;;
       (use 'clj-webdriver.taxi)
       (with-webdriver* {:browser-name :firefox :tags [\"github\"]}
         (to \"https://github.com\")
         (click \"a[href*='login']\")
         (input-text \"#login_field\" \"your_username\")
         (-> \"#password\"
           (input-text \"your_password\")
           submit))"
  [options & body]
   (let [options-with-defaults (merge {:reserved false
                                       :reserve true} options)]
     `(binding [~'clj-webdriver.taxi/*driver*
                (get-or-create-webdriver! ~options-with-defaults)]
        (try
          ~@body
          (finally
            (release-webdriver! ~'clj-webdriver.taxi/*driver*))))))
