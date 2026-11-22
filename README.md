# Scittle × Replicant × Web Awesome — GUI kitchen sink

**Live demo (single-file version):** https://franks42.github.io/scittle-replicant-webawesome-kitchensink/single-file/  
**Guide:** [docs/GUIDE.md](docs/GUIDE.md) (also on the site: [GUIDE.html](https://franks42.github.io/scittle-replicant-webawesome-kitchensink/docs/GUIDE.html))

A template for building browser UIs in ClojureScript **without React, a bundler, or a build step**:

- **[Scittle](https://github.com/babashka/scittle)** evaluates ClojureScript in the browser.
- **[Replicant](https://github.com/cjohansen/replicant)** renders hiccup to the DOM (loaded as a Scittle plugin).
- **[Web Awesome](https://webawesome.com)** provides the components: all 78 free ones (3.15.0, MIT) are exercised here.

![Kitchen sink, app version](docs/screenshot.png)

The same UI ships two ways:

| | `single-file/index.html` | `app/` |
|---|---|---|
| What | One self-contained HTML file (~290 KB) | HTML shell + `kitchen_sink.cljs` served by babashka |
| Needs | Any static server | `bb dev` (babashka) |
| Server features | none | EDN API, websocket push, hot reload |
| Use it to | Hand someone a working page; prototype | Start a real web app |
| Hosted | GitHub Pages, from `main` (this repo) | your own server |

`app/` is the source of truth; `single-file/index.html` is **generated** from it (`bb build-single`). GitHub Pages serves this repo from `main`, so after changing `app/`, run `bb build-single` and commit the regenerated file to update the live demo.

## Quick start

```bash
# App version: API + websocket + hot reload
bb dev                 # http://localhost:8090

# Single-file version
bb build-single        # regenerate single-file/index.html from app/
bb serve-single        # http://localhost:8091   (or: cd single-file && python3 -m http.server)
```

Requires [babashka](https://babashka.org). The browser loads Web Awesome, Scittle and Font Awesome icons from pinned jsdelivr URLs; no npm install. A page must be served over HTTP (`file://` won't load the ES modules).

## How the app version extends the page

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
  public/index.html           HTML shell (pinned CDN links, styles)
  public/cljs/kitchen_sink.cljs   the whole UI: state atom, views, server integration
  public/img/, include.html, page-demo.html   demo assets
scripts/
  build_single.clj            app/ -> single-file/index.html (inline cljs, embed assets)
  serve_static.clj            tiny static server for single-file/
single-file/index.html        GENERATED - don't edit by hand
docs/GUIDE.md                 how to work with this stack (written for AI assistants; useful for humans)
test/
  kitchen_sink_test.py        60 interaction checks (Playwright), for either version
  server_test.py              14 checks: API, websocket, client count, hot reload, single-file mode
bb.edn                        tasks: dev, build-single, serve-single
```

## Tests

```bash
pip install playwright && playwright install chromium
bb dev & bb serve-single &
python3 test/kitchen_sink_test.py http://localhost:8090/index.html   # 60/60
python3 test/kitchen_sink_test.py http://localhost:8091/index.html   # 60/60
python3 test/server_test.py                                          # 14/14
```

Optional env: `KS_CHROME=/path/to/chrome`, `KS_PROXY=http://host:port`.

## Things worth knowing

Full details and an event cheat sheet are in [docs/GUIDE.md](docs/GUIDE.md). The short version:

- Use Web Awesome's **`dist-cdn`** build; `dist` needs a bundler.
- In Replicant, **`[my-fn args]` is not a component call** (that's Reagent); write `(my-fn args)`.
- Bind `:value` to state for form controls, **except `wa-otp-input`**: keep it uncontrolled or typed digits are lost.
- Replicant attaches `:on` handlers natively, so `wa-*` custom events just work; no React adapter needed.
- Nine components are marked experimental in Web Awesome 3.15.0 (carousel, code highlighter, counter, lightbox, markdown, number ticker, tag input, and the carousel and lightbox items).

## Credits

Components: Web Awesome (MIT). Icons: Font Awesome Free. Images in `app/public/img/` were generated for this demo.

## License

This project: [EPL-2.0](LICENSE). Web Awesome is MIT; Font Awesome Free icons are CC BY 4.0 (icons) / MIT (code); both are loaded from jsdelivr, not vendored.
