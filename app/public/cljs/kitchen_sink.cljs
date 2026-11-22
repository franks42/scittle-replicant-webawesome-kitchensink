;; Web Awesome kitchen sink: all 78 free components, rendered with Replicant,
;; evaluated in the browser by Scittle. Runs in three modes (window.KS_MODE):
;;   app    - (default) loaded by app/public/index.html from the bb server
;;            (app/server.clj): form posts to /api/profile, server pushes over /ws,
;;            hot reload.
;;   static - loaded by app/public/static.html from any static host (e.g. GitHub
;;            Pages): same file, same assets, server features switched off.
;;   single - inlined into single-file/index.html by scripts/build_single.clj;
;;            no server, assets embedded as data: URLs.
;; Shared, interop-free views and aliases live in views.cljc (loaded first).
(ns kitchen-sink
  (:require [replicant.dom :as d]
            [replicant.string :as rs]
            [kitchen-sink.views :as v]          ; app/public/cljs/views.cljc, shared with the server
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.string :as str]))

;; Bump this and save the file while `bb dev` runs: the page hot-reloads
;; and keeps its state (everything below uses defonce for state).
(def ui-version "1")

(def mode (or (.-KS_MODE js/window) "app"))   ; "app" | "static" | "single"
(def single? (= mode "single"))
(def server? (= mode "app"))

;; Asset indirection: relative paths in app mode, data: URLs / inline HTML in single mode.
(def asset-paths
  (merge {"avatar" "img/avatar.png" "bounce" "img/bounce.gif" "dawn-gray" "img/dawn-gray.jpg"
          "include" "include.html" "page-demo" "page-demo.html"}
         (into {} (for [n ["dawn" "forest" "dusk" "glacier"]
                        [k v] [[n (str "img/" n ".jpg")] [(str n "-thumb") (str "img/" n "-thumb.jpg")]]]
                    [k v]))))

(defn asset [k]
  (or (some-> (.-KS_ASSETS js/window) (aget k)) (get asset-paths k)))

;; ---------------------------------------------------------------------------
;; State + helpers
(defonce state
  (atom {:name "Ada Lovelace" :email "" :password "" :bio "" :qty 3
         :coffee "latte" :extras ["oat"] :agree? false :interests #{"development"}
         :roast "medium" :plan "pro" :notify? true :volume 40 :range [20 80] :rating 3
         :color "#0071ec" :otp "" :tags "design, accessibility" :time "09:30" :issued ""
         :align "center" :dialog? false :drawer? false :tab "general" :page 3 :step "shipping"
         :tree-sel [] :slide 0 :split 50 :bytes 1536000 :number 1234567.891 :ticker 1000000
         :anim "bounce" :anim-play? true :popup? true :popup-place "top" :mut-variant "brand"
         :size "—" :visible? false :chips ["Clojure" "Scittle" "Replicant" "Babashka"]
         :menu nil :bold? true :form nil :log ()}))

(swap! state update :srv #(merge {:ws "closed" :time nil :clients 0 :saved nil :profiles nil :reloads 0} %))
(swap! state #(merge {:fn-count 0 :data-count 0 :last-action nil
                      :lc-show? false :items [1 2 3] :item-seq 3} %))

(def log-size 200)   ; the Event log panel scrolls, so keep a useful history

(defn log-line [tag ev detail] (str tag " · " ev (when (some? detail) (str " → " detail))))

