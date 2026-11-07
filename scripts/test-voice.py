"""Uses synthetic browser microphones; does not record or send real microphone audio."""
from pathlib import Path
import json, time
from playwright.sync_api import sync_playwright

endpoints = json.loads(Path("build/voice-test.json").read_text())
with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe", headless=True, args=["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream", "--autoplay-policy=no-user-gesture-required"])
    context = browser.new_context(permissions=["microphone"])
    alice, bob = context.new_page(), context.new_page()
    for page, endpoint in [(alice,endpoints["alice"]),(bob,endpoints["bob"])]:
        page.goto(endpoint)
        page.wait_for_function("() => typeof settings !== 'undefined' && !!settings")
        page.locator("#start").click()
        page.wait_for_function("() => running === true", timeout=60000)
    for page in [alice,bob]:
        page.wait_for_function("() => [...connections.values()].some(p => p.dc && p.dc.readyState === 'open')",timeout=30000)
    print("PASS two WebRTC peers connected with synthetic microphones")
    alice.evaluate("begin()")
    alice.wait_for_function("() => transmitting === true")
    bob.wait_for_function("() => nextPlay > 0",timeout=30000)
    print("PASS signed encrypted DAC audio with automatic server keys decoded and scheduled for playback on remote peer")
    bob.evaluate("begin()")
    bob.wait_for_timeout(500)
    assert not bob.evaluate("transmitting"), "Two speakers acquired the floor"
    print("PASS simultaneous second speaker denied")
    alice.evaluate("end()")
    bob.evaluate("end()")
    bob.wait_for_timeout(800)
    bob.evaluate("begin()")
    bob.wait_for_function("() => transmitting === true",timeout=10000)
    print("PASS floor transfers to second peer")
    for page in [alice,bob]:
        page.evaluate("leave()")
        page.wait_for_function("() => !running && stream.getTracks().every(t => t.readyState === 'ended')")
    print("PASS leaving voice stops microphone tracks")
    browser.close()
Path("build/stop-voice-test").touch()
