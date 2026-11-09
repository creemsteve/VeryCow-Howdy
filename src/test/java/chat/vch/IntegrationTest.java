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
                alice.state.pronouns="they/them";alice.updateProfile(sa);bob.poll(sb);check("they/them".equals(sb.accounts.get(alice.account(sa)).pronouns),"pronouns delivered with profile");
                alice.request(sa,"typing",Json.obj("channel",text.id),true);bob.poll(sb);check(text.id.equals(bob.typing.get(sb.id).get(alice.account(sa))),"typing status delivered to channel members");
                alice.request(sa,"typing",Json.obj("channel",""),true);bob.poll(sb);check(!bob.typing.get(sb.id).containsKey(alice.account(sa)),"typing status can be cleared");
                byte[] collection=Json.bytes(Json.obj("images",List.of(Crypto.b64(picture),Crypto.b64(picture))));alice.send(sa,text,"images",collection);bob.poll(sb);check(Arrays.equals(collection,bob.decrypt(sb,sb.messages.get(2))),"multiple images delivered in one encrypted message");
                rejects(()->bob.request(sb,"friend",Json.obj("account",alice.account(sa),"action","accept"),true),"unsolicited friend acceptance rejected");
                alice.request(sa,"friend",Json.obj("account",bob.account(sb),"action","request"),true);bob.poll(sb);check(sb.friendRequests.contains(alice.account(sa)),"friend request delivered");
                bob.request(sb,"friend",Json.obj("account",alice.account(sa),"action","accept"),true);alice.poll(sa);bob.poll(sb);
                Friendship friendship=sa.friendships.get(0);Channel dm=sa.channels.stream().filter(c->c.id.equals(friendship.textChannel)).findFirst().orElseThrow();
                alice.send(sa,dm,"text",Crypto.utf("private hello"));bob.poll(sb);Envelope direct=sb.messages.stream().filter(m->m.channel.equals(dm.id)).findFirst().orElseThrow();check(new String(bob.decrypt(sb,direct),java.nio.charset.StandardCharsets.UTF_8).equals("private hello"),"friends exchange encrypted direct messages");
                check(sa.channels.stream().anyMatch(c->c.id.equals(friendship.voiceChannel)&&c.maxMembers==2),"friend call channel created");
                var call=alice.request(sa,"call",Json.obj("action","ring","account",bob.account(sb)),true);String callId=Json.str(call,"id");
                bob.poll(sb);check(bob.incomingCalls.get(sb.id).size()==1 && bob.incomingCalls.get(sb.id).get(0).channel.equals(friendship.voiceChannel),"friend receives call invitation");
                rejects(()->alice.request(sa,"call",Json.obj("action","accept","id",callId),true),"caller cannot accept recipient invitation");
                bob.request(sb,"call",Json.obj("action","accept","id",callId),true);bob.poll(sb);check(bob.incomingCalls.get(sb.id).isEmpty(),"answer clears incoming ring");
                rejects(()->bob.request(sb,"call",Json.obj("action","accept","id",callId),true),"answered invitation cannot be replayed");
                call=alice.request(sa,"call",Json.obj("action","ring","account",bob.account(sb)),true);String declinedId=Json.str(call,"id");
                bob.request(sb,"call",Json.obj("action","decline","id",declinedId),true);bob.poll(sb);check(bob.incomingCalls.get(sb.id).isEmpty(),"decline clears incoming ring");
                call=alice.request(sa,"call",Json.obj("action","ring","account",bob.account(sb)),true);String canceledId=Json.str(call,"id");
                alice.request(sa,"call",Json.obj("action","cancel","id",canceledId),true);bob.poll(sb);check(bob.incomingCalls.get(sb.id).isEmpty(),"caller can cancel a ringing invitation");

                alice.request(sa,"voice",Json.obj("channel",friendship.voiceChannel,"action","heartbeat"),true);bob.request(sb,"voice",Json.obj("channel",friendship.voiceChannel,"action","heartbeat"),true);
                alice.request(sa,"voice",Json.obj("channel",friendship.voiceChannel,"action","claim"),true);
                Envelope callFrame=alice.envelope(sa,friendship.voiceChannel,"voice",Crypto.utf("call tokens"));
                alice.request(sa,"voice",Json.obj("channel",friendship.voiceChannel,"action","media","envelope",callFrame),true);
                check(bob.request(sb,"voice",Json.obj("channel",friendship.voiceChannel,"action","heartbeat","after",0),true).getAsJsonArray("frames").size()==1,"friend call audio is relayed through server");
                bob.request(sb,"friend",Json.obj("account",alice.account(sa),"action","remove"),true);rejects(()->alice.request(sa,"call",Json.obj("action","ring","account",bob.account(sb)),true),"calling a removed friend is rejected");alice.poll(sa);check(sa.friendships.isEmpty()&&sa.channels.stream().noneMatch(c->c.id.equals(friendship.voiceChannel)),"removing friend revokes direct channels");
                host.rotateChannelKey(text.id);bob.poll(sb);check(new String(bob.decrypt(sb,sb.messages.get(0)),java.nio.charset.StandardCharsets.UTF_8).equals("hello encrypted community"),"server key rotation preserves archived history");
                Envelope replay=sa.messages.get(0);int old=host.state.messages.size();alice.request(sa,"send",Json.GSON.toJsonTree(replay).getAsJsonObject(),true);check(host.state.messages.size()==old,"repeated message ID deduplicated");
                rejects(()->alice.send(sa,text,"image",new byte[100001]),"oversized image rejected");
                rejects(()->alice.send(sa,text,"video",Crypto.utf("mp4")),"video message kind rejected");
                Channel voice=sa.channels.stream().filter(c->c.type.equals("voice")).findFirst().orElseThrow();
                var fa=alice.request(sa,"voice",Json.obj("channel",voice.id,"action","claim"),true);var fb=bob.request(sb,"voice",Json.obj("channel",voice.id,"action","claim"),true);
                check(Json.str(fa,"floor").equals(alice.account(sa))&&Json.str(fb,"floor").equals(alice.account(sa)),"half-duplex floor permits only one speaker");
                Envelope voiceFrame=alice.envelope(sa,voice.id,"voice",Crypto.utf("voice tokens"));
                alice.request(sa,"voice",Json.obj("channel",voice.id,"action","media","envelope",voiceFrame),true);
                fb=bob.request(sb,"voice",Json.obj("channel",voice.id,"action","heartbeat","after",0),true);check(fb.getAsJsonArray("frames").size()==1,"voice media delivered through server");
                alice.request(sa,"voice",Json.obj("channel",voice.id,"action","release"),true);fb=bob.request(sb,"voice",Json.obj("channel",voice.id,"action","claim"),true);check(Json.str(fb,"floor").equals(bob.account(sb)),"floor transfers after push-to-talk release");
                Channel privateChannel=new Channel();privateChannel.name="restricted";privateChannel.allow.add(alice.account(sa));host.state.channels.add(privateChannel);host.save();bob.poll(sb);check(sb.channels.stream().noneMatch(c->c.id.equals(privateChannel.id)),"private channel hidden from unauthorized account");
                check(!sb.channelKeys.containsKey(privateChannel.id),"restricted channel encryption key is withheld from unauthorized accounts");
                var config=Json.obj("name","Updated community","description","A description","image",Crypto.b64(avatar),"channels",host.state.channels);
                rejects(()->bob.request(sb,"admin",config,true),"non-admin cannot change server layout");
                host.state.admins.add(bob.account(sb));host.save();bob.poll(sb);
                check(sb.admin && sb.adminChannels.stream().anyMatch(c->c.id.equals(privateChannel.id)) && !sb.channelKeys.containsKey(privateChannel.id),"admin sees full layout without receiving restricted channel keys");
                bob.request(sb,"admin",config,true);alice.poll(sa);
                check("Updated community".equals(sa.name) && "A description".equals(sa.description) && Crypto.b64(avatar).equals(sa.image),"admin server customization reaches clients");
                var invalid=Json.obj("name","","channels",host.state.channels);rejects(()->bob.request(sb,"admin",invalid,true),"invalid server customization rejected");

                Envelope forbidden=alice.envelope(sa,text.id,"text",Crypto.utf("forbidden"));forbidden.channel=privateChannel.id;forbidden.author=bob.account(sb);
                rejects(()->bob.request(sb,"send",Json.GSON.toJsonTree(forbidden).getAsJsonObject(),true),"unauthorized send rejected");
                host.state.admins.add(alice.account(sa));host.state.admins.remove(bob.account(sb));host.save();
                Role lower=new Role();lower.name="Member";Role manager=new Role();manager.name="Manager";manager.members.add(bob.account(sb));manager.permissions.add("manage_roles");
                alice.request(sa,"roles",Json.obj("roles",List.of(lower,manager),"everyonePermissions",List.of("view","connect")),true);bob.poll(sb);
                check(sb.roleManager && !sb.admin,"role manager permission is delivered without administrator status");
                rejects(()->bob.send(sb,text,"text",Crypto.utf("forbidden")),"server enforces role send permission");
                Role elevated=Json.GSON.fromJson(Json.GSON.toJson(manager),Role.class);elevated.permissions.add("send");
                rejects(()->bob.request(sb,"roles",Json.obj("roles",List.of(lower,elevated),"everyonePermissions",List.of("view","connect")),true),"role manager cannot edit own role");
                Role grant=Json.GSON.fromJson(Json.GSON.toJson(lower),Role.class);grant.permissions.add("send");
                rejects(()->bob.request(sb,"roles",Json.obj("roles",List.of(grant,manager),"everyonePermissions",List.of("view","connect")),true),"role manager cannot grant a permission they lack");
                Role assignAdmin=Json.GSON.fromJson(Json.GSON.toJson(lower),Role.class);assignAdmin.members.add(alice.account(sa));
                rejects(()->bob.request(sb,"roles",Json.obj("roles",List.of(assignAdmin,manager),"everyonePermissions",List.of("view","connect")),true),"role manager cannot assign roles to an administrator");
                manager.permissions.add("send");alice.request(sa,"roles",Json.obj("roles",List.of(lower,manager),"everyonePermissions",List.of("view","connect")),true);
                bob.request(sb,"roles",Json.obj("roles",List.of(grant,manager),"everyonePermissions",List.of("view","connect")),true);
                check(host.state.roles.get(0).permissions.contains("send"),"manager may edit lower roles within their permissions");
                alice.request(sa,"roles",Json.obj("roles",List.of(grant,manager),"everyonePermissions",List.of("view")),true);
                rejects(()->bob.request(sb,"voice",Json.obj("channel",voice.id,"action","heartbeat"),true),"server enforces role voice permission");
                alice.request(sa,"roles",Json.obj("roles",List.of(grant,manager),"everyonePermissions",List.of("view","connect")),true);
                grant.members.add(bob.account(sb));alice.request(sa,"roles",Json.obj("roles",List.of(grant,manager),"everyonePermissions",List.of("view","connect")),true);
                Channel configured=host.state.channels.stream().filter(c->c.id.equals(text.id)).findFirst().orElseThrow();configured.roleOverrides.put("everyone",Map.of("view",false));configured.roleOverrides.put(grant.id,Map.of("view",false));configured.roleOverrides.put(manager.id,Map.of("view",true));host.save();bob.poll(sb);
                check(sb.channels.stream().anyMatch(c->c.id.equals(text.id)),"role channel allow overrides everyone and role denies");
                long revision=host.state.channelKeys.get(text.id).revision;configured.roleOverrides.remove(manager.id);host.save();
                check(host.state.channelKeys.get(text.id).revision>revision,"role access changes rotate channel keys");
                var keys=bob.request(sb,"keys",Json.obj(),true).getAsJsonObject("channelKeys");check(!keys.has(text.id),"role-restricted channel key is withheld");
                rejects(()->bob.request(sb,"typing",Json.obj("channel",text.id),true),"role-restricted channel rejects access");
                alice.save();bob.save();host.ban(bob.account(sb),false);rejects(()->bob.poll(sb),"account hash ban revokes active session");
                host.ban("127.0.0.1",true);rejects(()->alice.poll(sa),"IP ban blocks active client");
            }finally{host.close();}
        }
        try(Vault bv=vault(root.resolve("bob.vault"));Vault sv=vault(root.resolve("server.vault"))){ClientCore bob=new ClientCore(bv);SavedServer saved=bob.state.servers.get(0);check(saved.messages.size()==4,"message history persisted across client restart");check(new String(bob.decrypt(saved,saved.messages.get(0)),java.nio.charset.StandardCharsets.UTF_8).equals("hello encrypted community"),"persisted history decrypts after unlock");ChatServer host=new ChatServer(sv);check(host.state.bannedHashes.size()==1&&host.state.bannedIps.size()==1,"bans persist across server restart");check(host.state.roles.size()==2 && host.state.roles.get(1).permissions.contains("manage_roles"),"ordered roles persist across server restart");}
        System.out.println("\n"+checks+" checks passed. Test data: "+root);
    }
    private static void rejectsWith(Action action,String expected,String label)throws Exception{try{action.run();throw new AssertionError(label);}catch(Exception e){check(e.getMessage()!=null&&e.getMessage().contains(expected),label+" ("+e.getMessage()+")");}}
    private static SavedServer endpoint(ChatServer host)throws Exception{SavedServer s=new SavedServer();s.address="127.0.0.1:"+host.state.port;return s;}
}
