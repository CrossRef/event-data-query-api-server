(ns event-data-query-api-server.core
  (:require [org.httpkit.server :as server])
  (:require [clojure.data.json :as json]
            [clojure.core.async :refer [chan <! >!! go-loop dropping-buffer]])
  (:require [clojure.tools.logging :as l])
  (:require [config.core :refer [env]])
  (:require [clj-time.core :as clj-time]
            [clj-time.format :as clj-time-format]
            [clj-time.coerce :as clj-time-coerce]
            [crossref.util.doi :as cr-doi]
            [compojure.core :refer [defroutes GET]]
            [ring.middleware.params :as middleware-params]
            [ring.middleware.resource :as middleware-resource]
            [ring.middleware.content-type :as middleware-content-type]
            [liberator.core :refer [defresource]])
  (:import [com.amazonaws.services.s3 AmazonS3 AmazonS3Client]
           [com.amazonaws.auth BasicAWSCredentials]
           [com.amazonaws.services.s3.model GetObjectRequest PutObjectRequest ObjectMetadata])
  (:gen-class))


(def ymd (clj-time-format/formatter "yyyy-MM-dd"))

(defn prev-date-str [date-str]
  (clj-time-format/unparse ymd (clj-time/minus (clj-time-format/parse ymd date-str) (clj-time/days 1))))

(defn next-date-str [date-str]
  (clj-time-format/unparse ymd (clj-time/plus (clj-time-format/parse ymd date-str) (clj-time/days 1))))

(defn get-aws-client
  []
  (new AmazonS3Client (new BasicAWSCredentials (:s3-access-key-id env) (:s3-secret-access-key env))))

(def aws-client (delay (get-aws-client)))

(defn upload-as-json
  "Upload a stream, return true if it worked."
  [structure bucket-name remote-name]
  (l/info "Uploading to" bucket-name remote-name)
  (let [^java.io.ByteArrayOutputStream buffer (new java.io.ByteArrayOutputStream)
        stream (new java.io.OutputStreamWriter buffer)]
    (json/write structure stream)
    (.close stream)
    (let [bytes (.toByteArray buffer)
          metadata (new ObjectMetadata)
          _ (.setContentType metadata "application/json")
          _ (.setContentLength metadata (alength bytes))
          request (new PutObjectRequest bucket-name remote-name (new java.io.ByteArrayInputStream bytes) metadata)]
      (.putObject @aws-client request))))

; A channel for uploading cached results in the background.
; Can be dropping because uploads are an optimisation, not on the critical path.
(def upload-chan (chan (dropping-buffer 1024)))

(defn run-uploads-background
  "Start running uploads in the background.
  Pull Clojure data structure to be JSON serialized, the bucket name and the bucket path."
  []
  (go-loop [[data bucket-name remote-name] (<! upload-chan)]
    (upload-as-json data bucket-name remote-name)))

(defn download-json-file
  "Download a JSON file from S3 and return parsed."
  [bucket-name remote-name]
  (l/info "Downloading from " bucket-name remote-name)
  (when
    (.doesObjectExist @aws-client bucket-name remote-name)
      (let [request (new GetObjectRequest bucket-name remote-name)
            obj (.getObject @aws-client request)
            ^InputStream stream (.getObjectContent obj)
            result (json/read (new java.io.BufferedReader (new java.io.InputStreamReader stream)) :key-fn keyword)]
        (.close obj)
        result)))

(def download-query-json-file (partial download-json-file (:query-data-bucket env)))

(defn get-cached
  "Cache the function and arg dict"
  [url-path fun args]
        ; fetch cached version from S3
  (let [cached (download-query-json-file url-path)

        ; generate if we didn't find it
        generated-result (when-not cached (fun args))

        ; could be nil
        result (or cached generated-result)]

    ; if we couldn't find it, save it in S3
    (when (and (not cached) generated-result)
      (>!! upload-chan [generated-result (:query-data-bucket env) url-path]))
  result))


; Construct paths
; These don't have leading slashes because they can also be S3 paths.
(defn path-view-date [args]
  (str "" (:view args) "/" (:date args) "/events.json"))

(defn path-view-date-source [args]
  (str "" (:view args) "/" (:date args) "/sources/" (:source args) "/events.json"))

(defn path-view-date-prefix [args]
  (str "" (:view args) "/" (:date args) "/prefixes/" (:prefix args) "/events.json"))

(defn path-view-date-source-work [args]
  (str "" (:view args) "/" (:date args) "/sources/" (:source args) "/works/" (:work args) "/events.json"))

(defn path-view-date-work [args]
  (str "" (:view args) "/" (:date args) "/works/" (:work args) "/events.json")) 

