package chat.vch;

import chat.vch.Models.*;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;

/** Shared desktop/mobile voice control, signatures, replay protection and in-memory codec. */
public final class VoiceSession implements AutoCloseable {
    private final ClientCore core;
    private final SavedServer server;
    private final Channel channel;
    private final Path models;
    private final Set<String> received=new LinkedHashSet<>();
    private DacCodec codec;
    private String floor="";
    private long floorAt;
    private boolean closed;
    private final Object relay=new Object();
    private long cursor=-1;
    private final java.util.concurrent.CompletableFuture<Void> stopped=new java.util.concurrent.CompletableFuture<>();
    public VoiceSession(ClientCore core,SavedServer server,Channel channel,Path models){
        this.core=core;this.server=server;this.channel=channel;this.models=models;
    }
    public JsonObject request(String path,JsonObject in)throws Exception{
        synchronized(this){if(closed)throw new IOException("Voice session ended");}
        if(path.startsWith("/video/")) return video(path.substring(7), in);
        switch(path){
            case "/settings":return Json.obj("account",core.account(server),"channel",channel.name,"input",core.state.microphone,"output",core.state.speakers,"inputGain",core.state.inputGain,"outputGain",core.state.outputGain,"ptt",core.state.pttKey);
            case "/signal":{synchronized(relay){synchronized(this){if(closed)throw new IOException("Voice session ended");}in.addProperty("channel",channel.id);in.addProperty("after",cursor);JsonObject out=core.request(server,"voice",in,true);
                if(!"leave".equals(Json.str(in,"action"))){
                    if(!"https-relay-v1".equals(Json.str(out,"transport")))throw new IOException("Update the server for server-relayed voice");
                    core.acceptChannelKeys(server,out.getAsJsonObject("channelKeys"));cursor=Json.num(out,"cursor",cursor);
                    synchronized(this){floor=Json.str(out,"floor");floorAt=System.currentTimeMillis();}
                }return out;}}
            case "/media":{synchronized(relay){synchronized(this){if(closed)throw new IOException("Voice session ended");}in.addProperty("channel",channel.id);in.addProperty("action","media");return core.request(server,"voice",in,true);}}
            case "/codec":return codec(in);
            case "/encode":{
                synchronized(this){if(!floor.equals(core.account(server))||System.currentTimeMillis()-floorAt>3000)throw new IOException("Push-to-talk floor unavailable");}
                in.addProperty("op","encode");in.addProperty("bitRate",Math.min(channel.bitRate,Math.max(500,core.state.outgoingBitRate)));JsonObject encoded=codec(in);
                Envelope e=core.envelope(server,channel.id,"voice",Crypto.unb64(Json.str(encoded,"data")));return Json.GSON.toJsonTree(e).getAsJsonObject();
            }
            case "/decode":{
                Envelope e=Json.GSON.fromJson(in.get("envelope"),Envelope.class);
                if(e.keyRevision>server.keyRevisions.getOrDefault(channel.id,0L))core.refreshChannelKeys(server);
                if(e.keyRevision<1||e.keyRevision!=server.keyRevisions.getOrDefault(channel.id,0L))throw new IOException("Voice encryption changed; waiting for the current channel key");
                synchronized(this){if(!"voice".equals(e.kind)||!channel.id.equals(e.channel)||!e.author.equals(Json.str(in,"author"))||!e.author.equals(floor)||Math.abs(System.currentTimeMillis()-e.time)>12000||!received.add(e.id))throw new IOException("Invalid or replayed voice frame");if(received.size()>1024)received.remove(received.iterator().next());}
                return codec(Json.obj("op","decode","data",Crypto.b64(core.decrypt(server,e))));
            }
            default:throw new IOException("Unknown voice endpoint");
        }
    }
    private JsonObject video(String action, JsonObject in) throws Exception {
        if(action.equals("encode")) {
            byte[] payload=Crypto.unb64(Json.str(in,"data"));
            if(payload.length>125000)throw new IOException("Video frame too large");
            Envelope e=core.envelope(server,channel.id,"video/"+Json.str(in,"stream"),payload);
            return Json.GSON.toJsonTree(e).getAsJsonObject();
        }
        if(action.equals("decode")) {
            Envelope e=Json.GSON.fromJson(in.get("envelope"),Envelope.class);
            if(e==null||!("video/"+Json.str(in,"stream")).equals(e.kind)||!channel.id.equals(e.channel)
                ||!Json.str(in,"author").equals(e.author)||e.time<System.currentTimeMillis()-12000||e.time>System.currentTimeMillis()+12000)throw new IOException("Invalid video frame");
            if(e.keyRevision>server.keyRevisions.getOrDefault(channel.id,0L))core.refreshChannelKeys(server);
            if(e.keyRevision<1||e.keyRevision!=server.keyRevisions.getOrDefault(channel.id,0L))throw new IOException("Video encryption changed");
            synchronized(this){if(!received.add(e.id))throw new IOException("Replayed video frame");if(received.size()>1024)received.remove(received.iterator().next());}
            return Json.obj("data",Crypto.b64(core.decrypt(server,e)));
        }
        if(!Set.of("start","stop","configure","poll","media").contains(action))throw new IOException("Unknown video action");
        in.addProperty("channel",channel.id);in.addProperty("action",action);
        return core.request(server,"video",in,true);
    }
    private synchronized JsonObject codec(JsonObject in)throws Exception{if(closed)throw new IOException("Voice session ended");if(codec==null)codec=new DacCodec(models);return codec.request(in);}
    public java.util.concurrent.CompletableFuture<Void> whenClosed(){return stopped;}
    public void close(){synchronized(this){if(closed)return;closed=true;if(codec!=null)codec.close();}Thread leave=new Thread(()->{try{synchronized(relay){core.request(server,"voice",Json.obj("channel",channel.id,"action","leave"),true);}}catch(Exception ignored){}finally{stopped.complete(null);}},"vch-voice-leave");leave.setDaemon(true);leave.start();}
}