(defn log! [tag ev & [detail]]
  (swap! state update :log #(take log-size (cons (log-line tag ev detail) %))))

(defn tv [e] (.. e -target -value))
;; DOM nodes captured by life-cycle hooks (Replicant's way to get at a node),
;; keyed by name. A separate atom: changing it must not trigger a re-render.
(defonce refs (atom {}))

(defn ref
  "Attributes that keep refs[k] pointing at this element while it is mounted:
   (merge {:id ...} (ref :stepper)), then (.next (:stepper @refs))."
  [k]
  {:replicant/on-mount   (fn [{:replicant/keys [node]}] (swap! refs assoc k node))
   :replicant/on-unmount (fn [_] (swap! refs dissoc k))})
(defn on! [k f] (fn [e] (swap! state assoc k (f e))))

(defn tile
  "A card for one demo. `tags` are the Web Awesome elements it exercises."
  [{:keys [tags wide? exp? data?]} & body]
  (into [:section {:class (if wide? "tile wide" "tile") :data-tags (str/join " " tags)}
         [:h3 (str/join " · " (map #(str "<" % ">") tags))
          (when exp? [:span.exp " experimental"])
          (when data? [:wa-badge {:variant "success" :appearance "outlined" :style {:margin-left ".5rem"}} "events as data"])]]
        body))

(def tree-data
  [["Deciduous" [["Birch"] ["Maple" [["Field maple"] ["Red maple"] ["Sugar maple"]]] ["Oak"]]]
   ["Coniferous" [["Cedar"] ["Pine"] ["Spruce"]]]
   ["Palms" [["Areca"] ["Coconut"]]]])

(defn tree-item [[label kids] depth]
  (into [:wa-tree-item (cond-> {} (and kids (< depth 1)) (assoc :expanded true)) label]
        (map #(tree-item % (inc depth)) kids)))

(def images [["dawn" "Dawn over the hills"] ["forest" "Forest valley"]
             ["dusk" "Dusk ridgeline"] ["glacier" "Glacier field"]])

(def markdown-src
  "### Rendered by `<wa-markdown>`\n\nThis block is **Markdown** inside a `script type=\"text/markdown\"`, emitted by Replicant.\n\n- hiccup in, custom elements out\n- no build step\n- one atom of state")

;; ---------------------------------------------------------------------------
;; Events as data. Replicant lets an :on handler be plain data instead of a
;; function: it passes that data, untouched, to the one function registered with
;; replicant.dom/set-dispatch!. Below: a tiny action vocabulary, placeholder
;; substitution for values that only exist at event time, and a PURE reducer
;; (state, action) -> state, applied in a single swap!.
;;
;;   [:store/assoc k v]  [:store/assoc-in path v]  [:store/inc k]  [:log tag ev detail]
;;
;; Placeholders, replaced from the DOM event before the actions run:
(defn interpolate [event-data actions]
  (walk/postwalk
   (fn [x]
     (case x
       :life-cycle/phase (some-> (:replicant/life-cycle event-data) name)   ; "mount", "unmount", ...
       :event/target.value     (.. (:replicant/dom-event event-data) -target -value)
       :event/target.checked   (.. (:replicant/dom-event event-data) -target -checked)
       :event/target.page      (.. (:replicant/dom-event event-data) -target -page)
       :event/target.active    (.. (:replicant/dom-event event-data) -target -active)
       :event/detail.name      (.. (:replicant/dom-event event-data) -detail -name)
       :event/detail.index     (.. (:replicant/dom-event event-data) -detail -index)
       :event/detail.item.value (.. (:replicant/dom-event event-data) -detail -item -value)
       x))
   actions))

(defn apply-action
  "Pure: one action applied to the state map. Unit-testable without a browser."
  [s [op & args :as action]]
  (case op
    :store/assoc    (let [[k v] args] (assoc s k v))
    :store/assoc-in (let [[path v] args] (assoc-in s path v))
    :store/inc      (update s (first args) (fnil inc 0))
    ;; domain actions are just more cases:
    :demo/add-item    (let [n (inc (:item-seq s 0))] (-> s (assoc :item-seq n) (update :items (fnil conj []) n)))
    :demo/remove-item (update s :items #(vec (remove #{(first args)} %)))
    :log            (let [[tag ev detail] args]
                      (update s :log #(take log-size (cons (log-line tag ev detail) %))))
    (update s :log #(cons (log-line "dispatch" "unknown action" (pr-str action)) %))))

(defn dispatch! [event-data actions]
  (let [actions (interpolate event-data actions)
        apply! (fn [] (swap! state (fn [s] (reduce apply-action (assoc s :last-action actions) actions))))]
    (case (:replicant/trigger event-data)
      ;; one swap! = one state transition, however many actions
      :replicant.trigger/dom-event  (apply!)
      ;; life-cycle hooks fire while Replicant is rendering: apply after it finishes.
      ;; (Use data hooks on mount/unmount; a state change from on-render would loop.)
      :replicant.trigger/life-cycle (js/setTimeout apply! 0)
      nil)))

(d/set-dispatch! dispatch!)

;; ---------------------------------------------------------------------------
;; Server integration (app mode). This is the part to grow into a real app:
;; plain fetch + EDN for request/response, one websocket for server pushes.
(defn api! [method path body on-ok]
  (-> (js/fetch path (clj->js (cond-> {:method method :headers {"Content-Type" "application/edn"}}
                                body (assoc :body (pr-str body)))))
      (.then (fn [r] (.then (.text r) (fn [t] [(.-ok r) (.-status r) t]))))
      (.then (fn [[ok status t]]
               (if ok
                 (on-ok (edn/read-string t))
                 (log! "api" (str method " " path) (str "HTTP " status ": " t)))))
      (.catch (fn [e] (log! "api" "error" (.-message e))))))

(defn post-profile! [entries]
  (let [profile (into {} (map (fn [[k v]] [(keyword k) v])) (remove #(= "password" (first %)) entries))]
    (api! "POST" "/api/profile" profile
          (fn [{:keys [profile count]}]
            (swap! state assoc-in [:srv :saved] profile)
            (log! "server" "saved profile" (str "#" (:id profile) ", " count " stored"))))))

(defn load-profiles! []
  (api! "GET" "/api/profiles" nil
        (fn [{:keys [profiles]}]
          (swap! state assoc-in [:srv :profiles] profiles)
          (log! "server" "GET /api/profiles" (count profiles)))))

(defn reload-cljs! [file]
  ;; Re-evaluate the changed file in place. defonce keeps state and the socket.
  (-> (js/fetch (str file "?t=" (js/Date.now)))
      (.then #(.text %))
      (.then (fn [src]
               (js/scittle.core.eval_string src)
               (swap! state update-in [:srv :reloads] inc)
               (log! "hot-reload" file (str "ui-version " ui-version))))))

(defonce ws (atom nil))

(defn connect! []
  (let [sock (js/WebSocket. (str (if (= "https:" js/location.protocol) "wss://" "ws://") js/location.host "/ws"))]
    (reset! ws sock)
    (set! (.-onopen sock) #(swap! state assoc-in [:srv :ws] "open"))
    (set! (.-onclose sock) (fn [_]
                             (swap! state assoc-in [:srv :ws] "closed")
                             (reset! ws nil)
                             (js/setTimeout connect! 2000)))
    (set! (.-onmessage sock)
          (fn [e]
            (let [{:keys [op] :as msg} (edn/read-string (.-data e))]
              (case op
                :hello  (swap! state assoc-in [:srv :clients] (:clients msg))
                :tick   (swap! state update :srv merge (select-keys msg [:time :clients]))
                :reload (reload-cljs! (:file msg))
                (log! "ws" "unknown message" (pr-str msg))))))))

(when (and server? (nil? @ws)) (connect!))

(defn server-tile [s]
  (let [{:keys [ws time clients saved profiles reloads]} (:srv s)]
    (if-not server?
      [:section.tile.wide {:data-tags "server"}
       [:h3 (str "Server · " mode " mode")]
       [:wa-callout {:variant "neutral"} [:wa-icon {:slot "icon" :name "circle-info"}]
        (if single?
          "Single-file mode: no server; this ClojureScript is inlined in the HTML with all assets embedded."
          [:span "Static mode: this page loaded its UI from " [:code "cljs/kitchen_sink.cljs"]
           " on a plain static host; no server."])
        " Run the app version (" [:code "bb dev"] ") for the API, server push and hot reload."]]
      [:section.tile.wide {:data-tags "server" :id "server-tile"}
       [:h3 (str "Server (bb + http-kit) · ui-version " ui-version)]
       [:div.row
        [:wa-badge {:id "ws-status" :variant (if (= ws "open") "success" "danger")} (str "websocket " ws)]
        [:wa-badge {:variant "neutral"} (str "clients " clients)]
        [:wa-badge {:variant "neutral" :id "server-time"} (str "server time " (or time "—"))]
        [:wa-badge {:variant "brand" :id "reloads"} (str "hot reloads " reloads)]]
       [:p.muted "Submit the profile form in Forms below: it posts EDN to " [:code "/api/profile"] ". The server pushes its time over " [:code "/ws"]
        " every few seconds. Edit " [:code "app/public/cljs/kitchen_sink.cljs"] " and save: the page re-evaluates it and keeps its state."]
       [:div.row
        [:wa-button {:id "load-profiles" :size "s" :on {:click load-profiles!}} "GET /api/profiles"]
        (when saved [:span {:id "saved-profile"} (str "Last saved: #" (:id saved) " " (:name saved) " at " (:saved-at saved))])]
       (when profiles
         [:wa-scroller {:orientation "horizontal"}
          (v/profiles-table profiles)])        ; shared view (views.cljc), also rendered by the server
       [:p.muted "Server-side rendering: run " [:code "bb dev-ssr"] " and open "
        [:a {:id "ssr-link" :href "/ssr/profiles" :target "_blank"} "/ssr/profiles"]
        " - the same " [:code "profiles-table"] " and " [:code "profile-card"] " functions rendered to HTML by "
        [:code "replicant.string"] " in babashka."]])))

;; ---------------------------------------------------------------------------
;; Demo: the same counter with both kinds of handler, side by side.
(def fn-button-on   {:click #(swap! state update :fn-count inc)})
(def data-button-on {:click [[:store/inc :data-count] [:log "events-demo" "click" "data handler"]]})

(defn events-tile [s]
  [:section.tile.wide {:data-tags "events-demo" :id "events-demo"}
   [:h3 "Two ways to handle events · function handlers vs. events as data"]
   [:div.events-grid
    [:div.stack
     [:strong "Function handler"]
     [:div.row [:wa-button {:id "fn-inc" :on fn-button-on} "+1"] [:span {:id "fn-count"} (str "count " (:fn-count s))]]
     [:div.muted "The handler in the view:"]
     [:pre.code {:id "fn-on"} (pr-str fn-button-on)]
     [:ul.pros
      [:li.pro "Simplest: write the code inline, full Clojure + JS interop"]
      [:li.pro "Nothing to set up; conditionals are natural (see the dialog's wa-hide guard)"]
      [:li.con "Opaque: a closure can't be printed, compared, stored or sent anywhere"]
      [:li.con "Testing it needs a DOM and a real event"]]]
    [:div.stack
     [:strong "Events as data"]
     [:div.row [:wa-button {:id "data-inc" :variant "brand" :on data-button-on} "+1"] [:span {:id "data-count"} (str "count " (:data-count s))]]
     [:div.muted "The handler in the view:"]
     [:pre.code {:id "data-on"} (pr-str data-button-on)]
     [:ul.pros
      [:li.pro "The whole view is data: printable, comparable, testable without a browser"]
      [:li.pro "All effects go through one dispatch: one place to log, undo, replay, or send to a server"]
      [:li.pro "Actions are EDN, so they could come from (or go to) the bb server over the websocket"]
      [:li.con "Needs a small interpreter: dispatch, placeholders, an action vocabulary"]
      [:li.con "Indirection: event values come in via placeholders like :event/target.value"]]]]
   [:div.muted {:style {:margin-top ".75rem"}} "Last actions passed to dispatch (after placeholder substitution):"]
   [:pre.code {:id "last-action"} (if-let [a (:last-action s)] (pr-str a) "— click a tile marked \"events as data\" —")]
   [:p.muted "Tiles marked " [:wa-badge {:variant "success" :appearance "outlined"} "events as data"]
    " use data handlers; the rest use functions. Both kinds can be mixed, even in one " [:code ":on"] " map."]])

;; ---------------------------------------------------------------------------
;; Sections
(defn actions [s]
  [[tile {:tags ["wa-button"]}
    [:div.stack
     [:div.row
      (for [v ["neutral" "brand" "success" "warning" "danger"]]
        [:wa-button {:replicant/key v :variant v :on {:click #(log! "wa-button" "click" v)}} (str/capitalize v)])]
     [:div.row
      [:wa-button {:appearance "outlined" :variant "brand"} "Outlined"]
      [:wa-button {:appearance "plain" :variant "brand"} "Plain"]
      [:wa-button {:pill true :variant "success"} [:wa-icon {:slot "start" :name "check"}] "Pill"]
      [:wa-button {:loading true :variant "brand"} "Loading"]
      [:wa-button {:disabled true} "Disabled"]]]]

   [tile {:tags ["wa-button-group"] :data? true}
    [:wa-button-group {:label "Alignment"}
     (for [a ["left" "center" "right"]]
       [:wa-button {:replicant/key a :id (str "align-" a)
                    :appearance (if (= a (:align s)) "accent" "outlined")
                    :variant "brand"
                    :on {:click [[:store/assoc :align a] [:log "wa-button-group" "select" a]]}}
        [:wa-icon {:name (str "align-" a)}]])]
    [:p.muted (str "Selected: " (:align s))]]

   [tile {:tags ["wa-copy-button"]}
    [:div.row
     [:code (:name s)]
     [:wa-copy-button {:id "copy-btn" :value (:name s)
                       :on {:wa-copy #(log! "wa-copy-button" "wa-copy" (:name @state))}}]]
    [:p.muted "Copies the Name field's current value."]]

   [tile {:tags ["wa-dropdown" "wa-dropdown-item"]}
    [:div.row
     [:wa-dropdown {:on {:wa-select (fn [e]
                                      (let [item (.. e -detail -item)
                                            v (.-value item)]
                                        (when (= v "bold") (swap! state assoc :bold? (.-checked item)))
                                        (swap! state assoc :menu v)
                                        (log! "wa-dropdown" "wa-select" v)))}}
      [:wa-button {:slot "trigger" :id "menu-btn" :with-caret true} "Edit"]
      [:wa-dropdown-item {:value "cut"} [:wa-icon {:slot "icon" :name "scissors"}] "Cut" [:span {:slot "details"} "⌘X"]]
      [:wa-dropdown-item {:value "copy"} [:wa-icon {:slot "icon" :name "copy"}] "Copy" [:span {:slot "details"} "⌘C"]]
      [:wa-dropdown-item {:value "paste"} [:wa-icon {:slot "icon" :name "paste"}] "Paste" [:span {:slot "details"} "⌘V"]]
      [:wa-divider]
      [:wa-dropdown-item {:value "bold" :type "checkbox" :checked (:bold? s)} "Bold"]
      [:wa-dropdown-item "Export"
       [:wa-dropdown-item {:slot "submenu" :value "pdf"} "PDF"]
       [:wa-dropdown-item {:slot "submenu" :value "png"} "PNG"]]
      [:wa-divider]
      [:wa-dropdown-item {:value "delete" :variant "danger"} [:wa-icon {:slot "icon" :name "trash"}] "Delete"]]
     [:wa-badge {:variant "neutral"} (str "last: " (or (:menu s) "—") ", bold " (:bold? s))]]]])

(defn forms [s]
  [[tile {:tags ["wa-input" "wa-textarea" "wa-number-input"] :wide? false}
    [:form {:id "profile-form"
            :on {:submit (fn [e]
                           (.preventDefault e)
                           (let [fd (js/FormData. (.-target e))
                                 entries (mapv vec (js->clj (js/Array.from (.entries fd))))]
                             (swap! state assoc :form entries)
                             (log! "form" "submit" (count entries))
                             (when server? (post-profile! entries))))}}
     [:div.stack
      [:wa-input {:id "f-name" :name "name" :label "Name" :value (:name s) :with-clear true
                  :on {:input (on! :name tv) :wa-clear #(log! "wa-input" "wa-clear")}}
       [:wa-icon {:slot "start" :name "user"}]]
      [:wa-input {:id "f-email" :name "email" :label "Email" :type "email" :placeholder "ada@example.com"
                  :value (:email s) :on {:input (on! :email tv)}}]
      [:wa-input {:name "password" :label "Password" :type "password" :password-toggle true
                  :value (:password s) :on {:input (on! :password tv)}}]
      [:wa-textarea {:id "f-bio" :name "bio" :label "Bio" :maxlength 120 :with-count true :rows 2
                     :value (:bio s) :on {:input (on! :bio tv)}}]
      [:wa-number-input {:id "f-qty" :name "qty" :label "Quantity" :min 0 :max 10 :value (:qty s)
                         :on {:input (on! :qty tv) :change #(log! "wa-number-input" "change" (tv %))}}]
      [:div.row
       [:wa-button {:type "submit" :variant "brand" :id "submit-btn"} "Submit form"]
       [:wa-button {:type "button" :appearance "outlined" :id "clear-name"
                    :on {:click #(do (swap! state assoc :name "") (log! "app" "clear name (programmatic)"))}}
        "Clear name"]]
      (when-let [f (:form s)]
        [:div.muted {:id "form-result"} (str "FormData: " (pr-str f))])]]]

   [tile {:tags ["wa-select" "wa-option"] :data? true}
    [:div.stack
     [:wa-select {:id "f-coffee" :label "Coffee" :value (:coffee s)
                  :on {:change [[:store/assoc :coffee :event/target.value]
                                [:log "wa-select" "change" :event/target.value]]}}
      [:wa-option {:value "espresso"} [:wa-icon {:slot "start" :name "mug-hot"}] "Espresso"]
      [:wa-option {:value "latte"} "Latte"]
      [:wa-option {:value "cold-brew"} "Cold brew"]]
     [:wa-select {:id "f-extras" :label "Extras (multiple)" :multiple true :with-clear true
                  :value (clj->js (:extras s))
                  :on {:change #(let [v (vec (js->clj (tv %)))]
                                  (swap! state assoc :extras v) (log! "wa-select[multiple]" "change" v))}}
      [:wa-option {:value "oat"} "Oat milk"]
      [:wa-option {:value "shot"} "Extra shot"]
      [:wa-option {:value "vanilla"} "Vanilla"]
      [:wa-option {:value "ice"} "Ice"]]]]

   [tile {:tags ["wa-checkbox" "wa-checkbox-group" "wa-switch"]}
    [:div.stack
     [:wa-checkbox {:id "f-agree" :checked (:agree? s)
                    :on {:change #(do (swap! state assoc :agree? (.. % -target -checked))
                                      (log! "wa-checkbox" "change" (.. % -target -checked)))}}
      "I agree to the terms"]
     [:wa-checkbox-group {:label "Interests" :orientation "horizontal"}
      (for [i ["design" "development" "marketing"]]
        [:wa-checkbox {:replicant/key i :id (str "int-" i) :value i :checked (contains? (:interests s) i)
                       :on {:change #(let [c (.. % -target -checked)]
                                       (swap! state update :interests (if c conj disj) i)
                                       (log! "wa-checkbox-group" "change" (str i "=" c)))}}
         (str/capitalize i)])]
     [:div [:wa-checkbox {:indeterminate true} "Indeterminate"]]
     [:div [:wa-switch {:id "f-notify" :checked (:notify? s)   ; events as data
                  :on {:change [[:store/assoc :notify? :event/target.checked]
                                [:log "wa-switch" "change" :event/target.checked]]}}
      "Notifications"]]]]

   [tile {:tags ["wa-radio-group" "wa-radio"]}
    [:div.stack
     [:wa-radio-group {:id "f-roast" :label "Roast" :value (:roast s)
                       :on {:change #(do (swap! state assoc :roast (tv %)) (log! "wa-radio-group" "change" (tv %)))}}
      [:wa-radio {:value "light"} "Light"]
      [:wa-radio {:value "medium"} "Medium"]
      [:wa-radio {:value "dark"} "Dark"]]
     [:wa-radio-group {:id "f-plan" :label "Plan (button appearance)" :orientation "horizontal" :value (:plan s)
                       :on {:change #(do (swap! state assoc :plan (tv %)) (log! "wa-radio-group" "change" (tv %)))}}
      [:wa-radio {:appearance "button" :value "free"} "Free"]
      [:wa-radio {:appearance "button" :value "pro"} "Pro"]
      [:wa-radio {:appearance "button" :value "team"} "Team"]]]]

   [tile {:tags ["wa-slider"] :data? true}
    [:div.stack
     [:wa-slider {:id "f-volume" :label "Volume" :min 0 :max 100 :value (:volume s) :with-tooltip true
                  :on {:input  [[:store/assoc :volume :event/target.value]]
                       :change [[:log "wa-slider" "change" :event/target.value]]}}]
     [:wa-slider {:id "f-range" :label "Price range" :range true :min 0 :max 100
                  :min-value (first (:range s)) :max-value (second (:range s)) :with-markers true :step 10
                  :on {:change #(let [t (.-target %) r [(.-minValue t) (.-maxValue t)]]
                                  (swap! state assoc :range r) (log! "wa-slider[range]" "change" r))}}
      [:span {:slot "reference"} "$0"] [:span {:slot "reference"} "$100"]]]]

   [tile {:tags ["wa-rating" "wa-color-picker"]}
    [:div.stack
     [:div.row [:span "Rating"]
      [:wa-rating {:id "f-rating" :label "Rating" :value (:rating s)
                   :on {:change #(do (swap! state assoc :rating (tv %)) (log! "wa-rating" "change" (tv %)))}}]]
     [:wa-color-picker {:id "f-color" :label "Brand color" :value (:color s)
                        :swatches "#0071ec; #16a34a; #f59e0b; #dc2626; #7c3aed"
                        :on {:change #(do (swap! state assoc :color (tv %)) (log! "wa-color-picker" "change" (tv %)))}}]
     [:div.row [:span.muted "Picked:"] [:div {:style {:width "40px" :height "20px" :border-radius "4px" :background (:color s)}}]
      [:code (:color s)]]]]

   [tile {:tags ["wa-otp-input" "wa-tag-input"] :exp? true}
    [:div.stack
     ;; Uncontrolled on purpose: writing :value back on every input event clobbers
     ;; wa-otp-input's own segment state (measured: digits are lost). Read, don't write.
     [:wa-otp-input {:id "f-otp" :label "Verification code" :length 6
                     :on {:input (on! :otp tv)
                          :wa-complete #(log! "wa-otp-input" "wa-complete" (tv %))}}]
     [:wa-tag-input {:id "f-tags" :label "Keywords" :placeholder "Add a keyword" :value (:tags s) :with-clear true
                     :on {:change #(let [v (tv %)] (swap! state assoc :tags v) (log! "wa-tag-input" "change" (pr-str (js->clj v))))}}]
     [:div.muted "wa-tag-input is experimental"]]]

   [tile {:tags ["wa-time-input" "wa-known-date"]}
    [:div.stack
     [:wa-time-input {:id "f-time" :label "Meeting time" :value (:time s) :with-now true
                      :on {:change #(do (swap! state assoc :time (tv %)) (log! "wa-time-input" "change" (tv %)))}}]
     [:wa-known-date {:id "f-issued" :label "When was your passport issued?" :value (:issued s)
                      :on {:change #(do (swap! state assoc :issued (tv %)) (log! "wa-known-date" "change" (tv %)))}}]]]])

(defn feedback [s]
  [[tile {:tags ["wa-badge" "wa-tag"]}
    [:div.stack
     [:div.row
      (for [v ["brand" "success" "neutral" "warning" "danger"]]
        [:wa-badge {:replicant/key v :variant v} v])
      [:wa-badge {:pill true :attention "pulse" :variant "danger"} "3"]]
     [:div.row
      (for [c (:chips s)]
        [:wa-tag {:replicant/key c :with-remove true :variant "brand"
                  :on {:wa-remove #(do (swap! state update :chips (fn [cs] (vec (remove #{c} cs))))
                                       (log! "wa-tag" "wa-remove" c))}}
         c])
      (when (empty? (:chips s))
        [:wa-button {:size "s" :on {:click #(swap! state assoc :chips ["Clojure" "Scittle" "Replicant" "Babashka"])}} "Reset tags"])]]]

   [tile {:tags ["wa-callout"]}
    [:div.stack
     (for [[v icon text] [["brand" "circle-info" "Brand callout"] ["success" "circle-check" "Saved successfully"]
                          ["warning" "triangle-exclamation" "Check your input"] ["danger" "circle-xmark" "Something failed"]]]
       [:wa-callout {:replicant/key v :variant v} [:wa-icon {:slot "icon" :name icon}] text])]]

   [tile {:tags ["wa-progress-bar" "wa-progress-ring" "wa-spinner"]}
    [:div.stack
     [:wa-progress-bar {:id "progress" :value (:volume s) :label "Volume"} (str (:volume s) "%")]
     [:wa-progress-bar {:indeterminate true}]
     [:div.row
      [:wa-progress-ring {:value (:volume s)} (str (:volume s) "%")]
      [:wa-spinner {:style {:font-size "2rem"}}]
      [:span.muted "Bar and ring follow the Volume slider."]]]]

   [tile {:tags ["wa-skeleton"]}
    [:div.stack
     [:div.row [:wa-skeleton {:effect "sheen" :style {:width "48px" :height "48px" :--border-radius "50%"}}]
      [:div {:style {:flex 1}} [:wa-skeleton {:effect "sheen"}] [:wa-skeleton {:effect "pulse" :style {:width "60%" :margin-top ".5rem"}}]]]
     [:wa-skeleton {:effect "sheen"}]]]

   [tile {:tags ["wa-tooltip" "wa-toast" "wa-toast-item"]}
    [:div.stack
     [:div.row
      [:wa-button {:id "tip-target" :appearance "outlined"} "Hover me"]
      [:wa-tooltip {:for "tip-target" :on {:wa-show #(log! "wa-tooltip" "wa-show")}} "Tooltips use the " [:code "for"] " attribute"]
      (for [[v icon] [["success" "circle-check"] ["warning" "triangle-exclamation"] ["danger" "circle-xmark"]]]
        [:wa-button {:replicant/key v :id (str "toast-" v) :variant v
                     :on {:click #(do (.create (:toast @refs) (str "A " v " toast from Replicant")
                                               #js {:variant v :icon icon :duration 4000})
                                      (log! "wa-toast" "create" v))}}
         (str "Toast " v)])]
     [:wa-toast (merge {:id "toast" :placement "bottom-end"} (ref :toast))]
     [:wa-toast-item {:variant "brand" :duration 0} [:wa-icon {:slot "icon" :name "bell"}] "A static toast item (duration 0)"]]]])

(defn layout [s]
  [[tile {:tags ["wa-card"]}
    [:wa-card {:style {:max-width "320px"}}
     [:img {:slot "media" :src (asset "forest") :alt "Generated forest landscape"}]
     [:div {:slot "header"} [:strong "Forest valley"]]
     "A card with media, header and footer slots."
     [:div.row {:slot "footer"}
      [:wa-button {:variant "brand" :size "s" :on {:click #(log! "wa-card" "footer action")}} "Book"]
      [:wa-rating {:value 4 :readonly true :size "s"}]]]]

   [tile {:tags ["wa-details" "wa-accordion" "wa-accordion-item"]}
    [:div.stack
     [:wa-details {:id "details" :summary "What is Scittle?"
                   :on {:wa-show #(log! "wa-details" "wa-show") :wa-hide #(log! "wa-details" "wa-hide")}}
      "Scittle runs ClojureScript in the browser via SCI. No build step."]
     [:wa-accordion {:on {:wa-expand #(log! "wa-accordion" "wa-expand" (.. % -target -label))}}
      [:wa-accordion-item {:label "Replicant"} "A data-driven rendering library for hiccup."]
      [:wa-accordion-item {:label "Web Awesome"} "Web components (formerly Shoelace)."]
      [:wa-accordion-item {:label "Babashka"} "Fast native Clojure scripting."]]]]

   [tile {:tags ["wa-dialog" "wa-drawer"]}
    [:div.row
     [:wa-button {:id "open-dialog" :variant "brand" :on {:click #(swap! state assoc :dialog? true)}} "Open dialog"]
     [:wa-button {:id "open-drawer" :on {:click #(swap! state assoc :drawer? true)}} "Open drawer"]]
    [:wa-dialog {:id "dialog" :label "Dialog" :open (:dialog? s) :light-dismiss true
                 :on {:wa-hide #(when (= (.-target %) (.-currentTarget %))
                                  (swap! state assoc :dialog? false) (log! "wa-dialog" "wa-hide"))}}
     "Bound to " [:code ":dialog?"] " in the atom. Closes with Escape, backdrop click, the X, or the button."
     [:wa-button {:slot "footer" :id "close-dialog" :variant "brand" :on {:click #(swap! state assoc :dialog? false)}} "Close"]]
    [:wa-drawer {:id "drawer" :label "Drawer" :open (:drawer? s) :placement "end"
                 :on {:wa-hide #(when (= (.-target %) (.-currentTarget %))
                                  (swap! state assoc :drawer? false) (log! "wa-drawer" "wa-hide"))}}
     [:div.stack [:p "Drawers slide in from an edge."] [:wa-input {:label "Search" :placeholder "Find…"}]]
     [:wa-button {:slot "footer" :id "close-drawer" :on {:click #(swap! state assoc :drawer? false)}} "Close"]]]

   [tile {:tags ["wa-divider" "wa-split-panel"]}
    [:div.row [:span "Left"] [:wa-divider {:orientation "vertical" :style {:height "1.5rem"}}] [:span "Right"]]
    [:wa-divider]
    [:wa-split-panel {:id "split" :position (:split s)
                      :on {:wa-reposition #(swap! state assoc :split (js/Math.round (.. % -target -position)))}}
     [:div.split-pane {:slot "start"} "Start"]
     [:div.split-pane {:slot "end"} (str (:split s) "%")]]]

   [tile {:tags ["wa-scroller"]}
    [:wa-scroller {:orientation "horizontal"}
     [:table
      [:tr (for [h ["Tool" "Runs on" "Startup" "Build step" "Renders with" "Talks to"]] [:th {:replicant/key h} h])]
      [:tr [:td "Scittle"] [:td "Browser"] [:td "~instant"] [:td "none"] [:td "Replicant"] [:td "Web Awesome"]]
      [:tr [:td "Babashka"] [:td "Native"] [:td "~20ms"] [:td "none"] [:td "—"] [:td "everything"]]
      [:tr [:td "shadow-cljs"] [:td "JVM"] [:td "seconds"] [:td "yes"] [:td "any"] [:td "npm"]]]]
    [:p.muted "Scroll sideways; shadows mark overflow."]]

   [tile {:tags ["wa-page" "wa-zoomable-frame"] :wide? true}
    [:wa-zoomable-frame (merge (if single? {:srcdoc (asset "page-demo")} {:src (asset "page-demo")})  ; single: inline HTML
                               {:zoom "0.6" :style {:height "380px"}
                                :on {:load #(log! "wa-zoomable-frame" "load")}})]
    [:p.muted "A full " [:code "<wa-page>"] " layout lives in its own document (page-demo.html; inlined via srcdoc in single-file mode), shown here inside " [:code "<wa-zoomable-frame>"] "."]]])

(defn navigation [s]
  [[tile {:tags ["wa-breadcrumb" "wa-breadcrumb-item"]}
    [:wa-breadcrumb
     [:wa-breadcrumb-item [:wa-icon {:slot "start" :name "house"}] "Home"]
     [:wa-breadcrumb-item [:wa-icon {:slot "start" :name "folder"}] "Projects"]
     [:wa-breadcrumb-item "Kitchen sink"]]]

   [tile {:tags ["wa-tab-group" "wa-tab" "wa-tab-panel"] :data? true}
    [:wa-tab-group {:id "tabs" :active (:tab s)
                    :on {:wa-tab-show [[:store/assoc :tab :event/detail.name]
                                       [:log "wa-tab-group" "wa-tab-show" :event/detail.name]]}}
     [:wa-tab {:slot "nav" :panel "general"} "General"]
     [:wa-tab {:slot "nav" :panel "advanced"} "Advanced"]
     [:wa-tab {:slot "nav" :panel "disabled" :disabled true} "Disabled"]
     [:wa-tab-panel {:name "general"} "General panel."]
     [:wa-tab-panel {:name "advanced"} "Advanced panel."]
     [:wa-tab-panel {:name "disabled"} "—"]]]

   [tile {:tags ["wa-tree" "wa-tree-item"]}
    (into [:wa-tree {:id "tree" :selection "multiple"
                     :on {:wa-selection-change
                          #(let [sel (mapv (fn [i] (first (str/split-lines (str/trim (.-textContent i)))))
                                           (array-seq (.. % -detail -selection)))]
                             (swap! state assoc :tree-sel sel)
                             (log! "wa-tree" "wa-selection-change" (count sel)))}}]
          (map #(tree-item % 0) tree-data))
    [:p.muted (str "Selected: " (if (seq (:tree-sel s)) (str/join ", " (:tree-sel s)) "none"))]]

   [tile {:tags ["wa-pagination"] :data? true}
    [:wa-pagination {:id "pager" :total 237 :page-size 10 :page (:page s) :with-summary true :label "Results"
                     :on {:wa-page-change [[:store/assoc :page :event/target.page]
                                           [:log "wa-pagination" "wa-page-change" :event/target.page]]}}]
    [:p.muted (str "Page " (:page s))]]

   [tile {:tags ["wa-stepper" "wa-step"]}
    [:wa-stepper (merge (ref :stepper) {:id "stepper" :active (:step s) :clickable true
                  :on {:wa-step-change #(do (swap! state assoc :step (.. % -target -active))
                                            (log! "wa-stepper" "wa-step-change" (.. % -target -active)))}})
     [:wa-step {:name "cart" :completed true} "Cart"]
     [:wa-step {:name "shipping"} "Shipping" [:span {:slot "description"} "Address"]]
     [:wa-step {:name "payment"} "Payment"]
     [:wa-step {:name "done"} "Done"]]
    [:div.row {:style {:margin-top ".75rem"}}
     [:wa-button {:size "s" :id "step-prev" :on {:click #(.previous (:stepper @refs))}} "Back"]
     [:wa-button {:size "s" :variant "brand" :id "step-next" :on {:click #(.next (:stepper @refs))}} "Next"]]]])

(defn media [s]
  [[tile {:tags ["wa-avatar" "wa-icon"]}
    [:div.stack
     [:div.row
      [:wa-avatar {:image (asset "avatar") :label "Ada"}]
      [:wa-avatar {:initials "AL" :label "Ada Lovelace"}]
      [:wa-avatar {:label "Anonymous"}]
      [:wa-avatar {:initials "RQ" :shape "rounded" :label "Rounded"}]]
     [:div.row {:style {:font-size "1.6rem"}}
      (for [n ["cat" "fish" "moon" "sun" "bolt" "heart" "rocket"]]
        [:wa-icon {:replicant/key n :name n :label n}])
      [:wa-icon {:name "gear" :animation "spin" :label "spinning gear"}]
      [:wa-icon {:name "arrow-right" :rotate 90 :label "rotated"}]]]]

   [tile {:tags ["wa-animated-image"]}
    [:wa-animated-image {:src (asset "bounce") :alt "Bouncing ball" :play true :style {:max-width "320px"}
                         :on {:wa-load #(log! "wa-animated-image" "wa-load")}}]
    [:p.muted "Click to pause/play."]]

   [tile {:tags ["wa-carousel" "wa-carousel-item"] :exp? true}
    (into [:wa-carousel {:id "carousel" :pagination true :navigation true :loop true :mouse-dragging true
                         :on {:wa-slide-change #(do (swap! state assoc :slide (.. % -detail -index))
                                                    (log! "wa-carousel" "wa-slide-change" (.. % -detail -index)))}}]
          (for [[f alt] images] [:wa-carousel-item {:replicant/key f} [:img {:src (asset f) :alt alt}]]))]

   [tile {:tags ["wa-comparison"]}
    [:wa-comparison {:position 50 :on {:change #(log! "wa-comparison" "change" (js/Math.round (.. % -target -position)))}}
     [:img {:slot "before" :src (asset "dawn-gray") :alt "Grayscale"}]
     [:img {:slot "after" :src (asset "dawn") :alt "Color"}]]]

   [tile {:tags ["wa-lightbox" "wa-lightbox-item"] :exp? true}
    (into [:wa-lightbox {:id "lightbox" :label "Generated landscapes" :class "lb-thumbs" :with-thumbnails true
                         :on {:wa-show #(log! "wa-lightbox" "wa-show")
                              :wa-item-change #(log! "wa-lightbox" "wa-item-change")}}]
          (for [[f alt] images]
            [:wa-lightbox-item {:replicant/key f :src (asset f) :caption alt :width 1200 :height 800}
             [:img {:src (asset (str f "-thumb")) :alt alt}]]))
    [:p.muted "Click a thumbnail."]]

   [tile {:tags ["wa-qr-code"]}
    [:div.row
     [:wa-qr-code {:id "qr" :value (str "https://example.com/u/" (str/replace (:name s) " " "-")) :label "Profile link" :size 128}]
     [:span.muted "Encodes the Name field; type in it and the code regenerates."]]]

   [tile {:tags ["wa-code-highlighter"] :exp? true}
    [:wa-code-highlighter {:language "html"
                           :code "<wa-button variant=\"brand\">\n  <wa-icon slot=\"start\" name=\"rocket\"></wa-icon>\n  Launch\n</wa-button>"
                           :on {:wa-load #(log! "wa-code-highlighter" "wa-load")}}]
    [:p.muted "No Clojure grammar is bundled, so this shows the HTML Replicant emits for [:wa-button {:variant \"brand\"} …]."]]

   [tile {:tags ["wa-markdown"] :exp? true}
    [:wa-markdown [:script {:type "text/markdown"} markdown-src]]]])

(defn helpers [s]
  [[tile {:tags ["wa-format-bytes" "wa-format-number" "wa-format-date" "wa-relative-time"]}
    [:div.stack
     [:wa-number-input {:id "f-bytes" :label "Bytes" :value (:bytes s) :step 1024
                        :on {:input #(swap! state assoc :bytes (js/Number (tv %)))}}]
     [:div "Size: " [:strong [:wa-format-bytes {:value (:bytes s)}]] " · bits: " [:wa-format-bytes {:value (:bytes s) :unit "bit"}]]
     [:div "Number: " [:wa-format-number {:value (:number s)}] " · EUR: " [:wa-format-number {:value (:number s) :type "currency" :currency "EUR" :lang "nl"}]]
     [:div "Date: " [:wa-format-date {:date "2025-12-02T00:00:00-05:00" :month "long" :day "numeric" :year "numeric"}]]
     [:div "Web Awesome 3 shipped " [:wa-relative-time {:date "2025-12-02T00:00:00-05:00"}]]]]

   [tile {:tags ["wa-counter" "wa-number-ticker"] :exp? true}
    [:div.stack
     [:div "Launch in " [:wa-counter {:duration 190800000 :label "Time until launch"}]]
     [:div {:style {:font-size "2rem" :font-weight 700}}
      [:wa-number-ticker (merge (ref :ticker) {:id "ticker" :value (:ticker s) :on {:wa-finish #(log! "wa-number-ticker" "wa-finish")}})]]
     [:div.row
      [:wa-button {:size "s" :id "ticker-add" :on {:click #(swap! state update :ticker + 250000)}} "+250,000"]
      [:wa-button {:size "s" :on {:click #(.restart (:ticker @refs))}} "Restart"]]]]

   [tile {:tags ["wa-animation"]}
    [:div.row
     [:wa-animation {:name (:anim s) :duration 1500 :play (:anim-play? s)} [:div.box]]
     [:wa-select {:value (:anim s) :style {:width "160px"} :on {:change #(swap! state assoc :anim (tv %))}}
      (for [a ["bounce" "jello" "heartBeat" "flip" "tada" "wobble"]] [:wa-option {:replicant/key a :value a} a])]
     [:wa-switch {:checked (:anim-play? s) :on {:change #(swap! state assoc :anim-play? (.. % -target -checked))}} "Play"]]]

   [tile {:tags ["wa-popover" "wa-popup"]}
    [:div.stack
     [:div.row
      [:wa-button {:id "popover-btn" :appearance "outlined"} "Show popover"]
      [:wa-popover {:for "popover-btn" :on {:wa-show #(log! "wa-popover" "wa-show")}}
       [:div.stack [:strong "Popover"] [:p "Interactive content stays open."]
        [:wa-button {:size "s" :variant "brand" :on {:click #(log! "wa-popover" "button inside clicked")}} "Do it"]]]]
     [:div.row {:style {:padding "2.5rem 0 1rem 3rem"}}
      [:wa-popup {:placement (:popup-place s) :active (:popup? s) :distance 8 :arrow true}
       [:span.popup-anchor {:slot "anchor"}]
       [:div.popup-box "wa-popup"]]]
     [:div.row
      [:wa-switch {:checked (:popup? s) :on {:change #(swap! state assoc :popup? (.. % -target -checked))}} "Active"]
      [:wa-radio-group {:value (:popup-place s) :orientation "horizontal"
                        :on {:change #(swap! state assoc :popup-place (tv %))}}
       (for [p ["top" "right" "bottom"]] [:wa-radio {:replicant/key p :appearance "button" :value p} p])]]]]

   [tile {:tags ["wa-include"]}
    [:wa-include {:src (asset "include") :on {:wa-load #(log! "wa-include" "wa-load")}}]]

   [tile {:tags ["wa-intersection-observer"]}
    [:div.io-root {:id "io-root"}
     [:div {:style {:height "170px"}} [:span.muted "Scroll down ↓"]]
     [:wa-intersection-observer {:root "io-root" :threshold "1" :intersect-class "visible"
                                 :on {:wa-intersect #(let [v (.. % -detail -entry -isIntersecting)]
                                                       (swap! state assoc :visible? v)
                                                       (log! "wa-intersection-observer" "wa-intersect" v))}}
      [:div.io-target "I turn green when fully visible"]]
     [:div {:style {:height "170px"}}]]
    [:p.muted (str "Fully visible: " (:visible? s))]]

   [tile {:tags ["wa-resize-observer" "wa-mutation-observer"]}
    [:div.stack
     [:wa-resize-observer {:on {:wa-resize #(let [r (.-contentRect (aget (.. % -detail -entries) 0))]
                                              (swap! state assoc :size (str (js/Math.round (.-width r)) " × " (js/Math.round (.-height r)))))}}
      [:div.resizable (:size s)]]
     [:div.muted "Drag the corner to resize."]
     [:div [:wa-mutation-observer {:attr "variant"
                             :on {:wa-mutation #(log! "wa-mutation-observer" "wa-mutation" (:mut-variant @state))}}
      [:wa-button {:id "mut-btn" :variant (:mut-variant s)
                   :on {:click #(swap! state update :mut-variant {"brand" "success" "success" "danger" "danger" "brand"})}}
       (str "variant=" (:mut-variant s) " (click to mutate)")]]]]]

   [tile {:tags ["wa-random-content"]}
    [:wa-random-content (merge (ref :random) {:id "random" :on {:wa-content-change #(log! "wa-random-content" "wa-content-change")}})
     [:wa-callout {:variant "brand"} [:wa-icon {:slot "icon" :name "lightbulb"}] "Tip: Replicant attaches every :on handler natively."]
     [:wa-callout {:variant "success"} [:wa-icon {:slot "icon" :name "lightbulb"}] "Tip: use dist-cdn, not dist, without a bundler."]
     [:wa-callout {:variant "warning"} [:wa-icon {:slot "icon" :name "lightbulb"}] "Tip: register a local icon library for offline use."]]
    [:wa-button {:size "s" :style {:margin-top ".75rem"} :on {:click #(.randomize (:random @refs))}} "Randomize"]]])

;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------
;; Replicant features beyond hiccup + events.
(def alias-usage "[:ks/stat {:label \"Volume\"} (:volume s)]")

(defn replicant-features [s]
  [[tile {:tags ["life-cycle hooks"] :wide? false}
    [:div.stack
     [:div.muted "Hooks are attributes: " [:code ":replicant/on-mount"] ", " [:code ":replicant/on-render"] ", "
      [:code ":replicant/on-unmount"] ". A function hook gets a map with " [:code ":replicant/node"] ", "
      [:code ":replicant/remember"] " and " [:code ":replicant/memory"] "; a data hook goes to dispatch, like an event."]
     [:wa-switch {:id "lc-toggle" :checked (:lc-show? s)
                  :on {:change #(swap! state assoc :lc-show? (.. % -target -checked))}} "Show search field"]
     (when (:lc-show? s)
       [:div {:replicant/key "lc-wrap"
              ;; data hooks: dispatched like events, :life-cycle/phase filled in
              :replicant/on-mount   [[:log "life-cycle" :life-cycle/phase "search field (data hook)"]]
              :replicant/on-unmount [[:log "life-cycle" :life-cycle/phase "search field (data hook)"]]}
        [:wa-input {:id "lc-input" :label "Focused on mount" :placeholder "Type here"
                    ;; function hook: imperative DOM access, here to focus the field
                    :replicant/on-mount (fn [{:replicant/keys [node]}] (js/setTimeout #(.focus node) 50))}]])
     [:div {:id "lc-renders"
            ;; on-render runs on every render pass that includes this element (measured:
            ;; here, every state change, even unrelated ones).
            ;; memory: a value kept on the DOM node between hook calls, no app state involved
            :replicant/on-render (fn [{:replicant/keys [node remember memory]}]
                                   (let [n (inc (or memory 0))]
                                     (remember n)
                                     (set! (.-textContent node) (str "on-render has run " n " times (every render pass; count kept in hook memory)"))))}]
     [:div.muted "Refs: stepper, toast, ticker and random-content are reached through "
      [:code "(ref :k)"] " (an on-mount hook storing the node), not getElementById. Registered: "
      [:code {:id "refs-list"} (pr-str (vec (sort (keys @refs))))]]]]

   [tile {:tags ["transitions"]}
    [:div.stack
     [:div.muted [:code ":replicant/mounting"] " and " [:code ":replicant/unmounting"]
      " are attribute maps merged in while an element enters or leaves; CSS transitions animate the difference, and Replicant keeps a leaving element in the DOM until its transition ends."]
     [:div.row
      [:wa-button {:id "tr-add" :size "s" :variant "brand" :on {:click [[:demo/add-item]]}}
       [:wa-icon {:slot "start" :name "plus"}] "Add item"]]
     (into [:div {:class "tr-list" :id "tr-list"}]
           (for [id (:items s)]
             [:div {:replicant/key id :id (str "item-" id) :class "tr-item"
                    :replicant/mounting   {:class "tr-item tr-out-left"}
                    :replicant/unmounting {:class "tr-item tr-out-right"}}
              [:span (str "Item " id)]
              [:wa-button {:size "s" :appearance "plain" :class "tr-remove"
                           :on {:click [[:demo/remove-item id]]}}
               [:wa-icon {:name "xmark" :label (str "Remove item " id)}]]]))]]

   [tile {:tags ["aliases"]}
    [:div.stack
     [:div.muted "Your own hiccup tags: " [:code "(replicant.alias/register! :ks/stat stat)"]
      " in views.cljc. An alias function gets " [:code "[attrs children]"] " and returns hiccup."]
     [:div.ks-stats {:id "alias-demo"}
      [:ks/stat {:label "Volume"} (:volume s)]
      [:ks/stat {:label "Page"} (:page s)]
      [:ks/stat {:label "Rating"} (:rating s)]]
     [:ks/chip {:initials (v/initials (:name s))} (:name s)]
     [:div.muted "You write:"]
     [:pre.code alias-usage]
     [:div.muted "Replicant renders:"]
     [:pre.code {:id "alias-expanded"} (rs/render [:ks/stat {:label "Volume"} (:volume s)])]]]

   [tile {:tags ["replicant.string"]}
    [:div.stack
     [:div.muted "The same view functions render to an HTML string with " [:code "replicant.string/render"]
      ": here in the browser, and on the server under " [:code "bb dev-ssr"] ". Handlers and hooks are dropped; aliases expand."]
     (v/profile-card (select-keys s [:name :email :qty]))
     [:div.muted [:code "(rs/render (v/profile-card {...}))"] " - updates as you edit the profile form:"]
     [:pre.code {:id "string-out"} (rs/render (v/profile-card (select-keys s [:name :email :qty])))]]]])

(def categories
  [["Replicant features" replicant-features] ["Actions" actions] ["Forms" forms] ["Feedback" feedback] ["Layout" layout]
   ["Navigation" navigation] ["Media" media] ["Helpers" helpers]])

;; The State panel: one row per top-level key, sorted, with the keys changed by
;; the most recent swap! highlighted. (:log and :form are shown elsewhere.)
(defonce last-change (atom {:keys #{} :id 0}))

(defn state-panel [s]
  (let [changed (:keys @last-change)]
    (into [:div.state {:id "state"}]
          (for [[k v] (into (sorted-map) (dissoc s :log :form))]
            [:div {:replicant/key (str k) :data-key (str k)
                   :class (if (contains? changed k) "kv changed" "kv")}
             [:span.k (str k)] " " (pr-str v)]))))

(defn view [s]
  [:div.layout
   [:main
    [:div.grid (server-tile s) (events-tile s)]
    (for [[title f] categories]
      [:div {:replicant/key title}
       [:div.cat [:h2 title]]
       ;; Replicant renders plain data: [tile ...] is not a component call, so apply it here.
       (into [:div.grid] (map (fn [[tile-fn & args]] (apply tile-fn args))) (f s))])]
   [:aside.side
    [:h3 {:style {:margin "0 0 .5rem"}} "Event log"]
    [:div.log {:id "log"} (str/join "\n" (:log s))]
    [:h3 {:style {:margin "1rem 0 .5rem"}} "State " [:span.muted "(changed keys highlighted)"]]
    (state-panel s)]])

(def root (js/document.getElementById "app"))
(defn render! [] (d/render root (view @state)))

(defn scroll-to-change! []
  (when-let [row (js/document.querySelector "#state .kv.changed")]
    (let [panel (.-parentElement row)            ; .state is position:relative
          top (.-offsetTop row)
          bottom (+ top (.-offsetHeight row))]
      (cond (< top (.-scrollTop panel))
            (set! (.-scrollTop panel) (- top 8))
            (> bottom (+ (.-scrollTop panel) (.-clientHeight panel)))
            (set! (.-scrollTop panel) (+ (- bottom (.-clientHeight panel)) 8))))))

(defn note-change!
  "Remember which top-level keys the last swap! changed; clear the highlight
   after a moment unless another change came in."
  [old new]
  (let [ks (into #{} (remove #(= (get old %) (get new %))) (into (set (keys old)) (keys new)))]
    (when (seq (disj ks :log :form))
      (let [id (inc (:id @last-change))]
        (reset! last-change {:keys ks :id id})
        ;; after this render, bring the first highlighted row into view inside the
        ;; State panel (scrolls only the panel, never the page)
        (js/setTimeout scroll-to-change! 0)
        (js/setTimeout #(when (= id (:id @last-change))
                          (swap! last-change assoc :keys #{})
                          (render!))
                       2000)))))

;; Every swap! on state lands here: record what changed, then re-render.
(add-watch state ::render (fn [_ _ old new] (note-change! old new) (render!)))
(render!)
(set! (.-__state js/window) (fn [] (clj->js (dissoc @state :interests))))
(set! (.-__ready js/window) true)
