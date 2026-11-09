package chat.vch.flip;

import android.content.Context;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Display-only Java facade. Authentication, keys, persistence and media live in Rust. */
public final class NativeClient implements AutoCloseable {
    static { System.loadLibrary("vch_android"); }
    public static final Gson GSON=new Gson();
    private static native long create(String directory,byte[] password,String models,String nativeDirectory)throws IOException;
    private static native String exchange(long handle,String path,String input);
    private static native void destroy(long handle);
    private static native void gate(long handle,boolean pressed);
    private long handle;
    public State state;
    public NativeClient(Context context,char[] password)throws Exception {
        byte[] bytes=utf(new String(password));Arrays.fill(password,'\0');
        Path models=context.getFilesDir().toPath().resolve("models");
        try {handle=create(context.getFilesDir().getAbsolutePath(),bytes,models.toString(),context.getApplicationInfo().nativeLibraryDir);refresh();}
        catch(Exception e){close();throw e;}finally{Arrays.fill(bytes,(byte)0);}
    }
    public synchronized JsonObject call(String path,JsonObject data)throws IOException {
        JsonObject out=JsonParser.parseString(exchange(handle,path,GSON.toJson(data))).getAsJsonObject();
        if(out.has("error"))throw new IOException(str(out,"error"));return out;
    }
    public void press(boolean down){gate(handle,down);}
    public synchronized void refresh()throws IOException {
        State next=GSON.fromJson(call("/state",json()),State.class);
        if(state!=null)for(int i=0;i<next.servers.size();i++)for(SavedServer old:state.servers)if(old.id.equals(next.servers.get(i).id)){old.copy(next.servers.get(i));next.servers.set(i,old);break;}
        state=next;
    }
    public boolean connected(SavedServer s){return s.connected;}
    public String account(SavedServer s){return s.account;}
    public synchronized void connect(SavedServer s,char[] secret)throws Exception {
        try {
            if(s.id.isEmpty()){
                JsonObject out=call("/join",json("address",s.address,"password",secret==null?"":new String(secret),"torOnly",s.torOnly,"torSocksPort",s.torSocksPort));
                refresh();for(SavedServer candidate:state.servers)if(candidate.id.equals(str(out,"server"))){s.copy(candidate);state.servers.set(state.servers.indexOf(candidate),s);break;}
            }else {JsonObject data=json("server",s.id);if(secret!=null)data.addProperty("password",new String(secret));call("/connect",data);refresh();}
        } finally {if(secret!=null)Arrays.fill(secret,'\0');}
    }
    public synchronized void disconnect(SavedServer s)throws IOException {call("/disconnect",json("server",s.id));refresh();}
    public synchronized boolean poll(SavedServer s)throws IOException {boolean changed=call("/poll",json("server",s.id)).get("changed").getAsBoolean();refresh();return changed;}
    public synchronized void save()throws IOException {call("/profile",json("name",state.name,"bio",state.bio,"pronouns",state.pronouns,"avatar",state.avatar));refresh();}
    public synchronized void updateProfile(SavedServer s)throws IOException {request(s,"profile",json("name",state.name,"bio",state.bio,"pronouns",state.pronouns,"avatar",state.avatar),true);}
    public synchronized JsonObject request(SavedServer s,String route,JsonObject data,boolean authenticated)throws IOException {return call("/request",json("server",s.id,"route",route,"data",data));}
    public synchronized void send(SavedServer s,Channel channel,String kind,byte[] data)throws IOException {call("/send",json("server",s.id,"channel",channel.id,"kind",kind,"data",b64(data)));refresh();}
    public synchronized byte[] decrypt(SavedServer s,Envelope envelope)throws IOException {return unb64(str(call("/decrypt",json("server",s.id,"message",envelope.id)),"data"));}
    public void installModels(Context context)throws IOException {
        Path directory=context.getFilesDir().toPath().resolve("models");Files.createDirectories(directory);
        for(String file:new String[]{"dac.json","dac-encoder.onnx","dac-decoder.onnx"}){
            Path target=directory.resolve(file);if(Files.exists(target))continue;
            Path temporary=directory.resolve(file+".tmp");
            try(InputStream in=context.getAssets().open(file)){Files.copy(in,temporary,StandardCopyOption.REPLACE_EXISTING);}
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
        }
    }
    @Override public synchronized void close(){if(handle!=0){destroy(handle);handle=0;}state=null;}
    public static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static String b64(byte[] bytes){return android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP);}
    public static byte[] unb64(String text){return android.util.Base64.decode(text,android.util.Base64.DEFAULT);}
    public static String str(JsonObject object,String key){return object.has(key)&&!object.get(key).isJsonNull()?object.get(key).getAsString():"";}
    public static JsonObject json(Object... fields){JsonObject out=new JsonObject();for(int i=0;i<fields.length;i+=2)out.add((String)fields[i],GSON.toJsonTree(fields[i+1]));return out;}
    public static final class State {
        public String name="New member",bio="",pronouns="",avatar="",pttKey="138",microphone="Default",speakers="Default";
        public boolean darkMode=true;
        public int inputGain=100,outputGain=100,outgoingBitRate=6000;
        public List<SavedServer> servers=new ArrayList<>();
    }
    public static final class SavedServer {
        public String id="",name="",address="",description="",image="",fingerprint="",account="";
        public boolean connected,admin,roleManager,torOnly;
        public int torSocksPort=9050;
        public List<Channel> channels=new ArrayList<>(),adminChannels=new ArrayList<>();
        public List<Envelope> messages=new ArrayList<>();public Map<String,Account> accounts=new LinkedHashMap<>();
        public List<Friendship> friendships=new ArrayList<>();public Set<String> friendRequests=new LinkedHashSet<>(),everyonePermissions=new LinkedHashSet<>();
        public List<Role> roles=new ArrayList<>();public List<Call> calls=new ArrayList<>();public Map<String,String> typing=new LinkedHashMap<>();
        void copy(SavedServer other){id=other.id;name=other.name;address=other.address;description=other.description;image=other.image;fingerprint=other.fingerprint;account=other.account;connected=other.connected;admin=other.admin;roleManager=other.roleManager;torOnly=other.torOnly;torSocksPort=other.torSocksPort;channels=other.channels;adminChannels=other.adminChannels;messages=other.messages;accounts=other.accounts;friendships=other.friendships;friendRequests=other.friendRequests;roles=other.roles;everyonePermissions=other.everyonePermissions;calls=other.calls;typing=other.typing;}
        @Override public String toString(){return name+(torOnly?" [Tor]":"");}
    }
    public static final class Channel {
        public String id=UUID.randomUUID().toString(),name="general",type="text";
        public int bitRate=6000,maxMembers=16,slowModeSeconds;
        public Set<String> allow=new LinkedHashSet<>();public Map<String,Map<String,Boolean>> roleOverrides=new LinkedHashMap<>();
    }
    public static final class Envelope {public String id,channel,author,kind;public long time;}
    public static final class Account {public String hash,name,bio,pronouns,avatar;}
    public static final class Friendship {public String first,second,textChannel,voiceChannel;}
    public static final class Role {public String id=UUID.randomUUID().toString(),name="New role";public Set<String> permissions=new LinkedHashSet<>(),members=new LinkedHashSet<>();}
    public static final class Call {public String id,from,to,channel;}
}
