"""Server-mode checks for the app version (bb dev) plus a single-file sanity check.

Usage (servers running: `bb dev` on 8090, `bb serve-single` on 8091, and any static
server on app/public for static.html: `bb serve-static`):
    python3 test/server_test.py [app_url] [single_url] [static_url]
Optional env: KS_PROXY=http://host:port  (route CDN traffic through a proxy)
Requires: pip install playwright && playwright install chromium (or set KS_CHROME to a Chrome binary)
"""
import asyncio, os, pathlib, re, socket, sys
from playwright.async_api import async_playwright

APP = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/"
SINGLE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8091/"
STATIC = sys.argv[3] if len(sys.argv) > 3 else "http://localhost:8092/static.html"
CLJS = pathlib.Path(__file__).resolve().parent.parent / "app/public/cljs/kitchen_sink.cljs"
results = []

def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))

# ---- a minimal nREPL client (bencode over TCP), enough to eval one form
def benc(x):
    if isinstance(x, int): return b"i%de" % x
    if isinstance(x, str): x = x.encode()
    if isinstance(x, bytes): return b"%d:%s" % (len(x), x)
    if isinstance(x, list): return b"l" + b"".join(map(benc, x)) + b"e"
    if isinstance(x, dict): return b"d" + b"".join(benc(k) + benc(v) for k, v in sorted(x.items())) + b"e"

def bdec(buf, i=0):
    c = buf[i:i+1]
    if c == b"i": j = buf.index(b"e", i); return int(buf[i+1:j]), j + 1
    if c in (b"l", b"d"):
        i += 1; items = []
        while buf[i:i+1] != b"e": v, i = bdec(buf, i); items.append(v)
        return (items if c == b"l" else dict(zip(items[::2], items[1::2]))), i + 1
    j = buf.index(b":", i); n = int(buf[i:j]); return buf[j+1:j+1+n].decode(), j + 1 + n

def raw_status(url, path, headers, method="GET", websocket=False):
    """Status code of one hand-made HTTP request (to send any Host/Origin we like)."""
    from urllib.parse import urlparse
    u = urlparse(url)
    hdrs = dict(headers)
    if websocket:
        hdrs.update({"Upgrade": "websocket", "Connection": "Upgrade",
                     "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==", "Sec-WebSocket-Version": "13"})
    if method == "POST":
        hdrs.update({"Content-Type": "application/edn", "Content-Length": "13"})
    req = f"{method} {path} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in hdrs.items()) + "\r\n"
    with socket.create_connection((u.hostname, u.port), timeout=5) as sk:
        sk.sendall(req.encode() + (b'{:name "Eve"}' if method == "POST" else b""))
        return int(sk.recv(200).split(b" ")[1])

def port_open(port):
    try:
        socket.create_connection(("localhost", port), timeout=1).close(); return True
    except OSError:
        return False

