(defproject shale "0.2.1-SNAPSHOT"
  :description "A Clojure-backed Selenium hub replacement"
  :url "https://github.com/cardforcoin/shale"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [compojure "1.4.0"]
                 [javax.servlet/servlet-api "2.5"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [org.clojure/tools.nrepl "0.2.11"]
                 [liberator "0.12.0"]
                 [clj-http "1.1.2"]
                 [cheshire "5.4.0"]
                 [clj-webdriver "0.6.1"]
                 [clj-wallhack "1.0.1"]
                 [hiccup "1.0.5"]
                 [sonian/carica "1.1.0" :exclusions  [[cheshire]]]
                 [environ "1.0.0"]
                 [com.taoensso/carmine "2.7.0" :exclusions [org.clojure/clojure]]
                 [com.taoensso/timbre "3.3.1"]
                 [com.brweber2/clj-dns "0.0.2"]
                 [org.bovinegenius/exploding-fish "0.3.4"]
                 [com.cemerick/url "0.1.1"]
                 [overtone/at-at "1.2.0"]
                 [prismatic/schema "0.3.0"]
                 [slingshot "0.12.2"]
                 [camel-snake-kebab "0.2.5" :exclusions [org.clojure/clojure]]
                 [io.aviso/pretty "0.1.19"]]
  :scm {:name "git"
        :url "http://github.com/cardforcoin/shale"}
  :auto-clean false
  :main shale.core
  :plugins [[lein-ring "0.8.12"]
            [lein-environ "1.0.0"]]
  :test-selectors {:default (complement :integration)
                   :integration :integration
                   :all (constantly true)}
  :ring {:handler shale.handler/app
         :init shale.core/init
         :destroy shale.core/destroy
         :port 5000}
  :profiles {:dev
              {:dependencies [[org.clojure/tools.trace "0.7.8"]
                              [ring-mock "0.1.5"]]}
             :aws {:dependencies [[amazonica "0.2.26" :exclusions [joda-time]]]
                   :uberjar-name "shale-aws.jar"}
             :uberjar {:aot :all}}
  :aot [#"shale\.ext\.*" #"shale\.core"])
