---
name: scittle-replicant-webawesome
description: Build browser UIs in ClojureScript with no build step - Scittle (in-browser cljs), Replicant (hiccup rendering) and Web Awesome (78 web components). Use when asked for a Scittle/ClojureScript web UI, a single-HTML-file app, or Web Awesome components driven from cljs.
---

# Scittle + Replicant + Web Awesome UIs

No React, no bundler, no build step. Scittle interprets ClojureScript in the browser, Replicant renders hiccup to the DOM, Web Awesome provides the components (`<wa-*>` custom elements). Everything below was verified in a browser with Scittle 0.8.33, Web Awesome 3.15.0, Font Awesome Free 7.3.1.

Reference implementation (all 78 components, three delivery modes, tests): https://github.com/franks42/scittle-replicant-webawesome-kitchensink. Copy a component's tile from `app/public/cljs/kitchen_sink.cljs` when you need its exact wiring.

## 1. Start from this page (works as-is)

```html
<!DOCTYPE html>
<html lang="en" class="wa-theme-default">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <link rel="icon" href="data:," />
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.15.0/dist-cdn/styles/webawesome.css" />
  <script type="module" src="https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.15.0/dist-cdn/webawesome.loader.js"
          data-webawesome="https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.15.0/dist-cdn/"></script>
  <script type="module">
    import { registerIconLibrary } from "https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.15.0/dist-cdn/webawesome.loader.js";
    registerIconLibrary("default", {
      resolver: (name) => `https://cdn.jsdelivr.net/npm/@fortawesome/fontawesome-free@7.3.1/svgs/solid/${name}.svg`,
      mutator: (svg) => svg.setAttribute("fill", "currentColor"),
    });
  </script>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.8.33/dist/scittle.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.8.33/dist/scittle.replicant.js"></script>
