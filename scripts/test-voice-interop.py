"""Native desktop / browser-panel protocol compatibility; all audio is synthetic."""
from pathlib import Path
import json
import subprocess
import time
import uuid
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parent.parent
fixture = root / "build" / ("voice-interop-" + uuid.uuid4().hex)
fixture.mkdir(parents=True)


def state():
    try:
        return json.loads((fixture / "state.json").read_text())
    except (OSError, ValueError):
        return {}


def wait_for(predicate):
    end = time.monotonic() + 60
    while not predicate():
        if time.monotonic() > end:
            raise AssertionError("Native interop timed out: " + str(state()))
        time.sleep(0.1)


with (fixture / "host.log").open("w") as log:
    host = subprocess.Popen([str(root / "target/debug/examples/relay-fixture.exe"),
                             "native", str(fixture)], cwd=root, stdout=log, stderr=log)
    try:
        wait_for(lambda: (fixture / "endpoints.json").exists() and state().get("ready"))
        endpoint = json.loads((fixture / "endpoints.json").read_text())["bob"]
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                headless=True, args=["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream", "--autoplay-policy=no-user-gesture-required"])
            context = browser.new_context(permissions=["microphone"])
            page = context.new_page()
            page.add_init_script("window.RTCPeerConnection = class { constructor() { throw Error('Peer media is forbidden'); } };")
            page.goto(endpoint)
            page.wait_for_function("() => typeof settings !== 'undefined' && !!settings")
            page.locator("#start").click()
            page.wait_for_function("() => running === true", timeout=60000)
            page.wait_for_function("() => participants === 1", timeout=30000)
            print("PASS native desktop shares the server voice relay with the browser voice panel")
            page.evaluate("begin()")
            wait_for(lambda: state().get("received", 0) > 0)
            page.evaluate("end()")
            print("PASS native desktop decrypts, decodes and plays the panel's DAC audio")
            page.wait_for_timeout(1200)
            (fixture / "command").write_text("press")
            page.wait_for_function("() => nextPlay > 0", timeout=30000)
            print("PASS panel decrypts and plays DAC audio from native desktop")
            (fixture / "command").write_text("release")
            wait_for(lambda: not state().get("recording", True))
            page.evaluate("leave()")
            page.wait_for_function("() => !running && stream.getTracks().every(t => t.readyState === 'ended')")
            print("PASS panel uses no peer connection and leaving stops microphone tracks")
            browser.close()
    finally:
        (fixture / "command").write_text("stop")
        try:
            host.wait(timeout=20)
        except subprocess.TimeoutExpired:
            host.terminate()
            host.wait(timeout=10)
