package chat.vch;

import chat.vch.Models.*;
import java.net.ServerSocket;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Server-relayed signed/encrypted DAC, using synthetic PCM and an in-memory playback sink. */
public final class DesktopVoiceTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createDirectories(Path.of("build/native-voice-test/" + java.util.UUID.randomUUID()));
        try (Vault sv = new Vault(dir.resolve("server.vault"), "test password long enough".toCharArray());
             Vault av = new Vault(dir.resolve("alice.vault"), "test password long enough".toCharArray());
             Vault bv = new Vault(dir.resolve("bob.vault"), "test password long enough".toCharArray())) {
            AtomicLong clock = new AtomicLong();
            try (ChatServer host = new ChatServer(sv, new JoinAttemptLimiter(clock::get));
                 ClientCore alice = new ClientCore(av); ClientCore bob = new ClientCore(bv)) {
                try (ServerSocket port = new ServerSocket(0)) { host.state.port = port.getLocalPort(); }
                host.state.hashBits = 10; host.setPassword("test server password".toCharArray()); host.start("127.0.0.1");
                try (TorTransportTest.SocksFixture proxy = new TorTransportTest.SocksFixture(host.state.port)) {
                SavedServer a = endpoint(host), b = endpoint(host);
                a.torOnly = true; a.torSocksPort = proxy.port(); a.address = "https://voice-test.invalid:" + host.state.port;
                alice.connect(a, "test server password".toCharArray()); clock.addAndGet(10000);
                bob.connect(b, "test server password".toCharArray()); alice.poll(a); bob.poll(b);
                Channel channel = a.channels.stream().filter(c -> c.type.equals("voice")).findFirst().orElseThrow();
                SyntheticAudio aa = new SyntheticAudio(), ba = new SyntheticAudio();
                AtomicReference<DesktopVoice.State> as = new AtomicReference<>(), bs = new AtomicReference<>();
                try (DesktopVoice va = new DesktopVoice(alice, a, channel, Path.of("build/models"), aa, as::set);
                     DesktopVoice vb = new DesktopVoice(bob, b, channel, Path.of("build/models"), ba, bs::set)) {
                    va.start(); vb.start();
                    waitFor(() -> as.get() != null && bs.get() != null && as.get().connected() == 1 && bs.get().connected() == 1, as, bs);
                    check(!aa.recording && !ba.recording, "joining keeps microphone capture stopped");
                    System.out.println("PASS Tor-routed desktop and direct desktop joined the server voice channel");
                    va.press(); waitFor(() -> aa.recording && ba.blocks.get() > 0, as, bs);
                    check(ba.energy > 0.00001, "decoded signed encrypted DAC has non-silent PCM");
                    System.out.println("PASS native capture, encrypted DAC delivery, decode and playback");
                    vb.press(); Thread.sleep(500); check(!ba.recording, "second speaker denied");
                    va.release(); check(!aa.recording, "release immediately stops capture"); vb.release();
                    Thread.sleep(1000); vb.press(); waitFor(() -> ba.recording && aa.blocks.get() > 0, as, bs);
                    System.out.println("PASS speaking floor enforced and transferred between native peers");
                    vb.release(); check(!ba.recording, "focus-loss/release path stops capture");
                    va.press(); va.release(); Thread.sleep(1000); check(!aa.recording, "late claim cannot restart capture after release");
                    va.close(); vb.close(); va.stopped().get(15, TimeUnit.SECONDS); vb.stopped().get(15, TimeUnit.SECONDS);
                    check(aa.closed && ba.closed, "leave closes both audio devices");
                    System.out.println("PASS immediate PTT release, delayed claim cancellation and device cleanup");
                }
                SyntheticAudio cancelledAudio = new SyntheticAudio();
                DesktopVoice cancelled = new DesktopVoice(alice, a, channel, Path.of("build/models"), cancelledAudio, s -> {});
                cancelled.start(); cancelled.close(); cancelled.stopped().get(15, TimeUnit.SECONDS);
                check(!cancelledAudio.open, "leaving during startup cannot open a microphone later");
                System.out.println("PASS leaving during initialization cancels native audio startup");
                CountDownLatch opening = new CountDownLatch(1), finishOpen = new CountDownLatch(1);
                SyntheticAudio racingAudio = new SyntheticAudio() {
                    public void open(String mic, String speaker) {
                        opening.countDown();
                        try { if (!finishOpen.await(10, TimeUnit.SECONDS)) throw new AssertionError("Audio-open latch timed out"); }
                        catch (InterruptedException e) { throw new RuntimeException(e); }
                        super.open(mic, speaker);
                    }
                };
                DesktopVoice racing = new DesktopVoice(alice, a, channel, Path.of("build/models"), racingAudio, s -> {});
                racing.start(); check(opening.await(15, TimeUnit.SECONDS), "audio initialization reached");
                CompletableFuture<Void> close = CompletableFuture.runAsync(racing::close);
                finishOpen.countDown(); close.get(15, TimeUnit.SECONDS); racing.stopped().get(15, TimeUnit.SECONDS);
                check(racingAudio.closed && !racingAudio.open, "concurrent device open/leave cannot leak audio devices");
                System.out.println("PASS leaving concurrently with audio-device initialization closes devices");
                }
            }
        }
    }
    private static void waitFor(BooleanSupplier condition, AtomicReference<DesktopVoice.State> a, AtomicReference<DesktopVoice.State> b) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("Voice timeout: " + a.get() + " / " + b.get());
            Thread.sleep(25);
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static SavedServer endpoint(ChatServer host) throws Exception {
        SavedServer s = new SavedServer(); s.address = "https://127.0.0.1:" + host.state.port; s.fingerprint = Tls.fingerprint(host.state.tlsStore); return s;
    }
    static class SyntheticAudio implements VoiceAudio {
        volatile boolean open, closed, recording;
        volatile double energy;
        final AtomicInteger blocks = new AtomicInteger();
        int sample;
        public void open(String mic, String speaker) { open = true; }
        public void recording(boolean active) { recording = active; }
        public int read(byte[] pcm) {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return 0; }
            if (!recording || closed) return 0;
            ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            while (buffer.hasRemaining()) buffer.putShort((short) (8000 * Math.sin(2 * Math.PI * 440 * sample++ / 16000)));
            return pcm.length;
        }
        public void play(byte[] pcm) {
            ShortBuffer data = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            double sum = 0; while (data.hasRemaining()) { double n = data.get() / 32768.0; sum += n * n; }
            energy = sum / (pcm.length / 2); blocks.incrementAndGet();
        }
        public void silence() {}
        public void close() { recording = false; open = false; closed = true; }
    }
}
