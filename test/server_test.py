"""Server-mode checks for the app version (bb dev) plus a single-file sanity check.

Usage (servers running: `bb dev` on 8090, `bb serve-single` on 8091, and any static
server on app/public for static.html: `bb serve-static`):
    python3 test/server_test.py [app_url] [single_url] [static_url]
Optional env: KS_PROXY=http://host:port  (route CDN traffic through a proxy)
Requires: pip install playwright && playwright install chromium (or set KS_CHROME to a Chrome binary)
"""
import asyncio, os, pathlib, re, sys
from playwright.async_api import async_playwright

APP = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/"
SINGLE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8091/"
STATIC = sys.argv[3] if len(sys.argv) > 3 else "http://localhost:8092/static.html"
CLJS = pathlib.Path(__file__).resolve().parent.parent / "app/public/cljs/kitchen_sink.cljs"
results = []

def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))

async def main():
    async with async_playwright() as p:
        launch = {"args": ["--no-sandbox"]}
        if os.environ.get("KS_CHROME"): launch["executable_path"] = os.environ["KS_CHROME"]
        if os.environ.get("KS_PROXY"): launch["proxy"] = {"server": os.environ["KS_PROXY"], "bypass": "localhost,127.0.0.1"}
        b = await p.chromium.launch(**launch)
        ctx = await b.new_context(viewport={"width": 1440, "height": 1000})
        errs = []
        pg = await ctx.new_page()
        pg.on("pageerror", lambda e: errs.append(str(e)[:200]))
        pg.on("console", lambda m: m.type in ("error", "warning") and errs.append(m.text[:200]))
        await pg.goto(APP); await pg.wait_for_function("window.__ready===true", timeout=60000)
        ev = pg.evaluate
        txt = lambda sel: ev(f"document.querySelector('{sel}')?.textContent || ''")

        await pg.wait_for_function("document.querySelector('#ws-status')?.textContent.includes('open')", timeout=10000)
        check("websocket connects", True)
        await pg.wait_for_function("!document.querySelector('#server-time').textContent.includes('—')", timeout=8000)
        check("server pushes :tick", True, await txt("#server-time"))

        # POST via the real form
        loc = pg.locator("#f-name input").first; await loc.click(); await pg.keyboard.press("Control+a")
        await pg.keyboard.type("Hot Reload Keeper")
        await pg.locator("#submit-btn").click()
        await pg.wait_for_selector("#saved-profile", timeout=5000)
        saved = await txt("#saved-profile")
        check("form POSTs EDN to /api/profile", "Hot Reload Keeper" in saved, saved)
        await pg.locator("#load-profiles").click()
        await pg.wait_for_selector("#profiles-table", timeout=5000)
        rows = await ev("document.querySelectorAll('#profiles-table tr').length - 1")
        check("GET /api/profiles renders table", rows >= 1, f"{rows} rows")
        r = await pg.request.post(APP.rstrip("/") + "/api/profile", data="{:name \"\"}", headers={"Content-Type": "application/edn"})
        check("API validates (blank name -> 400)", r.status == 400, f"HTTP {r.status} {await r.text()}")

        # second client is counted by the server
        pg2 = await ctx.new_page(); await pg2.goto(APP); await pg2.wait_for_function("window.__ready===true", timeout=60000)
        await pg.wait_for_function("document.body.innerText.includes('clients 2')", timeout=8000)
        check("server counts 2 clients", True)
        await pg2.close()

        # hot reload: edit the cljs file on disk, page re-evaluates it, state survives
        orig = CLJS.read_text()
        try:
            CLJS.write_text(orig.replace('(def ui-version "1")', '(def ui-version "2")', 1))
            await pg.wait_for_function("document.querySelector('#server-tile h3').textContent.includes('ui-version 2')", timeout=10000)
            st = await ev("window.__state()")
            check("hot reload applies new code", True, await txt("#server-tile h3"))
            check("hot reload keeps state", st["name"] == "Hot Reload Keeper" and st["srv"]["reloads"] == 1,
                  f"name={st['name']!r} reloads={st['srv']['reloads']}")
            check("hot reload keeps one socket", "websocket open" in await txt("#ws-status"))
        finally:
            CLJS.write_text(orig)
        await pg.wait_for_function("document.querySelector('#server-tile h3').textContent.includes('ui-version 1')", timeout=10000)
        check("revert reloads again", (await ev("window.__state()"))["srv"]["reloads"] == 2)
        await pg.wait_for_timeout(6000)  # one more tick: duplicate sockets would show as extra clients
        check("still exactly 1 client after reloads", "clients 1" in await ev("document.body.innerText"),
              re.search(r"clients \d+", await ev("document.body.innerText")).group(0))
        check("app console clean", not errs, "; ".join(errs[:3]))

        # single-file: no server traffic, explanatory tile
        sp = await ctx.new_page(); ws_tried = []
        sp.on("websocket", lambda w: ws_tried.append(w.url))
        await sp.goto(SINGLE); await sp.wait_for_function("window.__ready===true", timeout=60000); await sp.wait_for_timeout(1500)
        tile = await sp.evaluate("document.querySelector('[data-tags=server]').textContent")
        check("single-file: no websocket attempted", not ws_tried, str(ws_tried))
        check("single-file: server tile explains mode", "Single-file mode" in tile)

        # static.html: same cljs file fetched separately from a plain static server
        st = await ctx.new_page(); st_ws = []; cljs_reqs = []
        st.on("websocket", lambda w: st_ws.append(w.url))
        st.on("request", lambda r: r.url.endswith("cljs/kitchen_sink.cljs") and cljs_reqs.append(r.url))
        await st.goto(STATIC); await st.wait_for_function("window.__ready===true", timeout=60000); await st.wait_for_timeout(1500)
        tile = await st.evaluate("document.querySelector('[data-tags=server]').textContent")
        check("static: cljs loaded as a separate file", len(cljs_reqs) == 1, str(cljs_reqs))
        check("static: no websocket attempted", not st_ws, str(st_ws))
        check("static: server tile explains mode", "Static mode" in tile)
        await b.close()

    for name, ok, d in results:
        print(("PASS " if ok else "FAIL ") + name + (f"  [{d}]" if d else ""))
    print(f"\n{sum(ok for _, ok, _ in results)}/{len(results)} server checks passed")

asyncio.run(main())
