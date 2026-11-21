"""Level A: Windows file locking + the public Relaunch helper, no Minecraft involved."""
import os, shutil, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import *

W = os.path.abspath("work/levela")
shutil.rmtree(W, ignore_errors=True)
jars = fetch_release_jars(os.path.join(W, "dl"))
mods = os.path.join(W, "mods"); os.makedirs(mods)
jar = os.path.join(mods, "vantage-1.0.6.jar")
fresh = jar + ".new"
shutil.copy(jars["1.0.6"], jar)
shutil.copy(jars["1.0.7"], fresh)
prev = jar + ".prev"; shutil.copy(jar, prev)   # what Updater.prepareSwap does before the swap
print("before: jar", sha256(jar)[:16], "new", sha256(fresh)[:16], "prev", sha256(prev)[:16], flush=True)
r = subprocess.run(["java", "winci/Hold.java", jar, fresh, W], capture_output=True, text=True, timeout=120)
print(r.stdout, r.stderr, flush=True)
for i in range(60):
    if sha256(jar) == JARS["1.0.7"]:
        break
    time.sleep(1)
print("after: jar", sha256(jar), "prev", sha256(prev), flush=True)
ok = sha256(jar) == JARS["1.0.7"] and sha256(prev) == JARS["1.0.6"]
print("LEVEL A", "PASS: locked jar was replaced by the Relaunch helper after the holder exited; prev kept" if ok else "FAIL", flush=True)
sys.exit(0 if ok else 1)
