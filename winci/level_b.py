"""Level B: real Minecraft 1.21.11 + Fabric Loader + Fabric API + the public Vantage 1.0.6 jar on Windows, software OpenGL.
Public Mojang/Fabric/Modrinth downloads only, offline dev username, no account."""
import glob, hashlib, json, os, re, shutil, subprocess, sys, time, urllib.parse, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import *

MC, LOADER = "1.21.11", "0.18.6"
W = os.path.abspath("work"); M = os.path.join(W, "meta"); GAME = os.path.join(W, "game"); LOGS = os.path.join(W, "logs")
for d in (W,):
    pass
os.makedirs(LOGS, exist_ok=True)


def sha1(b): return hashlib.sha1(b).hexdigest()


def fetchf(url, dest, want=None):
    if os.path.exists(dest) and (want is None or sha1(open(dest, "rb").read()) == want):
        return 0
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    data = get(url)
    if want and sha1(data) != want:
        sys.exit("sha1 mismatch " + url)
    open(dest, "wb").write(data)
    return 1


# ---- Java + software OpenGL ----
JH = os.environ.get("JAVA_HOME") or os.path.dirname(os.path.dirname(shutil.which("java")))
JAVA = os.path.join(JH, "bin", "java.exe")
print("java:", JAVA, flush=True)
mesa = os.path.join(W, "mesa"); os.makedirs(mesa, exist_ok=True)
subprocess.run(["gh", "release", "download"] + ([os.environ["MESA_TAG"]] if os.environ.get("MESA_TAG") else []) + ["--repo", "pal1000/mesa-dist-win", "--pattern", "*release-msvc.7z", "--dir", mesa, "--clobber"], check=True)
arch = glob.glob(os.path.join(mesa, "*release-msvc.7z"))[0]
print("mesa archive:", os.path.basename(arch), flush=True)
subprocess.run(["7z", "x", "-y", "-o" + os.path.join(mesa, "x"), arch], check=True, stdout=subprocess.DEVNULL)
x64 = os.path.join(mesa, "x", "x64")
for f in os.listdir(x64):
    if f.lower().endswith(".dll"):
        shutil.copy(os.path.join(x64, f), os.path.join(JH, "bin", f))
print("mesa dlls into java bin:", sorted(f for f in os.listdir(os.path.join(JH, "bin")) if f.lower() in ("opengl32.dll", "libglapi.dll", "libgallium_wgl.dll")), flush=True)
ENVX = {"GALLIUM_DRIVER": "llvmpipe", "MESA_GL_VERSION_OVERRIDE": "4.5", "MESA_GLSL_VERSION_OVERRIDE": "450", "LP_NUM_THREADS": "4"}

