package chat.vch;

import chat.vch.Models.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.sound.sampled.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class ClientApp extends JFrame {
    private final ClientCore core;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final DefaultListModel<SavedServer> servers=new DefaultListModel<>();
    private final DefaultListModel<Channel> channels=new DefaultListModel<>();
    private final JList<SavedServer> serverList=new JList<>(servers);
    private final JList<Channel> channelList=new JList<>(channels);
    private final JPanel timeline=new JPanel();
    private final JScrollPane scroll=new JScrollPane(timeline);
    private final JTextField compose=new JTextField();
    private final JLabel heading=Theme.title("Your conversations, together.",28),status=new JLabel("Unlock complete · local history available"),identity=new JLabel();
    private final DefaultListModel<String> members=new DefaultListModel<>();
    private VoicePanel voice;
    private final JPanel voiceArea=new JPanel(new BorderLayout());
    private CompletableFuture<Void> previousVoice=CompletableFuture.completedFuture(null);
    private boolean busy;
    private final java.util.List<File> attachments = new ArrayList<>();
    private final JLabel attachmentStatus = new JLabel();
    private volatile long lastTyped;
    private String attachmentDestination;
    private javax.swing.Timer timer;
    private JDialog ringingDialog;
    private javax.swing.Timer ringTimer;
    private String ringingId;
    public static void main(String[] args){SwingUtilities.invokeLater(()->{Theme.install();Vault v=Launcher.unlock("client");if(v!=null)try{new ClientApp(v).setVisible(true);}catch(Exception e){Theme.error(null,e);v.close();}});}
    public ClientApp(Vault v)throws Exception{
        super("VCH / Conversations");core=new ClientCore(v);setSize(1320,850);setMinimumSize(new Dimension(950,650));setLocationRelativeTo(null);setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel header=Theme.panel(new BorderLayout());header.setBackground(Theme.INK);JLabel brand=Theme.title("VCH  /  Conversations",22);brand.setForeground(Color.WHITE);header.add(brand,BorderLayout.WEST);
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));tools.setOpaque(false);tools.add(Theme.button("Profile",this::profile));tools.add(Theme.button("Audio",this::audio));tools.add(Theme.button("Friends",this::friends));tools.add(Theme.button("Manage server",this::manageServer));tools.add(Theme.button("Roles",()->{SavedServer s=selected();if(s==null||!s.roleManager||!core.connected(s)){status.setText("Connect with permission to manage roles");return;}manageRoles(s);}));tools.add(Theme.button("Appearance",this::appearance));tools.add(Theme.button("Lock",this::lock));header.add(tools,BorderLayout.EAST);add(header,BorderLayout.NORTH);
        JPanel nav=new JPanel(new BorderLayout(0,14));nav.setBorder(new EmptyBorder(22,16,18,16));nav.setPreferredSize(new Dimension(245,0));nav.setBackground(Theme.NAV);
        JPanel navTop=new JPanel(new BorderLayout(0,12));navTop.setOpaque(false);JLabel communities=Theme.title("WORKSPACES",12);communities.setForeground(Color.LIGHT_GRAY);navTop.add(communities,BorderLayout.NORTH);
        serverList.setVisibleRowCount(4);serverList.setFixedCellHeight(40);serverList.setBackground(Theme.NAV);serverList.setForeground(Color.WHITE);navTop.add(new JScrollPane(serverList));
        navTop.add(Theme.button("+ Join a server",this::join),BorderLayout.SOUTH);nav.add(navTop,BorderLayout.NORTH);
        channelList.setBackground(Theme.NAV);channelList.setForeground(Color.WHITE);channelList.setFixedCellHeight(40);nav.add(new JScrollPane(channelList));identity.setForeground(Color.LIGHT_GRAY);nav.add(identity,BorderLayout.SOUTH);add(nav,BorderLayout.WEST);
        JPanel center=Theme.panel(new BorderLayout(14,14));center.add(heading,BorderLayout.NORTH);timeline.setLayout(new BoxLayout(timeline,BoxLayout.Y_AXIS));timeline.setBackground(Color.WHITE);scroll.setBorder(BorderFactory.createEmptyBorder());center.add(scroll);
        JPanel bottom=new JPanel(new BorderLayout(8,8));JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));controls.add(Theme.button("Reconnect",this::reconnect));controls.add(Theme.button("Connection",this::connection));controls.add(Theme.button("Join voice",this::openVoice));controls.add(Theme.button("Leave voice",this::leaveVoice));JPanel voiceTools=new JPanel(new BorderLayout(0,8));voiceTools.add(controls,BorderLayout.NORTH);voiceTools.add(voiceArea);bottom.add(voiceTools,BorderLayout.NORTH);
        compose.setBorder(new EmptyBorder(14,14,14,14));
        compose.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){ public void insertUpdate(javax.swing.event.DocumentEvent e){lastTyped=System.currentTimeMillis();} public void removeUpdate(javax.swing.event.DocumentEvent e){lastTyped=System.currentTimeMillis();} public void changedUpdate(javax.swing.event.DocumentEvent e){lastTyped=System.currentTimeMillis();} });
        compose.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_V,Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),"vch-paste");
        compose.getActionMap().put("vch-paste",new AbstractAction(){public void actionPerformed(ActionEvent e){compose.paste();}});
        compose.addActionListener(e->sendText());bottom.add(compose);JPanel send=new JPanel(new FlowLayout(FlowLayout.RIGHT));send.add(Theme.button("Image",this::sendImage));send.add(Theme.button("Send →",this::sendText));bottom.add(send,BorderLayout.EAST);JPanel info=new JPanel(new GridLayout(0,1));info.add(attachmentStatus);info.add(status);bottom.add(info,BorderLayout.SOUTH);center.add(bottom,BorderLayout.SOUTH);add(center);
        JPanel right=Theme.panel(new BorderLayout(0,12));right.setPreferredSize(new Dimension(205,0));right.add(Theme.title("Members",18),BorderLayout.NORTH);JList<String> roster=new JList<>(members);roster.setBackground(Theme.PAPER);right.add(new JScrollPane(roster));add(right,BorderLayout.EAST);
        core.state.servers.forEach(servers::addElement);identity.setText(core.state.name);
        serverList.addListSelectionListener(e->{if(!e.getValueIsAdjusting())selectServer();});channelList.addListSelectionListener(e->{if(!e.getValueIsAdjusting())render();});
        if(!servers.isEmpty())serverList.setSelectedIndex(0);else welcome();
        applyAppearance();
        timer=new javax.swing.Timer(1600,e->pollAll());timer.start();
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){lock();}});
    }
    private SavedServer selected(){return serverList.getSelectedValue();}
    private void welcome(){timeline.removeAll();JPanel p=Theme.panel(new BorderLayout(0,16));p.setBackground(Color.WHITE);p.add(Theme.title("A space for your people.",34),BorderLayout.NORTH);JTextArea t=new JTextArea("Join a private server with just its IP address or domain and password.\n\nOne profile. Separate communities.\nChannel encryption is set up automatically after login.\nYour local archive unlocks with your password.");t.setEditable(false);t.setOpaque(false);t.setFont(Theme.font(18));p.add(t);timeline.add(p);timeline.revalidate();timeline.repaint();}
    private void selectServer(){SavedServer s=selected();channels.clear();if(s==null){welcome();return;}s.channels.forEach(channels::addElement);if(!channels.isEmpty())channelList.setSelectedIndex(0);else render();}
    private void render(){
        SavedServer s=selected();Channel c=channelList.getSelectedValue();if(s==null)return;heading.setText(c==null?s.name:c.toString());heading.setToolTipText(s.description);heading.setIcon(null);if(s.image!=null&&!s.image.isEmpty())try{var icon=Images.decode(Crypto.unb64(s.image),512);heading.setIcon(new ImageIcon(icon.getScaledInstance(40,40,Image.SCALE_SMOOTH)));}catch(Exception ignored){}identity.setText(core.state.name);members.clear();s.accounts.values().forEach(a->members.addElement(a.name));timeline.removeAll();
        if(c==null){timeline.revalidate();timeline.repaint();return;}
        if(c.type.equals("voice")){JPanel p=Theme.panel(new BorderLayout(0,15));p.setBackground(Color.WHITE);p.add(Theme.title("Speak with your community.",30),BorderLayout.NORTH);JTextArea t=new JTextArea("Push-to-talk · one speaker at a time\nEncrypted audio relayed through this server\nDAC target: "+c.bitRate+" bits/s\n\nChoose Join voice, then hold the talk button or your PTT key.\nVoice controls stay here while you browse text channels.\nChoose microphone, playback device and volume in Audio.\nChannel encryption and the bundled DAC model run locally.");t.setEditable(false);t.setOpaque(false);p.add(t);timeline.add(p);}
        else for(Envelope e:s.messages){if(!e.channel.equals(c.id))continue;JPanel row=new JPanel(new BorderLayout(12,6));row.setBackground(Color.WHITE);row.setBorder(new EmptyBorder(16,18,16,18));row.setMaximumSize(new Dimension(Integer.MAX_VALUE,500));Account a=s.accounts.get(e.author);
            JLabel label=new JLabel((a==null?e.author.substring(0,12):a.name+(a.pronouns==null||a.pronouns.isBlank()?"":" ("+a.pronouns+")"))+"   ·   "+java.time.Instant.ofEpochMilli(e.time).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0));label.setForeground(Theme.MUTED);row.add(label,BorderLayout.NORTH);
            if(a!=null&&!a.avatar.isEmpty())try{var pic=Images.decode(Crypto.unb64(a.avatar),512);row.add(new JLabel(new ImageIcon(pic.getScaledInstance(32,32,Image.SCALE_SMOOTH))),BorderLayout.WEST);}catch(Exception ignored){}
            try{byte[] plain=core.decrypt(s,e);if(e.kind.equals("image")){if(!Images.isWebp(plain))throw new IOException("Invalid image");var image=Images.decode(plain,2048);double scale=Math.min(1.0,Math.min(420.0/image.getWidth(),300.0/image.getHeight()));row.add(new JLabel(new ImageIcon(image.getScaledInstance(Math.max(1,(int)(image.getWidth()*scale)),Math.max(1,(int)(image.getHeight()*scale)),Image.SCALE_SMOOTH))));}
                else if(e.kind.equals("images")){JPanel images=new JPanel(new FlowLayout(FlowLayout.LEFT));images.setOpaque(false);var payload=Json.parse(plain);String caption=Json.str(payload,"text");if(!caption.isBlank()){JTextArea captionText=new JTextArea(caption);captionText.setLineWrap(true);captionText.setWrapStyleWord(true);captionText.setEditable(false);captionText.setOpaque(false);row.add(captionText,BorderLayout.SOUTH);}var items=payload.getAsJsonArray("images");if(items==null||items.size()<1||items.size()>3)throw new IOException("Invalid image collection");for(var item:items){byte[] bytes=Crypto.unb64(item.getAsString());if(bytes.length>100000)throw new IOException("Image exceeds limit");var image=Images.decode(bytes,2048);double scale=Math.min(1.0,Math.min(300.0/image.getWidth(),240.0/image.getHeight()));images.add(new JLabel(new ImageIcon(image.getScaledInstance(Math.max(1,(int)(image.getWidth()*scale)),Math.max(1,(int)(image.getHeight()*scale)),Image.SCALE_SMOOTH))));}row.add(images);}
                else{JTextArea text=new JTextArea(new String(plain,StandardCharsets.UTF_8));text.setLineWrap(true);text.setWrapStyleWord(true);text.setEditable(false);text.setOpaque(false);row.add(text);}}
            catch(Exception ex){JLabel locked=new JLabel("Encrypted message · "+ex.getMessage());locked.setForeground(Theme.MUTED);row.add(locked);}
            timeline.add(row);
        }
        if(c.type.equals("text")&&timeline.getComponentCount()==0){JLabel empty=Theme.title("This is the start of #"+c.name+".",24);empty.setBorder(new EmptyBorder(30,20,30,20));timeline.add(empty);}
        Map<String,String> active=core.typing.getOrDefault(s.id,Map.of());java.util.List<String> names=new ArrayList<>();active.forEach((id,ch)->{if(ch.equals(c.id)&&s.accounts.containsKey(id))names.add(s.accounts.get(id).name);});if(!names.isEmpty())timeline.add(new JLabel(String.join(", ",names)+" typing..."));
        timeline.add(Box.createVerticalGlue());if(core.state.darkMode)applyColors(timeline,true);timeline.revalidate();timeline.repaint();SwingUtilities.invokeLater(()->scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()));
    }
    private void join(){
        JTextField address=new JTextField("localhost",28);JPasswordField password=new JPasswordField(28);
        JCheckBox tor=new JCheckBox("Connect using Tor only (no direct fallback)");JSpinner port=new JSpinner(new SpinnerNumberModel(9050,1,65535,1));
        if(JOptionPane.showConfirmDialog(this,Theme.form("IP address or domain",address,"Server password",password,"Privacy",tor,"Local Tor SOCKS port",port,"Tor setup",new JLabel("Start Tor separately. Voice/calls use the server through Tor.")),"Join a server",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        SavedServer s=new SavedServer();s.address=address.getText().strip();s.torOnly=tor.isSelected();s.torSocksPort=(Integer)port.getValue();char[] p=password.getPassword();password.setText("");
        work("Registering encrypted identity…",()->{core.connect(s,p);},()->{if(!servers.contains(s))servers.addElement(s);serverList.setSelectedValue(s,true);status.setText("Connected · "+s.name);});
    }
    private void reconnect(){SavedServer s=selected();if(s==null)return;work("Connecting…",()->core.connect(s,null),()->{selectServer();status.setText("Connected · "+s.name);});}
    private void connection(){
        SavedServer s=selected();if(s==null)return;
        JCheckBox tor=new JCheckBox("Connect using Tor only (no direct fallback)",s.torOnly);JSpinner port=new JSpinner(new SpinnerNumberModel(s.torSocksPort,1,65535,1));
        if(JOptionPane.showConfirmDialog(this,Theme.form("Privacy",tor,"Local Tor SOCKS port",port,"Tor setup",new JLabel("Start Tor separately. Voice/calls use the server through Tor.")),"Connection settings",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        boolean torOnly=tor.isSelected();int socksPort=(Integer)port.getValue();
        leaveVoice();
        work("Saving connection settings…",()->{previousVoice.join();core.connectionSettings(s,torOnly,socksPort);},()->{serverList.repaint();status.setText("Connection settings saved · choose Reconnect");});
    }
    private void sendText(){
        SavedServer s=selected();Channel c=channelList.getSelectedValue();String text=compose.getText();
        if(s==null||c==null||!c.type.equals("text")||(text.isBlank()&&attachments.isEmpty()))return;
        if(!attachments.isEmpty()&&!Objects.equals(attachmentDestination,s.id+"/"+c.id)){status.setText("Selected images belong to a different channel");return;}
        if(Crypto.utf(text).length>16000){status.setText("Text limit is 16,000 UTF-8 bytes");return;}
        java.util.List<File> files=new ArrayList<>(attachments);
        work("Sending...",()->{if(files.isEmpty())core.send(s,c,"text",Crypto.utf(text));else{java.util.List<String> encoded=new ArrayList<>();for(File f:files)encoded.add(Crypto.b64(Images.prepare(f.toPath(),100000,1600)));core.send(s,c,"images",Json.bytes(Json.obj("images",encoded,"text",text)));}},()->{if(files.isEmpty()){if(compose.getText().equals(text))compose.setText("");}else{if(compose.getText().equals(text))compose.setText("");attachments.removeAll(files);attachmentStatus.setText(attachments.isEmpty()?"":attachments.size()+" images selected");}render();status.setText("Sent");});
    }
    private void sendImage(){SavedServer s=selected();Channel c=channelList.getSelectedValue();if(s==null||c==null||!c.type.equals("text")){status.setText("Select a text channel first");return;}String destination=s.id+"/"+c.id;if(!attachments.isEmpty()&&!Objects.equals(attachmentDestination,destination)){attachments.clear();}attachmentDestination=destination;FileDialog picker=new FileDialog(this,"Choose images",FileDialog.LOAD);picker.setMultipleMode(true);picker.setVisible(true);File[] files=picker.getFiles();if(files.length==0)return;if(attachments.size()+files.length>3){status.setText("Select up to 3 images per message");return;}attachments.addAll(Arrays.asList(files));attachmentStatus.setText(attachments.size()+" images selected; press Send when ready");}
    private void profile(){
        JTextField name=new JTextField(core.state.name,24),bio=new JTextField(core.state.bio,24),pronouns=new JTextField(core.state.pronouns,24);String[] avatar={core.state.avatar};JLabel picture=new JLabel(avatar[0].isEmpty()?"No profile picture":"WebP profile picture saved");
        JButton choose=Theme.button("Choose picture",()->{FileDialog fc=new FileDialog(this,"Choose profile picture",FileDialog.LOAD);fc.setVisible(true);if(fc.getFile()!=null)try{avatar[0]=Crypto.b64(Images.prepare(new File(fc.getDirectory(),fc.getFile()).toPath(),10000,256));picture.setText("Ready · "+Crypto.unb64(avatar[0]).length+" bytes");}catch(Exception e){Theme.error(this,e);}});
        JTextField hash=new JTextField(selected()==null?"Join a server to register":core.account(selected()));hash.setEditable(false);
        if(JOptionPane.showConfirmDialog(this,Theme.form("Display name",name,"Pronouns",pronouns,"About you",bio,"Profile picture (10 KB)",choose,"Picture status",picture,"Current server account hash",hash),"Your profile",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){
            if(name.getText().isBlank()||name.getText().length()>40||bio.getText().length()>240||pronouns.getText().length()>40){Theme.error(this,new Exception("Use a name of 1–40 characters and a bio up to 240 characters"));return;}
            work("Saving profile…",()->{core.state.name=name.getText().strip();core.state.bio=bio.getText();core.state.pronouns=pronouns.getText().strip();core.state.avatar=avatar[0];core.save();for(SavedServer s:core.state.servers)if(core.connected(s))core.updateProfile(s);},()->{identity.setText(core.state.name);status.setText("Profile saved across connected servers");});
        }
    }
    private void audio(){
        JComboBox<String> mic=new JComboBox<>(),speakers=new JComboBox<>();mic.addItem("Default");speakers.addItem("Default");for(Mixer.Info info:AudioSystem.getMixerInfo()){Mixer m=AudioSystem.getMixer(info);if(m.getTargetLineInfo().length>0)mic.addItem(info.getName());if(m.getSourceLineInfo().length>0)speakers.addItem(info.getName());}mic.setEditable(true);speakers.setEditable(true);mic.setSelectedItem(core.state.microphone);speakers.setSelectedItem(core.state.speakers);
        JSlider input=new JSlider(0,200,core.state.inputGain),output=new JSlider(0,200,core.state.outputGain),bitrate=new JSlider(500,6000,Math.max(500,core.state.outgoingBitRate));bitrate.setMajorTickSpacing(1000);bitrate.setPaintLabels(true);bitrate.setPaintTicks(true);JComboBox<String> ptt=new JComboBox<>(new String[]{"SPACE","V","F8"});ptt.setSelectedItem(core.state.pttKey);
        if(JOptionPane.showConfirmDialog(this,Theme.form("Microphone name",mic,"Playback device name",speakers,"Input gain",input,"Output volume",output,"Outgoing DAC target (bits/s)",bitrate,"Push-to-talk key",ptt),"Audio preferences",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)try{core.state.microphone=mic.getSelectedItem().toString();core.state.speakers=speakers.getSelectedItem().toString();core.state.inputGain=input.getValue();core.state.outputGain=output.getValue();core.state.outgoingBitRate=bitrate.getValue();core.state.pttKey=ptt.getSelectedItem().toString();core.save();status.setText("Audio saved · reopen voice to apply");}catch(Exception e){Theme.error(this,e);}
    }
    private void friends(){
        SavedServer s=selected();if(s==null||!core.connected(s)){status.setText("Connect to a server first");return;}
        java.util.List<Account> people=s.accounts.values().stream().filter(a->!a.hash.equals(core.account(s))).sorted(Comparator.comparing(a->a.name)).toList();
        if(people.isEmpty()){status.setText("No other registered accounts on this server");return;}
        String[] names=people.stream().map(a->a.name+" ("+a.hash.substring(0,12)+")"+(s.friendRequests.contains(a.hash)?" - request received":"")+(s.friendships.stream().anyMatch(f->f.includes(a.hash))?" - friend":"")).toArray(String[]::new);
        JList<String> list=new JList<>(names);list.setSelectedIndex(0);JOptionPane.showMessageDialog(this,new JScrollPane(list),"Choose a person, then close this dialog",JOptionPane.PLAIN_MESSAGE);int index=list.getSelectedIndex();if(index<0)return;
        Account person=people.get(index);Friendship friend=s.friendships.stream().filter(f->f.includes(person.hash)).findFirst().orElse(null);
        if(friend!=null){String[] actions={"Direct message","Start or join call","Remove friend","Cancel"};int action=JOptionPane.showOptionDialog(this,person.name,"Friend",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,actions,actions[0]);
            if(action==0){selectChannel(s,friend.textChannel,false);return;}
            if(action==1){work("Calling...",()->core.request(s,"call",Json.obj("action","ring","account",person.hash),true),()->{selectChannel(s,friend.voiceChannel,true);status.setText("Calling "+person.name);});return;}
            if(action==2&&JOptionPane.showConfirmDialog(this,"Remove "+person.name+" as a friend?","Remove friend",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)friendAction(s,person.hash,"remove");return;
        }
        if(s.friendRequests.contains(person.hash)){String[] actions={"Accept","Decline","Cancel"};int action=JOptionPane.showOptionDialog(this,"Friend request from "+person.name,"Friend request",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,actions,actions[0]);if(action==0)friendAction(s,person.hash,"accept");else if(action==1)friendAction(s,person.hash,"decline");}
        else if(JOptionPane.showConfirmDialog(this,"Send a friend request to "+person.name+"?","Friend request",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)friendAction(s,person.hash,"request");
    }
    private void selectChannel(SavedServer s,String id,boolean join){serverList.setSelectedValue(s,true);for(int i=0;i<channels.size();i++)if(channels.get(i).id.equals(id)){channelList.setSelectedIndex(i);if(join)openVoice();return;}status.setText("Call channel is unavailable");}
    private void updateIncomingCalls(){
        SavedServer source=null;CallInvite invite=null;
        for(SavedServer s:core.state.servers){java.util.List<CallInvite> calls=core.incomingCalls.getOrDefault(s.id,java.util.List.of());if(!calls.isEmpty()){source=s;invite=calls.get(0);break;}}
        if(ringingDialog!=null&&(invite==null||!invite.id.equals(ringingId))){ringingDialog.dispose();ringingDialog=null;if(ringTimer!=null)ringTimer.stop();ringingId=null;}
        if(invite==null||ringingDialog!=null)return;
        SavedServer server=source;CallInvite call=invite;ringingId=call.id;Account caller=server.accounts.get(call.from);String name=caller==null?"A friend":caller.name;
        JDialog dialog=new JDialog(this,"Incoming call",false);ringingDialog=dialog;JPanel body=Theme.panel(new BorderLayout(10,10));body.add(new JLabel(name+" is calling on "+server.name),BorderLayout.NORTH);JPanel buttons=new JPanel();
        Runnable decline=()->respondCall(server,call,"decline");buttons.add(Theme.button("Answer",()->respondCall(server,call,"accept")));buttons.add(Theme.button("Decline",decline));body.add(buttons);dialog.add(body);dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);dialog.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){decline.run();}});dialog.pack();dialog.setLocationRelativeTo(this);dialog.setVisible(true);
        Toolkit.getDefaultToolkit().beep();ringTimer=new javax.swing.Timer(2500,e->Toolkit.getDefaultToolkit().beep());ringTimer.start();
    }
    private void respondCall(SavedServer server,CallInvite call,String action){if(busy){javax.swing.Timer retry=new javax.swing.Timer(200,e->respondCall(server,call,action));retry.setRepeats(false);retry.start();return;}if(ringingDialog!=null){ringingDialog.dispose();ringingDialog=null;}if(ringTimer!=null)ringTimer.stop();ringingId=null;work("Responding to call...",()->{core.request(server,"call",Json.obj("action",action,"id",call.id),true);core.poll(server);},()->{if(action.equals("accept"))selectChannel(server,call.channel,true);else status.setText("Call declined");});}
    private void friendAction(SavedServer s,String account,String action){work("Updating friends...",()->{core.request(s,"friend",Json.obj("account",account,"action",action),true);core.poll(s);},()->{selectServer();status.setText("Friends updated");});}
    private void appearance(){
        JCheckBox dark=new JCheckBox("Dark mode",core.state.darkMode);
        if(JOptionPane.showConfirmDialog(this,dark,"Appearance",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)try{core.state.darkMode=dark.isSelected();core.save();applyAppearance();render();}catch(Exception e){Theme.error(this,e);}
    }
    private void applyAppearance(){applyColors(getContentPane(),core.state.darkMode);repaint();}
    private void applyColors(Component component,boolean dark){
        if(component instanceof JButton)return;
        Color background=dark?new Color(35,35,35):Theme.PAPER, foreground=dark?Color.WHITE:Theme.INK;
        if(component instanceof JPanel || component instanceof JViewport || component instanceof JScrollPane)component.setBackground(background);
        if(component instanceof JTextField || component instanceof JTextArea || component instanceof JList){component.setBackground(dark?new Color(48,48,48):Color.WHITE);component.setForeground(foreground);}
        if(component instanceof JLabel)component.setForeground(foreground);
        if(component instanceof Container)for(Component child:((Container)component).getComponents())applyColors(child,dark);
    }
    private void manageServer(){
        SavedServer s=selected();if(s==null)return;
        Object[] options=s.admin?new Object[]{"Edit server", "Manage roles", "Remove saved server", "Cancel"}:new Object[]{"Remove saved server", "Cancel"};
        int choice=JOptionPane.showOptionDialog(this,"Manage "+s.name,"Server",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,options,options[0]);
        if(choice<0||choice==options.length-1)return;
        if(!s.admin || choice==2){if(JOptionPane.showConfirmDialog(this,"Remove this server and its local history?","Remove server",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)try{if(voice!=null)leaveVoice();core.disconnect(s);core.state.servers.remove(s);core.save();servers.removeElement(s);if(!servers.isEmpty())serverList.setSelectedIndex(0);else welcome();}catch(Exception e){Theme.error(this,e);}return;}
        if(!core.connected(s)){status.setText("Reconnect to manage this server");return;}
        if(choice==1){manageRoles(s);return;}
        JTextField name=new JTextField(s.name,24), description=new JTextField(s.description,24);String[] picture={s.image};
        JButton image=Theme.button("Choose server image",()->{FileDialog picker=new FileDialog(this,"Choose server image",FileDialog.LOAD);picker.setVisible(true);if(picker.getFile()!=null)try{picture[0]=Crypto.b64(Images.prepare(new File(picker.getDirectory(),picker.getFile()).toPath(),10000,256));}catch(Exception e){Theme.error(this,e);}});
        DefaultListModel<Channel> model=new DefaultListModel<>();for(Channel channel:s.adminChannels)model.addElement(Json.GSON.fromJson(Json.GSON.toJson(channel),Channel.class));JList<Channel> list=new JList<>(model);list.setVisibleRowCount(6);
        JPanel buttons=new JPanel(new FlowLayout());buttons.add(Theme.button("Add text",()->editManagedChannel(model,-1,"text")));buttons.add(Theme.button("Add voice",()->editManagedChannel(model,-1,"voice")));buttons.add(Theme.button("Edit",()->{int i=list.getSelectedIndex();if(i>=0)editManagedChannel(model,i,model.get(i).type);}));buttons.add(Theme.button("Delete",()->{int i=list.getSelectedIndex();if(i>=0)model.remove(i);}));buttons.add(Theme.button("Move up",()->{int i=list.getSelectedIndex();if(i>0){Channel c=model.remove(i);model.add(i-1,c);list.setSelectedIndex(i-1);}}));
        JPanel panel=new JPanel(new BorderLayout());panel.add(Theme.form("Name",name,"Description",description,"Image",image),BorderLayout.NORTH);panel.add(new JScrollPane(list));panel.add(buttons,BorderLayout.SOUTH);
        if(JOptionPane.showConfirmDialog(this,panel,"Edit server and channel order",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        java.util.List<Channel> changed=Collections.list(model.elements());String title=name.getText().strip(),details=description.getText(),icon=picture[0];
        work("Saving server...",()->{core.request(s,"admin",Json.obj("name",title,"description",details,"image",icon,"channels",changed),true);core.poll(s);},()->{selectServer();status.setText("Server updated");});
    }
    private void manageRoles(SavedServer s){
        DefaultListModel<Role> model=new DefaultListModel<>();for(Role r:s.roles)model.addElement(Json.GSON.fromJson(Json.GSON.toJson(r),Role.class));
        JList<Role> list=new JList<>(model);list.setVisibleRowCount(7);
        JCheckBox view=new JCheckBox("View channels",s.everyonePermissions.contains("view")),send=new JCheckBox("Send messages",s.everyonePermissions.contains("send")),connect=new JCheckBox("Connect to voice",s.everyonePermissions.contains("connect"));
        view.setEnabled(s.admin);send.setEnabled(s.admin);connect.setEnabled(s.admin);
        JPanel base=new JPanel();base.add(new JLabel("Everyone:"));base.add(view);base.add(send);base.add(connect);
        JPanel buttons=new JPanel();buttons.add(Theme.button("Add",()->editRole(model,-1)));buttons.add(Theme.button("Edit",()->{int i=list.getSelectedIndex();if(i>=0)editRole(model,i);}));buttons.add(Theme.button("Delete",()->{int i=list.getSelectedIndex();if(i>=0)model.remove(i);}));buttons.add(Theme.button("Move higher",()->{int i=list.getSelectedIndex();if(i>=0&&i<model.size()-1){Role r=model.remove(i);model.add(i+1,r);list.setSelectedIndex(i+1);}}));buttons.add(Theme.button("Move lower",()->{int i=list.getSelectedIndex();if(i>0){Role r=model.remove(i);model.add(i-1,r);list.setSelectedIndex(i-1);}}));
        JPanel panel=new JPanel(new BorderLayout());panel.add(base,BorderLayout.NORTH);panel.add(new JScrollPane(list));panel.add(buttons,BorderLayout.SOUTH);
        if(JOptionPane.showConfirmDialog(this,panel,"Roles (highest at bottom)",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        Set<String> permissions=new HashSet<>();if(view.isSelected())permissions.add("view");if(send.isSelected())permissions.add("send");if(connect.isSelected())permissions.add("connect");
        java.util.List<Role> roles=Collections.list(model.elements());work("Saving roles...",()->{core.request(s,"roles",Json.obj("roles",roles,"everyonePermissions",permissions),true);core.poll(s);},()->{selectServer();status.setText("Roles updated");});
    }
    private void editRole(DefaultListModel<Role> model,int index){
        Role r=index<0?new Role():Json.GSON.fromJson(Json.GSON.toJson(model.get(index)),Role.class);
        JTextField name=new JTextField(r.name,24);JTextArea members=new JTextArea(String.join("\n",r.members),5,40);
        JCheckBox view=new JCheckBox("View",r.permissions.contains("view")),send=new JCheckBox("Send",r.permissions.contains("send")),connect=new JCheckBox("Connect",r.permissions.contains("connect")),manage=new JCheckBox("Manage lower roles",r.permissions.contains("manage_roles"));JPanel permissions=new JPanel();permissions.add(view);permissions.add(send);permissions.add(connect);permissions.add(manage);
        if(JOptionPane.showConfirmDialog(this,Theme.form("Name",name,"Permissions",permissions,"Member account hashes",new JScrollPane(members)),"Role",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        r.name=name.getText().strip();r.members.clear();for(String hash:members.getText().split("\\s+"))if(!hash.isBlank())r.members.add(hash);r.permissions.clear();if(view.isSelected())r.permissions.add("view");if(send.isSelected())r.permissions.add("send");if(connect.isSelected())r.permissions.add("connect");if(manage.isSelected())r.permissions.add("manage_roles");if(index<0)model.addElement(r);else model.set(index,r);
    }
    private void editManagedChannel(DefaultListModel<Channel> model,int index,String type){
        Channel c=index<0?new Channel():Json.GSON.fromJson(Json.GSON.toJson(model.get(index)),Channel.class);c.type=type;
        JTextField name=new JTextField(c.name,20);JTextArea allow=new JTextArea(String.join("\n",c.allow),4,24);JSpinner slow=new JSpinner(new SpinnerNumberModel(c.slowModeSeconds,0,3600,1)),rate=new JSpinner(new SpinnerNumberModel(c.bitRate,500,6000,500)),max=new JSpinner(new SpinnerNumberModel(c.maxMembers,2,32,1));
        JButton overrides=Theme.button("Edit role overrides",()->editRoleOverrides(c));
        if(JOptionPane.showConfirmDialog(this,Theme.form("Name",name,"Allowed account hashes (empty = all)",new JScrollPane(allow),"Role permissions",overrides,"Slow mode seconds",slow,"Voice bitrate",rate,"Voice capacity",max),"Channel settings",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        c.name=name.getText().strip();c.allow.clear();for(String line:allow.getText().split("\\s+"))if(!line.isBlank())c.allow.add(line);c.slowModeSeconds=(Integer)slow.getValue();c.bitRate=(Integer)rate.getValue();c.maxMembers=(Integer)max.getValue();if(index<0)model.addElement(c);else model.set(index,c);
    }
    private void editRoleOverrides(Channel c){
        SavedServer s=selected();if(s==null)return;
        Map<String,Map<String,Boolean>> original=new HashMap<>();c.roleOverrides.forEach((id,m)->original.put(id,new HashMap<>(m)));
        java.util.List<String> ids=new ArrayList<>(),names=new ArrayList<>();ids.add("everyone");names.add("Everyone");for(Role r:s.roles){ids.add(r.id);names.add(r.name);}
        JComboBox<String> role=new JComboBox<>(names.toArray(String[]::new));String[] values={"Inherit","Allow","Deny"};JComboBox<String> view=new JComboBox<>(values),send=new JComboBox<>(values),connect=new JComboBox<>(values);
        Runnable load=()->{Map<String,Boolean> m=c.roleOverrides.getOrDefault(ids.get(role.getSelectedIndex()),Map.of());view.setSelectedIndex(!m.containsKey("view")?0:m.get("view")?1:2);send.setSelectedIndex(!m.containsKey("send")?0:m.get("send")?1:2);connect.setSelectedIndex(!m.containsKey("connect")?0:m.get("connect")?1:2);};
        // Save each selection before switching to another role.
        final int[] previous={0};Runnable save=()->{Map<String,Boolean> m=new HashMap<>();if(view.getSelectedIndex()>0)m.put("view",view.getSelectedIndex()==1);if(send.getSelectedIndex()>0)m.put("send",send.getSelectedIndex()==1);if(connect.getSelectedIndex()>0)m.put("connect",connect.getSelectedIndex()==1);if(m.isEmpty())c.roleOverrides.remove(ids.get(previous[0]));else c.roleOverrides.put(ids.get(previous[0]),m);};
        role.addActionListener(e->{save.run();previous[0]=role.getSelectedIndex();load.run();});load.run();
        if(JOptionPane.showConfirmDialog(this,Theme.form("Role",role,"View",view,"Send",send,"Connect",connect),"Channel role overrides",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)save.run();else{c.roleOverrides.clear();c.roleOverrides.putAll(original);}
    }
    private void openVoice(){SavedServer s=selected();Channel c=channelList.getSelectedValue();if(s==null||c==null||!c.type.equals("voice"))return;try{if(!s.channelKeys.containsKey(c.id))throw new Exception("Reconnect to refresh your channel access");if(!core.connected(s))throw new Exception("Reconnect to the server first");leaveVoice();voice=new VoicePanel(this,core,s,c,this::leaveVoice);voiceArea.add(voice);voiceArea.revalidate();voiceArea.repaint();voice.startAfter(previousVoice);status.setText("Voice joined · use the controls below to speak");}catch(Exception e){Theme.error(this,e);}}
    private void leaveVoice(){if(voice!=null){previousVoice=voice.stopped();voice.close();voice=null;voiceArea.removeAll();voiceArea.revalidate();voiceArea.repaint();status.setText("Left voice channel · microphone stopped");}}
    private void pollAll(){if(busy)return;SavedServer typingServer=selected();Channel typingChannel=channelList.getSelectedValue();boolean isTyping=!compose.getText().isBlank()&&System.currentTimeMillis()-lastTyped<4000;java.util.List<SavedServer> active=new ArrayList<>(core.state.servers);if(active.stream().noneMatch(core::connected))return;busy=true;worker.submit(()->{boolean changed=false;String failure=null;for(SavedServer s:active)if(core.connected(s))try{if(s==typingServer&&typingChannel!=null&&typingChannel.type.equals("text")&&isTyping)try{core.request(s,"typing",Json.obj("channel",typingChannel.id),true);}catch(ClientCore.ApiException unsupported){if(unsupported.status!=404)throw unsupported;}changed|=core.poll(s); }catch(Exception e){failure=s.name+": "+e.getMessage();}boolean update=changed;String error=failure;SwingUtilities.invokeLater(()->{busy=false;if(update){SavedServer s=selected();Channel old=channelList.getSelectedValue();if(s!=null){channels.clear();s.channels.forEach(channels::addElement);if(old!=null)for(int i=0;i<channels.size();i++)if(channels.get(i).id.equals(old.id))channelList.setSelectedIndex(i);}render();}updateIncomingCalls();if(error!=null)status.setText(error);});});}
    private interface Task{void run()throws Exception;}
    private void work(String text,Task task,Runnable done){if(busy){status.setText("Finishing the current operation…");return;}busy=true;status.setText(text);worker.submit(()->{try{task.run();SwingUtilities.invokeLater(()->{busy=false;done.run();});}catch(Exception e){SwingUtilities.invokeLater(()->{busy=false;status.setText(e.getMessage());Theme.error(this,e);});}});}
    private void lock(){timer.stop();if(ringTimer!=null)ringTimer.stop();if(ringingDialog!=null)ringingDialog.dispose();if(voice!=null)voice.close();worker.shutdownNow();core.close();dispose();}
}
