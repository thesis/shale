(defproject shale "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [ring "1.3.1"]
                 [compojure "1.1.9"]
                 [liberator "0.12.2"]
                 [clj-json "0.5.3"]
                 [clj-webdriver "0.6.0"]
                 [clj-wallhack "1.0.1"]
                 [hiccup "1.0.5"]
                 [sonian/carica "1.1.0" :exclusions  [[cheshire]]]
                 [com.taoensso/carmine "2.7.0" :exclusions [org.clojure/clojure]]
                 [com.brweber2/clj-dns "0.0.2"]
                 [org.bovinegenius/exploding-fish "0.3.4"]]
  :auto-clean false
  :uberjar {:aot :all}
  :main shale.handler
  :plugins [[lein-ring "0.8.12"]]
  :ring {:handler shale.handler/app
         :port 5000}
  :profiles {:dev
             {:dependencies [[javax.servlet/servlet-api "2.5"]
                             [org.clojure/tools.trace "0.7.8"]]}})
