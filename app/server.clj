#!/usr/bin/env bb
;; App server for the kitchen sink: a minimal but real web app backend.
;;
;;   GET  /               -> public/index.html (loads cljs/kitchen_sink.cljs via Scittle)
;;   GET  /<file>         -> static files from public/
;;   POST /api/profile    -> EDN body, validated, stored in data/profiles.edn
;;   GET  /api/profiles   -> EDN list of stored profiles
;;   GET  /ws             -> websocket: {:op :hello}, {:op :tick} every 5 s,
;;                           {:op :reload :file "cljs/..."} when a .cljs file changes
;;
;; Run: bb dev   (from the repo root)   or   cd app && bb server.clj
;; Env: PORT (default 8090)
(ns server
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def root (fs/parent (fs/absolutize *file*)))
(def public-dir (fs/path root "public"))
(def data-file (fs/path root "data" "profiles.edn"))
(def port (parse-long (or (System/getenv "PORT") "8090")))

;; ---------------------------------------------------------------- storage
(defonce profiles
  (atom (if (fs/exists? data-file) (edn/read-string (slurp (str data-file))) [])))

(defn persist! [ps]
  (fs/create-dirs (fs/parent data-file))
  (spit (str data-file) (pr-str ps)))

;; ---------------------------------------------------------------- websocket
(defonce clients (atom #{}))

(defn send! [ch msg] (http/send! ch (pr-str msg)))
(defn broadcast! [msg] (doseq [ch @clients] (send! ch msg)))

(defn ws-handler [req]
  (http/as-channel req
    {:on-open  (fn [ch] (swap! clients conj ch)
                 (send! ch {:op :hello :clients (count @clients)}))
     :on-close (fn [ch _] (swap! clients disj ch))}))

;; ---------------------------------------------------------------- API
(defn edn-response
  ([body] (edn-response 200 body))
  ([status body] {:status status
                  :headers {"Content-Type" "application/edn; charset=utf-8"}
                  :body (pr-str body)}))

(defn now [] (str (java.time.LocalTime/now (java.time.ZoneId/systemDefault))))
(defn hhmmss [] (subs (now) 0 8))

(defn save-profile [{:keys [body]}]
  (let [p (try (edn/read-string (slurp body)) (catch Exception _ ::invalid))]
    (cond
      (not (map? p))            (edn-response 400 {:error "body must be an EDN map"})
      (str/blank? (:name p))    (edn-response 400 {:error "name is required"})
      :else
      (let [p (-> (select-keys p [:name :email :bio :qty])
                  (assoc :id (inc (count @profiles)) :saved-at (hhmmss)))
            ps (swap! profiles conj p)]
        (persist! ps)
        (println "saved profile" (:id p) (:name p))
        (edn-response {:ok true :profile p :count (count ps)})))))

;; ---------------------------------------------------------------- static files
(def mime {"html" "text/html; charset=utf-8" "js" "text/javascript" "css" "text/css"
           "cljs" "text/plain; charset=utf-8" "edn" "application/edn"
           "png" "image/png" "jpg" "image/jpeg" "gif" "image/gif" "svg" "image/svg+xml"})

(defn static [uri]
  (let [rel (if (= uri "/") "index.html" (subs uri 1))
        f   (fs/path public-dir rel)]
    (if (and (not (str/includes? rel "..")) (fs/regular-file? f))
      {:status 200
       :headers {"Content-Type" (get mime (fs/extension f) "application/octet-stream")
                 "Cache-Control" "no-store"}       ; dev server: always fresh
       :body (fs/file f)}
      {:status 404 :body "not found"})))

;; ---------------------------------------------------------------- routing
(defn handler [{:keys [request-method uri] :as req}]
  (case [request-method uri]
    [:get "/ws"]            (ws-handler req)
    [:post "/api/profile"]  (save-profile req)
    [:get "/api/profiles"]  (edn-response {:profiles @profiles})
    (if (= request-method :get)
      (static uri)
      {:status 405 :body "method not allowed"})))

;; ---------------------------------------------------------------- background
(defn ticker []
  (loop []
    (Thread/sleep 5000)
    (broadcast! {:op :tick :time (hhmmss) :clients (count @clients)})
    (recur)))

(defn watcher
  "Polls public/cljs for changed .cljs files and tells browsers to re-evaluate them."
  []
  (loop [seen {}]
    (let [now (into {} (for [f (fs/glob public-dir "cljs/**.cljs")]
                         [(str (fs/relativize public-dir f)) (.toMillis (fs/last-modified-time f))]))]
      (doseq [[f t] now :when (and (contains? seen f) (not= t (seen f)))]
        (println "changed" f "-> reload")
        (broadcast! {:op :reload :file f}))
      (Thread/sleep 300)
      (recur now))))

(defn -main []
  (http/run-server #'handler {:port port})
  (future (ticker))
  (future (watcher))
  (println (str "kitchen sink: http://localhost:" port))
  @(promise))

(when (= *file* (System/getProperty "babashka.file")) (-main))