def nrepl_eval(port, code, timeout=10):
    """Send one eval, collect responses until status contains done."""
    with socket.create_connection(("localhost", port), timeout=timeout) as sk:
        sk.sendall(benc({"op": "eval", "code": code, "id": "t1"}))
        buf, out = b"", []
        while True:
            chunk = sk.recv(65536)
            if not chunk: break
            buf += chunk
            try:
                while buf:
                    msg, n = bdec(buf); buf = buf[n:]; out.append(msg)
                    if "done" in msg.get("status", []): return out
            except (ValueError, IndexError):
                pass   # incomplete message: read more
        return out

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
        # the 400 from the validation-off submit is expected; the browser logs it as a failed resource
        pg.on("console", lambda m: m.type in ("error", "warning") and "status of 400" not in m.text and errs.append(m.text[:200]))
        await pg.goto(APP); await pg.wait_for_function("window.__ready===true", timeout=60000)
        ev = pg.evaluate
        txt = lambda sel: ev(f"document.querySelector('{sel}')?.textContent || ''")

        await pg.wait_for_function("document.querySelector('#ws-status')?.textContent.includes('open')", timeout=10000)
        check("websocket connects", True)
        await pg.wait_for_function("!document.querySelector('#server-time').textContent.includes('—')", timeout=8000)
        check("server pushes :tick", True, await txt("#server-time"))

        # browser validation off: the form posts anyway and shows the server's 400
        await pg.locator("#clear-name").click()
        await pg.locator("#f-validate [part~=control]").click()
        await pg.locator("#submit-btn").click()
        await pg.wait_for_selector("#server-errors", timeout=5000)
        check("form shows the server's 400 (browser validation off)", "Name is required" in await txt("#server-errors"),
              await txt("#server-errors"))
        await pg.locator("#f-validate [part~=control]").click()
        check("validation switch back on", await ev("!document.getElementById('profile-form').noValidate"))
        check("bb dev: nREPL tile says it's off", await ev("!!document.getElementById('nrepl-off')"))

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
        check("a good save clears the server errors", not await ev("!!document.getElementById('server-errors')"))
        # the request guard: other sites' Origins and DNS-rebinding Hosts get 403
        hp = APP.split("//")[1].split("/")[0]                    # localhost:8090
        evil = {"Host": hp, "Origin": "https://evil.example"}
        check("guard: own-origin page request allowed", raw_status(APP, "/", {"Host": hp}) == 200)
        check("guard: websocket from another site refused", raw_status(APP, "/ws", evil, websocket=True) == 403)
        check("guard: POST from another site refused", raw_status(APP, "/api/profile", evil, method="POST") == 403)
        rebind_host = "evil.example:" + hp.split(":")[1]
        check("guard: DNS-rebinding Host refused", raw_status(APP, "/", {"Host": rebind_host}) == 403)

        r = await pg.request.post(APP.rstrip("/") + "/api/profile", data="{:name \"\" :qty 7}", headers={"Content-Type": "application/edn"})
        body = await r.text()
        check("API validates with the shared rules (400 + field messages)",
              r.status == 400 and "Name is required" in body and "Email is required for quantities over 5" in body, f"HTTP {r.status} {body}")

        # server-side rendering route: explained 501 under plain `bb dev`
        r = await pg.request.get(APP.rstrip("/") + "/ssr/profiles")
        check("SSR route explains how to enable it under bb dev", r.status == 501 and "bb dev-full" in await r.text(), f"HTTP {r.status}")
        ssr = os.environ.get("KS_SSR_URL")   # e.g. http://localhost:8093 (a `bb dev-full` server)
        if ssr:
            api = await pg.request.get(ssr + "/api/profiles"); n = (await api.text()).count(":id ")
            r = await pg.request.get(ssr + "/ssr/profiles"); html = await r.text()
            rows = html.count("<tr>") - 1
            check("SSR (bb dev-full) renders the shared profiles-table", r.status == 200 and 'id="profiles-table"' in html and rows == n,
                  f"HTTP {r.status}, {rows} rows vs {n} profiles")
            check("SSR expands aliases and drops replicant attributes", 'class="ks-stat"' in html and "replicant/" not in html and ":ks/" not in html)

            # nREPL into the running page: editor -> :1339 -> websocket :1340 -> Scittle
            rp = await ctx.new_page(); await rp.goto(ssr + "/"); await rp.wait_for_function("window.__ready===true", timeout=60000)
            await rp.wait_for_function("document.querySelector('#nrepl-status')?.textContent.includes('open')", timeout=15000)
            check("bb dev-full: page connects to the nREPL websocket", True, await rp.evaluate("document.querySelector('#nrepl-status').textContent"))
            shp = ssr.split("//")[1].split("/")[0]
            check("nREPL websocket refuses other sites, no-Origin clients and rebinding",
                  [raw_status(ssr, "/_nrepl", {"Host": shp, "Origin": "https://evil.example"}, websocket=True),
                   raw_status(ssr, "/_nrepl", {"Host": shp}, websocket=True),
                   raw_status(ssr, "/_nrepl", {"Host": "evil.example:" + shp.split(":")[1],
                                               "Origin": "http://evil.example:" + shp.split(":")[1]}, websocket=True)] == [403, 403, 403])
            check("no sci.nrepl websocket server on :1340", not port_open(1340))
            port = int(os.environ.get("KS_NREPL_PORT", "1339"))
            out = await asyncio.to_thread(nrepl_eval, port, '(swap! kitchen-sink/state assoc :name "From nREPL") (:name @kitchen-sink/state)')
            values = [m["value"] for m in out if "value" in m]
            check("nREPL eval returns a value from the page", values and values[-1] == '"From nREPL"', str(out)[:200])
            await rp.wait_for_function("window.__state().name === 'From nREPL'", timeout=5000)
            check("nREPL eval changed the page's state and UI",
                  await rp.evaluate("document.querySelector('#f-name').value") == "From nREPL")
            await rp.close()

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
