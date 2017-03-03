(ns event-data-query-api-server.core
  (:require [org.httpkit.server :as server]
            [org.httpkit.client :as client]
            [event-data-common.jwt :as jwt]
            [event-data-common.artifact :as artifact])
  (:require [clojure.data.json :as json]
            [clojure.core.async :refer [chan <! >!! go-loop dropping-buffer]])
  (:require [clojure.tools.logging :as log])
  (:require [config.core :refer [env]])
  (:require [clj-time.core :as clj-time]
            [clj-time.format :as clj-time-format]
            [clj-time.coerce :as clj-time-coerce]
            [crossref.util.doi :as cr-doi]
            [compojure.core :refer [defroutes GET]]
            [ring.middleware.params :as middleware-params]
            [ring.middleware.resource :as middleware-resource]
            [ring.middleware.content-type :as middleware-content-type]
            [liberator.core :refer [defresource]]
            [liberator.representation :as representation]
            [ring.util.response :as ring-response])
  (:import [com.amazonaws.services.s3 AmazonS3 AmazonS3Client]
           [com.amazonaws.auth BasicAWSCredentials]
           [com.amazonaws.services.s3.model GetObjectRequest PutObjectRequest ObjectMetadata])
  (:gen-class))

(def event-data-homepage "https://www.crossref.org/services/event-data")

(def sourcelist-name
  "Artifact name for our source list."
  "crossref-sourcelist")

