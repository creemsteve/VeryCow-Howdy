package chat.vch;

import com.google.gson.*;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Desktop data-only WebRTC. All native handles are owned by the voice control thread. */
final class VoiceTransport implements AutoCloseable {
    private final HeadlessAudioDeviceModule audio = new HeadlessAudioDeviceModule();
    private final PeerConnectionFactory factory;
    private final RTCConfiguration config = new RTCConfiguration();
    private final Map<String, Peer> peers = new HashMap<>();
    private final BiConsumer<String, JsonObject> signal;
    private final BiConsumer<String, byte[]> receive;
    private final java.util.function.Consumer<Runnable> dispatch;

    VoiceTransport(String ice, BiConsumer<String, JsonObject> signal,
                   BiConsumer<String, byte[]> receive, java.util.function.Consumer<Runnable> dispatch) {
        try { factory = new PeerConnectionFactory(audio); }
        catch (Throwable e) { audio.dispose(); throw e; }
        this.signal = signal; this.receive = receive; this.dispatch = dispatch;
        for (String url : ice.split(",")) if (!url.isBlank()) {
            RTCIceServer server = new RTCIceServer(); server.urls.add(url.strip()); config.iceServers.add(server);
        }
    }

    void update(Set<String> members, String account) throws Exception {
        for (String id : new ArrayList<>(peers.keySet())) if (!members.contains(id)) peers.remove(id).close();
        for (String id : members) if (account.compareTo(id) < 0 && !peers.containsKey(id)) {
            Peer p = peer(id); RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = false; init.maxRetransmits = 0;
            p.attach(p.pc.createDataChannel("vch-dac", init));
            p.describe(true);
        }
    }

    void accept(String id, JsonObject data) throws Exception {
        Peer p = peer(id);
        if (data.has("description")) {
            JsonObject desc = data.getAsJsonObject("description");
            String type = Json.str(desc, "type");
            if (!type.equals("offer") && !type.equals("answer")) throw new IOException("Invalid voice description");
            p.set(new RTCSessionDescription(type.equals("offer") ? RTCSdpType.OFFER : RTCSdpType.ANSWER,
                    Json.str(desc, "sdp")), false);
            p.remoteReady = true;
            for (RTCIceCandidate candidate : p.earlyIce) p.pc.addIceCandidate(candidate);
            p.earlyIce.clear();
            if (type.equals("offer")) p.describe(false);
        } else if (data.has("candidate")) {
            JsonObject c = data.getAsJsonObject("candidate");
            RTCIceCandidate candidate = new RTCIceCandidate(Json.str(c, "sdpMid"),
                    (int) Json.num(c, "sdpMLineIndex", 0), Json.str(c, "candidate"));
            if (p.remoteReady) p.pc.addIceCandidate(candidate);
            else if (p.earlyIce.size() < 64) p.earlyIce.add(candidate);
        }
    }

    void send(byte[] data) throws Exception {
        for (Peer p : peers.values()) if (p.dc != null && p.dc.getState() == RTCDataChannelState.OPEN
                && p.dc.getBufferedAmount() < 32000) p.dc.send(new RTCDataChannelBuffer(ByteBuffer.wrap(data), false));
    }

    int connected() {
        return (int) peers.values().stream().filter(p -> p.dc != null && p.dc.getState() == RTCDataChannelState.OPEN).count();
    }

    private Peer peer(String id) { return peers.computeIfAbsent(id, Peer::new); }

    private final class Peer implements AutoCloseable {
        final String id;
        final RTCPeerConnection pc;
        final List<RTCIceCandidate> earlyIce = new ArrayList<>();
        RTCDataChannel dc;
        boolean remoteReady, closed;
        Peer(String id) {
            this.id = id;
            pc = factory.createPeerConnection(config, new PeerConnectionObserver() {
                public void onIceCandidate(RTCIceCandidate c) {
                    dispatch.accept(() -> { if (!closed) signal.accept(id, Json.obj("candidate",
                            Json.obj("candidate", c.sdp, "sdpMid", c.sdpMid, "sdpMLineIndex", c.sdpMLineIndex))); });
                }
                public void onDataChannel(RTCDataChannel channel) {
                    dispatch.accept(() -> { if (closed) { channel.close(); channel.dispose(); } else attach(channel); });
                }
                public void onConnectionChange(RTCPeerConnectionState state) {
                    if (state == RTCPeerConnectionState.FAILED) dispatch.accept(() -> {
                        if (peers.get(id) == Peer.this) { peers.remove(id); close(); }
                    });
                }
            });
            if (pc == null) throw new IllegalStateException("Unable to create voice peer");
        }
        void attach(RTCDataChannel channel) {
            if (dc != null) { channel.close(); channel.dispose(); return; }
            dc = channel;
            dc.registerObserver(new RTCDataChannelObserver() {
                public void onBufferedAmountChange(long size) {}
                public void onStateChange() {}
                public void onMessage(RTCDataChannelBuffer buffer) {
                    // JNI owns the callback buffer; copy before returning. Bound before allocating.
                    if (buffer.binary || buffer.data.remaining() > 100000) return;
                    byte[] bytes = new byte[buffer.data.remaining()]; buffer.data.get(bytes); receive.accept(id, bytes);
                }
            });
        }
        void describe(boolean offer) throws Exception {
            CompletableFuture<RTCSessionDescription> future = new CompletableFuture<>();
            CreateSessionDescriptionObserver observer = new CreateSessionDescriptionObserver() {
                public void onSuccess(RTCSessionDescription d) { future.complete(d); }
                public void onFailure(String error) { future.completeExceptionally(new IOException(error)); }
            };
            if (offer) pc.createOffer(new RTCOfferOptions(), observer); else pc.createAnswer(new RTCAnswerOptions(), observer);
            RTCSessionDescription d = future.get(5, TimeUnit.SECONDS); set(d, true);
            signal.accept(id, Json.obj("description", Json.obj("type", offer ? "offer" : "answer", "sdp", d.sdp)));
        }
        void set(RTCSessionDescription d, boolean local) throws Exception {
            CompletableFuture<Void> future = new CompletableFuture<>();
            SetSessionDescriptionObserver observer = new SetSessionDescriptionObserver() {
                public void onSuccess() { future.complete(null); }
                public void onFailure(String error) { future.completeExceptionally(new IOException(error)); }
            };
            if (local) pc.setLocalDescription(d, observer); else pc.setRemoteDescription(d, observer);
            future.get(5, TimeUnit.SECONDS);
        }
        public void close() {
            if (closed) return; closed = true;
            if (dc != null) { dc.unregisterObserver(); dc.close(); dc.dispose(); }
            pc.close();
        }
    }

    public void close() {
        for (Peer p : peers.values()) p.close(); peers.clear(); factory.dispose(); audio.dispose();
    }
}
