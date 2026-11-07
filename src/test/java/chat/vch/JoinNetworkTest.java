package chat.vch;

import chat.vch.Models.*;
import com.sun.net.httpserver.*;
import javax.net.ssl.HttpsURLConnection;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;

/** Exercises the production clock, actual default port, HTTP headers, and a forged server proof. */
public final class JoinNetworkTest {
    private static int checks;
    private static void check(boolean pass,String label){if(!pass)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static Vault vault(Path p)throws Exception{return new Vault(p,"join network test password".toCharArray());}
    public static void main(String[] args)throws Exception{
        Path root=Path.of("build/test-data/join-network-"+java.util.UUID.randomUUID());Files.createDirectories(root);
        try(Vault sv=vault(root.resolve("host.vault"));Vault cv=vault(root.resolve("client.vault"))){
            ChatServer server=new ChatServer(sv);server.state.hashBits=8;server.setPassword("server join password".toCharArray());server.start("127.0.0.1");
            try{
                ClientCore client=new ClientCore(cv);SavedServer s=new SavedServer();s.address="127.0.0.1";
                client.connect(s,"server join password".toCharArray());
                check(client.connected(s)&&s.address.equals("https://127.0.0.1:585"),"host-only input joins the actual default port 585");
                HttpsURLConnection c=(HttpsURLConnection)new URL(s.address+"/api/challenge").openConnection();c.setSSLSocketFactory(PinnedTls.create(s.fingerprint).getSocketFactory());c.setHostnameVerifier((host,session)->true);c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(3000);c.setReadTimeout(3000);
                try{try(OutputStream out=c.getOutputStream()){out.write(Crypto.utf("{}"));}check(c.getResponseCode()==429,"production IP cooldown returns HTTP 429");int retry=Integer.parseInt(c.getHeaderField("Retry-After"));check(retry>=1&&retry<=10,"retry response includes an accurate Retry-After header");try(InputStream in=c.getErrorStream()){check(Json.num(Json.parse(in.readAllBytes()),"retryAfterSeconds",0)==retry,"retry JSON and header agree");}}finally{c.disconnect();}
                client.poll(s);check(client.connected(s),"join cooldown does not block authenticated chat polling");
            }finally{server.close();}
        }
        // Wrong-password attempts against a spoofed certificate must not silently trust that certificate.
        String tlsStore=Tls.create(),fingerprint=Tls.fingerprint(tlsStore),id="forged-evidence-test";byte[] salt=Crypto.random(16),key=Crypto.passwordKey("server join password".toCharArray(),salt);
        var srp=JoinAuth.server(salt,JoinAuth.identity(id,fingerprint),key);String b=srp.generateServerCredentials().toString(16);
        AtomicBoolean identityDisclosed=new AtomicBoolean(),authReached=new AtomicBoolean();HttpsServer rogue=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),8);rogue.setHttpsConfigurator(new HttpsConfigurator(Tls.server(tlsStore)));
        rogue.createContext("/api/",x->{try{byte[] input=x.getRequestBody().readAllBytes();String raw=new String(input,java.nio.charset.StandardCharsets.UTF_8);if(raw.contains("publicKey")||raw.contains("profile"))identityDisclosed.set(true);var in=Json.parse(input);com.google.gson.JsonObject result;
            if(x.getRequestURI().getPath().equals("/api/challenge"))result=Json.obj("auth",JoinAuth.PROTOCOL,"server",id,"name","Untrusted","nonce","test-nonce","salt",Crypto.b64(salt),"bits",8,"algorithm","SHA-256","B",b);
            else if(x.getRequestURI().getPath().equals("/api/auth")){authReached.set(true);srp.calculateSecret(JoinAuth.number(Json.str(in,"A")));if(!srp.verifyClientEvidenceMessage(JoinAuth.number(Json.str(in,"M1"))))throw new IOException("Unexpected client proof");result=Json.obj("M2","1");}
            else{identityDisclosed.set(true);result=Json.obj("error","Unexpected identity request");}
            byte[] output=Json.bytes(result);x.sendResponseHeaders(200,output.length);try(OutputStream out=x.getResponseBody()){out.write(output);}
        }catch(Exception e){x.sendResponseHeaders(400,-1);}finally{x.close();}});rogue.start();
        try(Vault cv=vault(root.resolve("victim.vault"))){ClientCore client=new ClientCore(cv);SavedServer s=new SavedServer();s.address="127.0.0.1:"+rogue.getAddress().getPort();boolean refused=false;try{client.connect(s,"server join password".toCharArray());}catch(Exception e){refused=true;}
            check(refused&&authReached.get(),"forged server password evidence is rejected after the exchange");check(s.fingerprint==null&&s.passwordKey==null&&client.state.servers.isEmpty(),"forged evidence cannot persist certificate trust or cached credentials");check(!identityDisclosed.get(),"account identity is withheld until server password proof is verified");
        }finally{rogue.stop(0);java.util.Arrays.fill(key,(byte)0);}
        // Malformed initial attempts still occupy the same cooldown slot.
        AtomicLong clock=new AtomicLong();try(Vault sv=vault(root.resolve("malformed-host.vault"))){ChatServer host=new ChatServer(sv,new JoinAttemptLimiter(clock::get));host.state.port=0;host.setPassword("server join password".toCharArray());
            try(java.net.ServerSocket available=new java.net.ServerSocket(0)){host.state.port=available.getLocalPort();}host.start("127.0.0.1");
            try{int first=rawChallenge(host,"{");int second=rawChallenge(host,"{}");check(first==400&&second==429,"malformed initial request also consumes the ten-second slot");clock.addAndGet(10000);check(rawChallenge(host,"{}")==200,"fresh challenge admitted after the ten-second slot expires");
                clock.addAndGet(10000);Reply challenge=rawReply(host,"challenge",Json.obj());clock.addAndGet(10000);Reply invalid=rawReply(host,"auth",Json.obj("nonce","invalid"));Reply stale=rawReply(host,"auth",Json.obj("nonce",Json.str(challenge.body,"nonce"),"A","1","M1","1"));check(invalid.status==401&&stale.status==429,"direct-auth admission invalidates older unfinished password challenges");
            }finally{host.close();}}
        AtomicLong delayedClock=new AtomicLong();java.util.concurrent.CountDownLatch admitted=new java.util.concurrent.CountDownLatch(1);
        try(Vault sv=vault(root.resolve("delayed-host.vault"))){ChatServer host=new ChatServer(sv,new JoinAttemptLimiter(()->{admitted.countDown();return delayedClock.get();}));host.setPassword("server join password".toCharArray());try(java.net.ServerSocket available=new java.net.ServerSocket(0)){host.state.port=available.getLocalPort();}host.start("127.0.0.1");
            HttpsURLConnection delayed=(HttpsURLConnection)new URL("https://127.0.0.1:"+host.state.port+"/api/challenge").openConnection();delayed.setSSLSocketFactory(PinnedTls.create(Tls.fingerprint(host.state.tlsStore)).getSocketFactory());delayed.setHostnameVerifier((h,s)->true);delayed.setRequestMethod("POST");delayed.setDoOutput(true);delayed.setFixedLengthStreamingMode(2);delayed.setReadTimeout(5000);
            try{OutputStream body=delayed.getOutputStream();body.write('{');body.flush();if(!admitted.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Delayed request was not admitted");delayedClock.set(10000);check(rawChallenge(host,"{}")==200,"a new ten-second slot can replace a stalled initial request");body.write('}');body.close();check(delayed.getResponseCode()==429,"stalled request cannot mint another password attempt after a newer slot");}finally{delayed.disconnect();host.close();}}
        System.out.println(checks+" join network checks passed");
    }
    private static int rawChallenge(ChatServer host,String body)throws Exception{HttpsURLConnection c=(HttpsURLConnection)new URL("https://127.0.0.1:"+host.state.port+"/api/challenge").openConnection();c.setSSLSocketFactory(PinnedTls.create(Tls.fingerprint(host.state.tlsStore)).getSocketFactory());c.setHostnameVerifier((h,s)->true);c.setRequestMethod("POST");c.setDoOutput(true);try{try(OutputStream out=c.getOutputStream()){out.write(Crypto.utf(body));}return c.getResponseCode();}finally{c.disconnect();}}
    private record Reply(int status,com.google.gson.JsonObject body){}
    private static Reply rawReply(ChatServer host,String path,com.google.gson.JsonObject data)throws Exception{HttpsURLConnection c=(HttpsURLConnection)new URL("https://127.0.0.1:"+host.state.port+"/api/"+path).openConnection();c.setSSLSocketFactory(PinnedTls.create(Tls.fingerprint(host.state.tlsStore)).getSocketFactory());c.setHostnameVerifier((h,s)->true);c.setRequestMethod("POST");c.setDoOutput(true);try{try(OutputStream out=c.getOutputStream()){out.write(Json.bytes(data));}int status=c.getResponseCode();try(InputStream in=status==200?c.getInputStream():c.getErrorStream()){return new Reply(status,Json.parse(in.readAllBytes()));}}finally{c.disconnect();}}
}