(defn format-api-response 
  "Format an API response complete with pagination etc."
  [path-f args events]
  
  (let [prev-date (prev-date-str (:date args))
        next-date (next-date-str (:date args))]
    
    {:meta
      {:status "ok"
       :message-type "event-list"
       :total (count events)
       :total-pages 1
       :page 1
       :previous (str (:service-base env) (path-f (assoc args :date prev-date)))
       :next (str (:service-base env) (path-f (assoc args :date next-date)))}
     ; if we got a nil, send empty list.
     :events (or events [])}))

; A slew of 'get artifact' functions. 
; Each returns the Artifact, either for serving directly to the client or for further use.
; Because the result of each of these could be consumed by an end user, the complete API response is returned.
; Results are cached as-returned.


(defn view-date
  "All activity for the view, date.
  This is the lowest common denomenator."
  [args]
  ; ignoring view at the moment
  (format-api-response
    path-view-date
    args
    (download-query-json-file (str (:view args) "/" (:date args) "/events-base.json"))))

(defn view-date-cached [args] (get-cached (path-view-date args) view-date args))

(defn view-date-source
  "All activity for the view, date, source."
  [args]
  (let [data (:events (view-date-cached args))]
    (format-api-response
      path-view-date-source
      args
      (filter #(= (:source_id %) (:source args)) data))))

(defn view-date-source-cached [args] (get-cached (path-view-date-source args) view-date-source args))

(defn view-date-prefix
  "All activity for the view, date, prefix."
  [args]
  ; {:keys [view date source]}
  (let [data (:events (view-date-cached args))]
    (format-api-response
      path-view-date-prefix
      args
      (filter #(= (cr-doi/get-prefix (:obj_id %)) (:prefix args)) data))))

(defn view-date-prefix-cached [args] (get-cached (path-view-date-prefix args) view-date-prefix args))

(defn view-date-source-work
  "All activity for view, date, source, work.
  Subfilter of the prefix-filtered view."
  [args]
  ;{:keys [view date source]}
  (let [prefix (cr-doi/get-prefix (:work args))
        data (:events (view-date-source-cached (assoc args :prefix prefix)))
        doi (cr-doi/normalise-doi (:work args))]
    (format-api-response
      path-view-date-source-work
      args
      (filter #(and
                (= (cr-doi/normalise-doi (:obj_id %)) doi)
                (= (:source_id %) (:source args))) data))))

(defn view-date-source-work-cached [args] (get-cached (path-view-date-source-work args) view-date-source-work args))

(defn view-date-work
  "All activity for the view, date, source.
  Subfilter of the prefix-filtered view."
  [args]
  (let [prefix (cr-doi/get-prefix (:work args))
        data (:events (view-date-prefix-cached (assoc args :prefix prefix)))
        doi (cr-doi/normalise-doi (:work args))]
    (format-api-response
      path-view-date-work
      args
      (filter #(= (cr-doi/normalise-doi (:obj_id %)) doi) data))))

(defn view-date-work-cached [args] (get-cached (path-view-date-work args) view-date-work args))

(defresource query
  [artifact-f args]
  :available-media-types ["application/json"]
  :exists? (fn [ctx]
            ; drop leading slash
            (let [url-path (.substring (or (-> ctx :request :uri) "") 1)
                  
                  ; result (get-cached url-path artifact-f args)
                  result (artifact-f args)

                  ]
              [result {::result result}]))
  :handle-ok (fn [ctx]
                (::result ctx))
  :handle-not-found (fn [ctx]
                      {"meta"
                        {"status" "ok"
                         "message-type" "event-list"
                         "total" 0
                         "total-pages" 1
                         "page" 1}
                        "events" []}))


(defroutes app-routes
  ; Standlone shows the content, but from within a standalone (e.g. PDF-linked) page.
  (GET "/:view/:date/events.json" [view date] (query view-date {:view view :date date}))
  (GET "/:view/:date/prefixes/:prefix/events.json" [view date prefix] (query view-date-prefix-cached {:view view :date date :prefix prefix}))
  (GET "/:view/:date/sources/:source/events.json" [view date source] (query view-date-source-cached {:view view :date date :source source}))
  (GET "/:view/:date/sources/:source/works/:work{.*?}/events.json" [view date source work] (query view-date-source-work-cached {:view view :date date :source source :work work}))
  (GET "/:view/:date/works/:work{.*?}/events.json" [view date work] (query view-date-work-cached {:view view :date date :work work})))

(def app
  (-> app-routes
     middleware-params/wrap-params
     (middleware-resource/wrap-resource "public")
     (middleware-content-type/wrap-content-type)))

(defn run []
  (let [port (Integer/parseInt (:server-port env))]
    (l/info "Start server on " port)
    ; a few background processes in case of peak load
    (dotimes [_ 10] (run-uploads-background))
    (server/run-server app {:port port}))
  (.join (Thread/currentThread)))



(defn -main
  [& args]
  (run))
