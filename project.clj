(defproject shale "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [compojure "1.1.9"]
                 [liberator "0.12.2"]
                 [clj-json "0.5.3"]
                 [clj-webdriver "0.6.0"]
                 [clj-wallhack "1.0.1"]
                 [hiccup "1.0.5"]
                 [sonian/carica "1.1.0" :exclusions  [[cheshire]]]
                 [com.taoensso/carmine "2.7.0" :exclusions [org.clojure/clojure]]
                 [com.brweber2/clj-dns "0.0.2"]]

  :plugins [[lein-ring "0.8.11"]]
  :ring {:handler shale.handler/app}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring-mock "0.1.5"]]}})
