package chat.vch;
import chat.vch.Models.*;
import java.nio.file.*;
import java.net.ServerSocket;

/** Dedicated local fixture for the browser-to-browser WebRTC test. */
public final class VoiceTestHost {
    public static void main(String[] args)throws Exception{
        Path dir=Path.of("build/voice-fixture/"+java.util.UUID.randomUUID());Files.createDirectories(dir);
        try(Vault sv=new Vault(dir.resolve("server.vault"),"test password long enough".toCharArray());Vault av=new Vault(dir.resolve("alice.vault"),"test password long enough".toCharArray());Vault bv=new Vault(dir.resolve("bob.vault"),"test password long enough".toCharArray())){
            java.util.concurrent.atomic.AtomicLong joinClock=new java.util.concurrent.atomic.AtomicLong();ChatServer server=new ChatServer(sv,new JoinAttemptLimiter(joinClock::get));try(ServerSocket port=new ServerSocket(0)){server.state.port=port.getLocalPort();}server.state.hashBits=10;server.setPassword("test server password".toCharArray());server.start("127.0.0.1");
            ClientCore alice=new ClientCore(av),bob=new ClientCore(bv);SavedServer a=endpoint(server),b=endpoint(server);alice.connect(a,"test server password".toCharArray());joinClock.addAndGet(10000);bob.connect(b,"test server password".toCharArray());alice.poll(a);bob.poll(b);
            Channel channel=a.channels.stream().filter(c->c.type.equals("voice")).findFirst().orElseThrow();if(!a.channelKeys.get(channel.id).equals(b.channelKeys.get(channel.id)))throw new AssertionError("Automatic voice channel keys differ");
            try(VoiceBridge va=new VoiceBridge(alice,a,channel);VoiceBridge vb=new VoiceBridge(bob,b,channel)){
                Files.write(Path.of("build/voice-test.json"),Json.bytes(Json.obj("alice",va.uri().toString(),"bob",vb.uri().toString())));System.out.println("Voice fixture ready");
                long end=System.currentTimeMillis()+180000;while(System.currentTimeMillis()<end&&!Files.exists(Path.of("build/stop-voice-test")))Thread.sleep(500);
            }finally{server.close();}
        }
    }
    private static SavedServer endpoint(ChatServer host)throws Exception{SavedServer s=new SavedServer();s.address="https://127.0.0.1:"+host.state.port;s.fingerprint=Tls.fingerprint(host.state.tlsStore);return s;}
}
