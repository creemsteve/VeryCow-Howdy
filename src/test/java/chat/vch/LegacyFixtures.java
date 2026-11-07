package chat.vch;
import chat.vch.Models.*;
final class LegacyFixtures {
    static Envelope message(ClientCore core,SavedServer server,String channel,String text)throws Exception{
        Envelope e=new Envelope();e.id=java.util.UUID.randomUUID().toString();e.server=server.id;e.channel=channel;e.author=core.account(server);e.kind="text";e.time=System.currentTimeMillis();
        e.cipher=Crypto.b64(Crypto.seal(Crypto.unb64(server.channelKeys.get(channel)),Crypto.compress(Crypto.utf(text)),e.aad()));e.signature=Crypto.sign(Crypto.privateKey(core.state.privateKey),e.signedBytes());return e;
    }
}
