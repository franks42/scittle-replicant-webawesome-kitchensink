;; Minimal static file server (http-kit, built into babashka) for single-file/.
;; Run from the repo root: bb serve-single   (PORT env, default 8091)
(ns serve-static
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def dir "single-file")
(def port (parse-long (or (System/getenv "PORT") "8091")))

(defn handler [{:keys [uri]}]
  (let [rel (if (= uri "/") "index.html" (subs uri 1))
        f   (fs/path dir rel)]
    (if (and (not (str/includes? rel "..")) (fs/regular-file? f))
      {:status 200 :headers {"Content-Type" (if (= "html" (fs/extension f)) "text/html; charset=utf-8" "application/octet-stream")}
       :body (fs/file f)}
      {:status 404 :body "not found"})))

(http/run-server handler {:port port})
(println (str "single-file kitchen sink: http://localhost:" port))
@(promise)
