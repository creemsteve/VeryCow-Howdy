package chat.vch;

import chat.vch.Models.*;
import java.nio.file.*;
import java.net.ServerSocket;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.*;

public final class IntegrationTest {
    private static int checks;
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action,String label)throws Exception{boolean failed=false;try{action.run();}catch(Exception e){failed=true;}check(failed,label);}
    private static Vault vault(Path p)throws Exception{return new Vault(p,"correct horse battery staple".toCharArray());}
    public static void main(String[] args)throws Exception{
        Path root=Path.of("build/test-data/"+UUID.randomUUID()).toAbsolutePath();Files.createDirectories(root);
        byte[] key=Crypto.random(32),aad=Crypto.utf("context");byte[] sealed=Crypto.seal(key,Crypto.utf("sensitive payload"),aad);
        check(Arrays.equals(Crypto.utf("sensitive payload"),Crypto.open(key,sealed,aad)),"AES-256-GCM round trip");
        sealed[15]^=1;rejects(()->Crypto.open(key,sealed,aad),"tampered ciphertext rejected");
        var identity=Crypto.identity();String pub=Crypto.b64(identity.getPublic().getEncoded()),sig=Crypto.sign(identity.getPrivate(),aad);
        check(Crypto.verify(pub,aad,sig),"ML-DSA-65 identity signature");check(!Crypto.verify(pub,Crypto.utf("other"),sig),"modified signed content rejected");
        byte[] compressed=Crypto.compress(new byte[10000]);rejects(()->Crypto.decompress(compressed,100),"decompression bomb bounded");
        Path vaultFile=root.resolve("integrity.vault");try(Vault v=vault(vaultFile)){v.data().addProperty("secret","unique plaintext marker");v.save();rejects(()->vault(vaultFile),"concurrent vault access rejected");}
        check(!new String(Files.readAllBytes(vaultFile),java.nio.charset.StandardCharsets.ISO_8859_1).contains("unique plaintext marker"),"vault does not contain plaintext");
        rejects(()->new Vault(vaultFile,"wrong password password".toCharArray()),"wrong vault password rejected");
        try(Vault v=vault(vaultFile)){check(Json.str(v.data(),"secret").equals("unique plaintext marker"),"vault survives restart");}
        BufferedImage image=new BufferedImage(1200,900,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(java.awt.Color.BLUE);g.fillRect(0,0,1200,900);g.dispose();Path png=root.resolve("source.png");ImageIO.write(image,"png",png.toFile());
        byte[] picture=Images.prepare(png,100000,1600),avatar=Images.prepare(png,10000,256);
        check(picture.length<=100000&&Images.isWebp(picture),"image converted to WebP under 100 KB");check(avatar.length<=10000&&Images.isWebp(avatar),"avatar converted to WebP under 10 KB");
        Path fake=root.resolve("video.mp4");Files.writeString(fake,"not an image");rejects(()->Images.prepare(fake,100000,1600),"video attachment rejected");
        try(Vault sv=vault(root.resolve("server.vault"));Vault av=vault(root.resolve("alice.vault"));Vault bv=vault(root.resolve("bob.vault"))){
            java.util.concurrent.atomic.AtomicLong joinClock=new java.util.concurrent.atomic.AtomicLong();
            ChatServer host=new ChatServer(sv,new JoinAttemptLimiter(joinClock::get));
            check(host.state.port==585,"new servers default to port 585");
            try(ServerSocket socket=new ServerSocket(0)){host.state.port=socket.getLocalPort();}
            host.state.hashBits=10;host.setPassword("server join password".toCharArray());host.start("127.0.0.1");
            try{
                ClientCore alice=new ClientCore(av),bob=new ClientCore(bv);SavedServer sa=endpoint(host),sb=endpoint(host);
                rejects(()->alice.connect(sa,"incorrect join password".toCharArray()),"wrong server password rejected");
                check(sa.fingerprint==null&&sa.id==null&&host.state.accounts.isEmpty(),"wrong password cannot save a fingerprint or register an account");
                rejectsWith(()->alice.connect(sa,"server join password".toCharArray()),"Wait 10 seconds","same-IP retry blocked before authentication");
                SavedServer badPin=endpoint(host);badPin.fingerprint="0".repeat(64);rejects(()->alice.connect(badPin,"server join password".toCharArray()),"wrong certificate fingerprint rejected");
                joinClock.set(9999);rejectsWith(()->alice.connect(sa,"server join password".toCharArray()),"Wait 1 seconds","network attempt blocked before ten-second boundary");
                joinClock.set(10000);alice.connect(sa,"server join password".toCharArray());
                check(sa.fingerprint.equals(Tls.fingerprint(host.state.tlsStore)),"password handshake automatically verifies and saves the observed certificate");
                rejectsWith(()->bob.connect(sb,"server join password".toCharArray()),"Wait 10 seconds","same IP cooldown also covers a different account");
                joinClock.addAndGet(10000);bob.connect(sb,"server join password".toCharArray());check(host.state.accounts.size()==2,"two clients register with negotiated hash challenge");
                rejectsWith(()->alice.request(sa,"login",Json.obj("nonce","replay"),false),"Wait 10 seconds","direct login cannot bypass the join cooldown");
                joinClock.addAndGet(10000);alice.connect(sa,null);check(alice.connected(sa),"saved credential reconnect authenticates the password-bound certificate");
                check(!Crypto.account("another-server",alice.state.publicKey).equals(alice.account(sa)),"account hash isolated per server");
                Channel text=sa.channels.stream().filter(c->c.type.equals("text")).findFirst().orElseThrow();
                check(sa.channelKeys.get(text.id).equals(sb.channelKeys.get(text.id))&&Crypto.unb64(sa.channelKeys.get(text.id)).length==32,"server automatically provides matching channel keys after password login");
                alice.state.name="Alice";alice.state.avatar=Crypto.b64(avatar);alice.updateProfile(sa);alice.send(sa,text,"text",Crypto.utf("hello encrypted community"));bob.poll(sb);
                check(new String(bob.decrypt(sb,sb.messages.get(0)),java.nio.charset.StandardCharsets.UTF_8).equals("hello encrypted community"),"two-client encrypted compressed text delivery");
                check(!Json.GSON.toJson(host.state).contains("hello encrypted community"),"relay never stores text plaintext");
                alice.send(sa,text,"image",picture);bob.poll(sb);check(Arrays.equals(picture,bob.decrypt(sb,sb.messages.get(1))),"encrypted WebP delivery");
                check(sb.accounts.get(alice.account(sa)).avatar.equals(Crypto.b64(avatar)),"profile picture stored server-side");
                host.rotateChannelKey(text.id);bob.poll(sb);check(new String(bob.decrypt(sb,sb.messages.get(0)),java.nio.charset.StandardCharsets.UTF_8).equals("hello encrypted community"),"server key rotation preserves archived history");
                Envelope replay=sa.messages.get(0);int old=host.state.messages.size();alice.request(sa,"send",Json.GSON.toJsonTree(replay).getAsJsonObject(),true);check(host.state.messages.size()==old,"repeated message ID deduplicated");
                rejects(()->alice.send(sa,text,"image",new byte[100001]),"oversized image rejected");
                rejects(()->alice.send(sa,text,"video",Crypto.utf("mp4")),"video message kind rejected");
                Channel voice=sa.channels.stream().filter(c->c.type.equals("voice")).findFirst().orElseThrow();
                var fa=alice.request(sa,"voice",Json.obj("channel",voice.id,"action","claim"),true);var fb=bob.request(sb,"voice",Json.obj("channel",voice.id,"action","claim"),true);
                check(Json.str(fa,"floor").equals(alice.account(sa))&&Json.str(fb,"floor").equals(alice.account(sa)),"half-duplex floor permits only one speaker");
                alice.request(sa,"voice",Json.obj("channel",voice.id,"action","signal","to",bob.account(sb),"data",Json.obj("candidate",Json.obj("candidate","test"))),true);
                fb=bob.request(sb,"voice",Json.obj("channel",voice.id,"action","heartbeat"),true);check(fb.getAsJsonArray("signals").size()==1,"voice signaling delivered to peer");
                alice.request(sa,"voice",Json.obj("channel",voice.id,"action","release"),true);fb=bob.request(sb,"voice",Json.obj("channel",voice.id,"action","claim"),true);check(Json.str(fb,"floor").equals(bob.account(sb)),"floor transfers after push-to-talk release");
                Channel privateChannel=new Channel();privateChannel.name="restricted";privateChannel.allow.add(alice.account(sa));host.state.channels.add(privateChannel);host.save();bob.poll(sb);check(sb.channels.stream().noneMatch(c->c.id.equals(privateChannel.id)),"private channel hidden from unauthorized account");
                check(!sb.channelKeys.containsKey(privateChannel.id),"restricted channel encryption key is withheld from unauthorized accounts");
                Envelope forbidden=alice.envelope(sa,text.id,"text",Crypto.utf("forbidden"));forbidden.channel=privateChannel.id;forbidden.author=bob.account(sb);
                rejects(()->bob.request(sb,"send",Json.GSON.toJsonTree(forbidden).getAsJsonObject(),true),"unauthorized send rejected");
                alice.save();bob.save();host.ban(bob.account(sb),false);rejects(()->bob.poll(sb),"account hash ban revokes active session");
                host.ban("127.0.0.1",true);rejects(()->alice.poll(sa),"IP ban blocks active client");
            }finally{host.close();}
        }
        try(Vault bv=vault(root.resolve("bob.vault"));Vault sv=vault(root.resolve("server.vault"))){ClientCore bob=new ClientCore(bv);SavedServer saved=bob.state.servers.get(0);check(saved.messages.size()==2,"message history persisted across client restart");check(new String(bob.decrypt(saved,saved.messages.get(0)),java.nio.charset.StandardCharsets.UTF_8).equals("hello encrypted community"),"persisted history decrypts after unlock");ChatServer host=new ChatServer(sv);check(host.state.bannedHashes.size()==1&&host.state.bannedIps.size()==1,"bans persist across server restart");}
        System.out.println("\n"+checks+" checks passed. Test data: "+root);
    }
    private static void rejectsWith(Action action,String expected,String label)throws Exception{try{action.run();throw new AssertionError(label);}catch(Exception e){check(e.getMessage()!=null&&e.getMessage().contains(expected),label+" ("+e.getMessage()+")");}}
    private static SavedServer endpoint(ChatServer host)throws Exception{SavedServer s=new SavedServer();s.address="127.0.0.1:"+host.state.port;return s;}
}
