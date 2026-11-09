package chat.vch;

import chat.vch.Models.*;
import com.google.gson.*;
import java.util.*;

/** Ephemeral video relay. Called under ChatServer's lock after voice membership checks. */
final class VideoRelay {
    static final int MAX_KBPS = 1000;
    static final class Rejected extends Exception {
        final int status;
        Rejected(int status, String message) { super(message); this.status = status; }
    }
    private record Frame(Envelope envelope, long cursor, long expires) {}
    private record Charge(long time, int bytes) {}
    private static final class Stream {
        String id = UUID.randomUUID().toString(), channel, author, source;
        int kbps;
        long expires;
        final Deque<Frame> frames = new ArrayDeque<>();
        final Deque<Charge> charges = new ArrayDeque<>();
        final Set<String> seen = new LinkedHashSet<>();
    }
    private final Map<String, Stream> streams = new LinkedHashMap<>();
    private long cursor;

    JsonObject request(String account, Channel channel, ServerState state, JsonObject in, long now) throws Exception {
        String action = Json.str(in, "action"), id = Json.str(in, "stream");
        if (action.equals("start")) {
            String source = Json.str(in, "source");
            if (!Set.of("screen", "camera").contains(source)) throw new Rejected(400, "Invalid video source");
            int kbps = bitrate(in);
            Stream existing = streams.values().stream().filter(s -> s.author.equals(account) && s.source.equals(source)).findFirst().orElse(null);
            if (existing != null) throw new Rejected(409, "Stop the existing stream before starting another");
            Stream s = new Stream(); s.channel = channel.id; s.author = account; s.source = source; s.kbps = kbps; s.expires = now + 10000;
            streams.put(s.id, s);
            return Json.obj("stream", s.id, "kbps", s.kbps);
        }
        if (action.equals("poll")) {
            JsonArray wanted = in.has("receive") ? in.getAsJsonArray("receive") : new JsonArray();
            if (wanted.size() > 64) throw new Rejected(400, "Too many video subscriptions");
            Set<String> receive = new HashSet<>(); for (JsonElement item : wanted) receive.add(item.getAsString());
            long after = Json.num(in, "after", -1);
            JsonArray listings = new JsonArray(), frames = new JsonArray();
            for (Stream s : streams.values()) {
                if (!s.channel.equals(channel.id)) continue;
                if (s.author.equals(account)) s.expires = now + 10000;
                listings.add(Json.obj("id", s.id, "author", s.author, "source", s.source, "kbps", s.kbps));
                if (s.author.equals(account) || !receive.contains(s.id)) continue;
                for (Frame frame : s.frames) if (frame.cursor > after && frame.expires > now && frame.envelope.keyRevision == state.channelKeys.get(channel.id).revision)
                    frames.add(Json.obj("stream", s.id, "envelope", frame.envelope));
            }
            return Json.obj("streams", listings, "frames", frames, "cursor", cursor);
        }
        Stream s = streams.get(id);
        if (s == null || !s.author.equals(account) || !s.channel.equals(channel.id)) throw new Rejected(403, "Video stream unavailable");
        if (action.equals("stop")) { streams.remove(id); return Json.obj("ok", true); }
        if (action.equals("configure")) { s.kbps = bitrate(in); return Json.obj("kbps", s.kbps); }
        if (!action.equals("media")) throw new Rejected(400, "Unknown video action");
        Envelope e = Json.GSON.fromJson(in.get("envelope"), Envelope.class);
        if (e == null || e.id == null || !e.id.matches("[a-f0-9-]{36}") || !state.id.equals(e.server) || !channel.id.equals(e.channel)
                || !account.equals(e.author) || !("video/" + s.id).equals(e.kind) || e.cipher == null || e.cipher.length() > 166668
                || e.signature == null || e.signature.length() > 10000 || e.time < now - 12000 || e.time > now + 12000
                || !Crypto.verify(state.accounts.get(account).publicKey, e.signedBytes(), e.signature)) throw new Rejected(400, "Invalid video frame");
        if (e.keyRevision != state.channelKeys.get(channel.id).revision) throw new Rejected(409, "Video encryption changed; refresh the channel key");
        if (s.seen.contains(e.id)) return Json.obj("ok", true);
        int bytes = Crypto.unb64(e.cipher).length;
        while (!s.charges.isEmpty() && s.charges.peekFirst().time <= now - 1000) s.charges.removeFirst();
        // Strict sliding one-second budget, independent for camera and screen. Includes encrypted payload overhead.
        if (bytes + s.charges.stream().mapToInt(Charge::bytes).sum() > s.kbps * 125) throw new Rejected(429, "Video bitrate limit reached");
        s.charges.addLast(new Charge(now, bytes)); s.seen.add(e.id);
        if (s.seen.size() > 256) s.seen.remove(s.seen.iterator().next());
        if (s.frames.size() == 12) s.frames.removeFirst();
        s.frames.addLast(new Frame(e, ++cursor, now + 1500)); s.expires = now + 10000;
        return Json.obj("ok", true);
    }
    private int bitrate(JsonObject in) throws Rejected {
        long value = Json.num(in, "kbps", 500);
        if (value < 50 || value > MAX_KBPS) throw new Rejected(400, "Video bitrate must be 50–1000 kbps");
        return (int)value;
    }
    void cleanup(long now) { streams.values().removeIf(s -> s.expires < now); for (Stream s : streams.values()) s.frames.removeIf(f -> f.expires < now); }
    void leave(String account) { streams.values().removeIf(s -> s.author.equals(account)); }
    void clear() { streams.clear(); }
}
