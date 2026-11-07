package chat.vch;
import chat.vch.Models.*;
import javax.swing.*;
import java.nio.file.*;

public final class PreviewApp {
    public static void main(String[] args)throws Exception{
        Path dir=Path.of("build/preview/"+java.util.UUID.randomUUID());Files.createDirectories(dir);
        Vault vault=new Vault(dir.resolve("data.vault"),"preview password long enough".toCharArray());
        if(args.length>0&&args[0].equals("server")){SwingUtilities.invokeLater(()->{Theme.install();try{new ServerApp(vault).setVisible(true);}catch(Exception e){throw new RuntimeException(e);}});return;}
        ClientCore core=new ClientCore(vault);core.state.name="Alex Morgan";SavedServer server=new SavedServer();server.id="preview-community";server.name="Studio workspace";server.address="https://community.example:8443";
        Channel general=new Channel();general.name="general";Channel design=new Channel();design.name="design-review";Channel voice=new Channel();voice.type="voice";voice.name="Team lounge";server.channels.add(general);server.channels.add(design);server.channels.add(voice);server.channelKeys.put(general.id,Crypto.b64(Crypto.random(32)));server.keyRevisions.put(general.id,1L);
        Account me=new Account();me.hash=core.account(server);me.publicKey=core.state.publicKey;me.name=core.state.name;server.accounts.put(me.hash,me);core.state.servers.add(server);
        server.messages.add(core.envelope(server,general.id,"text",Crypto.utf("Welcome to the studio. This is our space for ideas, updates and conversations.")));
        server.messages.add(core.envelope(server,general.id,"text",Crypto.utf("Your local archive is encrypted and stays available between restarts.\nUse the Team lounge when you would like to talk.")));core.save();
        SwingUtilities.invokeLater(()->{Theme.install();try{new ClientApp(vault).setVisible(true);}catch(Exception e){throw new RuntimeException(e);}});
    }
}
