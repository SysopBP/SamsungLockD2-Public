"""Released-APK smoke tests in a disposable CI emulator; no handset data."""
import subprocess, pathlib, time, re, json, hashlib, base64
out=pathlib.Path("diagnostics"); out.mkdir(exist_ok=True)
def adb(*args, data=None):
    return subprocess.run(["adb",*args],input=data,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout
def shell(s): return adb("shell",s).decode(errors="replace")
def write_private(pkg,path,data):
    shell(f"run-as {pkg} mkdir -p {path.rsplit('/',1)[0]}")
    adb("shell",f"run-as {pkg} sh -c 'cat > {path}'",data=data)
def varint(n):
    b=bytearray()
    while n>127: b.append((n&127)|128); n>>=7
    b.append(n); return bytes(b)
def field(n,b): return varint((n<<3)|2)+varint(len(b))+b
def galaxy_prefs(paired):
    shell("am force-stop app.cutout.ringpreview")
    vals={"cutout_enabled":True,"shows_when_empty":True,"shows_when_empty_show_icon":True,"d2Enabled":paired,"ignore_silent_notifications":False}
    b=b"".join(field(1,field(1,k.encode())+field(2,b"\x08"+bytes([int(v)]))) for k,v in vals.items())
    write_private("app.cutout.ringpreview","files/datastore/behaviour_prefs.preferences_pb",b)
def d2_state(locked):
    shell("am force-stop app.d2lock")
    salt=bytes(range(16))
    pin={"salt":base64.b64encode(salt).decode(),"hash":base64.b64encode(hashlib.pbkdf2_hmac("sha256",b"246810",salt,210000)).decode(),"failures":0,"until":0}
    write_private("app.d2lock","no_backup/d2-pin.json",json.dumps(pin).encode())
    write_private("app.d2lock","shared_prefs/island_bridge.xml",f'<?xml version="1.0" encoding="utf-8"?><map><boolean name="enabled" value="true" /><boolean name="locked" value="{str(locked).lower()}" /></map>'.encode())
    shell("am start -W -n app.d2lock/.MainActivity")
    shell("input keyevent KEYCODE_HOME")
def bind():
    shell("am start -W -n app.cutout.ringpreview/com.ekoehler.expressivecutout.MainActivity")
    shell("settings put secure enabled_accessibility_services null")
    time.sleep(1)
    shell("settings put secure enabled_accessibility_services app.cutout.ringpreview/com.ekoehler.expressivecutout.service.CutoutAccessibilityService")
    shell("settings put secure accessibility_enabled 1")
    shell("cmd notification allow_listener app.cutout.ringpreview/com.ekoehler.expressivecutout.service.CutoutNotificationListenerService")
    shell("input keyevent KEYCODE_HOME")
def visible():
    s=shell("dumpsys window windows")
    blocks=re.split(r"\n  Window #",s)
    return any(("ty=ACCESSIBILITY_OVERLAY" in b or "ty=2032" in b) and "mHasSurface=true" in b and "isOnScreen=true" in b for b in blocks)
results=[]
def check(name,expected):
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        if visible()==expected: break
        time.sleep(1)
    time.sleep(2)
    actual=visible()
    for kind,cmd in [("window","dumpsys window windows"),("accessibility","dumpsys accessibility"),("logcat","logcat -d -t 1200")]:
        (out/f"{name}-{kind}.txt").write_text(shell(cmd))
    (out/f"{name}.png").write_bytes(adb("exec-out","screencap","-p"))
    results.append({"scenario":name,"expected_overlay":expected,"actual_overlay":actual,"passed":actual==expected})
    print(results[-1],flush=True)
    (out/"results.json").write_text(json.dumps(results,indent=2))
shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
shell("settings put system screen_off_timeout 1800000")
adb("install","-r","apks/galaxy.apk")
galaxy_prefs(False);bind();check("01-galaxy-standalone",True)
adb("install","-r","apks/old-d2.apk")
d2_state(False);galaxy_prefs(True);bind();check("02-old-d2-paired",True)
adb("install","-r","apks/new-d2.apk")
check("03-after-d2-update",True)
d2_state(False);check("04-new-d2-unlocked",True)
d2_state(True);check("05-new-d2-locked",False)
d2_state(False);check("06-new-d2-unlocked-again",True)
shell("input keyevent KEYCODE_SLEEP");time.sleep(2)
shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
shell("input keyevent KEYCODE_HOME");check("07-screen-cycle",True)
bind();check("08-accessibility-rebind",True)
galaxy_prefs(False);bind();check("09-new-d2-standalone",True)
if not all(r["passed"] for r in results): raise SystemExit("Overlay smoke test failure; inspect diagnostics.")
