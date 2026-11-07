package chat.vch;

import chat.vch.Models.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** In-process desktop voice: Java Sound, local DAC and native WebRTC data channels. */
final class DesktopVoice implements AutoCloseable {
    record State(boolean ready, boolean transmitting, int connected, int participants, String message) {}
    private final VoiceSession session;
    private final VoiceAudio audio;
    private final String account, microphone, speakers, ice;
    private final int inputGain, outputGain;
    private final Consumer<State> listener;
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(daemon("vch-voice-control"));
    private final ThreadPoolExecutor codec = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), daemon("vch-voice-codec"), new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor playback = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), daemon("vch-voice-playback"), new ThreadPoolExecutor.DiscardPolicy());
    private final AtomicBoolean closed = new AtomicBoolean(), encoding = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile boolean ready, pressed, transmitting;
    private volatile String floor = "";
    private volatile long leaseAt;
    private VoiceTransport transport;
    private int participants;
    private volatile int connected;

    DesktopVoice(ClientCore core, SavedServer server, Channel channel, Consumer<State> listener) throws Exception {
        this(core, server, channel, models(), VoiceAudio.system(), listener);
    }

    // Injected audio lets integration tests exercise the complete native path without using a real microphone.
    DesktopVoice(ClientCore core, SavedServer server, Channel channel, Path models, VoiceAudio audio, Consumer<State> listener) {
        session = new VoiceSession(core, server, channel, models); this.audio = audio; this.listener = listener;
        account = core.account(server); microphone = core.state.microphone; speakers = core.state.speakers;
        inputGain = core.state.inputGain; outputGain = core.state.outputGain; ice = core.state.iceUrls;
    }

    static Path models() throws Exception {
        Path app = Path.of(DesktopVoice.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().getParent();
        return Path.of(System.getProperty("vch.models", Files.exists(app.resolve("models/dac.json")) ? app.resolve("models").toString() : "build/models"));
    }

    void start() {
        dispatch(() -> {
            publish("Loading the local DAC model…");
            try {
                session.request("/codec", Json.obj("op", "init"));
                synchronized (this) {
                    if (closed.get()) return;
                    audio.open(microphone, speakers);
                }
                if (closed.get()) return;
                transport = new VoiceTransport(ice, (id, data) -> {
                    try { signal(Json.obj("action", "signal", "to", id, "data", data)); }
                    catch (Exception e) { fail(e); }
                }, this::receive, this::dispatch);
                ready = true;
                Thread capture = daemon("vch-voice-capture").newThread(this::capture); capture.start();
                heartbeat();
                control.scheduleWithFixedDelay(this::heartbeat, 800, 800, TimeUnit.MILLISECONDS);
            } catch (Exception | LinkageError e) { fail(e); }
        });
    }

    void press() {
        if (!ready || closed.get() || pressed) return;
        pressed = true;
        long ticket = generation.get();
        dispatch(() -> {
            try {
                if (!pressed || ticket != generation.get()) return;
                apply(signal(Json.obj("action", "claim")));
                if (!pressed || closed.get() || ticket != generation.get()) { signal(Json.obj("action", "release")); return; }
                if (!floor.equals(account)) { publish("Another member is speaking · release and try again"); return; }
                synchronized (this) {
                    if (!pressed || closed.get() || ticket != generation.get()) return;
                    audio.silence(); transmitting = true; audio.recording(true);
                }
                publish("You are speaking · release to mute");
            } catch (Exception e) { fail(e); }
        });
    }

    void release() {
        pressed = false; stopLocal();
        dispatch(() -> {
            try { if (ready) { apply(signal(Json.obj("action", "release"))); publish("Connected · hold to talk"); } }
            catch (Exception e) { fail(e); }
        });
    }

    private synchronized void stopLocal() {
        transmitting = false; generation.incrementAndGet(); audio.recording(false);
    }

    private void heartbeat() {
        if (!ready || closed.get()) return;
        try {
            apply(signal(Json.obj("action", transmitting && pressed ? "claim" : "heartbeat")));
            publish(transmitting ? "You are speaking · release to mute" : floor.isEmpty() ? "Connected · hold to talk" : "Another member is speaking");
        } catch (Exception e) { fail(e); }
    }

    private JsonObject signal(JsonObject in) throws Exception { return session.request("/signal", in); }

    private void apply(JsonObject state) throws Exception {
        floor = Json.str(state, "floor"); leaseAt = System.nanoTime();
        if (!floor.equals(account) && transmitting) stopLocal();
        Set<String> members = new HashSet<>(); for (JsonElement id : state.getAsJsonArray("peers")) members.add(id.getAsString());
        participants = members.size(); transport.update(members, account);
        for (JsonElement item : state.getAsJsonArray("signals")) {
            JsonObject entry = item.getAsJsonObject(); String from = Json.str(entry, "from");
            if (members.contains(from)) transport.accept(from, entry.getAsJsonObject("data"));
        }
        connected = transport.connected();
    }

    private void capture() {
        byte[] block = new byte[16000];
        while (!closed.get()) {
            try {
                if (!transmitting) { Thread.sleep(15); continue; }
                long ticket = generation.get(); int length = audio.read(block);
                if (length <= 0 || !canSend(ticket)) continue;
                if (!encoding.compareAndSet(false, true)) continue;
                byte[] pcm = VoiceAudio.toFloat(block, length, inputGain);
                try { codec.execute(() -> {
                    try {
                        if (!canSend(ticket)) return;
                        byte[] envelope = Json.bytes(session.request("/encode", Json.obj("data", Crypto.b64(pcm))));
                        dispatch(() -> {
                            if (canSend(ticket)) try { transport.send(envelope); } catch (Exception e) { fail(e); }
                        });
                    } catch (Exception e) { if (canSend(ticket)) fail(e); }
                    finally { encoding.set(false); }
                }); } catch (RejectedExecutionException busy) { encoding.set(false); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (Exception e) { if (!closed.get()) fail(e); break; }
        }
    }

    private boolean canSend(long ticket) {
        return !closed.get() && transmitting && pressed && ticket == generation.get()
                && floor.equals(account) && System.nanoTime() - leaseAt < TimeUnit.SECONDS.toNanos(3);
    }

    private void receive(String author, byte[] bytes) {
        if (closed.get() || !ready || transmitting || !author.equals(floor)) return;
        try { codec.execute(() -> {
            try {
                if (closed.get() || transmitting || !author.equals(floor)) return;
                JsonObject decoded = session.request("/decode", Json.obj("author", author, "envelope", Json.parse(bytes)));
                byte[] pcm = VoiceAudio.toPcm(Crypto.unb64(Json.str(decoded, "data")), outputGain);
                playback.execute(() -> { if (!closed.get() && !transmitting && author.equals(floor)) audio.play(pcm); });
            } catch (Exception e) { if (!closed.get()) publish("Voice frame dropped: " + e.getMessage()); }
        }); } catch (RejectedExecutionException busy) { /* Drop late blocks rather than grow latency. */ }
    }

    private void dispatch(Runnable task) {
        if (closed.get()) return;
        try { control.execute(() -> { if (!closed.get()) task.run(); }); }
        catch (RejectedExecutionException stopped) { /* Late native callback after leave. */ }
    }

    private void publish(String message) {
        listener.accept(new State(ready && !closed.get(), transmitting, closed.get() ? 0 : connected, participants, message));
    }

    private void fail(Throwable e) {
        if (closed.get()) return;
        close(); listener.accept(new State(false, false, 0, 0, "Voice unavailable: " + e.getMessage()));
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ready = false; pressed = false; stopLocal();
        // Stop physical devices immediately; queued network/model cleanup stays off Swing's event thread.
        audio.close(); codec.shutdownNow(); playback.shutdownNow();
        control.execute(() -> {
            try { if (transport != null) transport.close(); }
            finally {
                session.close();
                session.whenClosed().whenComplete((ignored, error) -> stopped.complete(null));
                control.shutdown();
            }
        });
    }

    CompletableFuture<Void> stopped() { return stopped; }

    private static ThreadFactory daemon(String name) {
        return task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; };
    }
}
