package chat.vch.flip;

import android.app.Activity;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.google.gson.*;
import static chat.vch.flip.NativeClient.*;
import chat.vch.flip.NativeClient.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Small-screen, focus-first client. All network/vault operations share one worker. */
public final class MainActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status;
    private NativeClient core;
    private SavedServer server;
    private Channel channel;
    private String page="locked", draft="";
    private boolean busy, foreground;
    private boolean external, screenConsent, mapping;
    private int imagePurpose, messageOffset;
    private String avatarDraft="", serverImageDraft="";
    private String profileName="",profilePronouns="",profileBio="";
    private NativeClient.State audioDraft;
    private String gainDraft,volumeDraft,rateDraft;
    private final List<String> attachments=new ArrayList<>();
    private List<Channel> editChannels;
    private List<Role> editRoles;
    private Set<String> editEveryone;
    private String editServerName,editServerDescription;
    private VoiceEngine voice;
    private VideoEngine video;
    private Button talk;
    private final Set<String> notifiedCalls=new HashSet<>();
    private final BroadcastReceiver screenOff=new BroadcastReceiver(){public void onReceive(Context c,Intent i){external=false;screenConsent=false;stopMedia();lock();if(foreground)unlock();}};
    private int generation;
    private android.net.Uri pendingImage;
    private final Runnable externalTimeout=()->{if(!foreground&&external){external=false;stopMedia();lock();}};
    private final Runnable poll=new Runnable(){public void run(){
        if(foreground && core!=null && !busy) {
            NativeClient c=core;
            MainActivity.this.run(()->{boolean changed=false;for(SavedServer s:new ArrayList<>(c.state.servers))if(c.connected(s))changed|=c.poll(s);return changed;},changed->{ incoming();if(Boolean.TRUE.equals(changed)&&page.equals("thread"))thread(); });
        }
        if(foreground)handler.postDelayed(this,5000);
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
        NotificationChannel calls=new NotificationChannel("vch_calls","VCH calls",NotificationManager.IMPORTANCE_HIGH);getSystemService(NotificationManager.class).createNotificationChannel(calls);
    }
    @Override public void onResume() {
        super.onResume(); foreground=true;
        handler.removeCallbacks(externalTimeout);
        if(core==null)unlock(); else if(voice!=null)voicePage();else if(!external)home();
        external=false;
        if(pendingImage!=null&&core!=null){android.net.Uri uri=pendingImage;pendingImage=null;loadImage(uri);}
        handler.removeCallbacks(poll); handler.postDelayed(poll,5000);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(core!=null&&intent.getBooleanExtra("calls",false)){screen("calls","Incoming calls");callButtons();action("Back",this::home);focus();}}
    @Override public void onStop() {
        foreground=false; handler.removeCallbacks(poll);if(voice!=null)voice.press(false);
        if(!external&&!screenConsent&&(video==null||!video.sharingScreen())){stopMedia();lock();}
        else if(video!=null)video.stopCamera();
        if(external)handler.postDelayed(externalTimeout,120000);
        super.onStop();
    }
    @Override public void onDestroy() {try{unregisterReceiver(screenOff);}catch(IllegalArgumentException ignored){}stopMedia();lock();handler.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy(); }
    private void lock() {
        generation++; busy=false; draft="";attachments.clear();avatarDraft="";serverImageDraft="";audioDraft=null;profileName="";profileBio="";profilePronouns="";editChannels=null;editRoles=null;editEveryone=null;mapping=false;pendingImage=null;
        getSystemService(NotificationManager.class).cancelAll();notifiedCalls.clear();
        NativeClient closing=core; core=null; server=null; channel=null;
        if(closing!=null)worker.submit(closing::close);
        if(content!=null)content.removeAllViews();
    }
    private void screen(String name, String title) {
        page=name;
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(core!=null&&!core.state.darkMode?0xfff4f4f4:0xff161616);
        TextView heading=label(title,16); heading.setTextColor(0xff78a9ff); root.addView(heading);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setFocusable(false);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(4,0,4,0);scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=label(busy?"Working…":"↑↓ Move · OK Select · Back",10); status.setMaxLines(2); root.addView(status);
        setContentView(root);
    }
    private TextView label(String text,int size) {
        TextView view=new TextView(this); view.setText(text);view.setTextSize(size);view.setTextColor(core!=null&&!core.state.darkMode?0xff161616:0xfff4f4f4);view.setPadding(6,4,6,4);return view;
    }
    private void note(String text) { content.addView(label(text,12)); }
    private Button action(String title,Runnable click) {
        Button button=new Button(this);button.setText(title);button.setTextSize(13);button.setAllCaps(false);button.setTextColor(0xfff4f4f4);
        button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);button.setMinHeight(34);button.setMinimumHeight(34);button.setPadding(8,4,8,4);
        button.setBackgroundResource(R.drawable.focus_row);button.setFocusable(true);button.setOnClickListener(v->{if(!busy)click.run();});
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.setMargins(0,2,0,2);content.addView(button,layout);return button;
    }
    private KeypadEditText field(String title,String value,boolean secret) {
        note(title);KeypadEditText edit=new KeypadEditText(this,title,secret);edit.setTextColor(core!=null&&!core.state.darkMode?0xff161616:0xfff4f4f4);edit.setHintTextColor(0xff808080);edit.setText(value);
        edit.setSelectAllOnFocus(!secret);if(!secret&&value.matches("[0-9]+"))edit.numeric();edit.setModeListener(mode->status.setText(mode+" · # mode · * delete · ↑↓ Move"));content.addView(edit,new LinearLayout.LayoutParams(-1,-2));return edit;
    }
    private void keypadHelp(){note("2–9 letters · 0 space\n1 symbols · # abc/ABC/123\n* delete · wait for next letter");}
    private void focus() { if(content.getChildCount()>0)content.post(()->{for(int i=0;i<content.getChildCount();i++)if(content.getChildAt(i).isFocusable()){content.getChildAt(i).requestFocus();break;}}); }
    private void unlock() {
        boolean create=!Files.exists(getFilesDir().toPath().resolve("data.vault"));
        screen("locked",create?"VCH Flip / Create vault":"VCH Flip / Unlock");
        note(create?"Choose at least 12 characters.":"Your archive is encrypted.");
        KeypadEditText password=field("Vault password","",true);
        KeypadEditText confirm=create?field("Confirm password","",true):null;
        action(create?"Create vault":"Unlock",()->{
            char[] secret=password.getText().toString().toCharArray();
            if(create&&!Arrays.equals(secret,confirm.getText().toString().toCharArray())) {Arrays.fill(secret,'\0');status.setText("Passwords do not match");return;}
            password.setText("");if(confirm!=null)confirm.setText("");
            run(()->new NativeClient(this,secret),result->{core=(NativeClient)result;home();});
        });keypadHelp();action("Exit",this::finish);focus();
    }
    private void home() {
        screen("home","VCH Flip / Communities");
        if(core.state.servers.isEmpty())note("Join your VCH community.");
        for(SavedServer s:core.state.servers)action(s.toString(),()->{server=s;channel=null;channels();});
        action("Join a server",this::join);action("Your profile",()->{avatarDraft=core.state.avatar;profileName=core.state.name;profilePronouns=core.state.pronouns;profileBio=core.state.bio;profile();});action("Audio / Appearance",this::audioStart);action("Help / About",this::help);
        callButtons();
        action("Lock vault",()->{lock();unlock();});focus();
    }
    private void join() {
        screen("join","Join community");
        KeypadEditText address=field("Host or IP (port 585)","",false),password=field("Join password","",true);
        CheckBox tor=new CheckBox(this);tor.setText("Tor only");tor.setTextSize(13);tor.setTextColor(0xfff4f4f4);content.addView(tor);
        KeypadEditText port=field("Tor SOCKS port","9050",false);
        action("Join",()->{
            SavedServer candidate=new SavedServer();candidate.address=address.getText().toString().trim();candidate.torOnly=tor.isChecked();
            try {candidate.torSocksPort=Integer.parseInt(port.getText().toString());if(candidate.torSocksPort<1||candidate.torSocksPort>65535)throw new NumberFormatException();}
            catch(NumberFormatException e){status.setText("SOCKS port: 1–65535");return;}
            char[] secret=password.getText().toString().toCharArray();password.setText("");NativeClient c=core;
            run(()->{c.connect(candidate,secret);return candidate;},s->{server=(SavedServer)s;channels();});
        });keypadHelp();action("Back",this::home);focus();
    }
    private void channels() {
        screen("channels",server.name);
        note(core.connected(server)?"Connected":"Offline archive");
        for(Channel c:server.channels)action(("text".equals(c.type)?"# ":"Voice / ")+c.name,()->{draft="";attachments.clear();messageOffset=0;channel=c;if("voice".equals(c.type))openVoice();else thread();});
        if(server.channels.stream().noneMatch(c->"text".equals(c.type)))note("Connect to load text channels.");
        action(core.connected(server)?"Disconnect":"Reconnect",()->{
            NativeClient c=core;SavedServer s=server;
            run(()->{if(c.connected(s))c.disconnect(s);else c.connect(s,null);return null;},r->channels());
        });
        action("Friends",this::friends);action("Connection / Tor",this::connection);action("Server details",this::details);
        if(server.admin)action("Manage server",this::manageStart);if(server.roleManager)action("Manage roles",this::rolesStart);callButtons();
        action("Remove saved server",()->confirm("Remove this server and its local archive?",()->{NativeClient c=core;SavedServer s=server;run(()->{c.call("/remove",json("server",s.id));c.refresh();return null;},r->{server=null;channel=null;home();});},this::channels));
        action("Back",this::home);focus();
    }
    private void thread() {
        if(channel==null){channels();return;}
        screen("thread","# "+channel.name);
        action("Write message",this::compose);
        List<Envelope> list=new ArrayList<>();for(Envelope e:server.messages)if(channel.id.equals(e.channel))list.add(e);
        // Newest first keeps the latest conversation reachable without scrolling a long archive.
        Collections.reverse(list);
        if(list.isEmpty())note("No messages yet.");
        for(int i=messageOffset;i<Math.min(messageOffset+30,list.size());i++){
            Envelope e=list.get(i);Account a=server.accounts.get(e.author);String who=a==null?"Member":a.name;
            String time=android.text.format.DateFormat.format("MM-dd HH:mm",e.time).toString();
            action(who+" · "+time+"\n"+("text".equals(e.kind)?"Read message":"View "+e.kind),()->message(e));
        }
        if(messageOffset+30<list.size())action("Older messages",()->{messageOffset+=30;thread();});
        if(messageOffset>0)action("Newer messages",()->{messageOffset=Math.max(0,messageOffset-30);thread();});
        for(var typing:server.typing.entrySet())if(channel.id.equals(typing.getValue())){Account a=server.accounts.get(typing.getKey());note((a==null?"Member":a.name)+" is typing…");}
        action("Refresh",()->{NativeClient c=core;SavedServer s=server;run(()->c.poll(s),r->thread());});
        action("Back to channels",this::channels);focus();
    }
    private void message(Envelope envelope) {
        NativeClient c=core;SavedServer s=server;
        run(()->c.decrypt(s,envelope),result->{
            screen("message","# "+channel.name);byte[] plain=(byte[])result;
            Account author=s.accounts.get(envelope.author);note(author==null?"Member":author.name);
            try {
                if("text".equals(envelope.kind)) {TextView body=label(new String(plain,java.nio.charset.StandardCharsets.UTF_8),14);body.setFocusable(true);body.setBackgroundResource(R.drawable.focus_row);content.addView(body);}
                else if("image".equals(envelope.kind))image(plain);
                else if("images".equals(envelope.kind)){
                    var payload=JsonParser.parseString(new String(plain,java.nio.charset.StandardCharsets.UTF_8));
                    var images=payload.isJsonArray()?payload.getAsJsonArray():payload.getAsJsonObject().getAsJsonArray("images");
                    if(payload.isJsonObject())note(str(payload.getAsJsonObject(),"text"));
                    if(images==null||images.size()>3)throw new IOException("Invalid image collection");
                    for(var item:images)image(NativeClient.unb64(item.getAsString()));
                } else note("Unsupported message type: "+envelope.kind);
            }catch(Exception e){note("Cannot display: "+e.getMessage());}
            Arrays.fill(plain,(byte)0);action("Back to messages",this::thread);focus();
        });
    }
    private void image(byte[] bytes)throws IOException {
        if(bytes.length>100000)throw new IOException("Image exceeds 100 KB");
        BitmapFactory.Options info=new BitmapFactory.Options();info.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,info);
        if(info.outWidth<1||info.outHeight<1||info.outWidth>2048||info.outHeight>2048)throw new IOException("Invalid image dimensions");
        info.inJustDecodeBounds=false;info.inSampleSize=Math.max(1,Math.max(info.outWidth,info.outHeight)/240);
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,info);if(bitmap==null)throw new IOException("Invalid image");
        ImageView view=new ImageView(this);view.setImageBitmap(bitmap);view.setAdjustViewBounds(true);view.setContentDescription("Chat attachment");view.setFocusable(true);view.setBackgroundResource(R.drawable.focus_row);content.addView(view,new LinearLayout.LayoutParams(-1,-2));
    }
    private void compose() {
        screen("compose","Write / # "+channel.name);
        KeypadEditText text=field("Message",draft,false);text.setSingleLine(false);text.setMinLines(2);text.setMaxLines(4);
        text.addTextChangedListener(new android.text.TextWatcher(){long last;public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(android.text.Editable e){draft=e.toString();if(System.currentTimeMillis()-last>3000&&!busy&&core!=null){last=System.currentTimeMillis();NativeClient c=core;SavedServer s=server;String id=channel.id;worker.submit(()->{try{c.request(s,"typing",json("channel",id),true);}catch(Exception ignored){}});}}public void onTextChanged(CharSequence s,int start,int before,int count){}});
        note(attachments.size()+" / 3 images staged");
        action("Add image",()->{draft=text.getText().toString();if(attachments.size()<3)pickImage(1);else status.setText("Maximum three images");});
        action("Clear images",()->{draft=text.getText().toString();attachments.clear();compose();});
        action("Send",()->{
            byte[] bytes=NativeClient.utf(text.getText().toString());if(bytes.length==0&&attachments.isEmpty()){status.setText("Enter a message or add images");return;}
            NativeClient c=core;SavedServer s=server;Channel target=channel;
            List<String> staged=new ArrayList<>(attachments);
            run(()->{if(bytes.length>0)c.send(s,target,"text",bytes);if(!staged.isEmpty())c.send(s,target,"images",utf(GSON.toJson(staged)));return null;},r->{draft="";attachments.clear();thread();});
        });action("Save draft / Back",()->{draft=text.getText().toString();thread();});keypadHelp();focus();
    }
    private void profile() {
        screen("profile","Your profile");
        KeypadEditText name=field("Display name",profileName,false),pronouns=field("Pronouns",profilePronouns,false),bio=field("Bio",profileBio,false);
        bind(name,v->profileName=v);bind(pronouns,v->profilePronouns=v);bind(bio,v->profileBio=v);
        if(!avatarDraft.isEmpty())try{image(unb64(avatarDraft));}catch(IOException ignored){}
        action("Choose profile image",()->pickImage(2));action("Remove profile image",()->{avatarDraft="";profile();});
        action("Save",()->{
            String n=name.getText().toString().trim(),p=pronouns.getText().toString().trim(),b=bio.getText().toString();
            if(n.isEmpty()||n.length()>40||b.length()>240||p.length()>40){status.setText("Name 1–40; pronouns ≤40; bio ≤240");return;}
            NativeClient c=core;String avatar=avatarDraft;run(()->{c.state.name=n;c.state.pronouns=p;c.state.bio=b;c.state.avatar=avatar;c.save();return null;},r->home());
        });action("Back",this::home);keypadHelp();focus();
    }
    private void friends() {
        screen("friends","Friends");
        NativeClient c=core;SavedServer s=server;
        for(String hash:s.friendRequests){Account a=s.accounts.get(hash);note("Request: "+(a==null?hash:a.name));action("Accept",()->friend("accept",hash));action("Decline",()->friend("decline",hash));}
        for(Friendship f:s.friendships){String hash=f.first.equals(c.account(s))?f.second:f.first;Account a=s.accounts.get(hash);
            action(a==null?hash:a.name,()->{for(Channel target:s.channels)if(target.id.equals(f.textChannel)){channel=target;thread();return;}status.setText("Reconnect to load friend channel");});}
        for(Friendship f:s.friendships){String hash=f.first.equals(c.account(s))?f.second:f.first;Account a=s.accounts.get(hash);String name=a==null?hash:a.name;
            action("Call "+name,()->callAction(s,"ring",json("account",hash),f.voiceChannel));action("Remove "+name,()->confirm("Remove friend "+name+"?",()->friend("remove",hash),this::friends));}
        callButtons();
        action("Add friend",this::addFriend);action("Back",this::channels);focus();
    }
    private void addFriend() {
        screen("addFriend","Add friend");
        for(Account a:server.accounts.values())if(!a.hash.equals(core.account(server)))action(a.name,()->friend("request",a.hash));
        KeypadEditText hash=field("Or enter account hash","",false);action("Send request",()->friend("request",hash.getText().toString().trim()));
        for(Account a:server.accounts.values())action("Profile / "+a.name,()->{screen("account",a.name);note(a.pronouns);note(a.bio);note(a.hash);if(a.avatar!=null&&!a.avatar.isEmpty())try{image(unb64(a.avatar));}catch(IOException ignored){}action("Back",this::addFriend);focus();});
        action("Back",this::friends);focus();
    }
    private void friend(String action,String hash) {
        NativeClient c=core;SavedServer s=server;
        run(()->{c.request(s,"friend",json("action",action,"account",hash),true);c.poll(s);return null;},r->friends());
    }
    private void details() {
        screen("details","Server details");note(server.name);note(server.description);note(server.address);note("Account\n"+core.account(server));note("Certificate\n"+server.fingerprint);
        note("Connection: "+(server.torOnly?"Tor only":"Direct HTTPS"));
        action("Back",this::channels);focus();
    }
    private CheckBox check(String title,boolean value){CheckBox box=new CheckBox(this);box.setText(title);box.setTextSize(13);box.setTextColor(core.state.darkMode?0xfff4f4f4:0xff161616);box.setChecked(value);content.addView(box);return box;}
    private void bind(EditText edit,java.util.function.Consumer<String> changed){edit.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changed.accept(s.toString());}public void afterTextChanged(android.text.Editable e){}});}
    private void confirm(String message,Runnable yes,Runnable no){screen("confirm","Confirm");note(message);action("Cancel",no);action("Confirm",yes);focus();}
    private void connection(){
        screen("connection","Connection / Tor");CheckBox tor=check("Tor only",server.torOnly);KeypadEditText port=field("SOCKS port on 127.0.0.1",Integer.toString(server.torSocksPort),false);
        note("Start a trusted Tor service separately. Tor-only never falls back to direct HTTPS.");
        action("Save connection",()->{try{int p=Integer.parseInt(port.getText().toString());if(p<1||p>65535)throw new NumberFormatException();NativeClient c=core;SavedServer s=server;boolean enabled=tor.isChecked();run(()->{c.call("/connection",json("server",s.id,"torOnly",enabled,"torSocksPort",p));c.refresh();return null;},r->channels());}catch(NumberFormatException e){status.setText("Port must be 1–65535");}});
        action("Back",this::channels);focus();
    }
    private void audio(){
        screen("audio","Audio / Appearance");NativeClient.State s=audioDraft;
        KeypadEditText input=field("Input gain % (0–300)",gainDraft,false),output=field("Output gain % (0–300)",volumeDraft,false),rate=field("Voice bits/s (500–6000)",rateDraft,false);
        bind(input,v->gainDraft=v);bind(output,v->volumeDraft=v);bind(rate,v->rateDraft=v);
        CheckBox dark=check("Dark appearance",s.darkMode);
        dark.setOnCheckedChangeListener((v,on)->s.darkMode=on);
        action("Microphone / "+s.microphone,()->devices(true));action("Output / "+s.speakers,()->devices(false));
        action("Map PTT key ("+getPreferences(0).getInt("ptt",138)+")",()->{mapping=true;status.setText("Press the physical PTT key");});
        action("Enable call notifications",()->{if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},90);});
        action("Save preferences",()->{try{
            int ig=Integer.parseInt(input.getText().toString()),og=Integer.parseInt(output.getText().toString()),br=Integer.parseInt(rate.getText().toString());
            JsonObject settings=json("inputGain",ig,"outputGain",og,"outgoingBitRate",br,"darkMode",dark.isChecked(),"microphone",s.microphone,"speakers",s.speakers,"pttKey",Integer.toString(getPreferences(0).getInt("ptt",138)));
            NativeClient c=core;run(()->{c.call("/audio",settings);c.refresh();return null;},r->home());
        }catch(NumberFormatException e){status.setText("Enter numeric gains and bitrate");}});action("Back",this::home);focus();
    }
    private void audioStart(){audioDraft=GSON.fromJson(GSON.toJson(core.state),NativeClient.State.class);gainDraft=Integer.toString(audioDraft.inputGain);volumeDraft=Integer.toString(audioDraft.outputGain);rateDraft=Integer.toString(audioDraft.outgoingBitRate);audio();}
    private void devices(boolean input){
        screen("devices",input?"Microphones":"Audio outputs");action("Default",()->{if(input)audioDraft.microphone="Default";else audioDraft.speakers="Default";audio();});
        AudioManager manager=getSystemService(AudioManager.class);
        for(AudioDeviceInfo device:manager.getDevices(input?AudioManager.GET_DEVICES_INPUTS:AudioManager.GET_DEVICES_OUTPUTS))action(device.getProductName()+" / "+device.getId(),()->{if(input)audioDraft.microphone="id:"+device.getId();else audioDraft.speakers="id:"+device.getId();audio();});
        action("Back",this::audio);focus();
    }
    private void manageStart(){editChannels=new ArrayList<>();for(Channel c:server.adminChannels)editChannels.add(GSON.fromJson(GSON.toJson(c),Channel.class));editServerName=server.name;editServerDescription=server.description;serverImageDraft=server.image;manage();}
    private void manage(){
        screen("manage","Manage / "+server.name);KeypadEditText name=field("Server name",editServerName,false),description=field("Description",editServerDescription,false);bind(name,v->editServerName=v);bind(description,v->editServerDescription=v);
        if(!serverImageDraft.isEmpty())try{image(unb64(serverImageDraft));}catch(IOException ignored){}
        action("Choose server image",()->pickImage(3));action("Remove server image",()->{serverImageDraft="";manage();});
        for(int i=0;i<editChannels.size();i++){final int at=i;Channel c=editChannels.get(i);action(("voice".equals(c.type)?"Voice / ":"# ")+c.name,()->editChannel(at));}
        action("Add text channel",()->{editChannels.add(new Channel());editChannel(editChannels.size()-1);});action("Add voice channel",()->{Channel c=new Channel();c.type="voice";c.name="Voice";editChannels.add(c);editChannel(editChannels.size()-1);});
        action("Save server settings",()->{NativeClient c=core;SavedServer s=server;JsonObject data=json("name",editServerName,"description",editServerDescription,"image",serverImageDraft,"channels",editChannels);run(()->{c.request(s,"admin",data,true);c.poll(s);return null;},r->channels());});
        action("Cancel",this::channels);focus();
    }
    private void editChannel(int at){
        Channel c=editChannels.get(at);screen("editChannel","Channel / "+c.name);
        KeypadEditText name=field("Name",c.name,false);bind(name,v->c.name=v);
        action("Type: "+c.type,()->{c.type=c.type.equals("text")?"voice":"text";editChannel(at);});
        KeypadEditText slow=field("Slow mode seconds (0–3600)",Integer.toString(c.slowModeSeconds),false),rate=field("Voice bits/s (500–6000)",Integer.toString(c.bitRate),false),capacity=field("Voice capacity (2–32)",Integer.toString(c.maxMembers),false);
        KeypadEditText allow=field("Allowed hashes (space separated)",String.join(" ",c.allow),false);bind(allow,v->c.allow=words(v));
        action("Apply numeric settings",()->{try{int sm=Integer.parseInt(slow.getText().toString()),br=Integer.parseInt(rate.getText().toString()),max=Integer.parseInt(capacity.getText().toString());if(sm<0||sm>3600||br<500||br>6000||max<2||max>32)throw new NumberFormatException();c.slowModeSeconds=sm;c.bitRate=br;c.maxMembers=max;manage();}catch(NumberFormatException e){status.setText("Check the numeric ranges");}});
        action("Account allow list",()->allowMembers(at));action("Role overrides",()->overrides(at));
        if(at>0)action("Move earlier",()->{Collections.swap(editChannels,at,at-1);manage();});if(at+1<editChannels.size())action("Move later",()->{Collections.swap(editChannels,at,at+1);manage();});
        action("Delete channel",()->confirm("Delete "+c.name+" when settings are saved?",()->{editChannels.remove(at);manage();},()->editChannel(at)));action("Back",this::manage);focus();
    }
    private static Set<String> words(String value){Set<String> result=new LinkedHashSet<>();for(String v:value.trim().split("\\s+"))if(!v.isEmpty())result.add(v);return result;}
    private void allowMembers(int at){Channel c=editChannels.get(at);screen("allow","Allowed accounts");note("Empty means everyone allowed by roles.");for(Account a:server.accounts.values()){CheckBox box=check(a.name,c.allow.contains(a.hash));box.setOnCheckedChangeListener((v,on)->{if(on)c.allow.add(a.hash);else c.allow.remove(a.hash);});}action("Back",()->editChannel(at));focus();}
    private void overrides(int at){screen("overrides","Channel role overrides");action("Everyone",()->overrideRole(at,"everyone","Everyone"));for(Role r:server.roles)action(r.name,()->overrideRole(at,r.id,r.name));action("Back",()->editChannel(at));focus();}
    private void overrideRole(int at,String id,String name){
        Channel c=editChannels.get(at);screen("overrideRole",name);Map<String,Boolean> values=c.roleOverrides.computeIfAbsent(id,k->new LinkedHashMap<>());
        for(String permission:new String[]{"view","send","connect"})action(permission+": "+(!values.containsKey(permission)?"Inherit":values.get(permission)?"Allow":"Deny"),()->{if(!values.containsKey(permission))values.put(permission,true);else if(values.get(permission))values.put(permission,false);else values.remove(permission);overrideRole(at,id,name);});action("Back",()->overrides(at));focus();
    }
    private void rolesStart(){editRoles=new ArrayList<>();for(Role r:server.roles)editRoles.add(GSON.fromJson(GSON.toJson(r),Role.class));editEveryone=new LinkedHashSet<>(server.everyonePermissions);roles();}
    private void roles(){screen("roles","Roles / low to high");action("Everyone",()->permissions("Everyone",editEveryone,false,this::roles));for(int i=0;i<editRoles.size();i++){int at=i;action(editRoles.get(i).name,()->editRole(at));}
        action("Add role",()->{editRoles.add(new Role());editRole(editRoles.size()-1);});
        action("Save roles",()->{NativeClient c=core;SavedServer s=server;JsonObject data=json("roles",editRoles,"everyonePermissions",editEveryone);run(()->{c.request(s,"roles",data,true);c.poll(s);return null;},r->channels());});action("Cancel",this::channels);focus();}
    private void permissions(String name,Set<String> values,boolean manage,Runnable back){screen("permissions",name);for(String key:manage?new String[]{"view","send","connect","manage_roles"}:new String[]{"view","send","connect"}){CheckBox box=check(key,values.contains(key));box.setOnCheckedChangeListener((v,on)->{if(on)values.add(key);else values.remove(key);});}action("Back",back);focus();}
    private void editRole(int at){Role r=editRoles.get(at);screen("editRole","Role / "+r.name);KeypadEditText name=field("Name",r.name,false);bind(name,v->r.name=v);action("Permissions",()->permissions(r.name,r.permissions,true,()->editRole(at)));
        KeypadEditText hashes=field("Member hashes",String.join(" ",r.members),false);bind(hashes,v->r.members=words(v));
        for(Account a:server.accounts.values()){CheckBox box=check(a.name,r.members.contains(a.hash));box.setOnCheckedChangeListener((v,on)->{if(on)r.members.add(a.hash);else r.members.remove(a.hash);});}
        if(at>0)action("Move lower",()->{Collections.swap(editRoles,at,at-1);roles();});if(at+1<editRoles.size())action("Move higher",()->{Collections.swap(editRoles,at,at+1);roles();});
        action("Remove role",()->{editRoles.remove(at);roles();});action("Back",this::roles);focus();}
    private void pickImage(int purpose){imagePurpose=purpose;external=true;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(intent,100);}catch(ActivityNotFoundException e){external=false;status.setText("No document picker is installed");}}
    private void loadImage(android.net.Uri uri){NativeClient c=core;int purpose=imagePurpose;run(()->{
        byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("Cannot open image");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>20_000_000)throw new IOException("Source image exceeds 20 MB");out.write(buffer,0,n);}bytes=out.toByteArray();}
        return str(c.call("/image",json("data",b64(bytes),"profile",purpose!=1)),"data");
    },result->{if(purpose==1){attachments.add((String)result);compose();}else if(purpose==2){avatarDraft=(String)result;profile();}else{serverImageDraft=(String)result;manage();}});}
    private void incoming(){
        if(core==null)return;Set<String> active=new HashSet<>();
        for(SavedServer s:core.state.servers)for(Call call:s.calls){active.add(call.id);if(notifiedCalls.add(call.id)){
            Account a=s.accounts.get(call.from);String who=a==null?"A friend":a.name;
            if(Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED){
                PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).putExtra("calls",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                getSystemService(NotificationManager.class).notify(call.id.hashCode(),new Notification.Builder(this,"vch_calls").setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle("VCH incoming call").setContentText(who+" / "+s.name).setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_CALL).build());
            }
            status.setText("Incoming call from "+who+" — open Friends");
        }}
        for(String id:new HashSet<>(notifiedCalls))if(!active.contains(id)){getSystemService(NotificationManager.class).cancel(id.hashCode());notifiedCalls.remove(id);}
    }
    private void callButtons(){if(core==null)return;for(SavedServer s:core.state.servers)for(Call call:s.calls){Account a=s.accounts.get(call.from);note("Incoming: "+(a==null?"Friend":a.name));action("Answer",()->callAction(s,"accept",json("id",call.id),call.channel));action("Decline",()->callAction(s,"decline",json("id",call.id),null));}}
    private void callAction(SavedServer s,String action,JsonObject data,String voiceChannel){if(voiceChannel!=null)stopMedia();data.addProperty("action",action);NativeClient c=core;run(()->{c.request(s,"call",data,true);c.poll(s);return null;},r->{server=s;if(voiceChannel!=null){for(Channel candidate:s.channels)if(candidate.id.equals(voiceChannel)){channel=candidate;openVoice();return;}}friends();});}
    private void openVoice(){
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},77);return;}
        NativeClient c=core;SavedServer s=server;Channel target=channel;
        run(()->{c.installModels(this);return c.call("/voice/open",json("server",s.id,"channel",target.id));},r->{
            voice=new VoiceEngine(this,c,s.account,this::mediaStatus);video=new VideoEngine(this,c,this::mediaStatus);voice.start();video.start();voicePage();
        });
    }
    private void mediaStatus(String message){handler.post(()->{
        if(message.startsWith("Sharing screen"))screenConsent=false;
        if(message.equals("Screen share stopped")||message.startsWith("screen:")){screenConsent=false;getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);if(!foreground&&!external){stopMedia();lock();}}
        if(core!=null&&(page.equals("voice")||page.equals("video")))status.setText(message);
    });}
    private void voicePage(){screen("voice","Voice / "+(channel==null?"Call":channel.name));note("Encrypted server relay");talk=action("Hold OK to talk",()->{});talk.setOnTouchListener((v,e)->{if(voice==null)return false;if(e.getAction()==android.view.MotionEvent.ACTION_DOWN){voice.press(true);return true;}if(e.getAction()==android.view.MotionEvent.ACTION_UP||e.getAction()==android.view.MotionEvent.ACTION_CANCEL){voice.press(false);v.performClick();return true;}return true;});
        action("Camera / Screen / Receive",this::videoPage);action("Leave voice",()->{stopMedia();channels();});focus();}
    private void videoPage(){screen("video","Camera / Screen");
        KeypadEditText cameraRate=field("Camera kbps (50–1000)",Integer.toString(video.cameraRate),false),screenRate=field("Screen kbps (50–1000)",Integer.toString(video.screenRate),false);
        action("Apply bitrates",()->{try{video.configure(Integer.parseInt(cameraRate.getText().toString()),Integer.parseInt(screenRate.getText().toString()));}catch(Exception e){status.setText(e.getMessage());}});
        action(video.sharingCamera()?"Stop camera":"Start camera",()->{if(video.sharingCamera()){video.stopCamera();videoPage();}else if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.CAMERA},78);else video.camera();});
        action(video.sharingScreen()?"Stop screen share":"Share screen",()->{if(video.sharingScreen()){video.stopScreen();videoPage();}else{screenConsent=true;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),79);}});
        for(VideoEngine.Stream stream:video.streams())action("Receive "+stream.source+" / "+stream.author.substring(0,Math.min(12,stream.author.length())),()->receiveVideo(stream));
        action("Refresh stream list",this::videoPage);action("Back to voice",this::voicePage);focus();}
    private void receiveVideo(VideoEngine.Stream stream){screen("receiveVideo","Receive / "+stream.source);SurfaceView surface=new SurfaceView(this);content.addView(surface,new LinearLayout.LayoutParams(-1,180));surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder holder){video.receive(stream,holder.getSurface());}public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){}public void surfaceDestroyed(SurfaceHolder holder){if(video!=null)video.unreceive(stream.id);}});action("Stop receiving / Back",()->{video.unreceive(stream.id);videoPage();});focus();}
    private void stopMedia(){if(voice!=null){VoiceEngine old=voice;voice=null;old.close();worker.submit(old::awaitStopped);}if(video!=null){VideoEngine old=video;video=null;old.close();worker.submit(old::awaitStopped);}screenConsent=false;getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED){if(status!=null)status.setText("Permission denied");return;}if(request==77&&core!=null)openVoice();if(request==78&&video!=null)video.camera();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==100&&result==RESULT_OK&&data!=null){pendingImage=data.getData();}
        if(request==79){screenConsent=false;if(result==RESULT_OK&&data!=null&&video!=null){screenConsent=true;ProjectionService.begin(this,video,result,data);}else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    }
    private void help() {
        screen("help","VCH Flip 0.2.1");note("For Sonim XP3plus 5G / AOSP 14. No Google services needed.");
        note("Use the direction pad and OK. Left soft key opens communities; right soft key goes back.");keypadHelp();
        note("Current Rust VCH core: text/images, encrypted offline archives, profiles, friends/calls, Tor, voice, camera/screen video, roles and server/channel management.");
        note("The vault locks when you leave, except during an explicit image picker or active screen share. Closing the flip stops media and locks. Calls are polled while unlocked.");
        note("Hold the mapped PTT key or focus Hold to talk and hold OK. Release/focus loss stops transmission. Incoming video starts only after Receive.");
        note("GPL-3.0-only. Native Rust core; ONNX Runtime (MIT); Gson (Apache 2.0); IBM Plex Mono (OFL). Third-party notices are bundled.");
        action("Back",this::home);focus();
    }
    @Override public boolean onKeyDown(int code,KeyEvent event) {
        if(mapping){if(code==KeyEvent.KEYCODE_BACK){mapping=false;audio();return true;}if(event.getRepeatCount()==0&&code!=KeyEvent.KEYCODE_POWER&&code!=KeyEvent.KEYCODE_ENDCALL&&code!=KeyEvent.KEYCODE_CALL){mapping=false;getPreferences(0).edit().putInt("ptt",code).apply();audio();}return true;}
        boolean typing=getCurrentFocus() instanceof EditText;
        if(voice!=null&&!typing&&(code==getPreferences(0).getInt("ptt",138)||code==KeyEvent.KEYCODE_HEADSETHOOK||(code==KeyEvent.KEYCODE_DPAD_CENTER&&getCurrentFocus()==talk))){voice.press(true);return true;}
        if(code==KeyEvent.KEYCODE_SOFT_LEFT || code==KeyEvent.KEYCODE_MENU){if(!busy&&core!=null){draft="";stopMedia();home();}return true;}
        if(code==KeyEvent.KEYCODE_SOFT_RIGHT){onBackPressed();return true;}
        return super.onKeyDown(code,event);
    }
    @Override public boolean onKeyUp(int code,KeyEvent event){if(voice!=null&&(code==getPreferences(0).getInt("ptt",138)||code==KeyEvent.KEYCODE_HEADSETHOOK||code==KeyEvent.KEYCODE_DPAD_CENTER)){voice.press(false);return true;}return super.onKeyUp(code,event);}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus&&voice!=null)voice.press(false);}
    @SuppressWarnings("deprecation") @Override public void onBackPressed() {
        if(busy){status.setText("Working… please wait");return;}
        switch(page){
            case "locked": case "home": finish();break;
            case "channels": case "join": case "profile": case "help":case "audio":home();break;
            case "message":case "compose":draft="";thread();break;
            case "thread":draft="";attachments.clear();channel=null;channels();break;
            case "voice":case "video":stopMedia();channels();break;
            case "addFriend":friends();break;
            default:channels();
        }
    }
    private interface Operation {Object call()throws Exception;}
    private void run(Operation operation,java.util.function.Consumer<Object> done) {
        if(busy||!foreground)return;busy=true;status.setText("Working…");final int ticket=generation;
        worker.submit(()->{
            Object result=null;Exception failure=null;
            try{result=operation.call();}catch(Exception e){failure=e;}
            Object out=result;Exception error=failure;
            handler.post(()->{
                if(ticket!=generation||!foreground){if(out instanceof AutoCloseable)try{((AutoCloseable)out).close();}catch(Exception ignored){}if(out instanceof byte[])Arrays.fill((byte[])out,(byte)0);return;}
                busy=false;
                if(error!=null){status.setText(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());}
                else {status.setText("↑↓ Move · OK Select · Back");done.accept(out);}
            });
        });
    }
}
