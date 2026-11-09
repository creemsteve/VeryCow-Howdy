package chat.vch;

import chat.vch.Models.*;
import com.google.gson.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real HTTPS membership/subscription tests plus deterministic sliding-window budgets. */
public final class VideoRelayTest {
    private static int checks;
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private interface Action {void run()throws Exception;}
    private static void rejects(Action action,int status,String label)throws Exception{
        try{action.run();}catch(ClientCore.ApiException e){check(e.status==status,label);return;}catch(VideoRelay.Rejected e){check(e.status==status,label);return;}
        throw new AssertionError(label+": accepted");
    }
    private static JsonObject video(ClientCore c,SavedServer s,Channel room,String action,JsonObject in)throws Exception{
        in.addProperty("channel",room.id);in.addProperty("action",action);return c.request(s,"video",in,true);
    }
    private static void join(ClientCore c,SavedServer s,Channel room)throws Exception{c.request(s,"voice",Json.obj("channel",room.id,"action","heartbeat"),true);}
    private static SavedServer endpoint(ChatServer host){SavedServer s=new SavedServer();s.address="https://127.0.0.1:"+host.state.port;return s;}
    public static void main(String[] args)throws Exception{
        Path root=Files.createDirectories(Path.of("build/test-data/video-"+UUID.randomUUID()));AtomicLong clock=new AtomicLong();
        try(Vault sv=new Vault(root.resolve("server"),"video test password".toCharArray());Vault av=new Vault(root.resolve("alice"),"video test password".toCharArray());Vault bv=new Vault(root.resolve("bob"),"video test password".toCharArray());
            ChatServer host=new ChatServer(sv,new JoinAttemptLimiter(clock::get));ClientCore alice=new ClientCore(av);ClientCore bob=new ClientCore(bv)){
            try(ServerSocket port=new ServerSocket(0)){host.state.port=port.getLocalPort();}host.state.hashBits=8;host.setPassword("server video password".toCharArray());host.start("127.0.0.1");
            SavedServer a=endpoint(host),b=endpoint(host);alice.connect(a,"server video password".toCharArray());clock.addAndGet(10000);bob.connect(b,"server video password".toCharArray());alice.poll(a);bob.poll(b);
            Channel room=a.channels.stream().filter(c->c.type.equals("voice")).findFirst().orElseThrow();
            rejects(()->video(alice,a,room,"start",Json.obj("source","screen","kbps",500)),403,"video cannot implicitly join a voice channel");
            join(alice,a,room);join(bob,b,room);
            rejects(()->video(alice,a,room,"start",Json.obj("source","screen","kbps",1001)),400,"server rejects bitrate above 1000 kbps");
            rejects(()->video(alice,a,room,"start",Json.obj("source","screen","kbps",0)),400,"server rejects zero bitrate");
            String screen=Json.str(video(alice,a,room,"start",Json.obj("source","screen","kbps",1000)),"stream");
            String camera=Json.str(video(alice,a,room,"start",Json.obj("source","camera","kbps",500)),"stream");
            rejects(()->video(alice,a,room,"start",Json.obj("source","screen","kbps",500)),409,"a participant has at most one stream per source");
            rejects(()->video(bob,b,room,"configure",Json.obj("stream",screen,"kbps",500)),403,"other participants cannot reconfigure a stream");
            rejects(()->video(alice,a,room,"configure",Json.obj("stream",screen,"kbps",1001)),400,"reconfiguration cannot bypass hard cap");
            byte[] payload=Crypto.random(2000);Envelope e=alice.envelope(a,room.id,"video/"+screen,payload);
            video(alice,a,room,"media",Json.obj("stream",screen,"envelope",e));
            JsonObject off=video(bob,b,room,"poll",Json.obj("after",0));
            check(off.getAsJsonArray("streams").size()==2&&off.getAsJsonArray("frames").isEmpty(),"stream discovery sends metadata with no video by default");
            JsonArray receive=new JsonArray();receive.add(screen);
            JsonObject on=video(bob,b,room,"poll",Json.obj("after",0,"receive",receive));
            check(on.getAsJsonArray("frames").size()==1,"explicit subscription delivers the chosen screen stream");
            Envelope delivered=Json.GSON.fromJson(on.getAsJsonArray("frames").get(0).getAsJsonObject().get("envelope"),Envelope.class);
            check(Arrays.equals(bob.decrypt(b,delivered),payload),"video payload retains encryption and signature verification");
            check(video(bob,b,room,"poll",Json.obj("after",Json.num(on,"cursor",0),"receive",receive)).getAsJsonArray("frames").isEmpty(),"video cursor prevents redelivery");
            video(alice,a,room,"media",Json.obj("stream",screen,"envelope",e));
            check(video(bob,b,room,"poll",Json.obj("after",0,"receive",receive)).getAsJsonArray("frames").size()==1,"duplicate video upload is deduplicated");
            rejects(()->video(alice,a,room,"media",Json.obj("stream",camera,"envelope",e)),400,"signed video frames cannot be substituted between sources");
            e.signature="forged";rejects(()->video(alice,a,room,"media",Json.obj("stream",screen,"envelope",e)),400,"forged video signature is rejected");
            video(alice,a,room,"configure",Json.obj("stream",camera,"kbps",250));
            JsonObject changed=video(bob,b,room,"poll",Json.obj("after",0));
            check(changed.getAsJsonArray("streams").asList().stream().anyMatch(x->Json.str(x.getAsJsonObject(),"id").equals(camera)&&Json.num(x.getAsJsonObject(),"kbps",0)==250),"each source exposes its independent updated bitrate");
            check(video(bob,b,room,"poll",Json.obj("after",0,"receive",new JsonArray())).getAsJsonArray("frames").isEmpty(),"unsubscribing stops payload delivery at the server");
            host.rotateChannelKey(room.id);
            check(video(bob,b,room,"poll",Json.obj("after",0,"receive",receive)).getAsJsonArray("frames").isEmpty(),"key rotation filters buffered old-key video");alice.refreshChannelKeys(a);bob.refreshChannelKeys(b);
            try(VoiceSession session=new VoiceSession(bob,b,room,Path.of("unused"))){
                Envelope fresh=alice.envelope(a,room.id,"video/"+screen,payload);
                JsonObject decoded=session.request("/video/decode",Json.obj("stream",screen,"author",alice.account(a),"envelope",fresh));
                check(Arrays.equals(Crypto.unb64(Json.str(decoded,"data")),payload),"shared desktop and Android bridge decodes video without loading DAC");
                try{session.request("/video/decode",Json.obj("stream",screen,"author",alice.account(a),"envelope",fresh));throw new AssertionError("replay accepted");}catch(java.io.IOException expected){check(true,"video decode rejects replayed envelopes");}
            }
            video(alice,a,room,"stop",Json.obj("stream",screen));
            check(video(alice,a,room,"poll",Json.obj()).getAsJsonArray("streams").size()==1,"stopping screen share leaves webcam running");
            check(host.state.messages.isEmpty()&&!Json.GSON.toJson(host.state).contains(camera),"video streams and frames never enter server history or vault state");
            alice.request(a,"voice",Json.obj("channel",room.id,"action","leave"),true);join(bob,b,room);
            check(video(bob,b,room,"poll",Json.obj()).getAsJsonArray("streams").isEmpty(),"leaving voice removes all video streams immediately");
            // Direct relay clock makes quota, expiry and source isolation deterministic.
            VideoRelay relay=new VideoRelay();long now=System.currentTimeMillis();String author=alice.account(a);
            String one=Json.str(relay.request(author,room,host.state,Json.obj("action","start","source","screen","kbps",50),now),"stream");
            String two=Json.str(relay.request(author,room,host.state,Json.obj("action","start","source","camera","kbps",50),now),"stream");
            Envelope p1=alice.envelope(a,room.id,"video/"+one,Crypto.random(4000));
            relay.request(author,room,host.state,Json.obj("action","media","stream",one,"envelope",p1),now);
            Envelope p2=alice.envelope(a,room.id,"video/"+one,Crypto.random(4000));
            rejects(()->relay.request(author,room,host.state,Json.obj("action","media","stream",one,"envelope",p2),now+999),429,"strict rolling bitrate window blocks excess video bytes");
            relay.request(author,room,host.state,Json.obj("action","media","stream",two,"envelope",alice.envelope(a,room.id,"video/"+two,Crypto.random(4000))),now+999);
            check(true,"webcam has its own budget while screen budget is exhausted");
            relay.request(author,room,host.state,Json.obj("action","media","stream",one,"envelope",p2),now+1000);check(true,"budget becomes available exactly after one second");
            relay.cleanup(now+12000);check(relay.request(author,room,host.state,Json.obj("action","poll"),now+12000).getAsJsonArray("streams").isEmpty(),"abandoned video streams expire without saving history");
        }
        System.out.println(checks+" video relay checks passed");
    }
}
