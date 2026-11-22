"""Interaction suite: 98 checks across the Web Awesome components, driven like a user would.

Usage (a server running, see README):
    python3 test/kitchen_sink_test.py http://localhost:8090/index.html     # app version (bb dev)
    python3 test/kitchen_sink_test.py http://localhost:8091/index.html     # single-file version (bb serve-single)
    python3 test/kitchen_sink_test.py http://localhost:8092/static.html    # static version (bb serve-static)
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
        # (app mode: the validation-off submit's 400 is expected and logged by the browser as a failed resource)
        pg.on("console", lambda m: m.type in ("error", "warning") and "status of 400" not in m.text and errs.append(m.type + ": " + m.text[:300]))
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
        MODE = await ev("window.KS_MODE || 'app'")
        html_cls = lambda: ev("[...document.documentElement.classList]")

        # ---------------------------------------------------------------- actions
        await step("wa-button click", lambda: click("section:not(#theming-tile) wa-button[variant=brand]:has-text('Brand')"),
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
        # ---------------------------------------------------------------- validation
        # The name is empty now. validate-profile (logic.cljc) puts its messages on
        # the fields as custom-error, so the browser blocks the submit.
        async def blocked():
            before = await ev("document.getElementById('form-result')?.innerText||''")
            await click("#submit-btn"); return before
        async def blocked_check():
            v = await ev("""[document.getElementById('form-result')?.innerText||'',
                             document.getElementById('f-name').matches(':state(user-invalid)'),
                             document.getElementById('log').innerText.includes('wa-invalid · name → Name is required')]""")
            return (v[1] and v[2] and '"name" "Grace Hopper"' in v[0], v[1:])
        await step("validation blocks submit (required + custom-error)", blocked, blocked_check)
        async def qty7():
            await typ("#f-name input", "Grace Hopper")
            await pg.locator("#f-qty input").first.click(); await pg.keyboard.press("Control+a"); await pg.keyboard.type("7"); await W(300)
        await step("cross-field rule: qty over 5 needs an email", qty7,
                   lambda: _r(ev("[document.getElementById('f-email').validationMessage, document.getElementById('f-email').validity.customError]"),
                              lambda v: (v == ["Email is required for quantities over 5", True], v)))
        await step("wa-invalid reports the shared message", lambda: click("#submit-btn"),
                   lambda: _r(log(), lambda l: ("wa-invalid · email → Email is required for quantities over 5" in l, "log")))
        async def novalidate():
            await click("#f-validate [part~=control]"); await click("#submit-btn"); await W(800)
        async def novalidate_check():
            v = await ev("""[document.getElementById('profile-form').noValidate,
                             document.getElementById('server-errors')?.innerText||'',
                             document.getElementById('form-result')?.innerText||'']""")
            if MODE == "app":   # the server runs the same rules and answers 400
                return (v[0] and "Email is required for quantities over 5" in v[1], v)
            return (v[0] and '"qty" "7"' in v[2], v)   # no server: the form just submits
        await step("browser validation off: " + ("server's 400 shown" if MODE == "app" else "form submits"), novalidate, novalidate_check)
        async def fix():
            await typ("#f-email input", "grace@example.com")
            await click("#f-validate [part~=control]"); await click("#submit-btn"); await W(800)
        async def fix_check():
            v = await ev("""[document.getElementById('f-email').checkValidity(), document.getElementById('profile-form').noValidate,
                             !!document.getElementById('server-errors'), document.getElementById('form-result')?.innerText||'']""")
            return (v[0] and not v[1] and not v[2] and "grace@example.com" in v[3], v)
        await step("valid again: submits, server errors cleared", fix, fix_check)

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

        # ---------------------------------------------------------------- theming
        await pg.locator("#theme-select").scroll_into_view_if_needed()
        brand_bg = "getComputedStyle(document.querySelector('#theming-tile wa-button[variant=brand]').shadowRoot.querySelector('[part~=base]')).backgroundColor"
        async def pick(id, value):
            await click(f"#{id}"); await click(f"#{id} wa-option[value={value}]"); await W(600)
        font0 = await ev("getComputedStyle(document.body).fontFamily")
        await step("theme: awesome (classes + lazy CSS)", lambda: pick("theme-select", "awesome"),
                   lambda: _r(ev("""[[...document.documentElement.classList], !!document.getElementById('css-styles-themes-awesome-css')?.sheet,
                                     getComputedStyle(document.body).fontFamily]"""),
                              lambda v: ("wa-theme-awesome" in v[0] and "wa-theme-default" not in v[0] and v[1] and v[2] != font0, v)))
        b0 = await ev(brand_bg)
        await step("brand color: red", lambda: pick("brand-select", "red"),
                   lambda: _r(ev(f"[[...document.documentElement.classList], {brand_bg}]"),
                              lambda v: ("wa-brand-red" in v[0] and v[1] != b0, v)))
        await step("palette: bright (lazy CSS)", lambda: pick("palette-select", "bright"),
                   lambda: _r(ev("[[...document.documentElement.classList], !!document.getElementById('css-styles-color-palettes-bright-css')]"),
                              lambda v: ("wa-palette-bright" in v[0] and v[1], v)))
        lum = r"""(()=>{const m=getComputedStyle(document.querySelector('.tile')).backgroundColor.match(/[\d.]+/g).map(Number);
                       return Math.round(0.2126*m[0]+0.7152*m[1]+0.0722*m[2])})()"""
        light = await ev(lum)
        await step("dark mode switch", lambda: click("#dark-switch [part~=control]"),
                   lambda: _r(ev(f"[[...document.documentElement.classList], {lum}]"),
                              lambda v: ("wa-dark" in v[0] and "wa-light" not in v[0] and v[1] < 80 < light, f"{v} (light was {light})")))
        await step("theme state shown as <html> classes", lambda: W(10),
                   lambda: _r(ev("[document.getElementById('html-classes').textContent, [...document.documentElement.classList].join(' ')]"),
                              lambda v: (v[0] == v[1], v)))
        await step("theme reset keeps dark", lambda: click("#theme-reset"),
                   lambda: _r(html_cls(), lambda c: (c == ["wa-theme-default", "wa-dark"], c)))
        await click("#dark-switch [part~=control]")
        await step("back to light", lambda: W(10),
                   lambda: _r(html_cls(), lambda c: (c == ["wa-theme-default", "wa-light"], c)))

        # ---------------------------------------------------------------- state panel
        await step("state panel: keys sorted", lambda: W(10),
                   lambda: _r(ev("[...document.querySelectorAll('#state [data-key]')].map(e=>e.dataset.key)"),
                              lambda ks: (ks == sorted(ks) and len(ks) > 20, f"{len(ks)} keys")))
        async def flip():
            await pg.locator("#f-notify").scroll_into_view_if_needed(); await pg.locator("#f-notify").click(); await W(200)
        # the switch uses a data handler, so its transition also records :last-action
        await step("state panel: highlights exactly the changed keys", flip,
                   lambda: _r(ev("[...document.querySelectorAll('#state .kv.changed')].map(e=>e.dataset.key)"),
                              lambda ks: (sorted(k for k in ks if k != ":srv") == [":last-action", ":notify?"], ks)))
        # (:srv is ignored: in app mode the server tick can land inside the 200 ms window)
        # (in app mode the server tick legitimately re-highlights :srv every 5 s)
        await step("state panel: highlight clears", lambda: W(2600),
                   lambda: _r(ev("[...document.querySelectorAll('#state .kv.changed')].map(e=>e.dataset.key)"),
                              lambda ks: (":notify?" not in ks and set(ks) <= {":srv"}, ks)))

        # ---------------------------------------------------------------- events: functions vs data
        async def fn_inc():
            await pg.locator("#fn-inc").scroll_into_view_if_needed(); await click("#fn-inc")
        await step("events demo: function handler counts", fn_inc,
                   lambda: _r(S(), lambda s: (s["fn-count"] == 1, s["fn-count"])))
        await step("events demo: data handler counts", lambda: click("#data-inc"),
                   lambda: _r(S(), lambda s: (s["data-count"] == 1, s["data-count"])))
        await step("events demo: one data event = one transition, all its keys highlighted", lambda: W(10),
                   lambda: _r(ev("[...document.querySelectorAll('#state .kv.changed')].map(e=>e.dataset.key)"),
                              lambda ks: ({":data-count", ":last-action"} <= set(ks), ks)))
        await step("events demo: data handler prints as data, fn handler as a function", lambda: W(10),
                   lambda: _r(ev("[document.getElementById('data-on').textContent, document.getElementById('fn-on').textContent]"),
                              lambda v: (v[0].startswith("{:click [[:store/inc :data-count]") and "[:store" not in v[1], v)))
        await step("events demo: dispatch received the exact action data", lambda: W(10),
                   lambda: _r(ev("document.getElementById('last-action').textContent"),
                              lambda t: (t == '[[:store/inc :data-count] [:log "events-demo" "click" "data handler"]]', t)))
        async def page_data():
            await click("#pager >> button:has-text('7')")   # the wa-pagination host has a 0x0 box; click scrolls the button itself
        await step("events as data: placeholders filled from the event (pagination)", page_data,
                   lambda: _r(ev("document.getElementById('last-action').textContent"),
                              lambda t: (t == '[[:store/assoc :page 7] [:log "wa-pagination" "wa-page-change" 7]]', t)))

        # ---------------------------------------------------------------- Replicant features
        await step("refs: nodes captured by on-mount hooks", lambda: W(10),
                   lambda: _r(ev("document.getElementById('refs-list').textContent"),
                              lambda t: (t == "[:random :stepper :ticker :toast]", t)))
        async def lc_on():
            await pg.locator("#lc-toggle").scroll_into_view_if_needed(); await click("#lc-toggle"); await W(400)
        await step("life-cycle: function hook focuses input on mount", lc_on,
                   lambda: _r(ev("[!!document.getElementById('lc-input'), document.activeElement && document.activeElement.id]"),
                              lambda v: (v == [True, "lc-input"], v)))
        await step("life-cycle: data hook dispatched on mount", lambda: W(10),
                   lambda: _r(log(), lambda l: ("life-cycle · mount → search field (data hook)" in l, "log")))
        async def lc_off():
            await click("#lc-toggle"); await W(300)
        await step("life-cycle: data hook dispatched on unmount", lc_off,
                   lambda: _r(ev("[!!document.getElementById('lc-input'), document.getElementById('log').innerText.includes('life-cycle · unmount → search field (data hook)')]"),
                              lambda v: (v == [False, True], v)))
        cnt = "parseInt(document.getElementById('lc-renders').textContent.match(/\\d+/)[0])"
        async def mem():
            before = await ev(cnt); await click("#data-inc"); return before
        async def mem_check():
            return (await ev(cnt) > 1, await ev("document.getElementById('lc-renders').textContent"))
        await step("life-cycle: on-render memory persists across renders", mem, mem_check)
        op = "(id)=>{const e=document.getElementById(id);return e?parseFloat(getComputedStyle(e).opacity):null}"
        async def tr_add():
            await pg.locator("#tr-add").scroll_into_view_if_needed(); await pg.locator("#tr-add").click()
            await W(60)
            return await pg.evaluate(op, "item-4")
        async def tr_add_check():
            await W(700); end = await pg.evaluate(op, "item-4")
            return (end == 1.0, f"opacity after 700ms = {end}")
        early = {}
        async def tr_add_step():
            early["mid"] = await tr_add()
        await step("transitions: mounting animates in", tr_add_step,
                   lambda: _r(tr_add_check(), lambda v: (v[0] and early["mid"] is not None and early["mid"] < 1.0, f"{v[1]}; at 60ms {early['mid']}")))
        async def tr_remove():
            await pg.locator("#item-4 .tr-remove").click(); await W(80)
            early["during"] = await pg.evaluate(op, "item-4")
        async def tr_remove_check():
            await W(900); gone = await pg.evaluate("!document.getElementById('item-4')")
            st = await S()
            return (early["during"] is not None and gone and 4 not in st["items"], f"in DOM at 80ms with opacity {early['during']}; removed later: {gone}")
        await step("transitions: unmounting stays in DOM until its transition ends", tr_remove, tr_remove_check)
        await step("aliases: :ks/stat expands to hiccup", lambda: W(10),
                   lambda: _r(ev("[document.querySelectorAll('#alias-demo .ks-stat').length, document.getElementById('alias-expanded').textContent]"),
                              lambda v: (v[0] == 3 and v[1].startswith('<div class=\"ks-stat\"><div class=\"ks-stat-label\">Volume</div>'), v)))
        async def str_name():
            await pg.locator("#f-name input").first.click(); await pg.keyboard.press("Control+a"); await pg.keyboard.type("Rich Hickey"); await W(250)
        await step("replicant.string: HTML output follows state", str_name,
                   lambda: _r(ev("document.getElementById('string-out').textContent"),
                              lambda t: (t.startswith('<div class="ks-profile">') and "Rich Hickey" in t and 'initials="RH"' in t and ":on" not in t, t[:120])))

        # ---------------------------------------------------------------- side panels stay usable
        async def to_bottom():
            await ev("window.scrollTo(0, document.body.scrollHeight)"); await W(400)
        await step("side panels visible at page bottom", to_bottom,
                   lambda: _r(ev("""['log','state'].map(id=>{const r=document.getElementById(id).getBoundingClientRect();
                                     return r.top>=0 && r.bottom<=window.innerHeight && r.height>80})"""),
                              lambda v: (v == [True, True], v)))
        await step("event log keeps history and scrolls", lambda: W(10),
                   lambda: _r(ev("(()=>{const l=document.getElementById('log');return [l.innerText.split('\\n').length, l.scrollHeight>l.clientHeight]})()"),
                              lambda v: (v[0] > 16 and v[1], f"{v[0]} lines, scrollable={v[1]}")))
        vis = """(k)=>{const p=document.getElementById('state'),r=p.querySelector(`[data-key="${k}"]`);
                    const a=r.getBoundingClientRect(),b=p.getBoundingClientRect();return a.top>=b.top-1&&a.bottom<=b.bottom+1}"""
        async def late_key():
            await ev("document.getElementById('state').scrollTop = 0"); await W(100)
            before = await pg.evaluate(vis, ":ticker")
            await click("#ticker-add")          # changes :ticker, near the end of the sorted keys
            return before
        async def late_check():
            v = await pg.evaluate("""(()=>{const p=document.getElementById('state');return [
                [...p.querySelectorAll('.kv.changed')].map(e=>e.dataset.key), p.scrollTop]})()""")
            now = await pg.evaluate(vis, ":ticker")
            return (":ticker" in v[0] and now and v[1] > 0, f"highlighted={v[0]} visible={now} scrollTop={v[1]}")
        async def late_step():
            before = await late_key()
            if before:
                raise RuntimeError(":ticker already visible before the change; viewport too tall to exercise scrolling")
        await step("state panel scrolls changed row into view", late_step, late_check)

        # ---------------------------------------------------------------- routing
        cats = "[...document.querySelectorAll('.cat h2')].map(e=>e.textContent)"
        await ev("window.scrollTo(0,0)")
        await step("route: nav link to Forms", lambda: click("#route-nav wa-button[data-route=forms]"),
                   lambda: _r(ev(f"[location.hash, window.__state().route, {cats}, document.getElementById('log').innerText.split('\\n')[0]]"),
                              lambda v: (v[0] == "#/forms" and v[1] == "forms" and v[2] == ["Forms"] and "router · hashchange → #/forms" in v[3], v)))
        async def back(): await pg.go_back(); await W(500)
        await step("route: browser back", back,
                   lambda: _r(ev(f"[location.hash, window.__state().route, {cats}.length]"),
                              lambda v: (v[0] == "" and v[1] == "all" and v[2] == 9, v)))
        async def bad(): await pg.locator("#route-missing").scroll_into_view_if_needed(); await click("#route-missing")
        await step("route: unknown page", bad,
                   lambda: _r(ev("[window.__state().route, !!document.getElementById('route-not-found')]"),
                              lambda v: (v == ["no-such-page", True], v)))
        deep = await ctx.new_page()
        await deep.goto(URL.split("#")[0] + "#/media"); await deep.wait_for_function("window.__ready===true", timeout=60000)
        await step("route: deep link opens on that page", lambda: W(10),
                   lambda: _r(deep.evaluate(f"[window.__state().route, {cats}]"),
                              lambda v: (v == ["media", ["Media"]], v)))
        await deep.close()
        await ev("location.hash = '#/'"); await W(400)
        await step("route: #/ shows everything", lambda: W(10),
                   lambda: _r(ev(f"[window.__state().route, {cats}.length]"), lambda v: (v == ["all", 9], v)))

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
