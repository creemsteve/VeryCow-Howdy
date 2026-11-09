"""Browser-to-browser synthetic audio through the Rust server and codec."""
from pathlib import Path
import json, subprocess, time, uuid
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parent.parent
fixture = root / 'build' / ('rust-voice-' + uuid.uuid4().hex)
fixture.mkdir(parents=True)
with (fixture / 'host.log').open('w') as log:
    host = subprocess.Popen([str(root / 'target/debug/examples/relay-fixture.exe'), 'voice', str(fixture)], cwd=root, stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 60
        while not (fixture / 'endpoints.json').exists():
            if host.poll() is not None or time.monotonic() > deadline:
                raise AssertionError((fixture / 'host.log').read_text())
            time.sleep(.1)
        endpoints = json.loads((fixture / 'endpoints.json').read_text())
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe', headless=True,
                args=['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'])
            context = browser.new_context(permissions=['microphone'])
            alice, bob = context.new_page(), context.new_page()
            for page, endpoint in [(alice, endpoints['alice']), (bob, endpoints['bob'])]:
                page.add_init_script("window.RTCPeerConnection=class{constructor(){throw Error('Peer media forbidden')}}")
                page.goto(endpoint)
                page.locator('#start').click()
                page.wait_for_function('() => running', timeout=60000)
            for page in [alice, bob]:
                page.wait_for_function('() => participants === 1', timeout=30000)
            alice.evaluate('begin()')
            alice.wait_for_function('() => transmitting')
            bob.wait_for_function('() => nextPlay > 0', timeout=30000)
            bob.evaluate('begin()')
            bob.wait_for_timeout(400)
            assert not bob.evaluate('transmitting')
            alice.evaluate('end()'); bob.evaluate('end()')
            bob.wait_for_timeout(800)
            bob.evaluate('begin()')
            bob.wait_for_function('() => transmitting')
            for page in [alice, bob]:
                page.evaluate('leave()')
                page.wait_for_function("() => !running && stream.getTracks().every(t => t.readyState === 'ended')")
            browser.close()
            print('PASS Rust browser voice: DAC playback, floor exclusion, transfer and microphone cleanup')
    finally:
        (fixture / 'stop').touch()
        try: host.wait(timeout=20)
        except subprocess.TimeoutExpired: host.terminate(); host.wait(timeout=10)
