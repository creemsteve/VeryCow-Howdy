package chat.vch;

import chat.vch.Models.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

public final class ServerApp extends JFrame {
    private final ChatServer server;
    private final Vault vault;
    private final JLabel status = Theme.title("Offline",18);
    private final JTextArea events = new JTextArea();
    private final DefaultListModel<Channel> channels = new DefaultListModel<>();
    private final JList<Channel> channelList = new JList<>(channels);
    private final DefaultListModel<String> accounts = new DefaultListModel<>(), bans = new DefaultListModel<>();
    private final JTextField name, port, bind = new JTextField("0.0.0.0");
    private final javax.swing.Timer timer;
    public static void main(String[] args) { SwingUtilities.invokeLater(() -> { Theme.install(); Vault v = Launcher.unlock("server"); if (v != null) try { new ServerApp(v).setVisible(true); } catch (Exception e) { Theme.error(null,e); v.close(); } }); }
    public ServerApp(Vault vault) throws Exception {
        super("VCH / Server Console"); this.vault=vault; server = new ChatServer(vault);
        setSize(1080,760); setMinimumSize(new Dimension(850,600)); setLocationRelativeTo(null); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        server.log = s -> SwingUtilities.invokeLater(() -> events.append(java.time.LocalTime.now().withNano(0) + "  " + s + "\n"));
        JPanel header = Theme.panel(new BorderLayout()); header.setBackground(Theme.INK);
        JLabel brand = Theme.title("VCH  /  Server Console",24); brand.setForeground(Color.WHITE); header.add(brand,BorderLayout.WEST); status.setForeground(Color.WHITE); header.add(status,BorderLayout.EAST); add(header,BorderLayout.NORTH);
        name = new JTextField(server.state.name,28); port = new JTextField(Integer.toString(server.state.port));
        JTabbedPane tabs = new JTabbedPane(); tabs.addTab("Overview",overview()); tabs.addTab("Server customization",customization()); tabs.addTab("Accounts & bans",moderation()); add(tabs);
        timer=new javax.swing.Timer(2500,e -> refresh());
        addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { timer.stop();server.close(); vault.close(); dispose(); } });
        timer.start(); refresh();
    }
    private JPanel overview() throws Exception {
        JPanel root = Theme.panel(new BorderLayout(12,12));
        JTextField fingerprint = new JTextField(Tls.fingerprint(server.state.tlsStore)); fingerprint.setEditable(false);
        JTextField id = new JTextField(server.state.id); id.setEditable(false);
        JPanel setup = Theme.form("Server name",name,"Listen address",bind,"Server port (default 585)",port,"Certificate (verified automatically)",fingerprint,"Server identity",id);
        root.add(setup,BorderLayout.NORTH);
        events.setEditable(false); events.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12)); events.setBackground(Theme.INK); events.setForeground(new Color(170,220,255)); root.add(new JScrollPane(events));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(Theme.button("Save settings",() -> run(() -> { if (server.running()) throw new Exception("Stop the server before changing its address or port"); synchronized(server) { server.state.name=name.getText().strip(); if (server.state.name.isEmpty() || server.state.name.length()>60) throw new Exception("Name must be 1–60 characters"); server.state.port=Integer.parseInt(port.getText()); if(server.state.port<1||server.state.port>65535)throw new Exception("Port must be 1–65535"); server.save(); } })));
        actions.add(Theme.button("Set join password",() -> { JPasswordField p=new JPasswordField(24); if(JOptionPane.showConfirmDialog(this,Theme.form("Join password (12+ characters)",p),"Server access",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)run(() -> server.setPassword(p.getPassword())); p.setText(""); }));
        actions.add(Theme.button("Start server",() -> run(() -> { server.start(bind.getText().strip()); status.setText("Online · " + server.state.port); })));
        actions.add(Theme.button("Stop server",() -> { server.close(); status.setText("Offline"); })); root.add(actions,BorderLayout.SOUTH); return root;
    }
    private JPanel customization() {
        JPanel root=Theme.panel(new BorderLayout(12,12)); root.add(Theme.title("Channels, access and audio",28),BorderLayout.NORTH);
        channelList.setFixedCellHeight(44); root.add(new JScrollPane(channelList));
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(Theme.button("Add text channel",() -> edit(null,"text"))); actions.add(Theme.button("Add voice channel",() -> edit(null,"voice")));
        actions.add(Theme.button("Edit selected",() -> { if(channelList.getSelectedValue()!=null)edit(channelList.getSelectedValue(),channelList.getSelectedValue().type); }));
        actions.add(Theme.button("Rotate channel key",()->{Channel c=channelList.getSelectedValue();if(c!=null)run(()->server.rotateChannelKey(c.id));}));
        actions.add(Theme.button("Delete selected",() -> { Channel c=channelList.getSelectedValue(); if(c!=null && JOptionPane.showConfirmDialog(this,"Delete #"+c.name+"? Saved client history remains available.","Delete channel",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)run(() -> { synchronized(server){server.state.channels.remove(c);server.save();}refreshChannels(); }); }));
        root.add(actions,BorderLayout.SOUTH); return root;
    }
    private void edit(Channel original,String type) {
        Channel c=original==null?new Channel():Json.GSON.fromJson(Json.GSON.toJson(original),Channel.class); c.type=type;
        JTextField channelName=new JTextField(c.name,24); JSpinner bitrate=new JSpinner(new SpinnerNumberModel(c.bitRate,500,6000,500));
        JSpinner members=new JSpinner(new SpinnerNumberModel(c.maxMembers,2,32,1)); JSpinner slow=new JSpinner(new SpinnerNumberModel(c.slowModeSeconds,0,3600,1));
        JTextArea allow=new JTextArea(String.join("\n",c.allow),5,28);
        JPanel form=type.equals("voice")?Theme.form("Channel name",channelName,"Target codec rate (bits/s)",bitrate,"Maximum members",members,"Allowed account hashes (empty = everyone)",new JScrollPane(allow)):
            Theme.form("Channel name",channelName,"Slow mode (seconds)",slow,"Allowed account hashes (empty = everyone)",new JScrollPane(allow));
        if(JOptionPane.showConfirmDialog(this,form,"Customize "+type+" channel",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        run(() -> { c.name=channelName.getText().strip(); if(c.name.isEmpty()||c.name.length()>40)throw new Exception("Channel names must be 1–40 characters");
            c.bitRate=(Integer)bitrate.getValue();c.maxMembers=(Integer)members.getValue();c.slowModeSeconds=(Integer)slow.getValue();c.allow.clear();
            for(String line:allow.getText().split("\\s+"))if(!line.isBlank()){if(!line.matches("[a-f0-9]{64}"))throw new Exception("Use full 64-character account hashes");c.allow.add(line);}
            synchronized(server){if(original!=null)server.state.channels.remove(original);server.state.channels.add(c);server.save();}refreshChannels();
        });
    }
    private JPanel moderation() {
        JPanel root=Theme.panel(new BorderLayout(12,12)); JList<String> list=new JList<>(accounts),banList=new JList<>(bans);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(list),new JScrollPane(banList));split.setResizeWeight(.6);root.add(split);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(Theme.button("Toggle administrator",() -> {String value=list.getSelectedValue();if(value!=null)run(() -> {String hash=value.split("  ")[0]; synchronized(server){if(!server.state.admins.remove(hash))server.state.admins.add(hash);server.save();}refresh();});}));
        buttons.add(Theme.button("Ban account hash",() -> {String value=JOptionPane.showInputDialog(this,"Full account hash",list.getSelectedValue()==null?"":list.getSelectedValue().split("  ")[0]);if(value!=null)run(() -> {if(!value.matches("[a-f0-9]{64}"))throw new Exception("Enter a full account hash");server.ban(value,false);refresh();});}));
        buttons.add(Theme.button("Ban IP address",() -> {String value=JOptionPane.showInputDialog(this,"Exact IPv4 or IPv6 address");if(value!=null)run(() -> {if(!value.matches("[0-9a-fA-F:.]+"))throw new Exception("Enter a literal IP address");server.ban(java.net.InetAddress.getByName(value).getHostAddress(),true);refresh();});}));
        buttons.add(Theme.button("Remove selected ban",() -> {String selected=banList.getSelectedValue();if(selected!=null)run(() -> {synchronized(server){(selected.startsWith("IP  ")?server.state.bannedIps:server.state.bannedHashes).remove(selected.substring(selected.indexOf("  ")+2));server.save();}refresh();});}));
        root.add(buttons,BorderLayout.SOUTH);return root;
    }
    private void refreshChannels(){Channel c=channelList.getSelectedValue();channels.clear();synchronized(server){server.state.channels.forEach(channels::addElement);}if(c!=null)channelList.setSelectedValue(c,true);}
    private void refresh(){synchronized(server){accounts.clear();server.state.accounts.values().stream().sorted(Comparator.comparing(a->a.name)).forEach(a->accounts.addElement(a.hash+"  "+a.name+(server.state.admins.contains(a.hash)?" (admin)":"")));bans.clear();server.state.bannedIps.forEach(b->bans.addElement("IP  "+b));server.state.bannedHashes.forEach(b->bans.addElement("HASH  "+b));}if(channels.isEmpty())refreshChannels();}
    private interface Task { void run() throws Exception; }
    private void run(Task task){try{task.run();}catch(Exception e){Theme.error(this,e);}}
}
