;; Browser-free unit tests: `bb test` (no Java, no deps, no browser).
;;
;; logic.cljc and views.cljc are the same files the browser loads through
;; Scittle. They have no interop and no dependencies, so babashka can load them
;; with load-file and test them as plain functions: state in, state out for the
;; reducer, data in, hiccup out for the views.
(ns unit-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.walk :as walk]))

(load-file "app/public/cljs/logic.cljc")
(load-file "app/public/cljs/views.cljc")

(require '[kitchen-sink.logic :as logic]
         '[kitchen-sink.views :as v])

;; ---------------------------------------------------------------------------
;; The reducer: actions are data, so the whole event path is testable here.

(deftest store-actions
  (is (= {:a 1} (logic/apply-action {} [:store/assoc :a 1])))
  (is (= {:a {:b 2}} (logic/apply-action {} [:store/assoc-in [:a :b] 2])))
  (is (= {:n 1} (logic/apply-action {} [:store/inc :n])) "inc treats a missing key as 0")
  (is (= {:n 6} (logic/apply-action {:n 5} [:store/inc :n]))))

(deftest log-action
  (let [s (logic/apply-action {} [:log "button" "click" "Primary"])]
    (is (= ["button · click → Primary"] (:log s))))
  (is (= ["x · y"] (:log (logic/apply-action {} [:log "x" "y" nil])))
      "a nil detail is left out")
  (let [s (reduce (fn [s i] (logic/add-log s "t" "e" i)) {} (range 250))]
    (is (= logic/log-size (count (:log s))) "the log is capped")
    (is (= "t · e → 249" (first (:log s))) "newest first")))

(deftest demo-actions
  (let [s (-> {:items [1 2 3] :item-seq 3}
              (logic/apply-action [:demo/add-item])
              (logic/apply-action [:demo/add-item])
              (logic/apply-action [:demo/remove-item 2]))]
    (is (= [1 3 4 5] (:items s)))
    (is (= 5 (:item-seq s)) "ids keep increasing after a removal")))

(deftest unknown-action-is-logged-not-thrown
  (let [s (logic/apply-action {} [:no/such-action 1])]
    (is (re-find #"unknown action" (first (:log s))))))

(deftest apply-actions-records-the-batch
  (let [actions [[:store/inc :data-count] [:log "events" "data handler" nil]]
        s (logic/apply-actions {:data-count 0} actions)]
    (is (= 1 (:data-count s)))
    (is (= actions (:last-action s)) "the state panel shows the last batch")
    (is (= 1 (count (:log s))))))

;; ---------------------------------------------------------------------------
;; Routing helpers

(deftest routing
  (is (= "date-and-time" (logic/slug "Date and  time")))
  (is (= :all (logic/parse-route "")))
  (is (= :all (logic/parse-route "#/")))
  (is (= :all (logic/parse-route nil)))
  (is (= "forms" (logic/parse-route "#/forms")))
  (is (= "#/" (logic/route-href :all)))
  (is (= "#/forms" (logic/route-href "forms")))
  (testing "round trip"
    (doseq [r [:all "forms" "replicant-features"]]
      (is (= r (logic/parse-route (logic/route-href r)))))))

;; ---------------------------------------------------------------------------
;; Theming: state -> classes on <html>

(deftest html-classes
  (is (= ["wa-theme-default" "wa-light"] (logic/html-classes {})))
  (is (= ["wa-theme-awesome" "wa-palette-bright" "wa-brand-green" "wa-dark"]
         (logic/html-classes {:theme "awesome" :palette "bright" :brand "green" :dark? true})))
  (is (= ["wa-theme-shoelace" "wa-light"]
         (logic/html-classes {:theme "shoelace" :palette "theme" :brand "theme"}))
      "\"theme\" means: use the theme's own palette / brand")
  (is (= {:theme "default" :palette "theme" :brand "theme" :dark? true}
         (logic/apply-action {:theme "awesome" :palette "bright" :brand "red" :dark? true}
                             [:theme/reset]))
      "reset keeps the dark-mode choice"))

;; ---------------------------------------------------------------------------
;; Validation: one function, used by the browser and by the server's 400

(deftest validate-profile
  (is (= {} (logic/validate-profile {:name "Ada" :email "" :qty 3})))
  (is (= {:name "Name is required"} (logic/validate-profile {:name "  " :qty "2"})))
  (is (= {:email "Not a valid email address"} (logic/validate-profile {:name "Ada" :email "ada@"})))
  (testing "cross-field rule: quantities over 5 need an email (form values are strings)"
    (is (= {:email "Email is required for quantities over 5"} (logic/validate-profile {:name "Ada" :qty "6"})))
    (is (= {} (logic/validate-profile {:name "Ada" :qty "6" :email "ada@example.com"})))
    (is (= {} (logic/validate-profile {:name "Ada" :qty 5}))))
  (is (= {:qty "Quantity must be between 0 and 10"} (logic/validate-profile {:name "Ada" :qty "11" :email "a@b.co"})))
  (is (= {} (logic/validate-profile {:name "Ada" :qty "not a number"})) "non-numbers are the browser's job"))

;; ---------------------------------------------------------------------------
;; Views: data in, hiccup out. No renderer needed to check structure.

(defn find-all
  "Every hiccup vector in tree whose tag is t."
  [t tree]
  (let [acc (atom [])]
    (walk/postwalk (fn [x] (when (and (vector? x) (= t (first x))) (swap! acc conj x)) x) tree)
    @acc))

(deftest initials
  (is (= "AL" (v/initials "Ada Lovelace")))
  (is (= "GB" (v/initials "Grace Brewster Hopper")) "first two words only")
  (is (= "" (v/initials nil))))

(deftest profiles-table
  (let [ps [{:id 1 :name "Ada" :email "ada@example.com" :qty 2 :saved-at "t1"}
            {:id 2 :name "Grace" :email "g@example.com" :qty 5 :saved-at "t2"}]
        t (v/profiles-table ps)
        rows (find-all :tr t)]
    (is (= :table (first t)))
    (is (= 3 (count rows)) "header + one row per profile")
    (is (= [1 2] (keep (comp :replicant/key second) rows)) "rows are keyed by id")
    (is (= "Grace" (-> rows last (nth 3) second)))))

(deftest profile-card-uses-aliases
  (let [card (v/profile-card {:name "Ada Lovelace" :email "" :qty 3})]
    (is (= 1 (count (find-all :ks/chip card))))
    (is (= 2 (count (find-all :ks/stat card))))
    (is (= "(no name)" (last (first (find-all :ks/chip (v/profile-card {})))))))
  (testing "alias functions expand to plain hiccup"
    (let [[tag attrs & _ :as h] ((:ks/stat v/aliases) {:label "Qty" :id "q"} ["3"])]
      (is (= :div tag))
      (is (= {:class "ks-stat" :id "q"} attrs) ":label is consumed, other attrs pass through")
      (is (= [[:div {:class "ks-stat-value"} "3"]] (find-all :div (last h)))))))

(let [{:keys [fail error]} (run-tests 'unit-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