</head>
<body>
  <div id="app"></div>
  <script type="application/x-scittle">
  (ns app (:require [replicant.dom :as d]))

  (defonce state (atom {:name "Ada" :open? false}))

  (defn view [s]
    [:div {:style {:display "grid" :gap "1rem" :max-width "420px" :padding "2rem"}}
     [:wa-input {:label "Name" :value (:name s)
                 :on {:input #(swap! state assoc :name (.. % -target -value))}}]
     [:wa-button {:variant "brand" :on {:click #(swap! state assoc :open? true)}} "Open"]
     [:wa-dialog {:label "Hello" :open (:open? s)
                  :on {:wa-hide #(when (= (.-target %) (.-currentTarget %))
                                   (swap! state assoc :open? false))}}
      (str "Hi " (:name s))]])

  (def root (js/document.getElementById "app"))
  (defn render! [] (d/render root (view @state)))
  (add-watch state ::render (fn [_ _ _ _] (render!)))
  (render!)
  </script>
</body>
</html>
```

Serve over HTTP (any static server, e.g. `bb scripts/serve_static.clj <dir> <port>` from the reference repo). `file://` does not work. To keep the UI in its own file: `<script type="application/x-scittle" src="app.cljs"></script>`.

## 2. Rules

1. **One atom, one render loop.** All state in a `defonce` atom; `add-watch` → `d/render` of `(view @state)`. Views are pure functions returning hiccup.
2. **Call view helpers as functions: `(card x)`, never `[card x]`.** Replicant renders data; `[fn args]` is Reagent syntax and renders as literal text.
3. **Events: `:on {:event-name handler}`** - native listeners, so standard (`input`, `change`, `click`) and custom (`wa-hide`, `wa-tab-show`, …) events work the same.
4. **Attributes:** `true` sets a boolean attribute, `false`/`nil` removes it (`:open`, `:checked`, `:disabled`, `:with-caret`). `:value` is set as a **property** (so `[value=x]` CSS selectors won't match it). Pass arrays as JS: `:value (clj->js v)` for `wa-select :multiple`.
5. **Repeated children:** add `:replicant/key`.
6. **Component methods:** give an `:id`, call on the node: `(.next (js/document.getElementById "stepper"))`.
7. **Controlled inputs:** bind `:value`/`:checked` to state and update from events - works for every form control **except `wa-otp-input`: leave it uncontrolled** (read `.value` in `input`/`wa-complete`), or typed digits are lost.
8. **Values are strings**, including `wa-number-input` (`"3"`); parse when you need numbers.
9. **Dialogs/drawers:** bind `:open` to state; on `wa-hide`, set it false only when `(= (.-target e) (.-currentTarget e))` (child overlays bubble `wa-hide` too).
10. **Don't use Reagent here.** Scittle's Reagent uses React 18, which drops `change`/`wa-*` events on custom elements, stringifies `false`, and sets `:value` as an attribute. Replicant needs no adapter.

## 3. Reading events

| Component | Event | Value |
|---|---|---|
| `wa-input`, `wa-textarea`, `wa-number-input`, `wa-slider` | `input` / `change` | `(.. e -target -value)` |
| `wa-select`, `wa-radio-group`, `wa-rating`, `wa-color-picker`, `wa-time-input`, `wa-known-date`, `wa-tag-input` | `change` | `.value` (array for `multiple`) |
| `wa-checkbox`, `wa-switch` | `change` | `(.. e -target -checked)` |
| `wa-slider` with `:range true` | `change` | `.minValue`, `.maxValue` |
| `wa-dropdown` | `wa-select` | `(.. e -detail -item -value)` |
| `wa-tab-group` | `wa-tab-show` | `(.. e -detail -name)` |
| `wa-tree` | `wa-selection-change` | `(.. e -detail -selection)` (JS array of items) |
| `wa-pagination` | `wa-page-change` | `(.. e -target -page)` |
| `wa-stepper` | `wa-step-change` | `(.. e -target -active)` |
| `wa-carousel` | `wa-slide-change` | `(.. e -detail -index)` |
| `wa-dialog`, `wa-drawer`, `wa-details`, `wa-popover`, `wa-tooltip` | `wa-show` / `wa-hide` | - |
| `wa-tag` (`:with-remove true`) | `wa-remove` | - |
| `<form>` of `wa-*` controls with `:name` | `submit` | `(.preventDefault e)`, then `(js/FormData. (.-target e))` |

Toasts: one `[:wa-toast {:id "toast"}]`, then `(.create (js/document.getElementById "toast") "Saved" #js {:variant "success" :duration 4000})`.

For any other component, read `dist-cdn/custom-elements.json` or `dist-cdn/skills/webawesome/references/components/<name>.md` in the `@awesome.me/webawesome` package (attributes, events, methods, slots, examples) - don't guess.

## 4. Talking to a server

```clojure
(require '[clojure.edn :as edn])
(defn api! [method path body on-ok]
  (-> (js/fetch path (clj->js (cond-> {:method method :headers {"Content-Type" "application/edn"}}
                                body (assoc :body (pr-str body)))))
      (.then #(.text %))
      (.then #(on-ok (edn/read-string %)))))
```

Server push: one `js/WebSocket`, EDN messages dispatched on `:op`; keep the socket in a `defonce` so re-evaluation doesn't open a second one. A babashka/http-kit server with an EDN API, websocket and hot reload is in the reference repo (`app/server.clj`).

## 5. Setup traps

- Use Web Awesome's **`dist-cdn`** build. `dist` has bare imports (`lit`, …) that need a bundler; symptom: components never upgrade.
- Icons default to Font Awesome's kit CDN; register a library (template does). `wa-number-input` asks that library for `plus`/`minus`, so a trimmed self-hosted icon set must include them.
- `size` values are `"s" | "m" | "l"` (`"small"` logs a deprecation warning).
- No Pro components in the free tier (e.g. `wa-combobox`); some doc examples use them.
- Experimental in 3.15.0: carousel, code-highlighter, counter, lightbox, markdown, number-ticker, tag-input.
- Strict-CSP hosts that block external stylesheets: self-host and flatten `webawesome.css` (it `@import`s four files).

## 6. Verify before you say it works

Open the page in a real browser (Playwright/Chrome) and check:

```js
document.querySelectorAll(':not(:defined)').length === 0   // every <wa-*> upgraded
```

- No console errors.
- Click through each interactive control and confirm state changed (expose `window.__state = () => clj->js @state` while developing, or show a state panel).
- With Playwright: selectors pierce shadow DOM (`#name input`, `[part~=track]`); find part names with `[...el.shadowRoot.querySelectorAll('[part]')].map(x => x.getAttribute('part'))`; scroll into view before coordinate clicks; select by text, not `[value=…]`.
