package chat.vch;

import chat.vch.Models.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Local SOCKS5 fixture plus the real HTTPS/SRP/chat stack; no public Tor network required. */
public final class TorTransportTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action,String fragment,String label)throws Exception{
        try{action.run();}catch(Exception e){check(e.getMessage()!=null&&e.getMessage().contains(fragment),label+" ("+e.getMessage()+")");return;}
        throw new AssertionError(label+": accepted");
    }
    private static Vault vault(Path path)throws Exception{return new Vault(path,"tor transport test password".toCharArray());}
    public static void main(String[] args)throws Exception{
        framing();
        Path root=Path.of("build/test-data/tor-"+UUID.randomUUID());Files.createDirectories(root);
        Path clientPath=root.resolve("client.vault");int savedPort;
        AtomicLong clock=new AtomicLong();
        try(Vault sv=vault(root.resolve("server.vault"));Vault cv=vault(clientPath);Vault bv=vault(root.resolve("bob.vault"))){
            ChatServer host=new ChatServer(sv,new JoinAttemptLimiter(clock::get));
            try(ServerSocket available=new ServerSocket(0)){host.state.port=available.getLocalPort();}
            host.state.hashBits=8;host.setPassword("server join password".toCharArray());host.start("127.0.0.1");
            try(SocksFixture proxy=new SocksFixture(host.state.port)){
                ClientCore client=new ClientCore(cv);SavedServer s=new SavedServer();s.torOnly=true;s.torSocksPort=proxy.port();
                // This name cannot resolve in public/local DNS. Only the fixture knows how to reach it.
                s.address="unresolved-vch-test.invalid:"+host.state.port;
                client.connect(s,"server join password".toCharArray());
                check(client.connected(s)&&!s.fingerprint.isBlank(),"Tor join completes real SRP and pins the HTTPS certificate");
                check(proxy.destinations.size()>=5&&proxy.destinations.stream().allMatch(h->h.equals("unresolved-vch-test.invalid")),"bootstrap, login, profile and poll pass unresolved hostname to SOCKS5");
                String original=s.address;s.address="a".repeat(56)+".onion:"+host.state.port;
                client.poll(s);check(proxy.destinations.get(proxy.destinations.size()-1).endsWith(".onion"),"onion hostnames reach SOCKS without local resolution");
                s.address="[2001:db8::1]:"+host.state.port;client.poll(s);
                check(proxy.destinations.get(proxy.destinations.size()-1).contains(":"),"IPv6 literals use SOCKS IPv6 addressing");s.address=original;
                Channel text=s.channels.stream().filter(c->c.type.equals("text")).findFirst().orElseThrow();
                client.send(s,text,"text",Crypto.utf("message over Tor transport"));
                check(new String(client.decrypt(s,s.messages.get(0)),StandardCharsets.UTF_8).equals("message over Tor transport"),"encrypted send, polling and decryption work over SOCKS");
                ClientCore bob=new ClientCore(bv);SavedServer bs=new SavedServer();bs.address="https://127.0.0.1:"+host.state.port;
                clock.addAndGet(10000);bob.connect(bs,"server join password".toCharArray());client.poll(s);
                client.request(s,"friend",Json.obj("action","request","account",bob.account(bs)),true);
                bob.request(bs,"friend",Json.obj("action","accept","account",client.account(s)),true);client.poll(s);bob.poll(bs);
                int before=proxy.destinations.size();
                var call=client.request(s,"call",Json.obj("action","ring","account",bob.account(bs)),true);
                bob.request(bs,"call",Json.obj("action","accept","id",Json.str(call,"id")),true);
                call=bob.request(bs,"call",Json.obj("action","ring","account",client.account(s)),true);
                var accepted=client.request(s,"call",Json.obj("action","accept","id",Json.str(call,"id")),true);
                Channel voice=s.channels.stream().filter(c->c.id.equals(Json.str(accepted,"channel"))).findFirst().orElseThrow();
                try(VoiceSession session=new VoiceSession(client,s,voice,root)){
                    var state=session.request("/signal",Json.obj("action","claim"));
                    check("https-relay-v1".equals(Json.str(state,"transport")),"Tor voice session negotiates HTTPS relay");
                    bob.request(bs,"voice",Json.obj("channel",voice.id,"action","heartbeat"),true);
                    Envelope frame=client.envelope(s,voice.id,"voice",Crypto.utf("Tor voice tokens"));
                    session.request("/media",Json.obj("envelope",frame));
                    var inbox=bob.request(bs,"voice",Json.obj("channel",voice.id,"action","heartbeat","after",0),true);
                    Envelope delivered=Json.GSON.fromJson(inbox.getAsJsonArray("frames").get(0),Envelope.class);
                    check(Arrays.equals(bob.decrypt(bs,delivered),Crypto.utf("Tor voice tokens")),"encrypted voice crosses SOCKS to a direct participant");
                    String videoId=Json.str(session.request("/video/start",Json.obj("source","screen","kbps",1000)),"stream");
                    var videoEnvelope=session.request("/video/encode",Json.obj("stream",videoId,"data",Crypto.b64(Crypto.utf("Tor video keyframe"))));
                    session.request("/video/media",Json.obj("stream",videoId,"envelope",videoEnvelope));
                    var videoInbox=bob.request(bs,"video",Json.obj("channel",voice.id,"action","poll","after",0,"receive",java.util.List.of(videoId)),true);
                    Envelope videoFrame=Json.GSON.fromJson(videoInbox.getAsJsonArray("frames").get(0).getAsJsonObject().get("envelope"),Envelope.class);
                    check(Arrays.equals(bob.decrypt(bs,videoFrame),Crypto.utf("Tor video keyframe")),"encrypted video follows the saved SOCKS route without peer traffic");
                    session.request("/video/stop",Json.obj("stream",videoId));
                    session.request("/signal",Json.obj("action","release"));
                    bob.request(bs,"voice",Json.obj("channel",voice.id,"action","claim"),true);
                    frame=bob.envelope(bs,voice.id,"voice",Crypto.utf("direct voice tokens"));
                    bob.request(bs,"voice",Json.obj("channel",voice.id,"action","media","envelope",frame),true);
                    inbox=session.request("/signal",Json.obj("action","heartbeat"));
                    delivered=Json.GSON.fromJson(inbox.getAsJsonArray("frames").get(0),Envelope.class);
                    check(Arrays.equals(client.decrypt(s,delivered),Crypto.utf("direct voice tokens")),"Tor participant receives signed encrypted voice through SOCKS");
                }
                check(proxy.destinations.size()>=before+6,"call initiation, acceptance, voice upload, polling and leave all use SOCKS");
                String pin=s.fingerprint;s.fingerprint="0".repeat(64);
                rejects(()->client.poll(s),"Tor-only connection failed", "Tor preserves certificate pin rejection");s.fingerprint=pin;
                clock.addAndGet(10000);client.disconnect(s);client.connect(s,null);
                check(client.connected(s),"cached-credential reconnect uses Tor throughout");
                rejects(()->client.connectionSettings(s,true,0),"between 1 and 65535", "invalid proxy port rejected without changing connection");
                check(client.connected(s),"invalid settings leave the current connection intact");
                client.connectionSettings(s,true,proxy.port());
                check(!client.connected(s),"saving transport settings invalidates the old session");
                savedPort=proxy.port();
                clock.addAndGet(10000);client.connect(s,null);
                try(ServerSocket trap=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
                    trap.setSoTimeout(250);SavedServer target=new SavedServer();target.address="127.0.0.1:"+trap.getLocalPort();target.torOnly=true;target.torSocksPort=proxy.port();
                    proxy.reject=true;
                    rejects(()->client.connect(target,"server join password".toCharArray()),"No direct connection was attempted", "SOCKS destination refusal fails closed");
                    noConnection(trap,"SOCKS refusal never falls back to the destination");
                    proxy.reject=false;proxy.badMethod=true;
                    rejects(()->client.connect(target,"server join password".toCharArray()),"No direct connection was attempted", "invalid SOCKS negotiation fails closed");
                    noConnection(trap,"invalid SOCKS negotiation never falls back");
                    proxy.close();
                    rejects(()->client.connect(target,"server join password".toCharArray()),"No direct connection was attempted", "unavailable Tor fails closed on join");
                    noConnection(trap,"unavailable Tor makes no direct destination connection");
                    target.id=s.id;target.fingerprint=s.fingerprint;
                    rejects(()->client.request(target,"voice",Json.obj("channel",voice.id,"action","heartbeat"),true),"No direct connection was attempted","voice polling fails closed when Tor stops");
                    rejects(()->client.request(target,"voice",Json.obj("channel",voice.id,"action","media"),true),"No direct connection was attempted","voice upload fails closed when Tor stops");
                    rejects(()->client.request(target,"call",Json.obj("action","ring","account",bob.account(bs)),true),"No direct connection was attempted","calls fail closed when Tor stops");
                    rejects(()->client.request(target,"video",Json.obj("channel",voice.id,"action","poll"),true),"No direct connection was attempted","video fails closed when Tor stops");
                    noConnection(trap,"voice and calls never fall back to direct transport");
                    rejects(()->client.poll(s),"No direct connection was attempted", "polling after proxy shutdown also fails closed");
                }
            }finally{host.close();}
        }
        try(Vault cv=vault(clientPath)){
            ClientCore restored=new ClientCore(cv);SavedServer saved=restored.state.servers.get(0);
            check(saved.torOnly&&saved.torSocksPort==savedPort,"Tor-only choice and SOCKS port survive encrypted vault reload");
            SavedServer legacy=Json.GSON.fromJson("{\"address\":\"example.com\"}",SavedServer.class);
            check(!legacy.torOnly&&legacy.torSocksPort==9050,"legacy saved servers retain direct mode and a usable default Tor port");
        }
        System.out.println("Tor transport checks: "+checks);
    }
    private static void noConnection(ServerSocket trap,String label)throws Exception{
        try(Socket unexpected=trap.accept()){throw new AssertionError(label+": direct socket accepted");}
        catch(SocketTimeoutException expected){check(true,label);}
    }
    private static TorTransport.Reply reply(String text)throws Exception{return TorTransport.readReply(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));}
    private static void framing()throws Exception{
        check(new String(reply("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}").body(),StandardCharsets.US_ASCII).equals("{}"),"fixed-length HTTPS responses parse");
        check(new String(reply("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n{\r\n1;x=y\r\n}\r\n0\r\nX-Trailer: value\r\n\r\n").body(),StandardCharsets.US_ASCII).equals("{}"),"chunked HTTPS responses and trailers parse");
        check(reply("HTTP/1.0 429 Too Many Requests\r\n\r\n{}").status()==429,"HTTP error status is preserved");
        check(reply("HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1/\r\nContent-Length: 0\r\n\r\n").status()==302,"redirects are returned without following them");
        rejects(()->reply("HTTP/1.1 200 OK\r\nContent-Length: 24000001\r\n\r\n"),"exceeds limit","oversized responses rejected before allocation");
        rejects(()->reply("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nffffffff\r\n"),"exceeds limit","oversized chunks rejected");
        rejects(()->reply("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{"),"Truncated","truncated body rejected");
        rejects(()->reply("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n"),"framing","ambiguous HTTP framing rejected");
        rejects(()->reply("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 4\r\n\r\n{}"),"Duplicate","duplicate framing headers rejected");
    }

    static final class SocksFixture implements AutoCloseable {
        private final ServerSocket listener;
        private final int targetPort;
        private final ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"test-socks");t.setDaemon(true);return t;});
        private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        final List<String> destinations=new CopyOnWriteArrayList<>();
        volatile boolean reject,badMethod;
        SocksFixture(int targetPort)throws IOException{
            this.targetPort=targetPort;listener=new ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));
            workers.submit(()->{while(!listener.isClosed())try{Socket s=listener.accept();sockets.add(s);workers.submit(()->serve(s));}catch(IOException e){if(!listener.isClosed())throw new UncheckedIOException(e);}});
        }
        int port(){return listener.getLocalPort();}
        private void serve(Socket client){
            try(client){
                client.setSoTimeout(15000);DataInputStream in=new DataInputStream(client.getInputStream());OutputStream out=client.getOutputStream();
                if(in.readUnsignedByte()!=5||in.readUnsignedByte()!=1||in.readUnsignedByte()!=0)throw new IOException("Bad greeting");
                out.write(new byte[]{5,(byte)(badMethod?255:0)});out.flush();if(badMethod)return;
                if(in.readUnsignedByte()!=5||in.readUnsignedByte()!=1||in.readUnsignedByte()!=0)throw new IOException("Expected CONNECT");
                int type=in.readUnsignedByte();
                String host=switch(type){case 3->new String(in.readNBytes(in.readUnsignedByte()),StandardCharsets.US_ASCII);case 4->InetAddress.getByAddress(in.readNBytes(16)).getHostAddress();default->throw new IOException("Expected remote DNS or IPv6 CONNECT");};
                int port=in.readUnsignedShort();destinations.add(host);
                if(reject){out.write(new byte[]{5,5,0,1,0,0,0,0,0,0});out.flush();return;}
                if(port!=targetPort)throw new IOException("Unexpected target port");
                try(Socket target=new Socket(Proxy.NO_PROXY)){
                    sockets.add(target);target.connect(new InetSocketAddress("127.0.0.1",targetPort),3000);target.setSoTimeout(15000);
                    out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();
                    Future<?> upload=workers.submit(()->{try{in.transferTo(target.getOutputStream());target.shutdownOutput();}catch(IOException ignored){}});
                    target.getInputStream().transferTo(out);upload.get(15,TimeUnit.SECONDS);sockets.remove(target);
                }
            }catch(Exception ignored){/* TLS rejection and deliberate proxy shutdown close active tunnels. */}
            finally{sockets.remove(client);}
        }
        public void close()throws IOException{listener.close();for(Socket s:sockets)try{s.close();}catch(IOException ignored){}workers.shutdownNow();}
    }
}
