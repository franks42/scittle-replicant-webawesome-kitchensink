"""Interaction suite: 60 checks across the Web Awesome components, driven like a user would.

Usage (a server running, see README):
    python3 test/kitchen_sink_test.py http://localhost:8090/index.html     # app version
    python3 test/kitchen_sink_test.py http://localhost:8091/index.html     # single-file version
Optional env: KS_PROXY=http://host:port (route CDN traffic through a proxy), KS_CHROME=/path/to/chrome,
              KS_SCREENSHOT=after.png (save a full-page screenshot at the end)
Requires: pip install playwright && playwright install chromium
"""
import asyncio, json, sys, os
from playwright.async_api import async_playwright

URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/index.html"
results = []

async def main():
    async with async_playwright() as p:
        proxy = os.environ.get("KS_PROXY")
        b = await p.chromium.launch(args=["--no-sandbox"],
                                    **({"executable_path": os.environ["KS_CHROME"]} if os.environ.get("KS_CHROME") else {}),
                                    **({"proxy": {"server": proxy, "bypass": "localhost,127.0.0.1"}} if proxy else {}))
        ctx = await b.new_context(viewport={"width": 1440, "height": 1000})
        await ctx.grant_permissions(["clipboard-read", "clipboard-write"], origin=URL.rsplit("/",1)[0])
        pg = await ctx.new_page()
        errs = []
        icon_reqs = set()
        ctx.on("request", lambda r: "/svgs/solid/" in r.url and icon_reqs.add(r.url.split("/svgs/solid/")[1]))
        pg.on("pageerror", lambda e: errs.append("PAGEERR " + str(e)[:300]))
        pg.on("console", lambda m: m.type in ("error", "warning") and errs.append(m.type + ": " + m.text[:300]))
        await pg.goto(URL)
        await pg.wait_for_function("window.__ready===true", timeout=60000)
        await pg.wait_for_timeout(3000)

        S = lambda: pg.evaluate("window.__state()")
        log = lambda: pg.evaluate("document.getElementById('log').innerText")
        ev = pg.evaluate
        W = pg.wait_for_timeout

        async def step(name, fn, check):
            try:
                await fn()
                ok, detail = await check()
            except Exception as e:
                ok, detail = False, f"EXC {type(e).__name__}: {str(e).splitlines()[0][:160]}"
            results.append((name, ok, detail))

        async def click(sel): await pg.locator(sel).first.click(); await W(350)

        # ---------------------------------------------------------------- actions
        await step("wa-button click", lambda: click("wa-button[variant=brand]:has-text('Brand')"),
                   lambda: _r(log(), lambda l: ("wa-button · click → brand" in l, "log")))
        await step("wa-button-group select", lambda: click("#align-right"),
                   lambda: _r(S(), lambda s: (s["align"] == "right", s["align"])))
        await step("wa-copy-button copies", lambda: click("#copy-btn"),
                   lambda: _r(ev("navigator.clipboard.readText()"), lambda t: (t == "Ada Lovelace", t)))
        async def dd():
            await click("#menu-btn"); await click("wa-dropdown-item:has-text('Copy') >> nth=0")
        await step("wa-dropdown select item", dd, lambda: _r(S(), lambda s: (s["menu"] == "copy", s["menu"])))
        async def dd2():
            await click("#menu-btn"); await click("wa-dropdown-item:has-text('Bold')")
        await step("wa-dropdown checkbox item", dd2, lambda: _r(S(), lambda s: (s["bold?"] is False, s["bold?"])))
        async def dd3():
            await click("#menu-btn")
            await pg.locator("wa-dropdown-item:has-text('Export')").first.hover(); await W(600)
            await click("wa-dropdown-item[slot=submenu]:has-text('PDF')")
        await step("wa-dropdown submenu", dd3, lambda: _r(S(), lambda s: (s["menu"] == "pdf", s["menu"])))
        await pg.keyboard.press("Escape")

        # ---------------------------------------------------------------- forms
        async def typ(sel, text, clear=True):
            loc = pg.locator(sel).first; await loc.click()
            if clear: await pg.keyboard.press("Control+a")
            await pg.keyboard.type(text); await W(250)
        await step("wa-input typing", lambda: typ("#f-name input", "Grace Hopper"),
                   lambda: _r(S(), lambda s: (s["name"] == "Grace Hopper", s["name"])))
        await step("wa-qr-code follows input", lambda: W(300),
                   lambda: _r(ev("document.getElementById('qr').value"), lambda v: (v.endswith("Grace-Hopper"), v)))
        await step("wa-textarea typing + count", lambda: typ("#f-bio textarea", "Compiler pioneer"),
                   lambda: _r(ev("[window.__state().bio, document.getElementById('f-bio').shadowRoot.textContent.includes('104')]"),
                              lambda v: (v[0] == "Compiler pioneer" and v[1], v)))
        async def qty():
            await pg.locator("#f-qty input").first.click(); await pg.keyboard.press("ArrowUp"); await pg.keyboard.press("ArrowUp"); await W(250)
        await step("wa-number-input stepping", qty, lambda: _r(S(), lambda s: (str(s["qty"]) == "5", s["qty"])))
        await step("form submit (FormData)", lambda: click("#submit-btn"),
                   lambda: _r(ev("document.getElementById('form-result')?.innerText||''"),
                              lambda t: ('"name" "Grace Hopper"' in t and '"qty" "5"' in t, t[:160])))
        await step("programmatic clear shows in wa-input", lambda: click("#clear-name"),
                   lambda: _r(ev("document.querySelector('#f-name').shadowRoot.querySelector('input').value"),
                              lambda v: (v == "", repr(v))))
        async def sel():
            await click("#f-coffee"); await click("#f-coffee wa-option[value=espresso]")
        await step("wa-select single", sel, lambda: _r(S(), lambda s: (s["coffee"] == "espresso", s["coffee"])))
        async def msel():
            await click("#f-extras"); await click("#f-extras wa-option[value=shot]"); await pg.keyboard.press("Escape"); await W(300)
        await step("wa-select multiple", msel, lambda: _r(S(), lambda s: (sorted(s["extras"]) == ["oat", "shot"], s["extras"])))
        await step("wa-checkbox", lambda: click("#f-agree"), lambda: _r(S(), lambda s: (s["agree?"] is True, s["agree?"])))
        await step("wa-checkbox-group member", lambda: click("#int-design"),
                   lambda: _r(log(), lambda l: ("design=true" in l, "log")))
        await step("wa-switch", lambda: click("#f-notify"), lambda: _r(S(), lambda s: (s["notify?"] is False, s["notify?"])))
        await step("wa-radio-group", lambda: click("#f-roast wa-radio[value=light]"),
                   lambda: _r(S(), lambda s: (s["roast"] == "light", s["roast"])))
        await step("wa-radio button appearance", lambda: click("#f-plan wa-radio[value=team]"),
                   lambda: _r(S(), lambda s: (s["plan"] == "team", s["plan"])))
        async def slide():
            await pg.locator("#f-volume").scroll_into_view_if_needed()
            bb = await pg.locator("#f-volume [part~=track]").first.bounding_box()
            await pg.mouse.click(bb["x"] + bb["width"] * 0.7, bb["y"] + bb["height"] / 2); await W(300)
        await step("wa-slider", slide, lambda: _r(S(), lambda s: (int(s["volume"]) == 70, s["volume"])))
        await step("wa-progress-bar/ring follow slider", lambda: W(100),
                   lambda: _r(ev("[document.getElementById('progress').value, document.querySelector('wa-progress-ring').value]"),
                              lambda v: (int(v[0]) == 70 and int(v[1]) == 70, v)))
        async def rng():
            await pg.locator("#f-range").scroll_into_view_if_needed()
            bb = await pg.locator("#f-range [part~=track]").first.bounding_box()
            await pg.mouse.click(bb["x"] + bb["width"] * 0.1, bb["y"] + bb["height"] / 2); await W(300)
        await step("wa-slider range", rng, lambda: _r(S(), lambda s: (s["range"] == [10, 80], s["range"])))
        async def rate():
            await pg.locator("#f-rating").focus(); await pg.keyboard.press("ArrowRight"); await W(250)
        await step("wa-rating", rate, lambda: _r(S(), lambda s: (int(s["rating"]) == 4, s["rating"])))
        async def color():
            await click("#f-color [part~=trigger]"); await W(400)
            sw = pg.locator("#f-color [part~=swatch]").nth(1); await sw.click(); await W(300)
            await pg.keyboard.press("Escape"); await W(300)
        await step("wa-color-picker swatch", color, lambda: _r(S(), lambda s: (s["color"].lower() == "#16a34a", s["color"])))
        await step("wa-otp-input complete", lambda: typ("#f-otp [part~=segment] >> nth=0", "123456", clear=False),
                   lambda: _r(log(), lambda l: ("wa-otp-input · wa-complete → 123456" in l, "log")))
        async def tags():
            await pg.locator("#f-tags input").first.click(); await pg.keyboard.type("clojure"); await pg.keyboard.press("Enter"); await W(300)
        await step("wa-tag-input add tag", tags,
                   lambda: _r(ev("JSON.stringify(document.getElementById('f-tags').value)"), lambda v: ("clojure" in v, v)))
        async def tm():
            await pg.locator("#f-time").focus(); await W(150)
            await pg.keyboard.type("1145"); await pg.keyboard.press("Tab"); await W(300)
        await step("wa-time-input typing", tm, lambda: _r(S(), lambda s: (s["time"].startswith("11:45"), s["time"])))
        async def kd():
            for part, txt in (("field-month", "03"), ("field-day", "14"), ("field-year", "2019")):
                await pg.locator(f"#f-issued [part~={part}] [part~=field-input]").first.click(); await pg.keyboard.type(txt); await W(120)
            await pg.keyboard.press("Tab"); await W(300)
        await step("wa-known-date typing", kd, lambda: _r(S(), lambda s: (s["issued"] == "2019-03-14", s["issued"])))

        # ---------------------------------------------------------------- feedback
        await step("wa-tag remove", lambda: click("wa-tag:has-text('Babashka') >> [part~=remove-button]"),
                   lambda: _r(S(), lambda s: (s["chips"] == ["Clojure", "Scittle", "Replicant"], s["chips"])))
        await step("wa-toast create", lambda: click("#toast-success"),
                   lambda: _r(ev("document.querySelectorAll('#toast wa-toast-item').length"), lambda n: (n >= 1, n)))
        async def tip():
            await pg.hover("#tip-target"); await W(700)
        await step("wa-tooltip show", tip, lambda: _r(log(), lambda l: ("wa-tooltip · wa-show" in l, "log")))

        # ---------------------------------------------------------------- layout
        await step("wa-details toggle", lambda: click("#details"),
                   lambda: _r(ev("document.getElementById('details').open"), lambda v: (v is True, v)))
        await step("wa-accordion expand", lambda: click("wa-accordion-item[label=Replicant]"),
                   lambda: _r(log(), lambda l: ("wa-accordion · wa-expand" in l, "log")))
        async def dlg():
            await click("#open-dialog"); await W(400)
            o = await ev("document.getElementById('dialog').open"); await pg.keyboard.press("Escape"); await W(600); return o
        async def dlg_check():
            s = await S(); o = await ev("document.getElementById('dialog').open")
            return (s["dialog?"] is False and o is False, f"state {s['dialog?']} el {o}")
        await step("wa-dialog open + Escape", dlg, dlg_check)
        async def drw():
            await click("#open-drawer"); await W(500); await click("#close-drawer"); await W(600)
        await step("wa-drawer open + close", drw,
                   lambda: _r(ev("[window.__state()['drawer?'], document.getElementById('drawer').open]"),
                              lambda v: (v == [False, False], v)))
        async def split():
            dv = await pg.locator("#split [part~=divider]").first.bounding_box()
            x, y = dv["x"] + dv["width"] / 2, dv["y"] + dv["height"] / 2
            await pg.mouse.move(x, y); await pg.mouse.down(); await pg.mouse.move(x - 120, y, steps=8); await pg.mouse.up(); await W(300)
        await step("wa-split-panel drag", split, lambda: _r(S(), lambda s: (s["split"] < 40, s["split"])))
        async def frame_check():
            v = await ev("""(()=>{const f=document.querySelector('wa-zoomable-frame');const d=f.shadowRoot.querySelector('iframe').contentDocument;
                 const p=d.querySelector('wa-page');return [!!p, !!(p&&p.shadowRoot), d.querySelectorAll(':not(:defined)').length]})()""")
            return (v[0] and v[1] and v[2] == 0, v)
        await step("wa-page inside wa-zoomable-frame", lambda: W(100), frame_check)

        # ---------------------------------------------------------------- navigation
        await step("wa-tab-group switch", lambda: click("#tabs wa-tab[panel=advanced]"),
                   lambda: _r(S(), lambda s: (s["tab"] == "advanced", s["tab"])))
        async def tree():
            it = pg.locator("#tree wa-tree-item").nth(1); await it.scroll_into_view_if_needed()
            bb = await it.locator("wa-checkbox").first.bounding_box()  # checkbox is pointer-events:none; the row takes the click
            await pg.mouse.click(bb["x"] + bb["width"] / 2, bb["y"] + bb["height"] / 2); await W(300)
        await step("wa-tree select item", tree,
                   lambda: _r(S(), lambda s: ("Birch" in s["tree-sel"], s["tree-sel"])))
        await step("wa-pagination page", lambda: click("#pager >> button:has-text('5')"),
                   lambda: _r(S(), lambda s: (int(s["page"]) == 5, s["page"])))
        await step("wa-stepper next()", lambda: click("#step-next"),
                   lambda: _r(S(), lambda s: (s["step"] == "payment", s["step"])))
        await step("wa-stepper click step", lambda: click("#stepper wa-step[name=cart]"),
                   lambda: _r(S(), lambda s: (s["step"] == "cart", s["step"])))

        # ---------------------------------------------------------------- media
        await step("wa-carousel next", lambda: click("#carousel >> [part~=navigation-button-next]"),
                   lambda: _r(S(), lambda s: (s["slide"] == 1, s["slide"])))
        async def cmp():
            h = await pg.locator("wa-comparison [part~=handle]").first.bounding_box()
            x, y = h["x"] + h["width"] / 2, h["y"] + h["height"] / 2
            await pg.mouse.move(x, y); await pg.mouse.down(); await pg.mouse.move(x + 100, y, steps=6); await pg.mouse.up(); await W(300)
        await step("wa-comparison drag", cmp, lambda: _r(log(), lambda l: ("wa-comparison · change" in l, "log")))
        async def lb():
            await click("#lightbox wa-lightbox-item >> nth=1"); await W(700)
            o = await ev("document.getElementById('lightbox').open")
            await pg.keyboard.press("Escape"); await W(500); return o
        await step("wa-lightbox open", lb, lambda: _r(log(), lambda l: ("wa-lightbox · wa-show" in l, "log")))
        await step("wa-animated-image pause", lambda: click("wa-animated-image"),
                   lambda: _r(ev("document.querySelector('wa-animated-image').play"), lambda v: (v is False, v)))
        await step("wa-markdown rendered", lambda: W(10),
                   lambda: _r(ev("!!document.querySelector('wa-markdown h3')||!!document.querySelector('wa-markdown').shadowRoot?.querySelector('h3')"), lambda v: (v, v)))
        await step("wa-code-highlighter loaded", lambda: W(10), lambda: _r(ev("[CSS.highlights.size, document.querySelector('wa-code-highlighter').matches(':state(error)')]"), lambda v: (v[0] > 0 and v[1] is False, f"CSS highlights={v[0]} error={v[1]}")))

        # ---------------------------------------------------------------- helpers
        async def tick():
            await pg.locator("#ticker").scroll_into_view_if_needed(); await W(2500)
        await step("wa-number-ticker animates in view", tick,
                   lambda: _r(ev("document.getElementById('ticker').shadowRoot?.textContent||''"), lambda t: ("1,000,000" in t, t.strip()[:40])))
        async def tick2():
            await click("#ticker-add"); await W(2500)
        await step("wa-number-ticker new value", tick2,
                   lambda: _r(ev("document.getElementById('ticker').shadowRoot?.textContent||''"), lambda t: ("1,250,000" in t, t.strip()[:40])))
        await step("wa-counter ticking", lambda: W(1200),
                   lambda: _r(ev("document.querySelector('wa-counter').shadowRoot.textContent.replace(/\\s+/g,' ').trim()"), lambda t: ("hours" in t, t[:60])))
        await step("wa-format-bytes reacts", lambda: typ("#f-bytes input", "2048"),
                   lambda: _r(ev("document.querySelector('wa-format-bytes').shadowRoot.textContent"), lambda t: ("2.05" in t or "2 kB" in t or "2.0" in t, t)))
        await step("wa-animation stop", lambda: click("wa-switch:has-text('Play')"),
                   lambda: _r(ev("document.querySelector('wa-animation').play"), lambda v: (v is False, v)))
        async def popv():
            await click("#popover-btn"); await W(400)
        await step("wa-popover show", popv, lambda: _r(log(), lambda l: ("wa-popover · wa-show" in l, "log")))
        await pg.keyboard.press("Escape"); await W(300)
        await step("wa-popup placement", lambda: click("wa-radio[value=right]"),
                   lambda: _r(ev("document.querySelector('wa-popup').getAttribute('placement')"), lambda v: (v == "right", v)))
        await step("wa-include loaded", lambda: W(10),
                   lambda: _r(ev("document.querySelector('wa-include').textContent.includes('include.html')"), lambda v: (v, v)))
        async def io():
            await ev("document.getElementById('io-root').scrollTop = 120"); await W(600)
        await step("wa-intersection-observer", io, lambda: _r(S(), lambda s: (s["visible?"] is True, s["visible?"])))
        async def rs():
            await ev("document.querySelector('.resizable').style.width='300px'"); await W(500)
        await step("wa-resize-observer", rs, lambda: _r(S(), lambda s: (s["size"].startswith("296") or s["size"].startswith("300"), s["size"])))
        await step("wa-mutation-observer", lambda: click("#mut-btn"),
                   lambda: _r(log(), lambda l: ("wa-mutation-observer · wa-mutation → success" in l, "log")))
        async def rnd():
            n0 = (await log()).count("wa-content-change")
            for _ in range(3): await click("wa-button:has-text('Randomize')")
            return n0
        await step("wa-random-content randomize", rnd, lambda: _r(log(), lambda l: (l.count("wa-content-change") >= 2, "log")))

        if os.environ.get("KS_SCREENSHOT"): await pg.screenshot(path=os.environ["KS_SCREENSHOT"], full_page=True)
        passed = sum(1 for r in results if r[1])
        for name, ok, d in results:
            print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  [{d}]"))
        print(f"\n{passed}/{len(results)} interaction checks passed")
        print("icons requested:", sorted(icon_reqs))
        print("console:", "\n".join(dict.fromkeys(errs)) or "none")
        await b.close()

async def _r(coro, f):
    return f(await coro)

asyncio.run(main())
