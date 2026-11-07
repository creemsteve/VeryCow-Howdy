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
            if(args.length>0&&args[0].equals("native")){
                Path fixture=Path.of(args[1]);Files.createDirectories(fixture);
                DesktopVoiceTest.SyntheticAudio audio=new DesktopVoiceTest.SyntheticAudio();
                java.util.concurrent.atomic.AtomicReference<DesktopVoice.State> status=new java.util.concurrent.atomic.AtomicReference<>();
                try(DesktopVoice nativeVoice=new DesktopVoice(alice,a,channel,Path.of("build/models"),audio,status::set);VoiceBridge browserVoice=new VoiceBridge(bob,b,channel)){
                    nativeVoice.start();Files.write(fixture.resolve("endpoints.json"),Json.bytes(Json.obj("bob",browserVoice.uri().toString())));
                    long end=System.currentTimeMillis()+120000;String last="";
                    while(System.currentTimeMillis()<end){
                        Path command=fixture.resolve("command");String action=Files.exists(command)?Files.readString(command):"";
                        if(!action.equals(last)){last=action;switch(action){case "press":nativeVoice.press();break;case "release":nativeVoice.release();break;case "stop":return;}}
                        DesktopVoice.State state=status.get();
                        Path temporary=fixture.resolve("state.tmp");Files.write(temporary,Json.bytes(Json.obj("ready",state!=null&&state.ready(),"connected",state==null?0:state.connected(),"recording",audio.recording,"received",audio.blocks.get(),"message",state==null?"":state.message())));
                        Files.move(temporary,fixture.resolve("state.json"),StandardCopyOption.REPLACE_EXISTING);Thread.sleep(100);
                    }
                }finally{server.close();}return;
            }
            try(VoiceBridge va=new VoiceBridge(alice,a,channel);VoiceBridge vb=new VoiceBridge(bob,b,channel)){
                Files.write(Path.of("build/voice-test.json"),Json.bytes(Json.obj("alice",va.uri().toString(),"bob",vb.uri().toString())));System.out.println("Voice fixture ready");
                long end=System.currentTimeMillis()+180000;while(System.currentTimeMillis()<end&&!Files.exists(Path.of("build/stop-voice-test")))Thread.sleep(500);
            }finally{server.close();}
        }
    }
    private static SavedServer endpoint(ChatServer host)throws Exception{SavedServer s=new SavedServer();s.address="https://127.0.0.1:"+host.state.port;s.fingerprint=Tls.fingerprint(host.state.tlsStore);return s;}
}
