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
    private final Map<String,Presence> typing = new HashMap<>();
    private final Map<String,CallInvite> calls = new HashMap<>();
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
            String policy=Crypto.hex(Crypto.hash(Crypto.utf(c.type+"|"+String.join("|",new TreeSet<>(c.allow))+"|"+Json.GSON.toJson(state.roles)+"|"+Json.GSON.toJson(new TreeSet<>(state.everyonePermissions))+"|"+Json.GSON.toJson(c.roleOverrides))));
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
    private boolean permits(Channel c,String account,String permission) {
        if (!c.permits(account)) return false;
        if (state.friendships.stream().anyMatch(f -> f.includes(account) && (c.id.equals(f.textChannel) || c.id.equals(f.voiceChannel)))) return true;
        if (state.admins.contains(account)) return true;
        boolean allowed=state.everyonePermissions.contains(permission);
        for(Role r:state.roles) if(r.members.contains(account) && r.permissions.contains(permission)) allowed=true;
        Map<String,Boolean> everyone=c.roleOverrides.get("everyone");
        if(everyone!=null && everyone.containsKey(permission)) allowed=everyone.get(permission);
        // Discord-style role overrides: combine denies first, then allows.
        boolean deny=false, allow=false;
        for(Role r:state.roles) if(r.members.contains(account)) {
            Map<String,Boolean> override=c.roleOverrides.get(r.id);
            if(override!=null && override.containsKey(permission)) { if(override.get(permission))allow=true;else deny=true; }
        }
        if(deny)allowed=false;if(allow)allowed=true;
        return allowed;
    }
    private boolean canView(Channel c,String account) { return permits(c,account,"view"); }
    private boolean canManageRoles(String account) { return state.admins.contains(account) || state.roles.stream().anyMatch(r->r.members.contains(account)&&r.permissions.contains("manage_roles")); }
    private int highestRole(String account) { for(int i=state.roles.size()-1;i>=0;i--)if(state.roles.get(i).members.contains(account))return i;return -1; }
    private Set<String> globalPermissions(String account) { Set<String> result=new HashSet<>(state.everyonePermissions);for(Role r:state.roles)if(r.members.contains(account))result.addAll(r.permissions);return result; }
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
            byte[] bytes = x.getRequestBody().readNBytes(700001);
            if (bytes.length > 700000) throw new Failure(413, "Request too large");
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
                List<Channel> channels = state.channels.stream().filter(c -> canView(c,s.account)).toList();
                Set<String> ids = new HashSet<>(); channels.forEach(c -> ids.add(c.id));
                List<Envelope> messages = state.messages.stream().filter(m -> m.sequence > after && ids.contains(m.channel)).limit(10).toList();
                long cursor = messages.isEmpty() ? Math.max(after, state.nextSequence - 1) : messages.get(messages.size()-1).sequence;
                typing.entrySet().removeIf(e -> e.getValue().expires < now);
                Map<String,String> activeTyping = new HashMap<>(); typing.forEach((account,p) -> { if (!account.equals(s.account) && ids.contains(p.channel)) activeTyping.put(account,p.channel); });
                return Json.obj("name", state.name, "description", state.description, "image", state.image, "admin", state.admins.contains(s.account), "roleManager", canManageRoles(s.account), "adminChannels", state.admins.contains(s.account) ? state.channels : List.of(), "roles", canManageRoles(s.account) ? state.roles : List.of(), "everyonePermissions", canManageRoles(s.account) ? state.everyonePermissions : List.of(), "calls", calls.values().stream().filter(call->call.to.equals(s.account)).toList(), "friendships", state.friendships.stream().filter(f->f.includes(s.account)).toList(), "friendRequests", state.friendRequests.getOrDefault(s.account,Set.of()), "channels", channels, "accounts", state.accounts, "messages", messages, "cursor", cursor,"channelKeys",keysFor(channels), "typing", activeTyping);
            }
            case "/api/call": {
                String action=Json.str(in,"action");
                if(action.equals("ring")) {
                    String target=Json.str(in,"account");
                    Friendship friendship=state.friendships.stream().filter(f->f.includes(s.account)&&f.includes(target)&&!target.equals(s.account)).findFirst().orElseThrow(()->new Failure(403,"Only friends can be called"));
                    if(calls.values().stream().anyMatch(c->c.to.equals(target)||c.from.equals(s.account)))throw new Failure(409,"A call is already ringing");
                    CallInvite call=new CallInvite();call.id=UUID.randomUUID().toString();call.from=s.account;call.to=target;call.channel=friendship.voiceChannel;call.expires=now+30000;calls.put(call.id,call);
                    return Json.obj("id",call.id,"expires",call.expires);
                }
                CallInvite call=calls.get(Json.str(in,"id"));
                if(call==null)throw new Failure(404,"Call is no longer ringing");
                if(action.equals("cancel")&&call.from.equals(s.account) || (action.equals("accept")||action.equals("decline"))&&call.to.equals(s.account)){calls.remove(call.id);return Json.obj("ok",true,"channel",call.channel);}
                throw new Failure(403,"Call action unavailable");
            }
            case "/api/friend": {
                String target=Json.str(in,"account"), action=Json.str(in,"action");
                if(target.equals(s.account) || !state.accounts.containsKey(target)) throw new Failure(400,"Choose another registered account");
                Friendship existing=state.friendships.stream().filter(f->f.includes(s.account)&&f.includes(target)).findFirst().orElse(null);
                if(action.equals("request")) {
                    if(existing!=null) throw new Failure(409,"Already friends");
                    state.friendRequests.computeIfAbsent(target,k->new HashSet<>()).add(s.account);
                } else if(action.equals("accept")) {
                    Set<String> requests=state.friendRequests.getOrDefault(s.account,Set.of());
                    if(!requests.contains(target) || existing!=null) throw new Failure(409,"Friend request unavailable");
                    requests.remove(target);Set<String> reciprocal=state.friendRequests.get(target);if(reciprocal!=null)reciprocal.remove(s.account);
                    Friendship friendship=new Friendship();friendship.first=s.account;friendship.second=target;
                    Channel text=new Channel();text.name="Direct message";text.allow.add(s.account);text.allow.add(target);
                    Channel voice=new Channel();voice.name="Friend call";voice.type="voice";voice.maxMembers=2;voice.allow.add(s.account);voice.allow.add(target);
                    friendship.textChannel=text.id;friendship.voiceChannel=voice.id;state.channels.add(text);state.channels.add(voice);state.friendships.add(friendship);
                } else if(action.equals("decline")) {
                    Set<String> requests=state.friendRequests.get(s.account);if(requests!=null)requests.remove(target);
                } else if(action.equals("remove")) {
                    if(existing==null)throw new Failure(404,"Friend unavailable");
                    calls.values().removeIf(call->call.channel.equals(existing.voiceChannel));state.friendships.remove(existing);state.channels.removeIf(c->c.id.equals(existing.textChannel)||c.id.equals(existing.voiceChannel));
                    state.channelKeys.remove(existing.textChannel);state.channelKeys.remove(existing.voiceChannel);
                    presence.remove(s.account);presence.remove(target);floors.remove(existing.voiceChannel);signals.remove(s.account);signals.remove(target);
                } else throw new Failure(400,"Unknown friend action");
                save();return Json.obj("ok",true);
            }
            case "/api/roles": {
                if(!canManageRoles(s.account)) throw new Failure(403,"Manage roles permission required");
                if(!in.has("roles") || !in.get("roles").isJsonArray() || !in.has("everyonePermissions") || !in.get("everyonePermissions").isJsonArray()) throw new Failure(400,"Role configuration required");
                Role[] roles=Json.GSON.fromJson(in.get("roles"),Role[].class);
                Set<String> base=new HashSet<>(Arrays.asList(Json.GSON.fromJson(in.get("everyonePermissions"),String[].class)));
                Set<String> known=Set.of("view","send","connect","manage_roles"), ids=new HashSet<>();
                if(roles.length>100 || !Set.of("view","send","connect").containsAll(base)) throw new Failure(400,"Invalid role configuration");
                for(Role role:roles) if(role==null || role.id==null || !role.id.matches("[a-f0-9-]{36}") || !ids.add(role.id) || role.name==null || role.name.isBlank() || role.name.length()>40 || role.name.chars().anyMatch(Character::isISOControl) || role.permissions==null || !known.containsAll(role.permissions) || role.members==null || role.members.size()>1000 || !state.accounts.keySet().containsAll(role.members)) throw new Failure(400,"Invalid role");
                if(!state.admins.contains(s.account)) {
                    int top=highestRole(s.account),protectedCount=state.roles.size()-top,newTop=roles.length-protectedCount;
                    if(top<0 || newTop<0 || !base.equals(state.everyonePermissions)) throw new Failure(403,"Cannot change everyone permissions or higher roles");
                    for(int i=0;i<protectedCount;i++) if(!Json.GSON.toJson(state.roles.get(top+i)).equals(Json.GSON.toJson(roles[newTop+i]))) throw new Failure(403,"Cannot change your role or higher roles");
                    Map<String,Role> old=new HashMap<>();for(int i=0;i<top;i++)old.put(state.roles.get(i).id,state.roles.get(i));
                    Map<String,Role> changed=new HashMap<>();for(int i=0;i<newTop;i++)changed.put(roles[i].id,roles[i]);
                    Set<String> all=new HashSet<>(old.keySet());all.addAll(changed.keySet());Set<String> own=globalPermissions(s.account);
                    for(String id:all){Role before=old.get(id),after=changed.get(id);if(Json.GSON.toJson(before).equals(Json.GSON.toJson(after)))continue;
                        if(after!=null&&!own.containsAll(after.permissions))throw new Failure(403,"Cannot grant permissions you do not have");
                        Set<String> members=new HashSet<>();if(before!=null)members.addAll(before.members);if(after!=null)for(String member:after.members)if(!members.add(member))members.remove(member);
                        for(String member:members)if(state.admins.contains(member)||highestRole(member)>=top||member.equals(s.account))throw new Failure(403,"Cannot change roles of an equal or higher member");
                    }
                }
                // Do not leave stale channel overrides referencing deleted roles.
                state.roles=new ArrayList<>(Arrays.asList(roles));state.everyonePermissions=base;
                for(Channel c:state.channels) c.roleOverrides.keySet().removeIf(id->!id.equals("everyone") && !ids.contains(id));
                save();return Json.obj("ok",true);
            }
            case "/api/admin": {
                if (!state.admins.contains(s.account)) throw new Failure(403, "Administrator access required");
                String name = Json.str(in,"name").strip(), description = Json.str(in,"description"), image = Json.str(in,"image");
                if (name.isEmpty() || name.length()>60 || name.chars().anyMatch(Character::isISOControl) || description.length()>500) throw new Failure(400,"Invalid server details");
                byte[] picture = Crypto.unb64(image);
                if (picture.length>10000 || (picture.length>0 && !MediaRules.isWebp(picture))) throw new Failure(400,"Server image must be WebP, at most 10,000 bytes");
                if (!in.has("channels") || !in.get("channels").isJsonArray()) throw new Failure(400,"Channels required");
                Channel[] updated = Json.GSON.fromJson(in.get("channels"),Channel[].class);
                if(updated.length>100) throw new Failure(400,"Too many channels");
                Set<String> ids = new HashSet<>();
                for(Channel c:updated) {
                    if(c==null || c.id==null || !c.id.matches("[a-f0-9-]{36}") || !ids.add(c.id) || c.name==null || c.name.isBlank() || c.name.length()>40 || c.name.chars().anyMatch(Character::isISOControl) || !("text".equals(c.type)||"voice".equals(c.type)) || c.allow==null || c.allow.size()>1000 || c.allow.stream().anyMatch(a->a==null || !a.matches("[a-f0-9]{64}")) || c.bitRate<500 || c.bitRate>6000 || c.maxMembers<2 || c.maxMembers>32 || c.slowModeSeconds<0 || c.slowModeSeconds>3600) throw new Failure(400,"Invalid channel configuration");
                    Channel old=state.channels.stream().filter(existing->existing.id.equals(c.id)).findFirst().orElse(null);
                    if(old!=null && !old.type.equals(c.type)) throw new Failure(400,"Cannot change channel type");
                    if(c.roleOverrides==null || c.roleOverrides.size()>101) throw new Failure(400,"Invalid role overrides");
                    for(var override:c.roleOverrides.entrySet()) if((!override.getKey().equals("everyone") && state.roles.stream().noneMatch(r->r.id.equals(override.getKey()))) || override.getValue()==null || !Set.of("view","send","connect").containsAll(override.getValue().keySet()) || override.getValue().containsValue(null)) throw new Failure(400,"Invalid role override");
                }
                for(Friendship f:state.friendships) for(String id:List.of(f.textChannel,f.voiceChannel)) {
                    Channel old=state.channels.stream().filter(c->c.id.equals(id)).findFirst().orElseThrow();
                    Channel replacement=Arrays.stream(updated).filter(c->c.id.equals(id)).findFirst().orElse(null);
                    if(replacement==null || !Json.GSON.toJson(old).equals(Json.GSON.toJson(replacement))) throw new Failure(400,"Friend channels are managed by their participants");
                }
                state.name=name; state.description=description; state.image=image; state.channels=new ArrayList<>(Arrays.asList(updated)); save();
                return Json.obj("ok",true);
            }
            case "/api/typing": {
                String id = Json.str(in,"channel");
                if (id.isEmpty()) typing.remove(s.account);
                else { channel(id,s.account,"text"); typing.put(s.account,new Presence(id,now+5000)); }
                return Json.obj("ok",true);
            }
            case "/api/keys": return Json.obj("channelKeys",keysFor(state.channels.stream().filter(c->canView(c,s.account)).toList()));
            case "/api/profile": {
                String name = Json.str(in, "name").strip(), bio = Json.str(in, "bio"), avatar = Json.str(in, "avatar"), pronouns = Json.str(in, "pronouns").strip();
                if (name.isEmpty() || name.length() > 40 || name.contains("<") || name.contains(">") || name.chars().anyMatch(Character::isISOControl) || bio.length() > 240 || pronouns.length() > 40 || pronouns.contains("<") || pronouns.contains(">") || pronouns.chars().anyMatch(Character::isISOControl)) throw new Failure(400, "Invalid profile name or profile too long");
                byte[] b = Crypto.unb64(avatar);
                if (b.length > 10000 || (b.length > 0 && !MediaRules.isWebp(b))) throw new Failure(413, "Avatar must be WebP, at most 10,000 bytes");
                Account a = state.accounts.get(s.account); a.name = name; a.bio = bio; a.avatar = avatar; a.pronouns = pronouns; save(); return Json.obj("ok", true);
            }
            case "/api/send": {
                Envelope m = Json.GSON.fromJson(in, Envelope.class); Channel c = channel(m.channel, s.account, "text");
                if(!permits(c,s.account,"send")) throw new Failure(403,"Sending is not permitted");
                if (!s.account.equals(m.author) || !state.id.equals(m.server) || m.id == null || !m.id.matches("[a-f0-9-]{36}")
                    || !("text".equals(m.kind) || "image".equals(m.kind) || "images".equals(m.kind)) || m.cipher == null || m.cipher.length() > ("images".equals(m.kind) ? 600000 : 155000)
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
                if(!permits(c,s.account,"connect")) throw new Failure(403,"Voice access is not permitted");
                if (action.equals("leave")) { presence.remove(s.account); floors.entrySet().removeIf(e -> e.getValue().account.equals(s.account)); signals.remove(s.account); return Json.obj("ok", true); }
                long count = presence.entrySet().stream().filter(e -> e.getValue().channel.equals(c.id) && !e.getKey().equals(s.account)).count();
                if (count >= c.maxMembers) throw new Failure(409, "Voice channel is full");
                presence.put(s.account, new Presence(c.id, now + 10000)); Floor floor = floors.get(c.id);
                if (action.equals("claim") && (floor == null || floor.account.equals(s.account))) { floor = new Floor(s.account, now + 3500); floors.put(c.id, floor); }
                if (action.equals("release") && floor != null && floor.account.equals(s.account)) { floors.remove(c.id); floor = null; }
                if (action.equals("signal")) {
                    String to = Json.str(in, "to"); Presence target = presence.get(to);
                    if (target == null || !target.channel.equals(c.id) || !canView(c,to) || !permits(c,to,"connect")) throw new Failure(404, "Peer left");
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
        return state.channels.stream().filter(c -> c.id.equals(id) && canView(c,hash) && c.type.equals(type)).findFirst().orElseThrow(() -> new Failure(403, "Channel unavailable"));
    }
    private void rate(String key, int limit, long now) throws Failure {
        long[] r = rates.computeIfAbsent(key, k -> new long[]{now, 0});
        if (now - r[0] > 60000) { r[0] = now; r[1] = 0; } if (++r[1] > limit) throw new Failure(429, "Too many requests");
    }
    private void cleanup(long now) {
        challenges.values().removeIf(c -> c.expires < now); sessions.values().removeIf(s -> s.expires < now);
        authenticatedJoins.values().removeIf(j->{if(j.expires>=now)return false;Arrays.fill(j.key,(byte)0);return true;});
        calls.values().removeIf(call->call.expires < now);
        presence.values().removeIf(p -> p.expires < now); floors.values().removeIf(f -> f.expires < now);
        signals.values().forEach(q -> q.removeIf(e -> e.expires < now)); signals.values().removeIf(List::isEmpty);
        rates.values().removeIf(r -> now - r[0] > 120000);
    }
    public synchronized void ban(String value, boolean ip) throws Exception {
        Set<String> affected=new HashSet<>();sessions.values().stream().filter(s->ip?s.ip.equals(value):s.account.equals(value)).forEach(s->affected.add(s.account));if(!ip)affected.add(value);
        (ip ? state.bannedIps : state.bannedHashes).add(value);rotateAllChannelKeys();save();
        sessions.entrySet().removeIf(e -> ip ? e.getValue().ip.equals(value) : e.getValue().account.equals(value));
        calls.values().removeIf(call->affected.contains(call.from)||affected.contains(call.to));presence.keySet().removeIf(affected::contains);floors.values().removeIf(f->affected.contains(f.account));signals.keySet().removeIf(affected::contains); log.accept("Ban added");
    }
    private void admitJoin(String ip)throws Failure{
        int wait=joinAttempts.acquire(ip);if(wait>0)throw new Failure(429,"Wait "+wait+" seconds before trying to join again",wait);
        // Every new admission, including invalid direct-auth attempts, replaces unfinished older work.
        pendingAdmissions.remove(ip);challenges.values().removeIf(c->c.ip.equals(ip));
        authenticatedJoins.values().removeIf(j->{if(!j.ip.equals(ip))return false;Arrays.fill(j.key,(byte)0);return true;});
    }
    public synchronized void close() { if (http != null) { http.stop(0); http = null; workers.shutdownNow(); sessions.clear(); presence.clear(); floors.clear(); signals.clear(); calls.clear(); challenges.clear();authenticatedJoins.values().forEach(j->Arrays.fill(j.key,(byte)0));authenticatedJoins.clear(); log.accept("Server stopped"); } }
    private static final class Failure extends Exception { final int code,retryAfter; Failure(int code, String message) { this(code,message,0); } Failure(int code,String message,int retryAfter){super(message);this.code=code;this.retryAfter=retryAfter;} }
}
