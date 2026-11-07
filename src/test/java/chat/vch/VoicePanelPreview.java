package chat.vch;

import chat.vch.Models.*;
import javax.swing.*;
import java.awt.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

/** Full client UI using a disposable local community and synthetic audio devices. */
public final class VoicePanelPreview {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createDirectories(Path.of("build/voice-ui/"+java.util.UUID.randomUUID()));
        try(Vault sv=new Vault(dir.resolve("host.vault"),"preview password long enough".toCharArray());
            Vault cv=new Vault(dir.resolve("client.vault"),"preview password long enough".toCharArray());
            ChatServer host=new ChatServer(sv);ClientCore core=new ClientCore(cv)) {
            try(ServerSocket port=new ServerSocket(0)){host.state.port=port.getLocalPort();}
            host.state.name="Studio workspace";host.state.hashBits=8;host.setPassword("preview server password".toCharArray());host.start("127.0.0.1");
            core.state.name="Voice preview";SavedServer server=new SavedServer();server.address="https://127.0.0.1:"+host.state.port;
            core.connect(server,"preview server password".toCharArray());core.save();
            DesktopVoiceTest.SyntheticAudio audio=new DesktopVoiceTest.SyntheticAudio();
            ClientApp[] app=new ClientApp[1];
            SwingUtilities.invokeAndWait(()->{try{
                Theme.install();app[0]=new ClientApp(cv);
                var listField=ClientApp.class.getDeclaredField("channelList");listField.setAccessible(true);
                @SuppressWarnings("unchecked") JList<Channel> list=(JList<Channel>)listField.get(app[0]);
                for(int i=0;i<list.getModel().getSize();i++)if(list.getModel().getElementAt(i).type.equals("voice"))list.setSelectedIndex(i);
                Channel channel=server.channels.stream().filter(c->c.type.equals("voice")).findFirst().orElseThrow();
                var leave=ClientApp.class.getDeclaredMethod("leaveVoice");leave.setAccessible(true);
                VoicePanel panel=new VoicePanel(app[0],core,server,channel,()->{try{leave.invoke(app[0]);}catch(Exception e){throw new RuntimeException(e);}},audio);
                var field=ClientApp.class.getDeclaredField("voice");field.setAccessible(true);field.set(app[0],panel);
                var area=ClientApp.class.getDeclaredField("voiceArea");area.setAccessible(true);((JPanel)area.get(app[0])).add(panel,BorderLayout.CENTER);
                app[0].setVisible(true);panel.startAfter(CompletableFuture.completedFuture(null));
            }catch(Exception e){throw new RuntimeException(e);}});
            while(app[0].isDisplayable())Thread.sleep(200);
        }
    }
}
