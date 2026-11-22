"""Runs the whole interaction suite under a strict Content-Security-Policy.

Writes app/public/csp-test.html (static.html plus a CSP <meta>, gitignored), with
sha256 hashes for the two inline <script>s, then runs test/kitchen_sink_test.py
against it. A violation shows up as a console error and fails the run.

The policy: no 'unsafe-eval' (Scittle interprets ClojureScript with SCI, it never
calls eval), no 'unsafe-inline' for scripts (hashes instead), CDN hosts listed.
Two allowances are needed: style-src 'unsafe-inline' (some Web Awesome
components write inline style attributes at runtime; Replicant's :style maps go
through the CSSOM and need no allowance) and connect-src data: (Web Awesome
fetches its built-in system icons from data: URLs).

Usage (bb serve-static running on 8092):
    python3 test/csp_test.py [base_url]       # default http://localhost:8092/
Same env as kitchen_sink_test.py (KS_CHROME, KS_PROXY).
"""
import base64, hashlib, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8092/"
src = (ROOT / "app/public/static.html").read_text()

def sha(text): return "'sha256-" + base64.b64encode(hashlib.sha256(text.encode()).digest()).decode() + "'"
inline_js = [m.group(1) for m in re.finditer(r"<script(?![^>]*\bsrc=)(?![^>]*x-scittle)[^>]*>(.*?)</script>", src, re.S)]

CDN, FONTS = "https://cdn.jsdelivr.net", "https://fonts.bunny.net"
policy = "; ".join([
    "default-src 'self'",
    f"script-src 'self' {CDN} " + " ".join(sha(s) for s in inline_js),
    f"style-src 'self' {CDN} {FONTS} 'unsafe-inline'",
    f"font-src 'self' {CDN} {FONTS}",
    f"img-src 'self' data: {CDN}",
    f"connect-src 'self' data: {CDN}",
    "object-src 'none'",
    "base-uri 'none'",
])
out = ROOT / "app/public/csp-test.html"
out.write_text(src.replace('<meta charset="utf-8" />',
                           f'<meta charset="utf-8" />\n  <meta http-equiv="Content-Security-Policy" content="{policy}" />', 1))
print("CSP:", policy, "\n")
r = subprocess.run([sys.executable, str(ROOT / "test/kitchen_sink_test.py"), BASE.rstrip("/") + "/csp-test.html"],
                   capture_output=True, text=True)
print(r.stdout[-3000:], r.stderr[-2000:])
ok = re.search(r"(\d+)/(\d+) interaction checks passed", r.stdout)
clean = "console: none" in r.stdout
print("CSP run:", "PASS" if ok and ok.group(1) == ok.group(2) and clean else "FAIL")
sys.exit(0 if ok and ok.group(1) == ok.group(2) and clean else 1)
