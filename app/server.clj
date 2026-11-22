#!/usr/bin/env bb
;; App server for the kitchen sink: a minimal but real web app backend.
;;
;;   GET  /               -> public/index.html (loads cljs/kitchen_sink.cljs via Scittle)
;;   GET  /<file>         -> static files from public/
;;   POST /api/profile    -> EDN body, validated with the same function the browser
;;                           uses (logic.cljc), stored in data/profiles.edn
;;   GET  /api/dev-info   -> which optional dev features are on (SSR, browser nREPL)
;;   GET  /api/profiles   -> EDN list of stored profiles
;;   GET  /ws             -> websocket: {:op :hello}, {:op :tick} every 5 s,
;;                           {:op :reload :file "cljs/..."} when a .cljs/.cljc file changes
;;   GET  /ssr/profiles   -> server-side rendering: the shared views in
;;                           public/cljs/views.cljc rendered to HTML with
;;                           replicant.string (only under `bb dev-full`, see below)
;;
;; Run: bb dev       (from the repo root)   or   cd app && bb server.clj
;;      bb dev-full  same server plus two dev dependencies (app/dev-full.edn):
;;                   Replicant, for server-side rendering at /ssr/profiles, and
;;                   sci.nrepl, for an nREPL into the running page (editor ->
;;                   localhost:1339 -> websocket :1340 -> Scittle in the browser).
;;                   Resolving them needs a Java runtime the first time
;;                   (babashka's deps resolution); plain `bb dev` needs none.
;; Env: PORT (default 8090), HOST (default 127.0.0.1: this dev server has no auth,
;;      so it doesn't listen on your network unless you say HOST=0.0.0.0),
;;      NREPL_PORT (1339), NREPL_WS_PORT (1340)
(ns server
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def root (fs/parent (fs/absolutize *file*)))
(def public-dir (fs/path root "public"))
(def data-file (fs/path root "data" "profiles.edn"))
(def host (or (System/getenv "HOST") "127.0.0.1"))
(def port (parse-long (or (System/getenv "PORT") "8090")))
(def nrepl-port (parse-long (or (System/getenv "NREPL_PORT") "1339")))
(def nrepl-ws-port (parse-long (or (System/getenv "NREPL_WS_PORT") "1340")))

;; Shared, dependency-free logic: the browser loads the same file.
(def logic-file (fs/path public-dir "cljs" "logic.cljc"))
(load-file (str logic-file))
(require '[kitchen-sink.logic :as logic])

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

;; ---------------------------------------------------------------- SSR (optional)
(def ssr-available?
  (try (require 'replicant.string) true
       (catch Exception _ false)))

;; ---------------------------------------------------------------- browser nREPL (optional)
;; Under `bb dev-full` sci.nrepl is on the classpath: start its nREPL server
;; (for the editor) and its websocket (for the page). The page asks
;; /api/dev-info and, if :nrepl is there, loads scittle.nrepl.js, which connects
;; to the websocket. One browser tab at a time; start the server before
;; opening the page (the page does not reconnect).
(def nrepl-available?
  (try (require 'sci.nrepl.browser-server) true
       (catch Exception _ false)))

(defn start-nrepl! []
  ((resolve 'sci.nrepl.browser-server/start!) {:nrepl-port nrepl-port :websocket-port nrepl-ws-port}))

(defn dev-info []
  (edn-response {:ssr? ssr-available?
                 :nrepl (when nrepl-available? {:port nrepl-port :websocket-port nrepl-ws-port})}))

(defn ssr-profiles []
  (if-not ssr-available?
    {:status 501 :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body (str "Server-side rendering needs Replicant on the server's classpath.\n"
                "Start the server with `bb dev-full` (uses app/dev-full.edn) instead of `bb dev`.\n")}
    (do
      ;; the very file the browser loads; re-read per request so edits show up
      (load-file (str (fs/path public-dir "cljs" "views.cljc")))
      (doseq [[k f] @(resolve 'kitchen-sink.views/aliases)]
        ((requiring-resolve 'replicant.alias/register!) k f))
      (let [render  (requiring-resolve 'replicant.string/render)
            table   (resolve 'kitchen-sink.views/profiles-table)
            card    (resolve 'kitchen-sink.views/profile-card)
            ps      @profiles]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                    "<title>Server-rendered profiles</title>"
                    "<style>body{font-family:system-ui,sans-serif;padding:1.5rem;max-width:56rem}"
                    "table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:.3rem .6rem;text-align:left}"
                    ".ks-stats{display:flex;gap:.6rem}.ks-stat{border:1px solid #ddd;border-radius:8px;padding:.3rem .6rem}"
                    ".ks-stat-label{font-size:11px;text-transform:uppercase;color:#666}.ks-stat-value{font-weight:600}"
                    ".ks-profile{display:flex;flex-direction:column;gap:.5rem;margin:.75rem 0}</style></head><body>"
                    (render [:main
                             [:h1 "Rendered on the server"]
                             [:p "By babashka, with " [:code "replicant.string/render"] ", from "
                              [:code "app/public/cljs/views.cljc"] " - the same functions the browser renders."]
                             [:h2 (str (count ps) " stored profiles")]
                             (table ps)
                             [:h2 "Profile cards"]
                             (for [p ps] [:div {:replicant/key (:id p)} (card p)])])
                    "</body></html>")}))))

(defn now [] (str (java.time.LocalTime/now (java.time.ZoneId/systemDefault))))
(defn hhmmss [] (subs (now) 0 8))

(defn save-profile [{:keys [body]}]
  (let [p (try (edn/read-string (slurp body)) (catch Exception _ ::invalid))]
    (cond
      (not (map? p))            (edn-response 400 {:error "body must be an EDN map"})
      (seq (logic/validate-profile p))
      (edn-response 400 {:error "invalid profile" :errors (logic/validate-profile p)})
      :else
      (let [p (-> (select-keys p [:name :email :bio :qty])
                  (assoc :id (inc (count @profiles)) :saved-at (hhmmss)))
            ps (swap! profiles conj p)]
        (persist! ps)
        (println "saved profile" (:id p) (:name p))
        (edn-response {:ok true :profile p :count (count ps)})))))

;; ---------------------------------------------------------------- static files
(def mime {"html" "text/html; charset=utf-8" "js" "text/javascript" "css" "text/css"
           "cljs" "text/plain; charset=utf-8" "cljc" "text/plain; charset=utf-8" "edn" "application/edn"
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
    [:get "/ssr/profiles"]  (ssr-profiles)
    [:get "/api/dev-info"]  (dev-info)
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
  "Polls public/cljs for changed .cljs/.cljc files and tells browsers to re-evaluate them."
  []
  (loop [seen {}]
    (let [now (into {} (for [f (fs/glob public-dir "cljs/**.{cljs,cljc}")]
                         [(str (fs/relativize public-dir f)) (.toMillis (fs/last-modified-time f))]))]
      (doseq [[f t] now :when (and (contains? seen f) (not= t (seen f)))]
        (println "changed" f "-> reload")
        (when (str/ends-with? f "logic.cljc")      ; the server validates with it too
          (load-file (str logic-file)))
        (broadcast! {:op :reload :file f}))
      (Thread/sleep 300)
      (recur now))))

(defn -main []
  (http/run-server #'handler {:port port :ip host})
  (future (ticker))
  (future (watcher))
  (when nrepl-available? (start-nrepl!))
  (println (str "kitchen sink: http://localhost:" port
                (if ssr-available? "  (SSR: /ssr/profiles)" "  (SSR and browser nREPL off: use bb dev-full)")
                (when nrepl-available? (str "  (nREPL for your editor: localhost:" nrepl-port ")"))))
  @(promise))

(when (= *file* (System/getProperty "babashka.file")) (-main))
