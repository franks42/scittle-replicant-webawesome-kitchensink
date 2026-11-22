;; Shared views: plain functions from data to hiccup. No browser interop and no
;; dependencies, so the same file is loaded unchanged by:
;;   - the browser (Scittle, a <script src> before kitchen_sink.cljs),
;;   - the server under `bb dev-full`, which renders them to HTML strings with
;;     replicant.string (GET /ssr/profiles), and
;;   - the browser-free unit tests (`bb test`).
;; This is Replicant's "one view, two renderers" story: data in, DOM or HTML out.
(ns kitchen-sink.views)

;; ---------------------------------------------------------------------------
;; Aliases: your own hiccup tags. An alias function receives the attribute map
;; and the children, and returns ordinary hiccup. Alias keywords must be
;; namespaced. This file only defines them (see `aliases` below); whoever renders
;; registers them with replicant.alias/register!, which keeps this file free of
;; dependencies.
(defn stat
  "[:ks/stat {:label \"Volume\"} 40] -> a small label/value card."
  [{:keys [label] :as attrs} children]
  [:div (merge {:class "ks-stat"} (dissoc attrs :label))
   [:div {:class "ks-stat-label"} label]
   (into [:div {:class "ks-stat-value"}] children)])

(defn chip
  "[:ks/chip {:initials \"AL\"} \"Ada Lovelace\"] -> avatar + name."
  [{:keys [initials] :as attrs} children]
  (into [:span (merge {:class "ks-chip"} (dissoc attrs :initials))
         [:wa-avatar {:initials initials :label (str initials) :style {:--size "1.6rem"}}]]
        children))

(def aliases
  "Register with (doseq [[k f] aliases] (replicant.alias/register! k f))."
  {:ks/stat stat
   :ks/chip chip})

;; ---------------------------------------------------------------------------
;; Views used by both the browser and the server.
(defn initials [name]
  (->> (re-seq #"\S+" (str name)) (map first) (take 2) (apply str)))

(defn profile-card [{:keys [name email qty]}]
  [:div {:class "ks-profile"}
   [:ks/chip {:initials (initials name)} (if (seq name) name "(no name)")]
   [:div {:class "ks-stats"}
    [:ks/stat {:label "Email"} (if (seq email) email "—")]
    [:ks/stat {:label "Quantity"} (str qty)]]])

(defn profiles-table [profiles]
  [:table {:id "profiles-table"}
   [:tr [:th "id"] [:th "name"] [:th "email"] [:th "qty"] [:th "saved-at"]]
   (for [p profiles]
     [:tr {:replicant/key (:id p)}
      [:td (:id p)] [:td (:name p)] [:td (:email p)] [:td (:qty p)] [:td (:saved-at p)]])])
