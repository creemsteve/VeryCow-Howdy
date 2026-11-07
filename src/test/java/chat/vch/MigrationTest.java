package chat.vch;

import chat.vch.Models.*;
import java.nio.file.*;

public final class MigrationTest {
    private static int checks;
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    private static Vault open(Path file)throws Exception{return new Vault(file,"migration vault password".toCharArray());}
    public static void main(String[] args)throws Exception{
        Path root=Path.of("build/test-data/migration-"+java.util.UUID.randomUUID());Files.createDirectories(root);String serverId,pin,verifier;
        try(Vault sv=open(root.resolve("server.vault"));Vault cv=open(root.resolve("client.vault"))){
            ChatServer host=new ChatServer(sv);host.setPassword("server join password".toCharArray());serverId=host.state.id;pin=Tls.fingerprint(host.state.tlsStore);verifier=host.state.passwordVerifier;
            ClientCore client=new ClientCore(cv);client.state.name="Saved profile";SavedServer s=new SavedServer();s.id=serverId;s.name="Saved community";s.address="https://127.0.0.1:8443";s.fingerprint=pin;s.passwordKey=verifier;
            Channel channel=host.state.channels.get(0);s.channels.add(channel);s.channelKeys.put(channel.id,Crypto.b64(Crypto.random(32)));Account account=new Account();account.hash=client.account(s);account.publicKey=client.state.publicKey;account.name=client.state.name;s.accounts.put(account.hash,account);host.state.accounts.put(account.hash,account);s.messages.add(LegacyFixtures.message(client,s,channel.id,"history before simplified join"));client.state.servers.add(s);client.save();host.save();
            var raw=sv.data().getAsJsonObject("server");raw.remove("joinProtocol");raw.addProperty("port",8443);sv.save();
        }
        try(Vault sv=open(root.resolve("server.vault"));Vault cv=open(root.resolve("client.vault"))){
            ChatServer host=new ChatServer(sv);ClientCore client=new ClientCore(cv);SavedServer s=client.state.servers.get(0);
            check(host.state.port==585&&s.address.equals("https://127.0.0.1:585"),"legacy server and saved client default ports migrate together");
            check(host.state.id.equals(serverId)&&s.id.equals(serverId)&&client.state.name.equals("Saved profile"),"migration preserves server and profile identities");
            check(Tls.fingerprint(host.state.tlsStore).equals(pin)&&s.fingerprint.equals(pin),"migration preserves established certificate pins");
            check(host.state.passwordVerifier.equals(verifier)&&s.passwordKey.equals(verifier),"existing server password and saved credential remain usable");
            check(new String(client.decrypt(s,s.messages.get(0)),java.nio.charset.StandardCharsets.UTF_8).equals("history before simplified join"),"migration preserves decryptable chat history and channel keys");
            host.start("127.0.0.1");try{client.connect(s,null);check(client.connected(s),"migrated client reconnects using cached credentials and the new password handshake");check(new String(client.decrypt(s,s.messages.get(0)),java.nio.charset.StandardCharsets.UTF_8).equals("history before simplified join"),"automatic server keys preserve a locally held legacy manual key for old history");}finally{host.close();}
            host.state.port=8443;host.save();s.address="https://127.0.0.1:8443";client.save();
        }
        try(Vault sv=open(root.resolve("server.vault"));Vault cv=open(root.resolve("client.vault"))){ChatServer host=new ChatServer(sv);ClientCore client=new ClientCore(cv);check(host.state.port==8443&&client.state.servers.get(0).address.endsWith(":8443"),"explicit custom ports are preserved after the one-time migration");SavedServer s=client.state.servers.get(0);check(new String(client.decrypt(s,s.messages.get(0)),java.nio.charset.StandardCharsets.UTF_8).equals("history before simplified join"),"legacy manual-key history remains readable after another client restart");}
        System.out.println(checks+" migration checks passed");
    }
}
