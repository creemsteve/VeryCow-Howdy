package chat.vch;

import chat.vch.Models.*;
import com.google.gson.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real HTTPS relay authorization, bounded live delivery and lifecycle tests; no model needed. */
public final class VoiceRelayTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action,int status,String label)throws Exception{
        try{action.run();}catch(java.io.IOException e){check(e instanceof ClientCore.ApiException api?api.status==status:("Server returned "+status).equals(e.getMessage()),label+" (HTTP "+status+")");return;}
        throw new AssertionError(label+": accepted");
    }
    private static Vault vault(Path path)throws Exception{return new Vault(path,"voice relay test password".toCharArray());}
    private static JsonObject voice(ClientCore client,SavedServer s,Channel c,String action)throws Exception{
        return client.request(s,"voice",Json.obj("channel",c.id,"action",action,"after",0),true);
    }
    private static JsonObject send(ClientCore client,SavedServer s,Channel c,Envelope frame)throws Exception{
        return client.request(s,"voice",Json.obj("channel",c.id,"action","media","envelope",frame),true);
    }
    private static void sign(ClientCore client,Envelope e)throws Exception{e.signature=Crypto.sign(Crypto.privateKey(client.state.privateKey),e.signedBytes());}
    public static void main(String[] args)throws Exception{
        Path root=Files.createDirectories(Path.of("build/test-data/voice-relay-"+UUID.randomUUID()));
        AtomicLong clock=new AtomicLong();
        try(Vault sv=vault(root.resolve("server.vault"));Vault av=vault(root.resolve("alice.vault"));Vault bv=vault(root.resolve("bob.vault"));Vault cv=vault(root.resolve("carol.vault"));
            ChatServer host=new ChatServer(sv,new JoinAttemptLimiter(clock::get));ClientCore alice=new ClientCore(av);ClientCore bob=new ClientCore(bv);ClientCore carol=new ClientCore(cv)){
            try(ServerSocket available=new ServerSocket(0)){host.state.port=available.getLocalPort();}
            host.state.hashBits=8;host.setPassword("server join password".toCharArray());host.start("127.0.0.1");
            SavedServer a=endpoint(host),b=endpoint(host),c=endpoint(host);
            alice.connect(a,"server join password".toCharArray());clock.addAndGet(10000);bob.connect(b,"server join password".toCharArray());clock.addAndGet(10000);carol.connect(c,"server join password".toCharArray());alice.poll(a);bob.poll(b);
            Channel room=host.state.channels.stream().filter(ch->ch.type.equals("voice")).findFirst().orElseThrow();
            Channel other=new Channel();other.type="voice";host.state.channels.add(other);
            Channel restricted=new Channel();restricted.type="voice";restricted.allow.add(alice.account(a));host.state.channels.add(restricted);host.save();alice.poll(a);
            Envelope first=alice.envelope(a,room.id,"voice",Crypto.utf("live voice tokens"));
            rejects(()->alice.request(a,"voice",Json.obj("channel",room.id,"action","heartbeat"),false),401,"relay requires authentication");
            rejects(()->send(alice,a,room,first),403,"media cannot implicitly join or claim a speaking lease");
            var joined=voice(alice,a,room,"claim");long start=Json.num(joined,"cursor",-1);
            check("https-relay-v1".equals(Json.str(joined,"transport")),"server advertises HTTPS relay protocol");
            voice(bob,b,room,"heartbeat");
            rejects(()->send(bob,b,room,bob.envelope(b,room.id,"voice",Crypto.utf("intrusion"))),403,"server denies media from a listener");
            rejects(()->voice(bob,b,restricted,"heartbeat"),403,"private channel cannot be joined");
            room.maxMembers=2;rejects(()->voice(carol,c,room,"heartbeat"),409,"server enforces channel capacity");room.maxMembers=16;
            rejects(()->voice(alice,a,room,"signal"),400,"legacy peer signaling is rejected");
            send(alice,a,room,first);send(alice,a,room,first);
            var state=voice(bob,b,room,"heartbeat");JsonArray frames=state.getAsJsonArray("frames");
            check(frames.size()==1,"repeated uploads are deduplicated in the live buffer");
            Envelope delivered=Json.GSON.fromJson(frames.get(0),Envelope.class);
            check(Arrays.equals(bob.decrypt(b,delivered),Crypto.utf("live voice tokens")),"recipient verifies and decrypts the relayed ciphertext");
            check(voice(alice,a,room,"heartbeat").getAsJsonArray("frames").isEmpty(),"server does not echo a sender's frames");
            check(bob.request(b,"voice",Json.obj("channel",room.id,"action","heartbeat","after",Json.num(state,"cursor",0)),true).getAsJsonArray("frames").isEmpty(),"cursor prevents redelivery on the next poll");
            check(voice(carol,c,room,"heartbeat").getAsJsonArray("frames").isEmpty(),"new participant cannot fetch audio sent before joining");
            voice(carol,c,other,"heartbeat");check(voice(carol,c,other,"heartbeat").getAsJsonArray("frames").isEmpty(),"media is isolated by channel");
            first.author=bob.account(b);sign(alice,first);rejects(()->send(alice,a,room,first),400,"sender identity is bound to the authenticated account");first.author=alice.account(a);
            first.channel=other.id;sign(alice,first);rejects(()->send(alice,a,room,first),400,"signed envelope must match the voice channel");first.channel=room.id;
            first.server="another server";sign(alice,first);rejects(()->send(alice,a,room,first),400,"signed envelope must match the server");first.server=a.id;
            first.kind="text";sign(alice,first);rejects(()->send(alice,a,room,first),400,"non-voice envelopes cannot use the relay");first.kind="voice";
            first.time=System.currentTimeMillis()-15000;sign(alice,first);rejects(()->send(alice,a,room,first),400,"stale voice frame is rejected");
            first.time=Long.MIN_VALUE;sign(alice,first);rejects(()->send(alice,a,room,first),400,"timestamp overflow cannot bypass freshness checks");first.time=System.currentTimeMillis();
            first.signature="forged";rejects(()->send(alice,a,room,first),400,"forged signature is rejected");
            Envelope large=alice.envelope(a,room.id,"voice",Crypto.random(40000));rejects(()->send(alice,a,room,large),400,"oversized ciphertext is rejected");
            first.keyRevision=0;sign(alice,first);rejects(()->send(alice,a,room,first),409,"obsolete encryption revision is rejected");
            host.rotateChannelKey(room.id);check(voice(bob,b,room,"heartbeat").getAsJsonArray("frames").isEmpty(),"rotation prevents delivery of buffered old-key frames");voice(alice,a,room,"claim");alice.refreshChannelKeys(a);
            List<String> ids=new ArrayList<>();
            for(int i=0;i<8;i++){Envelope frame=alice.envelope(a,room.id,"voice",Crypto.utf("block "+i));ids.add(frame.id);send(alice,a,room,frame);}
            frames=voice(bob,b,room,"heartbeat").getAsJsonArray("frames");
            check(frames.size()==6&&Json.str(frames.get(0).getAsJsonObject(),"id").equals(ids.get(2)),"slow receivers get only the six newest buffered frames");
            check(Json.num(voice(bob,b,room,"heartbeat"),"cursor",0)>start,"live cursor advances independently of chat history");
            check(host.state.messages.isEmpty()&&!Json.GSON.toJson(host.state).contains(ids.get(7)),"voice media is never stored in chat history or server snapshots");
            Thread.sleep(3100);voice(alice,a,room,"claim");check(voice(bob,b,room,"heartbeat").getAsJsonArray("frames").isEmpty(),"live buffer expires after three seconds even while the floor is renewed");
            send(alice,a,room,alice.envelope(a,room.id,"voice",Crypto.utf("before release")));voice(alice,a,room,"release");
            check(voice(bob,b,room,"claim").getAsJsonArray("frames").isEmpty(),"floor transfer discards previous speaker's buffered audio");
            rejects(()->send(alice,a,room,alice.envelope(a,room.id,"voice",Crypto.utf("after release"))),403,"released speaker cannot continue uploading");
            Thread.sleep(3600);rejects(()->send(bob,b,room,bob.envelope(b,room.id,"voice",Crypto.utf("expired lease"))),403,"server enforces lease expiry without trusting the client");
            voice(alice,a,room,"claim");voice(alice,a,other,"heartbeat");
            check(Json.str(voice(bob,b,room,"claim"),"floor").equals(bob.account(b)),"switching channels releases the old speaking lease");
            voice(bob,b,room,"leave");rejects(()->send(bob,b,room,bob.envelope(b,room.id,"voice",Crypto.utf("after leave"))),403,"leaving revokes voice upload membership");
            voice(alice,a,restricted,"claim");Envelope privateFrame=alice.envelope(a,restricted.id,"voice",Crypto.utf("private"));
            restricted.allow.clear();restricted.allow.add(bob.account(b));host.save();
            rejects(()->send(alice,a,restricted,privateFrame),403,"access revocation stops an active speaker");
            check(Json.str(voice(bob,b,restricted,"claim"),"floor").equals(bob.account(b)),"revoked participant cannot retain the floor");
        }
        System.out.println(checks+" voice relay checks passed");
    }
    private static SavedServer endpoint(ChatServer host){SavedServer s=new SavedServer();s.address="https://127.0.0.1:"+host.state.port;return s;}
}
