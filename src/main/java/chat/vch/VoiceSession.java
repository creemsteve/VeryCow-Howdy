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
    public VoiceSession(ClientCore core,SavedServer server,Channel channel,Path models){this.core=core;this.server=server;this.channel=channel;this.models=models;}
    public JsonObject request(String path,JsonObject in)throws Exception{
        synchronized(this){if(closed)throw new IOException("Voice session ended");}
        switch(path){
            case "/settings":return Json.obj("account",core.account(server),"channel",channel.name,"input",core.state.microphone,"output",core.state.speakers,"inputGain",core.state.inputGain,"outputGain",core.state.outputGain,"ptt",core.state.pttKey,"ice",core.state.iceUrls);
            case "/signal":{in.addProperty("channel",channel.id);JsonObject out=core.request(server,"voice",in,true);core.acceptChannelKeys(server,out.getAsJsonObject("channelKeys"));synchronized(this){floor=Json.str(out,"floor");floorAt=System.currentTimeMillis();}return out;}
            case "/codec":return codec(in);
            case "/encode":{
                synchronized(this){if(!floor.equals(core.account(server))||System.currentTimeMillis()-floorAt>3000)throw new IOException("Push-to-talk floor unavailable");}
                in.addProperty("op","encode");in.addProperty("bitRate",channel.bitRate);JsonObject encoded=codec(in);
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
    private synchronized JsonObject codec(JsonObject in)throws Exception{if(codec==null)codec=new DacCodec(models);return codec.request(in);}
    public void close(){synchronized(this){closed=true;if(codec!=null)codec.close();}new Thread(()->{try{core.request(server,"voice",Json.obj("channel",channel.id,"action","leave"),true);}catch(Exception ignored){}},"vch-voice-leave").start();}
}
