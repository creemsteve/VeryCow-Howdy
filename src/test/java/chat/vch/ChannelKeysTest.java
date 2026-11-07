package chat.vch;

import chat.vch.Models.*;
import java.nio.file.*;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class ChannelKeysTest {
    private static int checks;
    private static void check(boolean pass,String label){if(!pass)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action run,String label)throws Exception{boolean rejected=false;try{run.run();}catch(Exception e){rejected=true;}check(rejected,label);}
    private static Vault vault(Path path)throws Exception{return new Vault(path,"channel key test password".toCharArray());}
    private static SavedServer endpoint(ChatServer server)throws Exception{SavedServer s=new SavedServer();s.address="127.0.0.1:"+server.state.port;return s;}
    private static String plain(ClientCore core,SavedServer s,Envelope e)throws Exception{return new String(core.decrypt(s,e),java.nio.charset.StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of("build/test-data/channel-keys-"+UUID.randomUUID());Files.createDirectories(root);AtomicLong clock=new AtomicLong();String currentAtRestart;long revisionAtRestart;String textId;
        try(Vault sv=vault(root.resolve("server.vault"));Vault av=vault(root.resolve("alice.vault"));Vault bv=vault(root.resolve("bob.vault"));Vault cv=vault(root.resolve("carol.vault"))){
            ChatServer host=new ChatServer(sv,new JoinAttemptLimiter(clock::get));try(ServerSocket port=new ServerSocket(0)){host.state.port=port.getLocalPort();}host.state.hashBits=8;host.setPassword("server join password".toCharArray());
            check(host.state.channelKeys.size()==host.state.channels.size(),"server generates keys for both default channel types");
            Set<String> unique=new HashSet<>();for(ChannelKeySet keys:host.state.channelKeys.values()){check(Crypto.unb64(keys.current).length==32,"server-generated key has 256 random bits");unique.add(keys.current);}check(unique.size()==host.state.channels.size(),"each channel has an independent key");
            host.start("127.0.0.1");
            try{
                ClientCore alice=new ClientCore(av),bob=new ClientCore(bv),carol=new ClientCore(cv);SavedServer a=endpoint(host),b=endpoint(host),c=endpoint(host);
                rejects(()->alice.connect(a,"incorrect password here".toCharArray()),"incorrect password cannot receive channel keys");check(a.channelKeys.isEmpty()&&a.keyRevisions.isEmpty(),"failed authentication leaves client key storage empty");
                SavedServer probe=endpoint(host);probe.fingerprint=Tls.fingerprint(host.state.tlsStore);rejects(()->alice.request(probe,"keys",Json.obj(),false),"unauthenticated key endpoint is denied");
                clock.addAndGet(10000);alice.connect(a,"server join password".toCharArray());clock.addAndGet(10000);bob.connect(b,"server join password".toCharArray());
                Channel text=a.channels.stream().filter(ch->ch.type.equals("text")).findFirst().orElseThrow();textId=text.id;
                check(a.channelKeys.get(text.id).equals(host.state.channelKeys.get(text.id).current)&&a.channelKeys.equals(b.channelKeys),"authenticated users receive the server's matching text and voice keys");
                alice.send(a,text,"text",Crypto.utf("before rotation"));bob.poll(b);Envelope before=b.messages.get(0);String old=b.channelKeys.get(text.id);
                host.rotateChannelKey(text.id);alice.send(a,text,"text",Crypto.utf("after automatic refresh"));bob.poll(b);
                check(!old.equals(b.channelKeys.get(text.id))&&b.keyRevisions.get(text.id)==2,"rotation is refreshed automatically without client key entry");
                check(plain(bob,b,before).equals("before rotation")&&plain(bob,b,b.messages.get(1)).equals("after automatic refresh"),"old and new history decrypt after server rotation");
                check(host.state.messages.size()==2,"a stale sender refreshes and retries without duplicate messages");
                byte[] cipher=Crypto.unb64(b.messages.get(1).cipher);rejects(()->Crypto.open(Crypto.unb64(old),cipher,b.messages.get(1).aad()),"previous key cannot decrypt post-rotation traffic");
                rejects(()->alice.request(probe,"send",Json.GSON.toJsonTree(alice.envelope(a,text.id,"text",Crypto.utf("not sent"))).getAsJsonObject(),false),"key knowledge does not bypass session authentication");
                Envelope stale=alice.envelope(a,text.id,"text",Crypto.utf("stale frame"));host.rotateChannelKey(text.id);rejects(()->alice.request(a,"send",Json.GSON.toJsonTree(stale).getAsJsonObject(),true),"server rejects a previously prepared envelope from an old key revision");
                alice.poll(a);bob.poll(b);
                clock.addAndGet(10000);carol.connect(c,"server join password".toCharArray());check(plain(carol,c,c.messages.get(0)).equals("before rotation"),"new authorized member receives managed history keys");
                Channel restricted=new Channel();restricted.name="private";restricted.allow.add(alice.account(a));host.state.channels.add(restricted);host.save();alice.poll(a);bob.poll(b);
                check(a.channelKeys.containsKey(restricted.id)&&!b.channelKeys.containsKey(restricted.id),"channel ACL controls automatic key delivery");
                var bobKeys=bob.request(b,"keys",Json.obj(),true).getAsJsonObject("channelKeys");check(!bobKeys.has(restricted.id),"key endpoint filters restricted channel secrets");
                String privateOld=host.state.channelKeys.get(restricted.id).current;restricted.allow.add(bob.account(b));host.save();bob.poll(b);check(b.channelKeys.containsKey(restricted.id),"granting channel access delivers a key automatically");
                restricted.allow.remove(bob.account(b));host.save();alice.poll(a);check(!privateOld.equals(a.channelKeys.get(restricted.id)),"channel access changes rotate encryption keys");check(!bob.request(b,"keys",Json.obj(),true).getAsJsonObject("channelKeys").has(restricted.id),"removed member cannot fetch current private-channel keys");
                String bannedOld=b.channelKeys.get(text.id);host.ban(bob.account(b),false);rejects(()->bob.refreshChannelKeys(b),"account ban revokes key delivery to existing sessions");alice.poll(a);check(!bannedOld.equals(a.channelKeys.get(text.id)),"account ban rotates future channel traffic keys");
                String passwordOld=a.channelKeys.get(text.id);host.setPassword("replacement server password".toCharArray());rejects(()->alice.refreshChannelKeys(a),"password change revokes previous sessions before key delivery");clock.addAndGet(10000);alice.connect(a,"replacement server password".toCharArray());check(!passwordOld.equals(a.channelKeys.get(text.id)),"new password login receives rotated channel keys");
                String ipOld=a.channelKeys.get(text.id);host.ban("127.0.0.1",true);rejects(()->alice.refreshChannelKeys(a),"IP ban blocks current key retrieval");check(!ipOld.equals(host.state.channelKeys.get(text.id).current),"IP ban also rotates channel keys");
                currentAtRestart=host.state.channelKeys.get(text.id).current;revisionAtRestart=host.state.channelKeys.get(text.id).revision;
                String onDisk=new String(Files.readAllBytes(root.resolve("server.vault")),java.nio.charset.StandardCharsets.ISO_8859_1);check(!onDisk.contains(currentAtRestart),"server channel keys are encrypted in the server vault");
            }finally{host.close();}
        }
        try(Vault sv=vault(root.resolve("server.vault"));Vault av=vault(root.resolve("alice.vault"))){ChatServer host=new ChatServer(sv);ClientCore alice=new ClientCore(av);SavedServer saved=alice.state.servers.get(0);check(host.state.channelKeys.get(textId).current.equals(currentAtRestart)&&host.state.channelKeys.get(textId).revision==revisionAtRestart,"server restart preserves current keys and revisions");check(plain(alice,saved,saved.messages.get(0)).equals("before rotation"),"client restart preserves readable managed history");}
        System.out.println(checks+" managed channel-key checks passed");
    }
}
