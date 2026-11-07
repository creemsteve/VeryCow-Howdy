package chat.vch;

import chat.vch.Models.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.bouncycastle.crypto.agreement.srp.SRP6Server;

public final class ChatServer implements AutoCloseable {
    private final Vault vault;
    public final ServerState state;
    private HttpsServer http;
    private ExecutorService workers;
    private record Challenge(String nonce, String ip, long expires, SRP6Server srp) {}
    private record AuthenticatedJoin(String ip, long expires, byte[] key) {}
    private record Session(String account, String ip, long expires) {}
    private record Presence(String channel, long expires) {}
    private record Floor(String account, long expires) {}
    private record Signal(String from, String channel, JsonObject data, long expires) {}
    private final Map<String,Challenge> challenges = new HashMap<>();
    private final Map<String,AuthenticatedJoin> authenticatedJoins = new HashMap<>();
    private final JoinAttemptLimiter joinAttempts;
    private final Map<String,String> pendingAdmissions = new HashMap<>();
    private final Map<String,Session> sessions = new HashMap<>();
    private final Map<String,Presence> presence = new HashMap<>();
    private final Map<String,Floor> floors = new HashMap<>();
    private final Map<String,List<Signal>> signals = new HashMap<>();
    private final Map<String,Long> lastSend = new HashMap<>();
    private final Map<String,long[]> rates = new HashMap<>();
    public java.util.function.Consumer<String> log = s -> {};
    public ChatServer(Vault vault) throws Exception { this(vault,new JoinAttemptLimiter()); }
    ChatServer(Vault vault, JoinAttemptLimiter joinAttempts) throws Exception {
        this.vault = vault;
        this.joinAttempts = joinAttempts;
        if (vault.data().has("server")) {
            JsonObject stored=vault.data().getAsJsonObject("server");
            state = Json.GSON.fromJson(stored, ServerState.class);
            if(!stored.has("joinProtocol")){if(state.port==8443)state.port=ServerAddress.DEFAULT_PORT;state.joinProtocol=2;save();}
        }
        else {
            state = new ServerState(); state.tlsStore = Tls.create();
            Channel text = new Channel(); state.channels.add(text);
            Channel voice = new Channel(); voice.name = "Lounge"; voice.type = "voice"; state.channels.add(voice); save();
        }
        if(ensureChannelKeys())save();
    }
    public synchronized void setPassword(char[] password) throws Exception {
        try { if (password.length < 12) throw new IOException("Server password requires at least 12 characters");
            boolean replacing=state.passwordVerifier!=null;
            byte[] salt = Crypto.random(16); state.passwordSalt = Crypto.b64(salt); state.passwordVerifier = Crypto.b64(Crypto.passwordKey(password, salt));
            if(replacing)rotateAllChannelKeys();save();
            sessions.clear();
            challenges.clear(); authenticatedJoins.values().forEach(j -> Arrays.fill(j.key,(byte)0));authenticatedJoins.clear();
        } finally { Arrays.fill(password, '\0'); }
    }
    public synchronized void save() throws Exception { ensureChannelKeys();vault.data().add("server", Json.GSON.toJsonTree(state)); vault.save(); }
    private boolean ensureChannelKeys(){
        boolean changed=false;
        for(Channel c:state.channels){
            String policy=Crypto.hex(Crypto.hash(Crypto.utf(c.type+"|"+String.join("|",new TreeSet<>(c.allow)))));
            ChannelKeySet keys=state.channelKeys.get(c.id);
            if(keys==null){keys=new ChannelKeySet();keys.accessPolicy=policy;state.channelKeys.put(c.id,keys);changed=true;}
            else if(!policy.equals(keys.accessPolicy)){rotate(keys);keys.accessPolicy=policy;changed=true;}
        }
        return changed;
    }
    private void rotate(ChannelKeySet keys){keys.previous.add(keys.current);keys.current=Crypto.b64(Crypto.random(32));keys.revision++;}
    private void rotateAllChannelKeys(){ensureChannelKeys();for(Channel c:state.channels)rotate(state.channelKeys.get(c.id));}
    public synchronized void rotateChannelKey(String channelId)throws Exception{
        ensureChannelKeys();if(state.channels.stream().noneMatch(c->c.id.equals(channelId)))throw new IOException("Channel unavailable");
        rotate(state.channelKeys.get(channelId));save();log.accept("Channel encryption key rotated");
    }
    private JsonObject keysFor(List<Channel> channels){
        JsonObject keys=new JsonObject();for(Channel c:channels){ChannelKeySet k=state.channelKeys.get(c.id);keys.add(c.id,Json.obj("current",k.current,"previous",k.previous,"revision",k.revision));}return keys;
    }
    public synchronized void start(String bind) throws Exception {
        if (http != null) return;
        if (state.passwordVerifier == null) throw new IOException("Set a server join password first");
        http = HttpsServer.create(new InetSocketAddress(bind, state.port), 64);
        http.setHttpsConfigurator(new HttpsConfigurator(Tls.server(state.tlsStore)));
        workers = new ThreadPoolExecutor(4, 16, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.AbortPolicy());
        http.setExecutor(workers); http.createContext("/api/", this::handle); http.start(); log.accept("Listening on " + bind + ":" + state.port);
    }
    public synchronized boolean running() { return http != null; }
    private void handle(HttpExchange x) throws IOException {
        int status = 200; JsonObject result;
        String admission=null,admissionIp=x.getRemoteAddress().getAddress().getHostAddress();
        try {
            if(x.getRequestURI().getPath().equals("/api/challenge")) {
                synchronized(this){
                    String ip=x.getRemoteAddress().getAddress().getHostAddress();
                    if(state.bannedIps.contains(ip))throw new Failure(403,"Address banned");
                    admitJoin(ip);
                    admission=Crypto.b64(Crypto.random(16));pendingAdmissions.put(ip,admission);
                }
            }
            if (!x.getRequestMethod().equals("POST")) throw new Failure(405, "POST required");
            byte[] bytes = x.getRequestBody().readNBytes(225001);
            if (bytes.length > 225000) throw new Failure(413, "Request too large");
            JsonObject input = Json.parse(bytes);
            synchronized (this) { result = route(x, input, admission); }
        } catch (Failure e) { status = e.code; result = Json.obj("error", e.getMessage());if(e.retryAfter>0){x.getResponseHeaders().set("Retry-After",Integer.toString(e.retryAfter));result.addProperty("retryAfterSeconds",e.retryAfter);} }
        catch (Exception e) { status = 400; result = Json.obj("error", "Invalid request"); }
        finally{if(admission!=null)synchronized(this){pendingAdmissions.remove(admissionIp,admission);}}
        byte[] response = Json.bytes(result); x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store"); x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.sendResponseHeaders(status, response.length); try (OutputStream out = x.getResponseBody()) { out.write(response); } finally { x.close(); }
    }
    private JsonObject route(HttpExchange x, JsonObject in, String admission) throws Exception {
        String ip = x.getRemoteAddress().getAddress().getHostAddress(), path = x.getRequestURI().getPath(); long now = System.currentTimeMillis();
        cleanup(now);
        if (state.bannedIps.contains(ip)) throw new Failure(403, "Address banned");
        rate(ip, 1200, now);
        if (path.equals("/api/challenge")) {
            if(admission==null||!admission.equals(pendingAdmissions.get(ip)))throw new Failure(429,"A newer join attempt replaced this request; try again in 10 seconds",10);
            if (challenges.size() > 2048) throw new Failure(503, "Try again later");
            String nonce = Crypto.b64(Crypto.random(32));
            SRP6Server srp=JoinAuth.server(Crypto.unb64(state.passwordSalt),JoinAuth.identity(state.id,Tls.fingerprint(state.tlsStore)),Crypto.unb64(state.passwordVerifier));
            String publicB=srp.generateServerCredentials().toString(16);
            challenges.put(nonce, new Challenge(nonce, ip, now + 120000, srp));
            return Json.obj("server", state.id, "name", state.name, "nonce", nonce, "salt", state.passwordSalt, "bits", state.hashBits, "algorithm", "SHA-256","auth",JoinAuth.PROTOCOL,"B",publicB,"keyDistribution","server-v1");
        }
        if(path.equals("/api/auth")) {
            String nonce=Json.str(in,"nonce");Challenge challenge=challenges.get(nonce);
            if(challenge==null||!challenge.ip.equals(ip)){admitJoin(ip);throw new Failure(401,"Password handshake expired");}
            challenges.remove(nonce);joinAttempts.continued(ip);
            try{
                challenge.srp.calculateSecret(JoinAuth.number(Json.str(in,"A")));
                if(!challenge.srp.verifyClientEvidenceMessage(JoinAuth.number(Json.str(in,"M1"))))throw new Failure(401,"Authentication failed");
                String evidence=challenge.srp.calculateServerEvidenceMessage().toString(16);
                byte[] key=JoinAuth.sessionKey(challenge.srp.calculateSessionKey());
                authenticatedJoins.put(nonce,new AuthenticatedJoin(ip,now+30000,key));
                return Json.obj("M2",evidence);
            }catch(Failure e){throw e;}catch(Exception e){throw new Failure(401,"Authentication failed");}
        }
        if (path.equals("/api/login")) {
            String nonce = Json.str(in, "nonce"), publicKey = Json.str(in, "publicKey"), solution = Json.str(in, "solution");
            AuthenticatedJoin join=authenticatedJoins.get(nonce);
            if(join==null||!join.ip.equals(ip)){admitJoin(ip);throw new Failure(401,"Password handshake required");}
            authenticatedJoins.remove(nonce);joinAttempts.continued(ip);
            try {
            if (publicKey.length() > 5000) throw new Failure(400, "Invalid identity");
            String hash = Crypto.account(state.id, publicKey);
            if (state.bannedHashes.contains(hash)) throw new Failure(403, "Account banned");
            String transcript = JoinAuth.transcript(state.id,Tls.fingerprint(state.tlsStore),nonce,publicKey,solution);
            if (!Crypto.proof(nonce + "|" + hash, solution, state.hashBits)
                || !java.security.MessageDigest.isEqual(Crypto.hmac(join.key, Crypto.utf(transcript)), Crypto.unb64(Json.str(in, "proof")))
                || !Crypto.verify(publicKey, Crypto.utf(transcript), Json.str(in, "signature"))) throw new Failure(401, "Authentication failed");
            if (!state.accounts.containsKey(hash)) { Account a = new Account(); a.hash = hash; a.publicKey = publicKey; state.accounts.put(hash, a); save(); log.accept("Registered " + hash.substring(0, 16)); }
            String token = Crypto.b64(Crypto.random(32)); sessions.put(token, new Session(hash, ip, now + 8 * 3600000L));
            return Json.obj("token", token, "hash", hash, "server", state.id);
            }finally{Arrays.fill(join.key,(byte)0);}
        }
        String authorization = x.getRequestHeaders().getFirst("Authorization");
        Session s = sessions.get(authorization == null ? "" : authorization.replaceFirst("^Bearer ", ""));
        if (s == null || !s.ip.equals(ip)) throw new Failure(401, "Reconnect to the server");
        if (state.bannedHashes.contains(s.account)) throw new Failure(403, "Account banned");
        switch (path) {
            case "/api/poll": {
                long after = Math.max(0, Json.num(in, "after", 0));
                List<Channel> channels = state.channels.stream().filter(c -> c.permits(s.account)).toList();
                Set<String> ids = new HashSet<>(); channels.forEach(c -> ids.add(c.id));
                List<Envelope> messages = state.messages.stream().filter(m -> m.sequence > after && ids.contains(m.channel)).limit(100).toList();
                long cursor = messages.isEmpty() ? Math.max(after, state.nextSequence - 1) : messages.get(messages.size()-1).sequence;
                return Json.obj("name", state.name, "channels", channels, "accounts", state.accounts, "messages", messages, "cursor", cursor,"channelKeys",keysFor(channels));
            }
            case "/api/keys": return Json.obj("channelKeys",keysFor(state.channels.stream().filter(c->c.permits(s.account)).toList()));
            case "/api/profile": {
                String name = Json.str(in, "name").strip(), bio = Json.str(in, "bio"), avatar = Json.str(in, "avatar");
                if (name.isEmpty() || name.length() > 40 || name.contains("<") || name.contains(">") || name.chars().anyMatch(Character::isISOControl) || bio.length() > 240) throw new Failure(400, "Invalid profile name or profile too long");
                byte[] b = Crypto.unb64(avatar);
                if (b.length > 10000 || (b.length > 0 && !MediaRules.isWebp(b))) throw new Failure(413, "Avatar must be WebP, at most 10,000 bytes");
                Account a = state.accounts.get(s.account); a.name = name; a.bio = bio; a.avatar = avatar; save(); return Json.obj("ok", true);
            }
            case "/api/send": {
                Envelope m = Json.GSON.fromJson(in, Envelope.class); Channel c = channel(m.channel, s.account, "text");
                if (!s.account.equals(m.author) || !state.id.equals(m.server) || m.id == null || !m.id.matches("[a-f0-9-]{36}")
                    || !("text".equals(m.kind) || "image".equals(m.kind)) || m.cipher == null || m.cipher.length() > 155000
                    || Math.abs(now - m.time) > 300000 || !Crypto.verify(state.accounts.get(s.account).publicKey, m.signedBytes(), m.signature)) throw new Failure(400, "Invalid message");
                if (state.messages.stream().anyMatch(e -> e.id.equals(m.id) && e.author.equals(m.author))) return Json.obj("ok", true);
                if(m.keyRevision!=state.channelKeys.get(c.id).revision)throw new Failure(409,"Channel encryption changed; refresh and try again");
                String throttle = s.account + "/" + c.id;
                if (now - lastSend.getOrDefault(throttle, 0L) < c.slowModeSeconds * 1000L) throw new Failure(429, "Channel slow mode is active");
                m.sequence = state.nextSequence++; state.messages.add(m); lastSend.put(throttle, now);
                if (state.messages.size() > 10000) state.messages.remove(0); save(); return Json.obj("ok", true, "sequence", m.sequence);
            }
            case "/api/voice": {
                Channel c = channel(Json.str(in, "channel"), s.account, "voice"); String action = Json.str(in, "action");
                if (action.equals("leave")) { presence.remove(s.account); floors.entrySet().removeIf(e -> e.getValue().account.equals(s.account)); signals.remove(s.account); return Json.obj("ok", true); }
                long count = presence.entrySet().stream().filter(e -> e.getValue().channel.equals(c.id) && !e.getKey().equals(s.account)).count();
                if (count >= c.maxMembers) throw new Failure(409, "Voice channel is full");
                presence.put(s.account, new Presence(c.id, now + 10000)); Floor floor = floors.get(c.id);
                if (action.equals("claim") && (floor == null || floor.account.equals(s.account))) { floor = new Floor(s.account, now + 3500); floors.put(c.id, floor); }
                if (action.equals("release") && floor != null && floor.account.equals(s.account)) { floors.remove(c.id); floor = null; }
                if (action.equals("signal")) {
                    String to = Json.str(in, "to"); Presence target = presence.get(to);
                    if (target == null || !target.channel.equals(c.id) || !c.permits(to)) throw new Failure(404, "Peer left");
                    JsonObject data = in.getAsJsonObject("data"); if (Json.bytes(data).length > 32000) throw new Failure(413, "Signal too large");
                    List<Signal> q = signals.computeIfAbsent(to, k -> new ArrayList<>());
                    if (q.size() > 100) throw new Failure(429, "Signal queue full"); q.add(new Signal(s.account, c.id, data, now + 15000));
                }
                List<String> peers = presence.entrySet().stream().filter(e -> e.getValue().channel.equals(c.id) && !e.getKey().equals(s.account)).map(Map.Entry::getKey).toList();
                List<Signal> inbox = action.equals("signal") ? null : signals.remove(s.account);
                return Json.obj("peers", peers, "signals", inbox == null ? List.of() : inbox.stream().filter(e -> e.channel.equals(c.id)).toList(),
                    "floor", floor == null ? "" : floor.account, "bitRate", c.bitRate,"channelKeys",keysFor(List.of(c)));
            }
            default: throw new Failure(404, "Unknown endpoint");
        }
    }
    private Channel channel(String id, String hash, String type) throws Failure {
        return state.channels.stream().filter(c -> c.id.equals(id) && c.permits(hash) && c.type.equals(type)).findFirst().orElseThrow(() -> new Failure(403, "Channel unavailable"));
    }
    private void rate(String key, int limit, long now) throws Failure {
        long[] r = rates.computeIfAbsent(key, k -> new long[]{now, 0});
        if (now - r[0] > 60000) { r[0] = now; r[1] = 0; } if (++r[1] > limit) throw new Failure(429, "Too many requests");
    }
    private void cleanup(long now) {
        challenges.values().removeIf(c -> c.expires < now); sessions.values().removeIf(s -> s.expires < now);
        authenticatedJoins.values().removeIf(j->{if(j.expires>=now)return false;Arrays.fill(j.key,(byte)0);return true;});
        presence.values().removeIf(p -> p.expires < now); floors.values().removeIf(f -> f.expires < now);
        signals.values().forEach(q -> q.removeIf(e -> e.expires < now)); signals.values().removeIf(List::isEmpty);
        rates.values().removeIf(r -> now - r[0] > 120000);
    }
    public synchronized void ban(String value, boolean ip) throws Exception {
        Set<String> affected=new HashSet<>();sessions.values().stream().filter(s->ip?s.ip.equals(value):s.account.equals(value)).forEach(s->affected.add(s.account));if(!ip)affected.add(value);
        (ip ? state.bannedIps : state.bannedHashes).add(value);rotateAllChannelKeys();save();
        sessions.entrySet().removeIf(e -> ip ? e.getValue().ip.equals(value) : e.getValue().account.equals(value));
        presence.keySet().removeIf(affected::contains);floors.values().removeIf(f->affected.contains(f.account));signals.keySet().removeIf(affected::contains); log.accept("Ban added");
    }
    private void admitJoin(String ip)throws Failure{
        int wait=joinAttempts.acquire(ip);if(wait>0)throw new Failure(429,"Wait "+wait+" seconds before trying to join again",wait);
        // Every new admission, including invalid direct-auth attempts, replaces unfinished older work.
        pendingAdmissions.remove(ip);challenges.values().removeIf(c->c.ip.equals(ip));
        authenticatedJoins.values().removeIf(j->{if(!j.ip.equals(ip))return false;Arrays.fill(j.key,(byte)0);return true;});
    }
    public synchronized void close() { if (http != null) { http.stop(0); http = null; workers.shutdownNow(); sessions.clear(); presence.clear(); floors.clear(); signals.clear(); challenges.clear();authenticatedJoins.values().forEach(j->Arrays.fill(j.key,(byte)0));authenticatedJoins.clear(); log.accept("Server stopped"); } }
    private static final class Failure extends Exception { final int code,retryAfter; Failure(int code, String message) { this(code,message,0); } Failure(int code,String message,int retryAfter){super(message);this.code=code;this.retryAfter=retryAfter;} }
}