(defn get-sourcelist
  "Fetch a set of source_ids that we're allowed to serve."
  []
  (let [source-names (-> sourcelist-name artifact/fetch-latest-artifact-string (clojure.string/split #"\n") set)]
    (log/info "Retrieved source names:" source-names)
    source-names))

; Load at startup. The list changes so infrequently that the server can be restarted when a new one is added.
(def sourcelist (future (get-sourcelist)))

(defn event-dois
  "An Event may have a DOI in the subj or obj or neither. Return a set of normalized DOIs found in either position."
  [event]
  (let [subj_id (:subj_id event)
        obj_id (:obj_id event)
        subj_doi (when (cr-doi/well-formed subj_id) (cr-doi/normalise-doi subj_id))
        obj_doi (when (cr-doi/well-formed obj_id) (cr-doi/normalise-doi obj_id))]
    (set (filter identity [subj_doi obj_doi]))))

(defn event-prefixes
  "An Event may have a DOI in the subj or obj or neither. Return a set of DOI prefixes found in either position."
  [event]
  (let [subj_id (:subj_id event)
        obj_id (:obj_id event)
        subj_prefix (when (cr-doi/well-formed subj_id) (cr-doi/get-prefix subj_id))
        obj_prefix (when (cr-doi/well-formed obj_id) (cr-doi/get-prefix obj_id))]
    (set (filter identity [subj_prefix obj_prefix]))))

(def ymd (clj-time-format/formatter "yyyy-MM-dd"))

; Dates for these ranges shouldn't be served.
; This allows a user to follow pagination links back in time blindly in the past.
; It also makes sure we don't serve up a response for data not yet collected (tomorrow).
(defn earliest-date [] (clj-time/date-time 2016 01 01))
(defn latest-date [] (clj-time/today-at 0 0))

(defn prev-date-str [date-str]
  (clj-time-format/unparse ymd (clj-time/minus (clj-time-format/parse ymd date-str) (clj-time/days 1))))

(defn next-date-str [date-str]
  (clj-time-format/unparse ymd (clj-time/plus (clj-time-format/parse ymd date-str) (clj-time/days 1))))

; Future of a set of source ids to exclude. 
(def exclude-source-ids (future (set (.split (or (:exclude-source-ids env) "") ","))))

(defn get-aws-client
  []
  (new AmazonS3Client (new BasicAWSCredentials (:s3-key env) (:s3-secret env))))

(def aws-client (delay (get-aws-client)))

(defn upload-as-json
  "Upload a stream, return true if it worked."
  [structure bucket-name remote-name]
  (log/info "Uploading to" bucket-name remote-name)
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
  (log/info "Starting background uploads")
  (go-loop [[data bucket-name remote-name] (<! upload-chan)]
    (log/info "Background upload" remote-name)
    (upload-as-json data bucket-name remote-name)
    (log/info "Finished background upload" remote-name)))

; Empty token is enough to connect.
(def jwt-signer (delay (jwt/build (:jwt-secrets env))))
(def jwt-token (delay (jwt/sign @jwt-signer {"sub" "*"})))

(defn download-json-file
  "Download a JSON file from S3 and return parsed."
  [bucket-name remote-name]
  (log/info "Downloading from " bucket-name remote-name)
  (when
    (.doesObjectExist @aws-client bucket-name remote-name)
      (let [request (new GetObjectRequest bucket-name remote-name)
            obj (.getObject @aws-client request)
            ^InputStream stream (.getObjectContent obj)
            result (json/read (new java.io.BufferedReader (new java.io.InputStreamReader stream)) :key-fn keyword)]
        (.close obj)
        result)))

(defn download-event-bus
  "Download from the Event Bus. Request is JWT authenticated."
  [url]
  (log/info "Downloading from Event Bus" url "with auth" @jwt-token)
  (let [response @(client/get url {:as :stream
                                   :headers {"Authorization" (str "Bearer " @jwt-token)}
                                   :timeout 900000})
        body (:body response)
        status (:status response)
        parsed (when (and body (= 200 status))
                 (json/read (clojure.java.io/reader body) :key-fn keyword))]

    (when-not (= 200 status)
      (log/error "Error from Event Bus: status" status " url:" url)
      (throw (new Exception "Internal error connecting to Event Bus.")))
    parsed))

(def download-query-json-file (partial download-json-file (:s3-bucket-name env)))

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
      (log/info "Enqueue cache:" url-path)
      (>!! upload-chan [generated-result (:s3-bucket-name env) url-path]))
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
  ; Top-level comes directly from the Event Bus.
  (let [events-response (download-event-bus (str (:event-bus-base env) "/events/archive/" (:date args)))
        events (:events events-response)
        filtered (remove #(@exclude-source-ids (:source_id %)) events)]
    (format-api-response
      path-view-date
      args
      filtered)))

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
  (let [data (:events (view-date-cached args))]
    (format-api-response
      path-view-date-prefix
      args
      (filter #((event-prefixes %) (:prefix args))
              data))))

(defn view-date-prefix-cached [args] (get-cached (path-view-date-prefix args) view-date-prefix args))

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
      (filter #((event-dois %) doi) data))))

(defn view-date-work-cached [args] (get-cached (path-view-date-work args) view-date-work args))

(defn view-date-source-work
  "All activity for view, date, source, work.
  Subfilter of the prefix-filtered view."
  [args]
  ;{:keys [view date source]}
  (let [prefix (cr-doi/get-prefix (:work args))
        ; prefix is more restrictive than source, less data to deal with
        data (:events (view-date-work-cached (assoc args :prefix prefix)))
        doi (cr-doi/normalise-doi (:work args))]
    (format-api-response
      path-view-date-source-work
      args
      (filter #(and
                ((event-dois %) doi)
                (= (:source_id %) (:source args))) data))))

(defn view-date-source-work-cached [args] (get-cached (path-view-date-source-work args) view-date-source-work args))

(defn try-parse-date
  "Parse date or nil on failure."
  [date-str]
  (try
    (clj-time-format/parse ymd date-str)
    (catch Exception _ nil)))

(defresource query
  [artifact-f args]
  :available-media-types ["application/json"]
  :malformed? (fn [ctx]
                (let [date (:date args)
                      parsed-date (and date (try-parse-date date))
                      view-ok (#{"collected" "occurred"} (:view args))]
                [(not (and date parsed-date view-ok))
                  {::parsed-date parsed-date}]))
  :exists? (fn [ctx]
            ; drop leading slash
            (let [url-path (.substring (or (-> ctx :request :uri) "") 1)
                  
                  result (artifact-f args)

                  date-ok (and
                            (clj-time/after? (::parsed-date ctx) (earliest-date))
                            (clj-time/before? (::parsed-date ctx) (latest-date)))]
              [(and date-ok result) {::result result}]))

  :handle-ok (fn [ctx]
                (let [override-whitelist (= (get-in ctx [:request :params "whitelist"]) "false")
                      experimental (= (get-in ctx [:request :params "experimental"]) "true")
                      events (:events (::result ctx))

                      ; Only keep whitelisted sources, unless override.
                      filtered-whitelist (if override-whitelist
                                           events
                                           (filter #(@sourcelist (:source-id %)) events))
                      
                      ; Remove experimental results, unless overrirde.
                      filtered-experimental (if experimental
                                              filtered-whitelist
                                              (remove :experimental filtered-whitelist))

                      final-events filtered-experimental]
                (log/info "Query override?" override-whitelist "experimental?" experimental "args" args)
                (assoc (::result ctx) :events final-events)))

  :handle-not-found (fn [ctx]
                      {"meta"
                        {"status" "error"
                         "message-type" "event-list"
                         "total" 0
                         "total-pages" 1
                         "page" 1}
                        "events" []}))


(defresource home
  []
  :available-media-types ["text/html"]
  :handle-ok (fn [ctx]
                (representation/ring-response
                  (ring-response/redirect event-data-homepage))))

(defroutes app-routes
  (GET "/" [] (home))

  ; Standlone shows the content, but from within a standalone (e.g. PDF-linked) page.
  (GET "/:view/:date/events.json" [view date] (query view-date-cached {:view view :date date}))
  (GET "/:view/:date/prefixes/:prefix/events.json" [view date prefix] (query view-date-prefix-cached {:view view :date date :prefix prefix}))
  (GET "/:view/:date/sources/:source/events.json" [view date source] (query view-date-source-cached {:view view :date date :source source}))
  (GET "/:view/:date/sources/:source/works/:work{.*?}/events.json" [view date source work] (query view-date-source-work-cached {:view view :date date :source source :work work}))
  (GET "/:view/:date/works/:work{.*?}/events.json" [view date work] (query view-date-work-cached {:view view :date date :work work})))

(defn wrap-cors [handler]
  (fn [request]
    (let [response (handler request)]
      (assoc-in response [:headers "Access-Control-Allow-Origin"] "*"))))

(def app
  (-> app-routes
     middleware-params/wrap-params
     (middleware-resource/wrap-resource "public")
     (middleware-content-type/wrap-content-type)
     (wrap-cors)))

(defn run []
  (let [port (Integer/parseInt (:port env))]
    (log/info "Start server on " port)
    ; a few background processes in case of peak load
    (dotimes [_ 10] (run-uploads-background))
    (server/run-server app {:port port}))
  (.join (Thread/currentThread)))

(defn -main
  [& args]
  (run))
