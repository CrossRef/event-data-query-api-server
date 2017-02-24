(defproject event-data-query-api-server "0.0.3"
  :description "Serve the Event Data Query API"
  :url "http://eventdata.crossref.org"
  :license {:name "The MIT License (MIT)"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.395"]
                 [yogthos/config "0.8"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.apache.logging.log4j/log4j-api "2.7"]
                 [org.apache.logging.log4j/log4j-core "2.7"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [com.amazonaws/aws-java-sdk "1.11.49"]
                 ; This is required to make AWS and HTTP-kit and friends play nicely.
                 [org.apache.httpcomponents/httpclient "4.5.2"]

                 [javax/javaee-api "7.0"]
                 [http-kit "2.1.18"]
                 [http-kit.fake "0.2.1"]
                 [liberator "0.14.1"]
                 [compojure "1.5.1"]
                 [ring "1.5.0"]
                 [ring/ring-jetty-adapter "1.5.0"]
                 [ring/ring-servlet "1.5.0"]
                 [org.eclipse.jetty/jetty-server "9.4.0.M0"]
                 [overtone/at-at "1.2.0"]
                 [robert/bruce "0.8.0"]
                 [compojure "1.5.1"]
                 [crossref-util "0.1.13"]
                 [event-data-common "0.1.15"]]
  :main ^:skip-aot event-data-query-api-server.core
  :java-source-paths ["src-java"]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :prod {:resource-paths ["config/prod"]}
             :dev {:resource-paths ["config/dev"]}})
