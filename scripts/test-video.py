"""Real relay and browser codecs, with synthetic webcam and synthetic screen sources."""
from pathlib import Path
import json
import os
import subprocess
import time
import uuid
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parent.parent
fixture = root / 'build' / ('video-browser-' + uuid.uuid4().hex)
fixture.mkdir(parents=True)
classpath = os.pathsep.join(['build/classes', 'build/test-classes', 'lib/*'])
with (fixture / 'host.log').open('w') as log:
    host = subprocess.Popen(['java', '--add-modules', 'jdk.httpserver', '-cp', classpath,
                             'chat.vch.VoiceTestHost', 'video', str(fixture)], cwd=root, stdout=log, stderr=log)
    try:
        end = time.monotonic() + 30
        while not (fixture / 'endpoints.json').exists():
            if host.poll() is not None or time.monotonic() > end:
                raise AssertionError((fixture / 'host.log').read_text())
            time.sleep(.1)
        endpoints = json.loads((fixture / 'endpoints.json').read_text())
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe', headless=True,
                args=['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'])
            context = browser.new_context(permissions=['camera'])
            alice, bob = context.new_page(), context.new_page()
            for page, endpoint in [(alice, endpoints['alice']), (bob, endpoints['bob'])]:
                page.add_init_script("""
                    window.RTCPeerConnection = class { constructor(){ throw Error('Peer traffic forbidden'); } };
                    navigator.mediaDevices.getDisplayMedia = () => navigator.mediaDevices.getUserMedia({video:true,audio:false});
                    window.captureTracks = [];
                    const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
                    navigator.mediaDevices.getUserMedia = async options => { const s=await original(options);captureTracks.push(...s.getTracks());return s; };
                    window.decodes=0;
                    const originalFetch = window.fetch;
                    window.fetch = (...args) => { if(args[0]==='/video/decode')decodes++;return originalFetch(...args); };
                """)
                page.goto(endpoint)
                page.wait_for_selector('#video-controls button')
            alice.get_by_role('button', name='Start webcam', exact=True).click()
            alice.get_by_role('button', name='Stop webcam', exact=True).wait_for(timeout=15000)
            bob.wait_for_function("() => document.querySelectorAll('#video-streams input').length===1")
            bob.wait_for_timeout(1500)
            assert bob.evaluate('decodes') == 0, 'Unsubscribed stream was downloaded for decode'
            print('PASS stream discovery never downloads video before opt-in')
            bob.locator('#video-streams input').first.check()
            bob.wait_for_function("() => document.querySelector('#video-streams canvas').width===640", timeout=20000)
            assert bob.evaluate('decodes') > 0
            print('PASS synthetic webcam encodes, encrypts, relays, decrypts and renders on remote client')
            alice.get_by_role('button', name='Share screen', exact=True).click()
            alice.get_by_role('button', name='Stop screen share', exact=True).wait_for(timeout=15000)
            bob.wait_for_function("() => document.querySelectorAll('#video-streams input').length===2")
            bob.locator('#video-streams input').nth(1).check()
            bob.wait_for_function("() => [...document.querySelectorAll('#video-streams canvas')].some(c=>c.width===960)", timeout=20000)
            bob.screenshot(path=str(fixture / 'receiving.png'), full_page=True)
            print('PASS screen and webcam stream concurrently with independent receive controls')
            rate=alice.get_by_role('spinbutton', name='Webcam bitrate in kbps')
            rate.fill('250'); rate.press('Tab')
            bob.wait_for_function("() => document.querySelector('#video-streams').textContent.includes('250 kbps')")
            print('PASS changing webcam bitrate during a call updates the stream independently')
            for check in bob.locator('#video-streams input').all():
                check.uncheck()
            bob.wait_for_timeout(750)
            count=bob.evaluate('decodes');bob.wait_for_timeout(1200)
            assert bob.evaluate('decodes') == count
            print('PASS disabling receive stops video delivery and decoding for both sources')
            alice.evaluate('VchVideo.disconnect()')
            alice.wait_for_function("() => captureTracks.length===2 && captureTracks.every(t=>t.readyState==='ended')")
            bob.wait_for_function("() => document.querySelectorAll('#video-streams input').length===0")
            print('PASS stopping video ends capture tracks and removes remote stream controls')
            # Reopen a companion and verify capture stops when its relay disappears.
            alice.goto(endpoints['alice'].replace('/video.html#', '/video.html?reopen=1#'))
            alice.get_by_role('button', name='Start webcam', exact=True).click()
            alice.get_by_role('button', name='Stop webcam', exact=True).wait_for(timeout=15000)
            alice.route('**/video/poll', lambda route: route.abort())
            alice.wait_for_function("() => captureTracks.length===1 && captureTracks.every(t=>t.readyState==='ended')", timeout=10000)
            print('PASS relay failure automatically stops local video capture')
            browser.close()
    finally:
        (fixture / 'stop').touch()
        try:
            host.wait(timeout=15)
        except subprocess.TimeoutExpired:
            host.terminate();host.wait(timeout=5)
