package chat.vch;

import chat.vch.Models.*;
import com.google.gson.*;
import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.util.*;

public final class ClientCore implements AutoCloseable {
    public final ClientState state;
    private final Vault vault;
    private final Map<String,String> tokens = new HashMap<>();
    private final Map<String,SSLSocketFactory> tls = new HashMap<>();
    public ClientCore(Vault vault) throws Exception {
        this.vault = vault;
        if (vault.data().has("client")) {
            state = Json.GSON.fromJson(vault.data().get("client"),ClientState.class);
            boolean migrated=false;for(SavedServer s:state.servers)if(s.joinProtocol<2){s.address=ServerAddress.migrateLegacy(s.address);s.joinProtocol=2;migrated=true;}
            if(migrated)save();
        }
        else { state = new ClientState(); var pair = Crypto.identity(); state.privateKey = Crypto.b64(pair.getPrivate().getEncoded()); state.publicKey = Crypto.b64(pair.getPublic().getEncoded()); save(); }
    }
    public synchronized void save() throws Exception { vault.data().add("client",Json.GSON.toJsonTree(state)); vault.save(); }
    public String account(SavedServer s) { return Crypto.account(s.id,state.publicKey); }
    public synchronized JsonObject request(SavedServer server, String path, JsonObject data, boolean auth) throws Exception {
        SSLSocketFactory factory = tls.get(server.fingerprint);
        if (factory == null) { factory = PinnedTls.create(server.fingerprint).getSocketFactory(); tls.put(server.fingerprint,factory); }
        return exchange(server,path,data,auth,factory);
    }
    private JsonObject exchange(SavedServer server,String path,JsonObject data,boolean auth,SSLSocketFactory factory)throws Exception{
        HttpsURLConnection c = (HttpsURLConnection)new URL(ServerAddress.normalize(server.address) + "/api/" + path).openConnection();
        c.setSSLSocketFactory(factory); c.setHostnameVerifier((h,session) -> true); // Exact certificate pin replaces public-CA hostname trust.
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(7000); c.setReadTimeout(10000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json");
        if (auth) { String token = tokens.get(server.id); if (token == null) throw new IOException("Server is disconnected"); c.setRequestProperty("Authorization","Bearer " + token); }
        try {
            byte[] bytes = Json.bytes(data); c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int code = c.getResponseCode();
            try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
                if (in == null) throw new IOException("Server returned " + code);
                byte[] response = Io.read(in,24000001); if (response.length > 24000000) throw new IOException("Server response exceeds limit");
                JsonObject result = Json.parse(response); if (code != 200) { if (auth&&code == 401) tokens.remove(server.id); throw new ApiException(code,Json.str(result,"error")); } return result;
            }
        } finally { c.disconnect(); }
    }
    public synchronized void connect(SavedServer s, char[] password) throws Exception {
        try {
            SavedServer candidate=new SavedServer();candidate.address=ServerAddress.normalize(s.address);
            PinnedTls.Bootstrap bootstrap=new PinnedTls.Bootstrap(s.fingerprint);
            JsonObject challenge = exchange(candidate,"challenge",Json.obj(),false,bootstrap.factory());
            if(!JoinAuth.PROTOCOL.equals(Json.str(challenge,"auth")))throw new IOException("Update the server to VCH 0.1.2 or newer");
            String serverId = Json.str(challenge,"server"),fingerprint=bootstrap.fingerprint(),nonce = Json.str(challenge,"nonce");
            if (!"SHA-256".equals(Json.str(challenge,"algorithm"))) throw new IOException("Unknown registration hash");
            if(password==null&&s.passwordKey==null)throw new IOException("Join using the server password first");
            byte[] key = password == null ? Crypto.unb64(s.passwordKey) : Crypto.passwordKey(password,Crypto.unb64(Json.str(challenge,"salt")));
            try {
                var srp=JoinAuth.client();
                String a=srp.generateClientCredentials(Crypto.unb64(Json.str(challenge,"salt")),JoinAuth.identity(serverId,fingerprint),key).toString(16);
                srp.calculateSecret(JoinAuth.number(Json.str(challenge,"B")));
                String m1=srp.calculateClientEvidenceMessage().toString(16);
                JsonObject evidence=exchange(candidate,"auth",Json.obj("nonce",nonce,"A",a,"M1",m1),false,bootstrap.factory());
                if(!srp.verifyServerEvidenceMessage(JoinAuth.number(Json.str(evidence,"M2"))))throw new IOException("Server password proof failed; certificate was not trusted");
                if(!"server-v1".equals(Json.str(challenge,"keyDistribution")))throw new IOException("Update the server to VCH 0.1.2 or newer for automatic channel encryption");
                if(s.id!=null&&!s.id.equals(serverId))throw new IOException("Server identity changed");
                candidate.id=serverId;candidate.fingerprint=fingerprint;candidate.name=Json.str(challenge,"name");
                byte[] sessionKey=JoinAuth.sessionKey(srp.calculateSessionKey());
                try {
                    String solution = Crypto.solve(nonce + "|" + account(candidate),(int)Json.num(challenge,"bits",16));
                    String transcript = JoinAuth.transcript(serverId,fingerprint,nonce,state.publicKey,solution);
                    JsonObject response = request(candidate,"login",Json.obj("nonce",nonce,"solution",solution,"publicKey",state.publicKey,
                        "proof",Crypto.b64(Crypto.hmac(sessionKey,Crypto.utf(transcript))),"signature",Crypto.sign(Crypto.privateKey(state.privateKey),Crypto.utf(transcript))),false);
                    s.address=candidate.address;s.id=serverId;s.name=candidate.name;s.fingerprint=fingerprint;s.joinProtocol=2;
                    tokens.put(s.id,Json.str(response,"token")); s.passwordKey = Crypto.b64(key);
                }finally{Arrays.fill(sessionKey,(byte)0);}
            } finally { Arrays.fill(key,(byte)0); }
            updateProfile(s); poll(s);
            if (!state.servers.contains(s)) state.servers.add(s); save();
        } finally { if (password != null) Arrays.fill(password,'\0'); }
    }
    public synchronized boolean connected(SavedServer s) { return tokens.containsKey(s.id); }
    public synchronized void disconnect(SavedServer s) { tokens.remove(s.id); }
    public synchronized void updateProfile(SavedServer s) throws Exception { request(s,"profile",Json.obj("name",state.name,"bio",state.bio,"avatar",state.avatar),true); }
    public synchronized boolean poll(SavedServer s) throws Exception {
        JsonObject r = request(s,"poll",Json.obj("after",s.lastSequence),true);
        String before = Json.GSON.toJson(s);
        s.name = Json.str(r,"name");
        s.channels = new ArrayList<>(Arrays.asList(Json.GSON.fromJson(r.get("channels"),Channel[].class)));
        Map<String,Account> accounts = new HashMap<>();
        for (var entry : r.getAsJsonObject("accounts").entrySet()) {
            Account a = Json.GSON.fromJson(entry.getValue(),Account.class);
            if (!entry.getKey().equals(Crypto.account(s.id,a.publicKey)) || a.name.contains("<") || a.name.contains(">")) throw new IOException("Invalid account identity");
            accounts.put(entry.getKey(),a);
        }
        for (Envelope e : Json.GSON.fromJson(r.get("messages"),Envelope[].class)) {
            Account a = accounts.get(e.author);
            if (a == null || !s.id.equals(e.server) || !Crypto.verify(a.publicKey,e.signedBytes(),e.signature)) throw new IOException("Message signature failed verification");
            if (s.messages.stream().noneMatch(m -> m.id.equals(e.id) && m.author.equals(e.author))) s.messages.add(e);
        }
        s.accounts = accounts; s.lastSequence = Json.num(r,"cursor",s.lastSequence);
        applyChannelKeys(s,r.getAsJsonObject("channelKeys"));
        boolean changed = !before.equals(Json.GSON.toJson(s)); if (changed) save(); return changed;
    }
    private static String validatedKey(String key)throws IOException{
        try{byte[] bytes=Crypto.unb64(key);if(bytes.length!=32)throw new IOException("Invalid server channel encryption key");return Crypto.b64(bytes);}
        catch(IllegalArgumentException e){throw new IOException("Invalid server channel encryption key",e);}
    }
    private boolean applyChannelKeys(SavedServer s,JsonObject supplied)throws Exception{
        if(supplied==null)throw new IOException("Update the server to VCH 0.1.2 or newer for automatic channel encryption");
        Map<String,String> current=new HashMap<>();Map<String,List<String>> history=new HashMap<>();Map<String,Long> revisions=new HashMap<>();
        for(var entry:supplied.entrySet()){
            String id=entry.getKey();JsonObject k=entry.getValue().getAsJsonObject();String key=validatedKey(Json.str(k,"current"));long revision=Json.num(k,"revision",0);
            long known=s.keyRevisions.getOrDefault(id,0L);
            if(revision<1||revision<known||(revision==known&&!key.equals(s.channelKeys.get(id))))throw new IOException("Server channel encryption revision is invalid");
            LinkedHashSet<String> previous=new LinkedHashSet<>(s.previousChannelKeys.getOrDefault(id,List.of()));String old=s.channelKeys.get(id);if(old!=null&&!old.equals(key))previous.add(old);
            if(k.has("previous"))for(JsonElement p:k.getAsJsonArray("previous"))previous.add(validatedKey(p.getAsString()));previous.remove(key);
            current.put(id,key);history.put(id,new ArrayList<>(previous));revisions.put(id,revision);
        }
        boolean changed=false;for(String id:current.keySet()){
            if(!current.get(id).equals(s.channelKeys.get(id))||!history.get(id).equals(s.previousChannelKeys.getOrDefault(id,List.of()))||!revisions.get(id).equals(s.keyRevisions.get(id)))changed=true;
            s.channelKeys.put(id,current.get(id));s.previousChannelKeys.put(id,history.get(id));s.keyRevisions.put(id,revisions.get(id));
        }
        return changed;
    }
    public synchronized void acceptChannelKeys(SavedServer s,JsonObject supplied)throws Exception{if(applyChannelKeys(s,supplied))save();}
    public synchronized void refreshChannelKeys(SavedServer s)throws Exception{acceptChannelKeys(s,request(s,"keys",Json.obj(),true).getAsJsonObject("channelKeys"));}
    public static final class ApiException extends IOException{
        public final int status;
        ApiException(int status,String message){super(message);this.status=status;}
    }
    public synchronized Envelope envelope(SavedServer s, String channel, String kind, byte[] plain) throws Exception {
        String key = s.channelKeys.get(channel);long revision=s.keyRevisions.getOrDefault(channel,0L);
        if (key == null||revision<1) throw new IOException("Channel encryption is not ready. Reconnect to refresh your channel access.");
        Envelope e = new Envelope(); e.id = UUID.randomUUID().toString(); e.server = s.id; e.channel = channel; e.author = account(s); e.kind = kind; e.time = System.currentTimeMillis();
        e.keyRevision=revision;
        e.cipher = Crypto.b64(Crypto.seal(Crypto.unb64(key),Crypto.compress(plain),e.aad())); e.signature = Crypto.sign(Crypto.privateKey(state.privateKey),e.signedBytes()); return e;
    }
    public synchronized void send(SavedServer s, Channel c, String kind, byte[] bytes) throws Exception {
        if (!c.type.equals("text") || !(kind.equals("text") || kind.equals("image"))) throw new IOException("Text channel required");
        if (kind.equals("text") && (bytes.length == 0 || bytes.length > 16000)) throw new IOException("Text limit is 16,000 UTF-8 bytes");
        if (kind.equals("image") && (bytes.length > 100000 || !MediaRules.isWebp(bytes))) throw new IOException("Images must be WebP, at most 100,000 bytes");
        for(int attempt=0;;attempt++){
            Envelope e = envelope(s,c.id,kind,bytes);
            try{request(s,"send",Json.GSON.toJsonTree(e).getAsJsonObject(),true);break;}
            catch(ApiException changed){if(changed.status!=409||attempt>0)throw changed;poll(s);}
        }
        poll(s);
    }
    public synchronized byte[] decrypt(SavedServer s, Envelope e) throws Exception {
        Account a = s.accounts.get(e.author);
        if (!s.id.equals(e.server) || a == null || !Crypto.account(s.id,a.publicKey).equals(e.author) || !Crypto.verify(a.publicKey,e.signedBytes(),e.signature)) throw new IOException("Signature verification failed");
        String key = s.channelKeys.get(e.channel); if (key == null) throw new IOException("Channel key needed");
        List<String> candidates=new ArrayList<>();candidates.add(key);candidates.addAll(s.previousChannelKeys.getOrDefault(e.channel,List.of()));
        for(String candidate:candidates)try{return Crypto.decompress(Crypto.open(Crypto.unb64(candidate),Crypto.unb64(e.cipher),e.aad()),e.kind.equals("image") ? 100000 : e.kind.equals("voice") ? 32000 : 16000);}catch(javax.crypto.AEADBadTagException wrongKey){}
        throw new IOException("This message needs a different channel key");
    }
    public synchronized void close() { tokens.clear(); vault.close(); }
}
