;; Pure application logic: no DOM, no interop, no dependencies.
;; Loaded unchanged by the browser (Scittle, before kitchen_sink.cljs), by the
;; bb server (app/server.clj validates with validate-profile) and by the
;; browser-free unit tests (`bb test`, test/unit_test.clj).
(ns kitchen-sink.logic
  (:require [clojure.string :as str]))

(def log-size 200)   ; the Event log panel scrolls, so keep a useful history

(defn log-line [tag ev detail]
  (str tag " · " ev (when (some? detail) (str " → " detail))))

(defn add-log [s tag ev detail]
  (update s :log #(take log-size (cons (log-line tag ev detail) %))))

(defn apply-action
  "One action applied to the state map: (state, action) -> state.
   Data event handlers are vectors of these actions; dispatch! reduces them
   over the state in a single swap!."
  [s [op & args :as action]]
  (case op
    :store/assoc    (let [[k v] args] (assoc s k v))
    :store/assoc-in (let [[path v] args] (assoc-in s path v))
    :store/inc      (update s (first args) (fnil inc 0))
    :log            (let [[tag ev detail] args] (add-log s tag ev detail))
    ;; domain actions are just more cases:
    :demo/add-item    (let [n (inc (:item-seq s 0))] (-> s (assoc :item-seq n) (update :items (fnil conj []) n)))
    :demo/remove-item (update s :items #(vec (remove #{(first args)} %)))
    :theme/reset      (merge s {:theme "default" :palette "theme" :brand "theme"})
    (add-log s "dispatch" "unknown action" (pr-str action))))

(defn apply-actions
  "All actions of one event, as one state transition."
  [s actions]
  (reduce apply-action (assoc s :last-action actions) actions))

;; ---------------------------------------------------------------------------
;; Routing: the URL hash is the source of truth for which section is shown.
(defn slug [title]
  (-> title str/lower-case (str/replace #"\s+" "-")))

(defn parse-route
  "\"#/forms\" -> \"forms\"; \"\", \"#\", \"#/\" -> :all"
  [hash]
  (let [r (str/replace (str hash) #"[#/]" "")]
    (if (= "" r) :all r)))

(defn route-href [route]
  (if (= route :all) "#/" (str "#/" route)))

;; ---------------------------------------------------------------------------
;; Theming: Web Awesome themes are classes on <html>.
(def themes   ["default" "awesome" "shoelace"])
(def palettes ["theme" "default" "bright" "shoelace"])        ; "theme" = the theme's own palette
(def brands   ["theme" "blue" "indigo" "purple" "pink" "red" "orange" "yellow" "green" "cyan" "gray"])

(defn html-classes
  "The class list for <html> given the theme settings in state."
  [{:keys [theme palette brand dark?]}]
  (cond-> [(str "wa-theme-" (or theme "default"))]
    (and palette (not= palette "theme")) (conj (str "wa-palette-" palette))
    (and brand (not= brand "theme"))     (conj (str "wa-brand-" brand))
    true                                 (conj (if dark? "wa-dark" "wa-light"))))

;; ---------------------------------------------------------------------------
;; Validation shared by the browser and the server. The browser puts these
;; messages on the fields (Web Awesome's custom-error attribute), so the form
;; won't submit; the server runs the same function and answers 400 with them,
;; for clients that skip the browser checks.
(def email-re #"^[^@\s]+@[^@\s]+\.[^@\s]+$")

(defn ->int [x]
  (cond (number? x) x
        (and (string? x) (re-matches #"\s*-?\d+\s*" x)) (parse-long (str/trim x))
        :else nil))

(defn validate-profile
  "{field message} for an invalid profile, {} for a valid one."
  [{:keys [name email qty]}]
  (let [qty (->int qty)]
    (cond-> {}
      (str/blank? name)
      (assoc :name "Name is required")

      (and (not (str/blank? email)) (not (re-matches email-re (str/trim email))))
      (assoc :email "Not a valid email address")

      (and (str/blank? email) qty (> qty 5))
      (assoc :email "Email is required for quantities over 5")

      (and (some? qty) (or (neg? qty) (> qty 10)))
      (assoc :qty "Quantity must be between 0 and 10"))))
