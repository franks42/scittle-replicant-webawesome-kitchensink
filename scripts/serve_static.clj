;; Minimal static file server (http-kit, built into babashka).
;; Usage: bb scripts/serve_static.clj <dir> [port]
;; Used by the bb tasks:
;;   bb serve-single   -> single-file/   on 8091   (one self-contained HTML file)
;;   bb serve-static   -> app/public/    on 8092   (static.html + cljs/kitchen_sink.cljs)
;; PORT env overrides the port. Listens on 127.0.0.1 only; HOST=0.0.0.0 to share on your network.
(ns serve-static
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def dir  (or (first *command-line-args*) "single-file"))
(def host (or (System/getenv "HOST") "127.0.0.1"))
(def port (parse-long (or (System/getenv "PORT") (second *command-line-args*) "8091")))

(def mime {"html" "text/html; charset=utf-8" "js" "text/javascript" "css" "text/css"
           "cljs" "text/plain; charset=utf-8" "cljc" "text/plain; charset=utf-8" "edn" "application/edn" "json" "application/json"
           "png" "image/png" "jpg" "image/jpeg" "gif" "image/gif" "svg" "image/svg+xml"})

(defn handler [{:keys [uri]}]
  (let [rel (if (str/ends-with? uri "/") (str (subs uri 1) "index.html") (subs uri 1))
        f   (fs/path dir rel)]
    (if (and (not (str/includes? rel "..")) (fs/regular-file? f))
      {:status 200
       :headers {"Content-Type" (get mime (fs/extension f) "application/octet-stream")}
       :body (fs/file f)}
      {:status 404 :body "not found"})))

(http/run-server handler {:port port :ip host})
(println (str "serving " dir "/ on http://localhost:" port))
@(promise)
