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
    private VoiceBridge voice;
    private boolean busy;
    private javax.swing.Timer timer;
    public static void main(String[] args){SwingUtilities.invokeLater(()->{Theme.install();Vault v=Launcher.unlock("client");if(v!=null)try{new ClientApp(v).setVisible(true);}catch(Exception e){Theme.error(null,e);v.close();}});}
    public ClientApp(Vault v)throws Exception{
        super("VCH / Conversations");core=new ClientCore(v);setSize(1320,850);setMinimumSize(new Dimension(950,650));setLocationRelativeTo(null);setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel header=Theme.panel(new BorderLayout());header.setBackground(Theme.INK);JLabel brand=Theme.title("VCH  /  Conversations",22);brand.setForeground(Color.WHITE);header.add(brand,BorderLayout.WEST);
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));tools.setOpaque(false);tools.add(Theme.button("Profile",this::profile));tools.add(Theme.button("Audio",this::audio));tools.add(Theme.button("Lock",this::lock));header.add(tools,BorderLayout.EAST);add(header,BorderLayout.NORTH);
        JPanel nav=new JPanel(new BorderLayout(0,14));nav.setBorder(new EmptyBorder(22,16,18,16));nav.setPreferredSize(new Dimension(245,0));nav.setBackground(Theme.NAV);
        JPanel navTop=new JPanel(new BorderLayout(0,12));navTop.setOpaque(false);JLabel communities=Theme.title("WORKSPACES",12);communities.setForeground(Color.LIGHT_GRAY);navTop.add(communities,BorderLayout.NORTH);
        serverList.setVisibleRowCount(4);serverList.setFixedCellHeight(40);serverList.setBackground(Theme.NAV);serverList.setForeground(Color.WHITE);navTop.add(new JScrollPane(serverList));
        navTop.add(Theme.button("+ Join a server",this::join),BorderLayout.SOUTH);nav.add(navTop,BorderLayout.NORTH);
        channelList.setBackground(Theme.NAV);channelList.setForeground(Color.WHITE);channelList.setFixedCellHeight(40);nav.add(new JScrollPane(channelList));identity.setForeground(Color.LIGHT_GRAY);nav.add(identity,BorderLayout.SOUTH);add(nav,BorderLayout.WEST);
        JPanel center=Theme.panel(new BorderLayout(14,14));center.add(heading,BorderLayout.NORTH);timeline.setLayout(new BoxLayout(timeline,BoxLayout.Y_AXIS));timeline.setBackground(Color.WHITE);scroll.setBorder(BorderFactory.createEmptyBorder());center.add(scroll);
        JPanel bottom=new JPanel(new BorderLayout(8,8));JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));controls.add(Theme.button("Reconnect",this::reconnect));controls.add(Theme.button("Join voice",this::openVoice));controls.add(Theme.button("Leave voice",()->{if(voice!=null){voice.close();voice=null;status.setText("Left voice channel");}}));bottom.add(controls,BorderLayout.NORTH);
        compose.setBorder(new EmptyBorder(14,14,14,14));compose.addActionListener(e->sendText());bottom.add(compose);JPanel send=new JPanel(new FlowLayout(FlowLayout.RIGHT));send.add(Theme.button("Image",this::sendImage));send.add(Theme.button("Send →",this::sendText));bottom.add(send,BorderLayout.EAST);bottom.add(status,BorderLayout.SOUTH);center.add(bottom,BorderLayout.SOUTH);add(center);
        JPanel right=Theme.panel(new BorderLayout(0,12));right.setPreferredSize(new Dimension(205,0));right.add(Theme.title("Members",18),BorderLayout.NORTH);JList<String> roster=new JList<>(members);roster.setBackground(Theme.PAPER);right.add(new JScrollPane(roster));add(right,BorderLayout.EAST);
        core.state.servers.forEach(servers::addElement);identity.setText(core.state.name);
        serverList.addListSelectionListener(e->{if(!e.getValueIsAdjusting())selectServer();});channelList.addListSelectionListener(e->{if(!e.getValueIsAdjusting())render();});
        if(!servers.isEmpty())serverList.setSelectedIndex(0);else welcome();
        timer=new javax.swing.Timer(1600,e->pollAll());timer.start();
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){lock();}});
    }
    private SavedServer selected(){return serverList.getSelectedValue();}
    private void welcome(){timeline.removeAll();JPanel p=Theme.panel(new BorderLayout(0,16));p.setBackground(Color.WHITE);p.add(Theme.title("A space for your people.",34),BorderLayout.NORTH);JTextArea t=new JTextArea("Join a private server with just its IP address or domain and password.\n\nOne profile. Separate communities.\nChannel encryption is set up automatically after login.\nYour local archive unlocks with your password.");t.setEditable(false);t.setOpaque(false);t.setFont(new Font("Segoe UI",Font.PLAIN,18));p.add(t);timeline.add(p);timeline.revalidate();timeline.repaint();}
    private void selectServer(){SavedServer s=selected();channels.clear();if(s==null){welcome();return;}s.channels.forEach(channels::addElement);if(!channels.isEmpty())channelList.setSelectedIndex(0);else render();}
    private void render(){
        SavedServer s=selected();Channel c=channelList.getSelectedValue();if(s==null)return;heading.setText(c==null?s.name:c.toString());identity.setText(core.state.name);members.clear();s.accounts.values().forEach(a->members.addElement(a.name));timeline.removeAll();
        if(c==null){timeline.revalidate();timeline.repaint();return;}
        if(c.type.equals("voice")){JPanel p=Theme.panel(new BorderLayout(0,15));p.setBackground(Color.WHITE);p.add(Theme.title("Speak with your community.",30),BorderLayout.NORTH);JTextArea t=new JTextArea("Push-to-talk · one speaker at a time\nEncrypted peer connections over WebRTC\nDAC target: "+c.bitRate+" bits/s\n\nChoose Join voice. Channel encryption is automatic.\nThe secure audio panel opens in your browser.\nThe bundled DAC model runs locally in Java.");t.setEditable(false);t.setOpaque(false);p.add(t);timeline.add(p);}
        else for(Envelope e:s.messages){if(!e.channel.equals(c.id))continue;JPanel row=new JPanel(new BorderLayout(12,6));row.setBackground(Color.WHITE);row.setBorder(new EmptyBorder(16,18,16,18));row.setMaximumSize(new Dimension(Integer.MAX_VALUE,500));Account a=s.accounts.get(e.author);
            JLabel label=new JLabel((a==null?e.author.substring(0,12):a.name)+"   ·   "+java.time.Instant.ofEpochMilli(e.time).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0));label.setForeground(Theme.MUTED);row.add(label,BorderLayout.NORTH);
            if(a!=null&&!a.avatar.isEmpty())try{var pic=Images.decode(Crypto.unb64(a.avatar),512);row.add(new JLabel(new ImageIcon(pic.getScaledInstance(32,32,Image.SCALE_SMOOTH))),BorderLayout.WEST);}catch(Exception ignored){}
            try{byte[] plain=core.decrypt(s,e);if(e.kind.equals("image")){if(!Images.isWebp(plain))throw new IOException("Invalid image");var image=Images.decode(plain,2048);int width=Math.min(420,image.getWidth());row.add(new JLabel(new ImageIcon(image.getScaledInstance(width,Math.max(1,image.getHeight()*width/image.getWidth()),Image.SCALE_SMOOTH))));}
                else{JTextArea text=new JTextArea(new String(plain,StandardCharsets.UTF_8));text.setLineWrap(true);text.setWrapStyleWord(true);text.setEditable(false);text.setOpaque(false);row.add(text);}}
            catch(Exception ex){JLabel locked=new JLabel("Encrypted message · "+ex.getMessage());locked.setForeground(Theme.MUTED);row.add(locked);}
            timeline.add(row);
        }
        if(c.type.equals("text")&&timeline.getComponentCount()==0){JLabel empty=Theme.title("This is the start of #"+c.name+".",24);empty.setBorder(new EmptyBorder(30,20,30,20));timeline.add(empty);}
        timeline.add(Box.createVerticalGlue());timeline.revalidate();timeline.repaint();SwingUtilities.invokeLater(()->scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()));
    }
    private void join(){
        JTextField address=new JTextField("localhost",28);JPasswordField password=new JPasswordField(28);
        if(JOptionPane.showConfirmDialog(this,Theme.form("IP address or domain",address,"Server password",password),"Join a server",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        SavedServer s=new SavedServer();s.address=address.getText().strip();char[] p=password.getPassword();password.setText("");
        work("Registering encrypted identity…",()->{core.connect(s,p);},()->{if(!servers.contains(s))servers.addElement(s);serverList.setSelectedValue(s,true);status.setText("Connected · "+s.name);});
    }
    private void reconnect(){SavedServer s=selected();if(s==null)return;work("Connecting…",()->core.connect(s,null),()->{selectServer();status.setText("Connected · "+s.name);});}
    private void sendText(){SavedServer s=selected();Channel c=channelList.getSelectedValue();String text=compose.getText();if(s==null||c==null||text.isBlank())return;work("Sending…",()->core.send(s,c,"text",Crypto.utf(text)),()->{compose.setText("");render();status.setText("Sent · encrypted & compressed");});}
    private void sendImage(){SavedServer s=selected();Channel c=channelList.getSelectedValue();if(s==null||c==null)return;JFileChooser picker=new JFileChooser();picker.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Still images","png","jpg","jpeg","webp"));if(picker.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)work("Compressing image locally…",()->core.send(s,c,"image",Images.prepare(picker.getSelectedFile().toPath(),100000,1600)),()->{render();status.setText("Image sent · WebP ≤ 100 KB");});}
    private void profile(){
        JTextField name=new JTextField(core.state.name,24),bio=new JTextField(core.state.bio,24);String[] avatar={core.state.avatar};JLabel picture=new JLabel(avatar[0].isEmpty()?"No profile picture":"WebP profile picture saved");
        JButton choose=Theme.button("Choose picture",()->{JFileChooser fc=new JFileChooser();if(fc.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)try{avatar[0]=Crypto.b64(Images.prepare(fc.getSelectedFile().toPath(),10000,256));picture.setText("Ready · "+Crypto.unb64(avatar[0]).length+" bytes");}catch(Exception e){Theme.error(this,e);}});
        JTextField hash=new JTextField(selected()==null?"Join a server to register":core.account(selected()));hash.setEditable(false);
        if(JOptionPane.showConfirmDialog(this,Theme.form("Display name",name,"About you",bio,"Profile picture (10 KB)",choose,"Picture status",picture,"Current server account hash",hash),"Your profile",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){
            if(name.getText().isBlank()||name.getText().length()>40||bio.getText().length()>240){Theme.error(this,new Exception("Use a name of 1–40 characters and a bio up to 240 characters"));return;}
            work("Saving profile…",()->{core.state.name=name.getText().strip();core.state.bio=bio.getText();core.state.avatar=avatar[0];core.save();for(SavedServer s:core.state.servers)if(core.connected(s))core.updateProfile(s);},()->{identity.setText(core.state.name);status.setText("Profile saved across connected servers");});
        }
    }
    private void audio(){
        JComboBox<String> mic=new JComboBox<>(),speakers=new JComboBox<>();mic.addItem("Default");speakers.addItem("Default");for(Mixer.Info info:AudioSystem.getMixerInfo()){Mixer m=AudioSystem.getMixer(info);if(m.getTargetLineInfo().length>0)mic.addItem(info.getName());if(m.getSourceLineInfo().length>0)speakers.addItem(info.getName());}mic.setEditable(true);speakers.setEditable(true);mic.setSelectedItem(core.state.microphone);speakers.setSelectedItem(core.state.speakers);
        JSlider input=new JSlider(0,200,core.state.inputGain),output=new JSlider(0,200,core.state.outputGain);JComboBox<String> ptt=new JComboBox<>(new String[]{"SPACE","V","F8"});ptt.setSelectedItem(core.state.pttKey);JTextField ice=new JTextField(core.state.iceUrls,30);
        if(JOptionPane.showConfirmDialog(this,Theme.form("Microphone name",mic,"Playback device name",speakers,"Input gain",input,"Output volume",output,"Push-to-talk key",ptt,"ICE URLs (comma separated)",ice),"Audio preferences",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)try{core.state.microphone=mic.getSelectedItem().toString();core.state.speakers=speakers.getSelectedItem().toString();core.state.inputGain=input.getValue();core.state.outputGain=output.getValue();core.state.pttKey=ptt.getSelectedItem().toString();core.state.iceUrls=ice.getText().strip();core.save();status.setText("Audio saved · reopen voice to apply");}catch(Exception e){Theme.error(this,e);}
    }
    private void openVoice(){SavedServer s=selected();Channel c=channelList.getSelectedValue();if(s==null||c==null||!c.type.equals("voice"))return;try{if(!s.channelKeys.containsKey(c.id))throw new Exception("Reconnect to refresh your channel access");if(!core.connected(s))throw new Exception("Reconnect to the server first");if(voice!=null)voice.close();voice=new VoiceBridge(core,s,c);Desktop.getDesktop().browse(voice.uri());status.setText("Voice panel opened · hold push-to-talk to speak");}catch(Exception e){Theme.error(this,e);}}
    private void pollAll(){if(busy)return;java.util.List<SavedServer> active=new ArrayList<>(core.state.servers);if(active.stream().noneMatch(core::connected))return;busy=true;worker.submit(()->{boolean changed=false;String failure=null;for(SavedServer s:active)if(core.connected(s))try{changed|=core.poll(s);}catch(Exception e){failure=s.name+": "+e.getMessage();}boolean update=changed;String error=failure;SwingUtilities.invokeLater(()->{busy=false;if(update){SavedServer s=selected();Channel old=channelList.getSelectedValue();if(s!=null){channels.clear();s.channels.forEach(channels::addElement);if(old!=null)for(int i=0;i<channels.size();i++)if(channels.get(i).id.equals(old.id))channelList.setSelectedIndex(i);}render();}if(error!=null)status.setText(error);});});}
    private interface Task{void run()throws Exception;}
    private void work(String text,Task task,Runnable done){if(busy){status.setText("Finishing the current operation…");return;}busy=true;status.setText(text);worker.submit(()->{try{task.run();SwingUtilities.invokeLater(()->{busy=false;done.run();});}catch(Exception e){SwingUtilities.invokeLater(()->{busy=false;status.setText(e.getMessage());Theme.error(this,e);});}});}
    private void lock(){timer.stop();if(voice!=null)voice.close();worker.shutdownNow();core.close();dispose();}
}
