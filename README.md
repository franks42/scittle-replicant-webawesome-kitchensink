# Scittle × Replicant × Web Awesome — GUI kitchen sink

**Live demos:** [single file](https://franks42.github.io/scittle-replicant-webawesome-kitchensink/single-file/) · [HTML + separate .cljs](https://franks42.github.io/scittle-replicant-webawesome-kitchensink/app/public/static.html)  
**Guide:** [docs/GUIDE.md](docs/GUIDE.md) (also on the site: [GUIDE.html](https://franks42.github.io/scittle-replicant-webawesome-kitchensink/docs/GUIDE.html))
**For AI coding assistants:** [skills/scittle-replicant-webawesome/SKILL.md](skills/scittle-replicant-webawesome/SKILL.md), a short, directive skill (template, rules, event table, traps, verification); `docs/GUIDE.md` is the long version with the reasoning and test evidence.

Browser UIs in ClojureScript **without React, a bundler, or a build step**:

- **[Scittle](https://github.com/babashka/scittle)** evaluates ClojureScript in the browser.
- **[Replicant](https://github.com/cjohansen/replicant)** renders hiccup to the DOM (loaded as a Scittle plugin).
- **[Web Awesome](https://webawesome.com)** provides the components: all 78 free ones (3.15.0, MIT) are exercised here.

![Kitchen sink, app version](docs/screenshot.png)

## What this is (and isn't)

This is a **kitchen-sink demo**: one page that puts every free Web Awesome component on screen, wires each interactive one to a single ClojureScript atom, and logs every event it handles. Its job is to show, by running code, that the stack works and *how* each component is driven: which attribute to set, which event to listen for, where the value lives in the event.

It is **not a library and not a starter app you should ship as-is.** Treat it as a template you adapt once you understand it:

1. Run it, click around, and watch the **Event log** and **State** panels on the right (see below). Every interaction is a Replicant `:on` handler doing a `swap!` on one atom, and the two panels show you both halves of that.
2. Read `app/public/cljs/kitchen_sink.cljs` with the page open (see *What to look for* below).
3. Copy the parts you need into your own project and delete the rest (see *Using it as a template*).

## Watch the Event log and State panels

The two panels on the right are the best way to learn how the page works. Click, type or drag anything and watch both:

- **Event log** (newest first, last 200 events, scrollable): which handler fired and with what value, e.g. `wa-button-group · select → right` or `wa-dialog · wa-hide`. That tells you which DOM event a component emits and what you read from it.
- **State** (one row per key, sorted, scrollable): the whole app state, with the keys the last `swap!` changed **highlighted for two seconds**; if a changed key is scrolled out of view, the panel scrolls to it. That tells you exactly what your handler changed.

Both panels sit in a column that stays fixed beside the page and is exactly one screen high, so you can scroll to any component and still see the log and the state while you use it.

So every interaction shows the full loop: **event → handler → `swap!` → changed keys → re-render**. In the screenshot above, clicking the right-align button logged `select → right` and highlighted `:align "right"`.

Things you'll notice while the page sits idle are real state changes, not noise:

- In app mode `:srv` lights up every 5 s: that's the server pushing a `:tick` over the websocket.
- `:size` changes once right after load, when the resize-observer demo first measures its box.

Both panels are ordinary code you can keep in your own app while developing: `log!`, `state-panel` and `note-change!` in `kitchen_sink.cljs`.

## Architecture

```
 Browser                                                     Server (app mode only)
 ───────────────────────────────────────────────────         ───────────────────────────────
 HTML shell (index.html / static.html)                       app/server.clj  (babashka + http-kit)
   ├─ <link> Web Awesome CSS ─┐                                ├─ GET  /…            static files
   ├─ <script> WA autoloader ─┤ pinned jsdelivr URLs           ├─ POST /api/profile  EDN in/out
   ├─ <script> Scittle ───────┤ (no npm, no bundler)           ├─ GET  /api/profiles EDN out
   ├─ <script> Replicant plugin                                ├─ /ws  websocket: :tick, :reload
   └─ <script type="application/x-scittle"                     └─ watcher: .cljs changed → :reload
             src="cljs/kitchen_sink.cljs">
                 │
                 ▼  Scittle interprets the ClojureScript
   (defonce state (atom {...}))  ◄────────────────────────┐
                 │ add-watch → render!                    │ swap!
                 ▼                                        │
   (view @state) → hiccup → replicant.dom/render          │
                 │                                        │
                 ▼                                        │
   <wa-input> <wa-select> <wa-dialog> …  ── events ──► :on handlers
   (Web Awesome custom elements; Lit renders their
    insides in shadow DOM; your code never touches Lit)
```

- **One atom, one render loop.** All UI state is in `state`; `add-watch` re-renders the whole view on every change (and records which keys changed, for the State panel). Replicant diffs and updates only what changed in the DOM.
- **Views are plain functions returning hiccup.** No component framework, no lifecycle; `(view s)` is pure data in, data out.
- **Web Awesome components are just HTML elements.** Replicant sets their attributes/properties and attaches native listeners, so standard events (`input`, `change`) and custom ones (`wa-hide`, `wa-tab-show`, …) work the same way.
- **The server is optional.** The whole left column of the diagram runs on any static host; the bb server on the right adds an API, push messages and hot reload.

## The three modes

The same `kitchen_sink.cljs` runs in three ways. The HTML page picks the mode by setting `window.KS_MODE` before the Scittle script runs; the cljs reads it into `mode`, `single?` and `server?`:

| | **single** | **static** | **app** (default) |
|---|---|---|---|
| Page | `single-file/index.html` | `app/public/static.html` | `app/public/index.html` |
| How the cljs arrives | inlined in the HTML | fetched: `<script src="cljs/kitchen_sink.cljs">` | fetched, same file |
| Assets (images, include, page-demo) | embedded as `data:` URLs / `srcdoc` | relative files next to the page | relative files, served by bb |
| Needs | any static host | any static host | `bb dev` |
| Server features | none | none | EDN API, websocket push, hot reload |
| Use it to | hand someone one working file | keep the UI in a real `.cljs` file, host anywhere (e.g. GitHub Pages) | grow into a real web app |
| Live | [GitHub Pages](https://franks42.github.io/scittle-replicant-webawesome-kitchensink/single-file/) | [GitHub Pages](https://franks42.github.io/scittle-replicant-webawesome-kitchensink/app/public/static.html) | your machine / server |

`app/` is the source of truth. `single-file/index.html` is **generated** from it by `bb build-single` (inlines the cljs, embeds the assets); never edit it by hand. GitHub Pages serves this repo from `main`, so after changing `app/`, run `bb build-single` and commit the regenerated file to update the live demo.

## What to look for

In `app/public/cljs/kitchen_sink.cljs`, top to bottom:

| Where | What it shows |
|---|---|
| `mode`, `single?`, `server?`, `asset` | How one file adapts to three deliveries (mode flag + asset indirection). |
| `;; State + helpers`: `state`, `log!`, `on!`, `tile` | The single atom, the event log, a handler helper (`(on! :name tv)` = "store this field's value"), and a layout helper. |
| `;; Server integration`: `api!`, `post-profile!`, `connect!`, `reload-cljs!`, `server-tile` | Request/response with EDN over `fetch`, server push over one websocket, and hot reload via `scittle.core.eval_string`. **This is the part to grow into a real app.** |
| `;; Sections`: `actions`, `forms`, `feedback`, `layout`, `navigation`, `media`, `helpers` | One function per category, one tile per component. Copy the tile for the component you need: it shows the attributes to set and the event to handle. |
| `state-panel`, `note-change!` | The State panel: sorted keys, and the watcher that diffs old vs. new state to highlight what the last `swap!` changed. |
| `view`, `render!`, `add-watch` (bottom) | The whole render loop: a few lines. |

Patterns worth noticing as you read:

- `[:wa-select {:value … :on {:change …}} …]`: attributes and handlers live in one map; there is no adapter layer.
- `(tile …)` is a **function call**, not `[tile …]`: Replicant renders data, it has no component syntax (that's Reagent).
- `wa-otp-input` is deliberately **uncontrolled**; every other form control binds `:value` to state (see the comment in `forms`).
- Methods on components are called on the DOM node: `(.next (el "stepper"))`, `(.create (el "toast") …)`.
- `defonce` for `state` and the websocket is what makes hot reload keep its state.

[docs/GUIDE.md](docs/GUIDE.md) has the event cheat sheet for every component and the gotchas we hit while testing.

## Using it as a template

1. **Pick a delivery mode.** Static (`static.html`) if you only need a UI; app (`index.html` + `server.clj`) if you need an API or push; single file if you need one artifact to hand around.
2. **Keep the skeleton:** the HTML shell (pinned CDN links, icon registration), the mode/asset lines if you keep several modes, `state`, `log!`/`on!` if you like them, the Event log and State panels while you develop (`log!`, `state-panel`, `note-change!`), and the render loop at the bottom. In app mode also keep `api!`, `connect!`, `reload-cljs!` and `server.clj`.
3. **Delete the demo:** the seven section functions, `categories`, `tree-data`, `images`, `markdown-src`, the demo images, `include.html` and `page-demo.html`, and the profile routes in `server.clj`.
4. **Add your views** as plain functions over `state`, copying the tiles of the components you use from the kitchen sink.
5. **Add server routes** to `handler` in `server.clj` and call them with `api!`.
6. **Keep the versions pinned** (Web Awesome, Scittle, Font Awesome in the HTML) and bump them deliberately, together; re-run the tests when you do.

## Quick start

```bash
# App version: API + websocket + hot reload
bb dev                 # http://localhost:8090

# Single-file version
bb build-single        # regenerate single-file/index.html from app/
bb serve-single        # http://localhost:8091

# HTML + separate .cljs, no server features
bb serve-static        # http://localhost:8092/static.html
```

Requires only [babashka](https://babashka.org) for all three modes; every server here is a bb script. The browser loads Web Awesome, Scittle and Font Awesome icons from pinned jsdelivr URLs; no npm install. A page must be served over HTTP (`file://` won't load the ES modules).

## The app server in detail

`app/public/index.html` is a thin shell (CDN links, styles, `<div id="app">`) that loads the UI with
`<script type="application/x-scittle" src="cljs/kitchen_sink.cljs">`. `app/server.clj` (http-kit, built into babashka) adds:

- **`POST /api/profile`, `GET /api/profiles`**: the kitchen sink's profile form posts EDN; the server validates (blank name → 400), stores in `app/data/profiles.edn`, replies with EDN.
- **`/ws` websocket**: the server pushes `{:op :tick}` every 5 s (time and client count) to every browser.
- **Hot reload**: edit and save `app/public/cljs/kitchen_sink.cljs` → the server pushes `{:op :reload}` → the page re-evaluates the file with `scittle.core.eval_string`. State survives because it lives in `defonce` atoms. Try changing `ui-version`.

That's the skeleton of a real app: add routes to `handler` in `server.clj`, add views and `api!` calls in the cljs file.

## Layout

```
app/
  server.clj                  bb server: static files, EDN API, websocket, file watcher
  public/index.html           HTML shell (pinned CDN links, styles) - app mode
  public/static.html          same shell with KS_MODE "static" - no server features
  public/cljs/kitchen_sink.cljs   the whole UI: state atom, views, server integration
  public/img/, include.html, page-demo.html   demo assets
scripts/
  build_single.clj            app/ -> single-file/index.html (inline cljs, embed assets)
  serve_static.clj            tiny static file server (bb + http-kit) used by serve-single and serve-static
single-file/index.html        GENERATED - don't edit by hand
docs/GUIDE.md                 how to work with this stack (written for AI assistants; useful for humans)
skills/scittle-replicant-webawesome/SKILL.md   concise Agent Skill for coding assistants
test/
  kitchen_sink_test.py        66 interaction checks (Playwright), for any of the three versions
  server_test.py              17 checks: API, websocket, client count, hot reload, single-file and static modes
bb.edn                        tasks: dev, serve-static, build-single, serve-single
```

## Tests

```bash
bb dev & bb serve-single & bb serve-static &                         # servers: all bb
pip install playwright && playwright install chromium                 # tests: Python + Playwright
python3 test/kitchen_sink_test.py http://localhost:8090/index.html   # 66/66
python3 test/kitchen_sink_test.py http://localhost:8091/index.html   # 66/66
python3 test/kitchen_sink_test.py http://localhost:8092/static.html  # 66/66
python3 test/server_test.py                                          # 17/17
```

The servers are all bb; the browser tests are the one part that needs Python (Playwright). Optional env: `KS_CHROME=/path/to/chrome`, `KS_PROXY=http://host:port`.

## Things worth knowing

Full details and an event cheat sheet are in [docs/GUIDE.md](docs/GUIDE.md). The short version:

- Use Web Awesome's **`dist-cdn`** build; `dist` needs a bundler.
- In Replicant, **`[my-fn args]` is not a component call** (that's Reagent); write `(my-fn args)`.
- Bind `:value` to state for form controls, **except `wa-otp-input`**: keep it uncontrolled or typed digits are lost.
- Replicant attaches `:on` handlers natively, so `wa-*` custom events just work; no React adapter needed.
- Nine components are marked experimental in Web Awesome 3.15.0 (carousel, code highlighter, counter, lightbox, markdown, number ticker, tag input, and the carousel and lightbox items).

## Credits and licenses

This template is glue; the heavy lifting is done by the projects below. Licenses were checked against the **exact versions this repo pins**, using the LICENSE files and package metadata those versions ship with (November 2026). The browser bundles were also inspected (source maps and embedded license blocks) to see what third-party code they contain.

### What the browser loads (from jsDelivr, unmodified)

| Project | Version | Role here | License | Copyright |
|---|---|---|---|---|
| [Scittle](https://github.com/babashka/scittle) | 0.8.33 | Runs ClojureScript in the browser (`scittle.js`) | EPL-1.0 | Michiel Borkent |
| [Replicant](https://github.com/cjohansen/replicant) | 2026.06.2 (bundled in `scittle.replicant.js`) | Renders hiccup, attaches `:on` handlers | MIT | Christian Johansen |
| [Web Awesome](https://webawesome.com) (free) | 3.15.0, `dist-cdn` | The 78 components | MIT | Fonticons, Inc. (Web Awesome is the successor of Shoelace, created by Cory LaViska) |
| [Font Awesome Free](https://fontawesome.com) | 7.3.1, SVG icons | Icons for `wa-icon` | CC BY 4.0 (icons) | Fonticons, Inc. |

**Bundled inside those files** (verified from source maps and embedded license blocks):

- `scittle.js`: [SCI](https://github.com/babashka/sci) (EPL-1.0), [edamame](https://github.com/borkdude/edamame) (EPL-1.0), [ClojureScript](https://github.com/clojure/clojurescript) core (EPL-1.0), [Google Closure Library](https://github.com/google/closure-library) (Apache-2.0), [shadow-cljs](https://github.com/thheller/shadow-cljs) runtime (EPL-1.0), and small shared parts from Scittle's optional plugins: [reagent](https://github.com/reagent-project/reagent) (MIT), [re-frame](https://github.com/day8/re-frame) (MIT), [cljs-ajax](https://github.com/JulianBirch/cljs-ajax) (EPL), [transit-cljs](https://github.com/cognitect/transit-cljs) (Apache-2.0).
- Web Awesome `dist-cdn`: [Lit](https://lit.dev) (BSD-3-Clause), [Floating UI](https://floating-ui.com) (MIT), [marked](https://github.com/markedjs/marked) (MIT), [nanoid](https://github.com/ai/nanoid) (MIT), [@ctrl/tinycolor](https://github.com/scttcper/tinycolor) (MIT), @konnorr/qr-creator (MIT), microlighter (MIT), @shoelace-style/localize (MIT), composed-offset-position (MIT), and @shoelace-style/animations (MIT).
  - The `wa-animation` presets in @shoelace-style/animations are generated from **animate.css 4.1.1 as published on npm, which is MIT**. Note that the animate.css repository has since moved to the Hippocratic License 2.1; that applies to later versions, not the npm 4.x release used here.
  - Web Awesome's built-in icons (chevrons, close, check, stars) are Font Awesome Free 7.0.0 SVGs embedded in its CSS (CC BY 4.0, with attribution comments).
  - `dist-cdn` also contains React wrappers (React, MIT) and test utilities (axe-core, MPL-2.0; chai, MIT). **This template never loads them**: a full run of the kitchen sink fetched 371 Web Awesome files and none of those.

### What runs on your machine (not in the browser)

| Project | Role here | License | Copyright |
|---|---|---|---|
| [babashka](https://babashka.org) | App server, `bb` tasks, single-file build | EPL-1.0 | Michiel Borkent |
| [http-kit](https://github.com/http-kit/http-kit) (bundled in babashka) | HTTP + websocket server | Apache-2.0 | http-kit contributors |
| [Playwright](https://playwright.dev) (tests only) | Browser automation for `test/` | Apache-2.0 | Microsoft |

[jsDelivr](https://www.jsdelivr.com) serves the browser files; it is a free CDN service with its own terms of use, not a code license.

### Attribution

None of these libraries is vendored into this repo; the pages load the original, unmodified files from jsDelivr, and those files carry their own notices: every Web Awesome file has a Fonticons copyright header plus "Bundled license information" blocks for the code it includes, Font Awesome's SVGs carry attribution comments, and `scittle.js` keeps the Closure Library notice. If you self-host these files, keep them unmodified (or keep their license comments) and ship the corresponding LICENSE files. Font Awesome's CC BY 4.0 requires attribution for the icons; the credit above plus the comments in the files are how this repo provides it.

This is a good-faith summary, not legal advice; check the linked licenses for your own use.

## License

This project's own files (the HTML shells, `kitchen_sink.cljs`, `server.clj`, scripts, tests, docs, and the images in `app/public/img/`, which were generated for this demo) are licensed under [EPL-2.0](LICENSE).
