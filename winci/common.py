import hashlib, json, os, sys, urllib.request

REPO = "idkhowtonamemyselfasadev/vantage"
JARS = {
    "1.0.6": "2f1acc22dd8d3a202b2716c4bad9190e0068f2dc04c304eeb2f21978f19135f5",
    "1.0.7": "1432ef54b8766b1bde5daecced86140e0e202fbdebcaa174844a05eb2f4c4fb8",
}
UA = {"User-Agent": "vantage-windows-ci-test"}


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""):
            h.update(b)
    return h.hexdigest()


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
        return r.read()


def fetch_release_jars(dest):
    os.makedirs(dest, exist_ok=True)
    out = {}
    for v, want in JARS.items():
        p = os.path.join(dest, f"vantage-{v}.jar")
        open(p, "wb").write(get(f"https://github.com/{REPO}/releases/download/v{v}/vantage-{v}.jar"))
        got = sha256(p)
        print(f"release jar {v}: sha256 {got} {'OK' if got == want else 'MISMATCH'}", flush=True)
        if got != want:
            sys.exit(f"sha256 mismatch for {v}")
        out[v] = p
    return out
