package chat.vch.flip;

import android.content.Context;
import android.media.*;
import com.google.gson.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static chat.vch.flip.NativeClient.*;

/** Native PCM devices around the current Rust DAC/encrypted relay session. */
final class VoiceEngine implements AutoCloseable {
    private final NativeClient client;
    private final String account;
    private final Consumer<String> status;
    private final Context context;
    private final AtomicBoolean pressed=new AtomicBoolean(),closed=new AtomicBoolean();
    private volatile AudioRecord recorder;
    private AudioTrack playback;
    private Thread thread;
    VoiceEngine(Context context,NativeClient client,String account,Consumer<String> status){this.context=context;this.client=client;this.account=account;this.status=status;}
    void press(boolean down){pressed.set(down);client.press(down);if(!down){AudioRecord r=recorder;if(r!=null)try{r.stop();}catch(IllegalStateException ignored){}}}
    void start(){thread=new Thread(this::loop,"vch-android-voice");thread.setDaemon(true);thread.start();}
    void awaitStopped(){if(thread!=null)try{thread.join();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private void loop(){
        boolean wasPressed=false;
        try {
            if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Microphone permission denied");
            int inputGain=client.state.inputGain,outputGain=client.state.outputGain;
            int buffer=Math.max(12800,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT));
            AudioRecord record=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,buffer);recorder=record;
            if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone initialization failed");
            playback=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()).setBufferSizeInBytes(64000).setTransferMode(AudioTrack.MODE_STREAM).build();
            AudioManager audio=context.getSystemService(AudioManager.class);
            for(AudioDeviceInfo d:audio.getDevices(AudioManager.GET_DEVICES_INPUTS))if(client.state.microphone.equals("id:"+d.getId()))record.setPreferredDevice(d);
            for(AudioDeviceInfo d:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(client.state.speakers.equals("id:"+d.getId()))playback.setPreferredDevice(d);
            playback.play();short[] samples=new short[3200];
            while(!closed.get()){
                boolean down=pressed.get();String action=down?"claim":wasPressed?"release":"heartbeat";wasPressed=down;
                JsonObject signal=client.call("/media/signal",json("action",action));
                boolean transmitting=down&&pressed.get()&&account.equals(str(signal,"floor"));
                if(transmitting){
                    playback.pause();playback.flush();
                    if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)record.startRecording();
                    int n=record.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);
                    if(n<0){if(!pressed.get())continue;throw new IllegalStateException("Microphone read failed: "+n);}
                    if(pressed.get()&&!closed.get()&&n>0){
                        ByteBuffer bytes=ByteBuffer.allocate(n*4).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<n;i++)bytes.putFloat(Math.max(-1,Math.min(1,samples[i]/32768f*inputGain/100f)));
                        JsonObject envelope=client.call("/media/encode",json("data",b64(bytes.array())));
                        if(pressed.get()&&!closed.get())try{client.call("/media/media",json("envelope",envelope));}catch(java.io.IOException e){if(pressed.get())throw e;}
                    }
                }else{
                    if(record.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)record.stop();
                    playback.play();JsonArray frames=signal.getAsJsonArray("frames");
                    if(frames!=null)for(JsonElement frame:frames){JsonObject e=frame.getAsJsonObject();JsonObject decoded=client.call("/media/decode",json("author",str(e,"author"),"envelope",e));
                        byte[] pcm=unb64(str(decoded,"data"));FloatBuffer floats=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] values=new float[floats.remaining()];floats.get(values);for(int i=0;i<values.length;i++)values[i]=Math.max(-1,Math.min(1,values[i]*outputGain/100f));if(!closed.get())playback.write(values,0,values.length,AudioTrack.WRITE_NON_BLOCKING);
                    }
                    Thread.sleep(150);
                }
                int peers=signal.has("peers")?signal.getAsJsonArray("peers").size():0;status.accept((transmitting?"Microphone live":"Ready — hold to talk")+" · "+peers+" peer(s)");
            }
        }catch(Exception error){if(!closed.get())status.accept("Voice: "+error.getMessage());}
        finally {
            press(false);AudioRecord r=recorder;recorder=null;if(r!=null){try{r.stop();}catch(IllegalStateException ignored){}r.release();}
            if(playback!=null){try{playback.stop();}catch(IllegalStateException ignored){}playback.release();playback=null;}
            try{client.call("/voice/close",json());}catch(Exception ignored){}
        }
    }
    @Override public void close(){closed.set(true);press(false);}
}
