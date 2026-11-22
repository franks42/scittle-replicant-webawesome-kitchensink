# Building browser UIs with Scittle + Replicant + Web Awesome

A working guide for AI assistants. Everything marked **verified** was exercised in headless Chrome (Playwright) against a 78-component kitchen-sink page; 60/60 interaction checks passed (72/72 in the repo's current suite, which adds State-panel, layout and events-as-data checks), and the published page was also opened on an iPad. Items marked **unverified** are stated as such.

Versions tested: Scittle 0.8.33 (with its Replicant plugin), Web Awesome 3.15.0 (MIT, free tier), Font Awesome Free solid SVGs for icons.

---

## 1. What this stack is

| Piece | Role | Notes |
|---|---|---|
| **Scittle** | Interprets ClojureScript in the browser (SCI). Code lives in `<script type="application/x-scittle">` tags. | No build step. ~970 KB JS. Fine for UI logic; not for heavy computation. |
| **Replicant** | Renders hiccup to the DOM, attaches `:on` handlers with `addEventListener`. | Loaded as a Scittle plugin (`scittle.replicant.js`). No React. |
| **Web Awesome** | ~78 web components (`<wa-*>`): forms, overlays, navigation, media, helpers. Formerly Shoelace. | Built on Lit internally; you never touch Lit. |

Mental model: **Replicant renders your light DOM (the `<wa-*>` elements, their attributes/properties, your handlers). Lit renders each component's internals inside its shadow root.** They meet only at the element's public surface: you set properties/attributes, the component fires events.

No React is involved. Do not reach for Reagent here (see §9 for why).

---

## 2. Page skeleton — single file, everything from jsdelivr (verified live)

Save as `index.html`, serve it with any static server, done. Every dependency is a pinned jsdelivr URL; nothing else needs to be downloaded. Verified by loading this exact file in Chrome against the live CDN: all components registered, icons rendered, typing, number stepping and the dialog worked, no console errors.

```html
<!DOCTYPE html>
<html lang="en" class="wa-theme-default">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <link rel="icon" href="data:," />

  <!-- Web Awesome 3.15.0 (dist-cdn build): stylesheet + autoloader -->
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.15.0/dist-cdn/styles/webawesome.css" />
  <script type="module"
          src="https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.15.0/dist-cdn/webawesome.loader.js"
          data-webawesome="https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.15.0/dist-cdn/"></script>

  <!-- Icons: Font Awesome Free SVGs from jsdelivr instead of Font Awesome's kit CDN -->
  <script type="module">
    import { registerIconLibrary } from "https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.15.0/dist-cdn/webawesome.loader.js";
    registerIconLibrary("default", {
      resolver: (name) => `https://cdn.jsdelivr.net/npm/@fortawesome/fontawesome-free@7.3.1/svgs/solid/${name}.svg`,
      mutator: (svg) => svg.setAttribute("fill", "currentColor"),
    });
  </script>

  <!-- Scittle 0.8.33 + its Replicant plugin -->
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.8.33/dist/scittle.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/scittle@0.8.33/dist/scittle.replicant.js"></script>
</head>
<body>
  <div id="app"></div>

  <script type="application/x-scittle">
  (ns app
    (:require [replicant.dom :as d]))

  (defonce state (atom {:name "Ada" :qty 1 :dialog? false}))

  (defn view [s]
    [:div {:style {:max-width "420px" :padding "2rem" :display "grid" :gap "1rem"}}
     [:wa-input {:label "Name" :value (:name s)
                 :on {:input (fn [e] (swap! state assoc :name (.. e -target -value)))}}
      [:wa-icon {:slot "start" :name "user"}]]
     [:wa-number-input {:label "Quantity" :min 0 :max 9 :value (:qty s)
                        :on {:input (fn [e] (swap! state assoc :qty (.. e -target -value)))}}]
     [:wa-button {:variant "brand" :on {:click #(swap! state assoc :dialog? true)}}
      [:wa-icon {:slot "start" :name "rocket"}] "Open dialog"]
     [:wa-dialog {:label "Hello" :open (:dialog? s)
                  :on {:wa-hide (fn [e] (when (= (.-target e) (.-currentTarget e))
                                          (swap! state assoc :dialog? false)))}}
      (str "Hi " (:name s) ", quantity " (:qty s))]])

  (def root (js/document.getElementById "app"))
  (defn render! [] (d/render root (view @state)))
  (add-watch state ::render (fn [_ _ _ _] (render!)))
  (render!)
  </script>
</body>
</html>
```

Serve it over HTTP with any static file server, e.g. this repo's `bb scripts/serve_static.clj <dir> <port>` (http-kit, built into babashka). `file://` will not work for ES-module loading.

### Pinned versions (bump deliberately, together)

| Dependency | URL base |
|---|---|
| Web Awesome 3.15.0 (`dist-cdn` build) | `https://cdn.jsdelivr.net/npm/@awesome.me/webawesome@3.15.0/dist-cdn/` |
| Scittle 0.8.33 + Replicant plugin | `https://cdn.jsdelivr.net/npm/scittle@0.8.33/dist/` |
| Font Awesome Free 7.3.1 icons | `https://cdn.jsdelivr.net/npm/@fortawesome/fontawesome-free@7.3.1/svgs/solid/` |

The `data-webawesome` attribute must point at the same `dist-cdn/` base as the loader; Web Awesome uses it to find each component on demand.

### Self-hosting instead (also verified)

Copy npm `@awesome.me/webawesome/dist-cdn/` to `./wa/`, `scittle/dist/scittle.js` + `scittle.replicant.js` to `./vendor/`, and `@fortawesome/fontawesome-free/svgs/solid/` to `./icons/solid/`; then use relative URLs (`wa/styles/webawesome.css`, `wa/webawesome.loader.js` with `data-webawesome="wa/"`, `icons/solid/${name}.svg`). Needed when the host blocks external scripts or must work offline.

---

## 3. Setup gotchas (all verified)

1. **Use `dist-cdn`, not `dist`.** `dist/` contains bare imports (`lit`, `@lit/react`, `microlighter/...`) that browsers can't resolve without a bundler. Symptom: components never register; `document.querySelectorAll(':not(:defined)')` stays non-empty; console shows `Failed to resolve module specifier`.
2. **Icons default to Font Awesome's CDN.** Register a local library (skeleton above). Built-in chrome (chevrons, close X, rating stars, checkmarks) uses an internal "system" set and needs nothing.
3. **Some components use the *default* icon library internally.** `wa-number-input`'s +/− buttons request `plus.svg` and `minus.svg` from whatever library you registered. With the full jsdelivr icon set this is automatic; if you self-host a trimmed icon folder, include them. Find others by logging requests to `/icons/` during a full interaction run.
4. **Strict CSP / hosted sandboxes:** if external stylesheets are blocked, flatten `webawesome.css` (it `@import`s `layers.css`, `native.css`, `utilities.css`, `themes/default.css`) into one local file. Scripts from a CDN may still be allowed.
5. **Remote images may be blocked** in sandboxed hosts. Ship local images for `wa-card`, `wa-carousel`, `wa-comparison`, `wa-lightbox`, `wa-animated-image`, `wa-avatar`.
6. **The autoloader is lazy.** Each `<wa-*>` loads the first time it appears in the DOM (≈125 files for a full kitchen sink). Elements inside a closed dialog still load when they enter the DOM.

---

## 4. Replicant rules that matter here

### 4.1 Functions are not components (verified — easy to get wrong)
Replicant renders **plain data**. `[my-fn arg]` is *not* a component call (that is Reagent's convention); Replicant will render the vector literally as text. Call the function:

```clojure
;; WRONG in Replicant
[:div [card {:title "x"}]]
;; RIGHT
[:div (card {:title "x"})]
```

### 4.2 Events
`:on {:event-name handler}` attaches a native listener. Standard and custom events work the same way:

```clojure
[:wa-select {:on {:change (fn [e] (.. e -target -value))}} ...]
[:wa-tab-group {:on {:wa-tab-show (fn [e] (.. e -detail -name))}} ...]
```

### 4.3 Attributes vs properties
- Strings/numbers → set normally. `true` → attribute present; `false`/`nil` → attribute removed. Booleans like `:open`, `:checked`, `:disabled`, `:with-caret`, `:with-clear` just work.
- `:value` is set as a **property** (verified on `wa-dropdown-item`, `wa-option`, `wa-input`). Consequence: the `value` *attribute* may be absent in the DOM, so CSS/Playwright selectors like `wa-option[value=banana]` won't match. Select by text instead.
- Arrays (e.g. `wa-select multiple`): pass a JS array: `:value (clj->js (:extras s))`.

### 4.4 Lists
Use `:replicant/key` on repeated children produced by `for`.

### 4.5 Calling component methods
Give the element an `:id` and call the method on the DOM node (verified):

```clojure
(defn el [id] (js/document.getElementById id))
[:wa-button {:on {:click #(.next (el "stepper"))}} "Next"]
[:wa-button {:on {:click #(.create (el "toast") "Saved" #js {:variant "success" :icon "circle-check" :duration 4000})}} "Save"]
```
(Replicant also has lifecycle hooks such as `:replicant/on-mount`; not used/verified in this setup.)

### 4.6 Events as data (verified)
A handler that is not a function is passed unchanged to the function registered with `replicant.dom/set-dispatch!`, called as `(fn [event-data handler-data])`; `event-data` contains `:replicant/trigger` (`:replicant.trigger/dom-event` for DOM events), `:replicant/dom-event` and `:replicant/node`. Placeholders such as `:event/target.value` are a convention you implement (e.g. with `clojure.walk/postwalk`), not a Replicant feature. The kitchen sink's *Events as data* section shows a complete version: placeholders, a pure `apply-action` reducer, and one `swap!` per event. Function and data handlers can be mixed in the same `:on` map.

### 4.7 State loop
One atom, `add-watch` → `d/render`. Rendering is synchronous on each `swap!`. This was fast enough for a page with all 78 components, including high-frequency events like `wa-reposition` from the split panel.

### 4.8 Multiple Scittle script tags
Each `<script type="application/x-scittle">` can define a namespace; later tags can `:require` earlier ones (verified).

---

## 5. Controlled vs uncontrolled inputs

Binding `:value` to state and updating state from `input` events (the "controlled" pattern) works for: `wa-input`, `wa-textarea`, `wa-number-input`, `wa-select` (single and multiple), `wa-slider`, `wa-rating`, `wa-color-picker`, `wa-tag-input`, `wa-time-input`, `wa-known-date`, `wa-radio-group`, and `checked` on `wa-checkbox`/`wa-switch`. Programmatic updates (e.g. clearing a field from a button) show up in the UI (verified).

**Exception — `wa-otp-input` must be uncontrolled (verified, 12/12 vs 12/12):** with `:value` bound, typed digits were lost at every typing speed; unbound, all 12 runs kept the code. Read the value from events instead:

```clojure
[:wa-otp-input {:label "Code" :length 6           ; no :value
                :on {:input (fn [e] (swap! state assoc :otp (.. e -target -value)))
                     :wa-complete (fn [e] (submit! (.. e -target -value)))}}]
```

The likely mechanism is a timing interaction between writing the property back and the component's own batched (Lit) update — **unverified**. If another component misbehaves when controlled, try leaving it uncontrolled first.

---

## 6. Event cheat sheet (all verified in the kitchen sink)

| Component | Event | Read |
|---|---|---|
| `wa-input`, `wa-textarea`, `wa-number-input` | `input`, `change` | `(.. e -target -value)` — a **string**, also for `wa-number-input` (`"3"`); parse with `js/Number` / `parse-long` if you need a number |
| `wa-select` | `change` | `.value` (JS array when `multiple`) |
| `wa-checkbox`, `wa-switch` | `change` | `(.. e -target -checked)` |
| `wa-radio-group` | `change` | `.value` on the group |
| `wa-slider` | `input` / `change` | `.value`; range mode: `.minValue`, `.maxValue` |
| `wa-rating`, `wa-color-picker`, `wa-time-input`, `wa-known-date`, `wa-tag-input` | `change` | `.value` |
| `wa-otp-input` | `input`, `wa-complete` | `.value` (keep uncontrolled) |
| `wa-dropdown` | `wa-select` | `(.. e -detail -item -value)`; checkbox items: `.checked` on the item |
| `wa-tab-group` | `wa-tab-show` | `(.. e -detail -name)` |
| `wa-dialog`, `wa-drawer` | `wa-hide` | guard `(= (.-target e) (.-currentTarget e))` so child overlays don't close it; then set state false |
| `wa-details` | `wa-show` / `wa-hide` | — |
| `wa-accordion` | `wa-expand` | `(.. e -target -label)` |
| `wa-tree` | `wa-selection-change` | `(.. e -detail -selection)` → JS array of items |
| `wa-pagination` | `wa-page-change` | `(.. e -target -page)` |
| `wa-stepper` | `wa-step-change` | `(.. e -target -active)`; methods `.next`, `.previous` |
| `wa-carousel` | `wa-slide-change` | `(.. e -detail -index)` |
| `wa-comparison` | `change` | `(.. e -target -position)` |
| `wa-split-panel` | `wa-reposition` | `(.. e -target -position)` (fires often) |
| `wa-tag` (`with-remove`) | `wa-remove` | remove from your list |
| `wa-copy-button` | `wa-copy` | — |
| `wa-lightbox` | `wa-show`, `wa-item-change` | — |
| `wa-tooltip`, `wa-popover` | `wa-show` | target via `:for "element-id"` |
| `wa-intersection-observer` | `wa-intersect` | `(.. e -detail -entry -isIntersecting)`; `:root` takes an element **id** |
| `wa-resize-observer` | `wa-resize` | `(.-contentRect (aget (.. e -detail -entries) 0))` |
| `wa-mutation-observer` | `wa-mutation` | `:attr "variant"` etc. |
| `wa-random-content` | `wa-content-change` | method `.randomize` |
| `wa-number-ticker` | `wa-finish` | method `.restart`; animates when scrolled into view |
| `wa-include`, `wa-animated-image`, `wa-code-highlighter` | `wa-load` | — |
| `<form>` containing `wa-*` controls with `:name` | `submit` | `(.preventDefault e)` then `(js/FormData. (.-target e))` — WA controls are form-associated |

`wa-load` may fire more than once for `wa-include`/`wa-animated-image`; the resource is still fetched only once (verified).

---

## 7. Component notes (verified)

- **Experimental in 3.15.0:** `wa-carousel`, `wa-carousel-item`, `wa-code-highlighter`, `wa-counter`, `wa-lightbox`, `wa-lightbox-item`, `wa-markdown`, `wa-number-ticker`, `wa-tag-input`. APIs may change.
- **`wa-page`** is a full-page layout (header, subheader, navigation, aside, footer slots). To demo it inside another page, put it in its own HTML file and show it with `[:wa-zoomable-frame {:src "page-demo.html" :zoom "0.6"}]`.
- **`wa-markdown`:** content goes in a child script: `[:wa-markdown [:script {:type "text/markdown"} md]]`. It renders once; to change content later, update the script's text and call `.renderMarkdown` (per docs; unverified here).
- **`wa-code-highlighter`:** use the `:code` attribute. Highlighting uses the CSS Custom Highlight API (no `<span>`s in the shadow DOM). No Clojure grammar is bundled; `html`, `javascript`, `css`, `python` and others are.
- **`wa-popup`:** the docs' main example uses `wa-combobox`, which is a **Pro** component. Build your own: `[:wa-popup {:placement "top" :active true :arrow true :distance 8} [:span {:slot "anchor"} ...] [:div "content"]]`.
- **`wa-toast`:** a single `[:wa-toast {:id "toast"}]` container; create items with `.create`. `wa-toast-item` with `:duration 0` can also be rendered statically.
- **`size` values:** use short forms `"s"`, `"m"`, `"l"`; `"small"` logs a deprecation warning in 3.x.
- **Docs ship with the package:** `dist-cdn/skills/webawesome/references/components/<name>.md` (one per component, with examples) and `dist-cdn/custom-elements.json` (attributes, events, methods, slots). Read these before guessing an API.

---

## 8. Testing with Playwright (lessons that cost time)

- Playwright CSS selectors pierce open shadow roots, so `#f-name input` reaches the inner `<input>`. Use `[part~=name]` to target exported parts: `[part~=track]`, `[part~=thumb]`, `[part~=trigger]`, `[part~=swatch]`, `[part~=segment]`, `[part~=field-input]`, `[part~=navigation-button-next]`, `[part~=handle]`, `[part~=divider]`, `[part~=remove-button]`, `[part~=checkbox]`.
- **Find part names** instead of guessing: `[...el.shadowRoot.querySelectorAll('[part]')].map(x => x.getAttribute('part'))`.
- **Don't select by `[value=...]`** (Replicant sets `value` as a property). Use `:has-text(...)`.
- **Scroll into view before coordinate clicks** (`scroll_into_view_if_needed`, then `bounding_box`); otherwise the click lands off-screen.
- **Sliders:** click on `[part~=track]` at a fraction of its width.
- **Color picker:** click `[part~=trigger]` to open, then a `[part~=swatch]`.
- **OTP:** click the first `[part~=segment]`, then type.
- **Known date:** click each `[part~=field-month|field-day|field-year] [part~=field-input]` and type.
- **Tree checkboxes** are `pointer-events: none`; the row takes the click. Use a mouse click at the checkbox's coordinates.
- **Dropdown submenus:** hover the parent item, wait ~500 ms, then click the `[slot=submenu]` item.
- A failed step can leave an overlay open and break later steps (we saw "typing" capture only one character after a failed dropdown step). Press Escape between groups and debug failures on a fresh page before blaming the stack.
- **Smoke checks:** `document.querySelectorAll(':not(:defined)').length === 0`; no console errors; every expected tag present.
- Clipboard tests need `context.grant_permissions(["clipboard-read","clipboard-write"])`.

---

## 9. Why not Reagent here (verified)

Scittle's Reagent uses React 18 (React 19 has no script-tag build). With custom elements, React 18:
- never delivers `on-change` or any `wa-*` event to your props (only `on-input` gets through);
- sets `:value`/`:checked` as attributes, so programmatic updates don't show;
- renders `false` as the string `"false"`, so boolean attributes stay "on";
- turns `:with-caret` into `withcaret` (Reagent camel-cases, React lowercases).

All four are fixable with a ~40-line adapter (native listeners via refs, properties set in did-mount/did-update, booleans dropped when false), but Replicant needs none of it. Use Replicant.

---

## 10. Checklist for a new page

1. Start from the §2 skeleton: pinned jsdelivr URLs for Web Awesome (`dist-cdn`, with matching `data-webawesome`), Scittle and its Replicant plugin.
2. `registerIconLibrary("default", …)` pointing at Font Awesome Free SVGs (jsdelivr or local); a trimmed local set needs `plus`/`minus` for `wa-number-input`.
3. Load `scittle.js` before `scittle.replicant.js`; app code in `application/x-scittle`.
4. One atom → `add-watch` → `d/render`.
5. View helpers are plain function calls, never `[fn args]`.
6. `:on` for every event; read `detail`/`target` per §6.
7. Bind `:value` for form controls, except `wa-otp-input`.
8. Guard `wa-hide` with `target == currentTarget` for dialogs/drawers.
9. Local images; no Pro components (e.g. `wa-combobox`).
10. Verify in a browser: no undefined elements, no console errors, and click through each interactive control.

---

## 11. Growing it into an app (patterns verified in this repo)

The `app/` version of the kitchen sink shows the minimum server-side setup; all of the below passed `test/server_test.py`.

- **Load the UI from a file:** `<script type="application/x-scittle" src="cljs/kitchen_sink.cljs"></script>`. Scittle fetches and evaluates it; keep the HTML as a thin shell (CDN links, styles, `#app`).
- **Request/response with EDN:** `js/fetch` with `(pr-str body)` and `Content-Type: application/edn`; parse the reply with `clojure.edn/read-string` (available in Scittle). On the bb side, `edn/read-string` the body and reply with `pr-str`. Validate on the server (the demo returns 400 for a blank name).
- **Server push:** one `js/WebSocket` to `/ws`, EDN messages dispatched on `:op`. http-kit (built into babashka) handles websockets with `http/as-channel`.
- **Hot reload without losing state:** the server watches `public/cljs/**.cljs` and pushes `{:op :reload :file ...}`; the client fetches the file and calls `(js/scittle.core.eval_string src)`. This works because:
  - state lives in `defonce` atoms, so re-evaluation keeps it;
  - the socket is held in a `defonce` and only opened when nil, so reloads don't open duplicates (verified: still 1 client after two reloads);
  - `add-watch` uses a fixed key, so re-adding it replaces the old watcher;
  - the file ends with `(render!)`, so new view code shows immediately.
- **New state keys after a reload:** `defonce` won't re-run its initial map, so merge defaults explicitly, e.g. `(swap! state update :srv #(merge {...defaults} %))`.
- **Static hosting of a separate `.cljs` file:** the same shell with `window.KS_MODE = "static"` set before the Scittle script loads the UI from `cljs/kitchen_sink.cljs` on any static host (verified locally and on GitHub Pages); the cljs checks the mode and skips the API and websocket.
- **One source, two deliveries:** a bb script (`scripts/build_single.clj`) inlines the cljs and embeds assets (data: URLs; `srcdoc` for iframe pages), and a `window.KS_MODE = "single"` flag switches off server features. Edit `app/`, regenerate the single file.