# ---- Minecraft + Fabric ----
manifest = json.loads(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
entry = next(v for v in manifest["versions"] if v["id"] == MC)
vraw = get(entry["url"]); assert sha1(vraw) == entry["sha1"]
v = json.loads(vraw)
fprof = json.loads(get(f"https://meta.fabricmc.net/v2/versions/loader/{MC}/{LOADER}/profile/json"))
VER = f"{MC}-{LOADER}"
cp = []


def allowed(lib):
    rules = lib.get("rules")
    if not rules: return True
    ok = False
    for r in rules:
        o = (r.get("os") or {}).get("name")
        if o is None or o == "windows": ok = r.get("action") == "allow"
    return ok


for lib in v["libraries"]:
    if not allowed(lib): continue
    art = lib["downloads"]["artifact"]; p = art["path"]
    if re.search(r"natives-(linux|macos|windows-arm64|windows-x86)", p) or ("natives-" in p and "natives-windows" not in p): continue
    dest = os.path.join(M, "libraries", p.replace("/", os.sep)); fetchf(art["url"], dest, art["sha1"]); cp.append(dest)
for lib in fprof["libraries"]:
    g, a, ver = lib["name"].split(":")[:3]
    p = f"{g.replace('.', '/')}/{a}/{ver}/{a}-{ver}.jar"
    dest = os.path.join(M, "libraries", p.replace("/", os.sep)); fetchf(lib["url"].rstrip("/") + "/" + p, dest, lib.get("sha1")); cp.append(dest)
client = os.path.join(M, "versions", VER, VER + ".jar")
fetchf(v["downloads"]["client"]["url"], client, v["downloads"]["client"]["sha1"]); cp.append(client)
nat = os.path.join(M, "natives", VER); os.makedirs(nat, exist_ok=True)
for j in cp:
    if "natives-windows" in j:
        with zipfile.ZipFile(j) as z:
            for nm in z.namelist():
                if nm.endswith(".dll"): open(os.path.join(nat, os.path.basename(nm)), "wb").write(z.read(nm))
print("natives:", sorted(os.listdir(nat)), flush=True)
ai = v["assetIndex"]
idx_path = os.path.join(M, "assets", "indexes", ai["id"] + ".json"); fetchf(ai["url"], idx_path, ai["sha1"])
idx = json.load(open(idx_path))
from concurrent.futures import ThreadPoolExecutor
def one(h):
    fetchf(f"https://resources.download.minecraft.net/{h[:2]}/{h}", os.path.join(M, "assets", "objects", h[:2], h), h)
with ThreadPoolExecutor(16) as ex: list(ex.map(one, [o["hash"] for o in idx["objects"].values()]))
print(f"assets: {len(idx['objects'])} objects, {len(cp)} classpath jars", flush=True)

# ---- fabric api (public Modrinth download, sha512 from the API) ----
q = "https://api.modrinth.com/v2/project/fabric-api/version?" + urllib.parse.urlencode({"game_versions": json.dumps([MC]), "loaders": json.dumps(["fabric"])})
fv = json.loads(get(q))[0]; ff = fv["files"][0]
fapi_bytes = get(ff["url"]); assert hashlib.sha512(fapi_bytes).hexdigest() == ff["hashes"]["sha512"]
print("fabric api:", ff["filename"], flush=True)

# ---- game folder: mods = fabric-api + the 1.0.6 jar with an OLD file time; config like a user who confirmed the terms ----
jars = fetch_release_jars(os.path.join(W, "dl"))
shutil.rmtree(GAME, ignore_errors=True)
mods = os.path.join(GAME, "mods"); cfg = os.path.join(GAME, "config", "vantage"); os.makedirs(mods); os.makedirs(cfg)
open(os.path.join(mods, ff["filename"]), "wb").write(fapi_bytes)
jar = os.path.join(mods, "vantage-1.0.6.jar"); shutil.copy(jars["1.0.6"], jar)
old = time.mktime((2026, 10, 1, 12, 0, 0, 0, 0, -1)); os.utime(jar, (old, old))
open(os.path.join(cfg, "terms-answer"), "w").write("confirmed 3\n")
json.dump({"modules": {"ClickGUI": {"settings": {"Check for updates": True, "Auto update": True}}}}, open(os.path.join(cfg, "config.json"), "w"))
open(os.path.join(GAME, "options.txt"), "w").write("soundCategory_master:0.0\nfullscreen:false\noverrideWidth:854\noverrideHeight:480\nonboardAccessibility:false\ntutorialStep:none\nskipMultiplayerWarning:true\nnarrator:0\nlang:en_us\nmaxFps:30\nrenderDistance:2\n")
H = lambda p: sha256(p) if os.path.exists(p) else None
print("BEFORE: mods/vantage-1.0.6.jar sha256", H(jar), "(mtime set to 2026-10-01)", flush=True)

args = [JAVA, "-Xmx2G", "-Xms512M"] + [f"-D{k}={nat}" for k in ("java.library.path", "jna.tmpdir", "org.lwjgl.system.SharedLibraryExtractPath", "io.netty.native.workdir")]
args += ["-Dminecraft.launcher.brand=ci", "-Dminecraft.launcher.version=1", "-Dvantage.prodtest=true", "-cp", ";".join(cp)] + fprof["arguments"]["jvm"]
args += [fprof["mainClass"], "--username", "Alex_Test", "--version", VER, "--gameDir", GAME, "--assetsDir", os.path.join(M, "assets"),
         "--assetIndex", ai["id"], "--uuid", "00000000000000000000000000000000", "--accessToken", "0", "--clientId", "0", "--xuid", "0", "--versionType", "release"]
print("command line:", len(subprocess.list2cmdline(args)), "chars,", len(args), "parts", flush=True)
env = dict(os.environ, **ENVX); env.pop("VANTAGE_RELAUNCHED", None)
latest = os.path.join(GAME, "logs", "latest.log")
def rd(f):
    try: return open(f, "r", errors="replace").read()
    except Exception: return ""
def dump(pid, tag):
    r = subprocess.run(["jcmd", str(pid), "Thread.print"], capture_output=True, text=True, timeout=60)
    open(os.path.join(LOGS, f"threads-{tag}.txt"), "w").write(r.stdout + r.stderr)
def launch(a, name):
    out = open(os.path.join(LOGS, name), "wb")
    return subprocess.Popen(a, cwd=GAME, env=env, stdout=out, stderr=subprocess.STDOUT)
def wait_for(p, needle, secs, tag):
    t0 = time.time(); lastsize = -1; lastchange = time.time(); dumped = False
    while time.time() - t0 < secs:
        time.sleep(3)
        s = rd(latest)
        if needle in s: return True
        if len(s) != lastsize: lastsize = len(s); lastchange = time.time()
        elif time.time() - lastchange > 90 and not dumped and p.poll() is None:
            dump(p.pid, tag); dumped = True; print("log stalled 90s, thread dump saved", flush=True)
        if p.poll() is not None and needle not in rd(latest): return False
    return False

# ---- launch 1: 1.0.6 with the real updater ----
p = launch(args, "launch1-stdout.log")
t0 = time.time()
got_title = wait_for(p, "PRODTEST title reached", 420, "launch1")
print(f"launch 1 (1.0.6): title reached = {got_title} after {time.time()-t0:.0f}s", flush=True)
shutil.copy(latest, os.path.join(LOGS, "launch1-latest.log")) if os.path.exists(latest) else None
print("jar while the game still runs: sha256", H(jar), "| .new present:", os.path.exists(jar + ".new"), "| .prev", H(jar + ".prev"), flush=True)
# let the game close itself (ProdTest stops after its world), else close it by pid; the helper started by the updater waits for that pid
t1 = time.time()
while p.poll() is None and time.time() - t1 < 240: time.sleep(3)
if p.poll() is None:
    print("game still running, closing it by pid", flush=True); subprocess.run(["taskkill", "/F", "/PID", str(p.pid)], capture_output=True)
else:
    print("game closed itself, exit code", p.returncode, flush=True)
swapped = False
for i in range(90):
    if H(jar) == JARS["1.0.7"]: swapped = True; break
    time.sleep(1)
print(f"after the game closed: jar swapped to 1.0.7 by the helper = {swapped}; jar {H(jar)}; prev {H(jar + '.prev')}; .new left: {os.path.exists(jar + '.new')}", flush=True)
time.sleep(3)

# ---- launch 2: plain launch on the swapped jar, watched by the rollback guard ----
a2 = [x for x in args if x != "-Dvantage.prodtest=true"]
p2 = launch(a2, "launch2-stdout.log")
t2 = time.time()
KEEP = "First launch after the update reached the title screen: the new build is kept"
kept = wait_for(p2, KEEP, 420, "launch2")
print(f"launch 2 (watched first launch on the new jar): kept line = {kept} after {time.time()-t2:.0f}s", flush=True)
shutil.copy(latest, os.path.join(LOGS, "launch2-latest.log")) if os.path.exists(latest) else None
subprocess.run(["taskkill", "/F", "/T", "/PID", str(p2.pid)], capture_output=True)
time.sleep(3)

# ---- launch 3: rollback. The marker says the last launch on the new jar never reached the title screen; the kept .prev must come back ----
open(os.path.join(cfg, "update-pending"), "w").write("booting\n" + JARS["1.0.6"] + "\n" + JARS["1.0.7"] + "\n")
a3 = list(args)  # with the rig flag, so the game closes itself
p3 = launch(a3, "launch3-stdout.log")
t3 = time.time()
rb = wait_for(p3, "PRODTEST title reached", 420, "launch3")
print(f"launch 3 (rollback armed): title reached = {rb} after {time.time()-t3:.0f}s; going-back line in log: {'going back to the previous jar' in rd(latest)}", flush=True)
shutil.copy(latest, os.path.join(LOGS, "launch3-latest.log")) if os.path.exists(latest) else None
t1 = time.time()
while p3.poll() is None and time.time() - t1 < 240: time.sleep(3)
if p3.poll() is None: subprocess.run(["taskkill", "/F", "/PID", str(p3.pid)], capture_output=True)
rolled = False
for i in range(90):
    if H(jar) == JARS["1.0.6"]: rolled = True; break
    time.sleep(1)
print(f"after launch 3 closed: jar rolled back to 1.0.6 by the helper = {rolled}; jar {H(jar)}; marker left: {os.path.exists(os.path.join(cfg, 'update-pending'))}", flush=True)
allt = "".join(rd(f) for f in sorted(glob.glob(os.path.join(LOGS, "*.log"))))
print("---- key lines ----")
for l in allt.splitlines():
    if re.search(r"Auto update|Newer build|fetching it|Own command line|Update download|Handing over|First launch|vantage 1\.0|Update not installed|Update check|Terms:|rolls back|going back|PRODTEST (title|world|failed)|Rendering|Renderer|Stopping", l) and "Realms" not in l:
        print(l[:260])
print("rollback test (launch 3):", "jar back on the 1.0.6 hash" if rolled else "NOT rolled back")
print("mods dir:", sorted(os.listdir(mods)))
print("AFTER (final, after rollback): mods/vantage-1.0.6.jar sha256", H(jar))
print("AFTER: mods/vantage-1.0.6.jar.prev sha256", H(jar + ".prev"), "(1.0.6 expected", JARS["1.0.6"][:16] + ")")
print("update-pending marker left:", os.path.exists(os.path.join(cfg, "update-pending")))
ok = swapped and kept and rolled and H(jar + ".prev") == JARS["1.0.6"]
print("LEVEL B", "PASS" if ok else "NOT PASSED (see lines above)", flush=True)
sys.exit(0 if ok else 1)
