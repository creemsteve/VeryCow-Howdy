package chat.vch.flip;

import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.view.Surface;
import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static chat.vch.flip.NativeClient.*;

/** Native AVC surfaces and opt-in decoders using the current encrypted video relay. */
final class VideoEngine implements AutoCloseable {
    static final class Stream {String id,author,source;int kbps;}
    private final Context context;
    private final NativeClient client;
    private final Consumer<String> status;
    private final ScheduledExecutorService network=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,Decoder> incoming=new ConcurrentHashMap<>();
    private volatile List<Stream> listings=Collections.emptyList();
    private volatile boolean closed;
    private volatile Encoder camera,screen;
    int cameraRate=500,screenRate=500;
    private long cursor=-1;
    private final List<Thread> captures=new CopyOnWriteArrayList<>();
    VideoEngine(Context context,NativeClient client,Consumer<String> status){this.context=context.getApplicationContext();this.client=client;this.status=status;}
    void start(){network.scheduleWithFixedDelay(this::poll,0,250,TimeUnit.MILLISECONDS);}
    List<Stream> streams(){return new ArrayList<>(listings);}
    boolean sharingCamera(){Encoder e=camera;return e!=null&&!e.stopped;}
    boolean sharingScreen(){Encoder e=screen;return e!=null&&!e.stopped;}
    synchronized void camera(){
        if(closed||sharingCamera())return;
        if(context.checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){status.accept("Camera permission required");return;}
        camera=new Encoder("camera",320,240,cameraRate);camera.start(null,null);
    }
    synchronized void screen(ProjectionService service,int result,Intent consent){
        if(closed||sharingScreen()){service.stopSelf();return;}
        screen=new Encoder("screen",240,320,screenRate);
        screen.service=service;
        screen.start(result,consent);
    }
    synchronized void stopCamera(){Encoder old=camera;camera=null;if(old!=null)old.close();}
    synchronized void stopScreen(){Encoder old=screen;screen=null;if(old!=null)old.close();}
    void configure(int cameraKbps,int screenKbps){
        if(cameraKbps<50||cameraKbps>1000||screenKbps<50||screenKbps>1000)throw new IllegalArgumentException("Each stream must be 50–1000 kbps");
        cameraRate=cameraKbps;screenRate=screenKbps;
        for(Encoder e:new Encoder[]{camera,screen})if(e!=null)e.kbps=e.source.equals("camera")?cameraKbps:screenKbps;
    }
    void receive(Stream stream,Surface surface){unreceive(stream.id);if(!closed)incoming.put(stream.id,new Decoder(stream,surface));}
    void unreceive(String id){Decoder old=incoming.remove(id);if(old!=null)old.close();}
    private void poll(){
        if(closed)return;
        try {
            JsonObject out=client.call("/media/video/poll",json("after",cursor,"receive",new ArrayList<>(incoming.keySet())));
            cursor=out.get("cursor").getAsLong();List<Stream> next=new ArrayList<>();
            String own=str(client.call("/media/settings",json()),"account");
            for(JsonElement item:out.getAsJsonArray("streams")){Stream s=GSON.fromJson(item,Stream.class);if(!s.author.equals(own))next.add(s);}
            listings=next;Set<String> active=new HashSet<>();for(Stream s:next)active.add(s.id);for(String id:incoming.keySet())if(!active.contains(id))unreceive(id);
            for(JsonElement item:out.getAsJsonArray("frames")){
                JsonObject frame=item.getAsJsonObject();String id=str(frame,"stream");Decoder decoder=incoming.get(id);if(decoder==null)continue;
                try {JsonObject plain=client.call("/media/video/decode",json("stream",id,"author",decoder.stream.author,"envelope",frame.get("envelope")));decoder.render(unb64(str(plain,"data")));}
                catch(Exception error){status.accept("Video receive: "+error.getMessage());unreceive(id);}
            }
        }catch(Exception error){if(!closed)status.accept("Video: "+error.getMessage());}
    }
    @Override public void close(){closed=true;stopCamera();stopScreen();for(String id:incoming.keySet())unreceive(id);network.shutdown();}
    void awaitStopped(){try{for(Thread t:captures)t.join();while(!network.awaitTermination(1,TimeUnit.SECONDS)){} }catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private final class Encoder implements AutoCloseable {
        final String source;final int width,height;
        volatile int kbps;volatile boolean stopped;
        private MediaCodec codec;
        private Surface input;
        private HandlerThread cameraThread;
        private CameraDevice device;
        private CameraCaptureSession session;
        private MediaProjection projection;
        private VirtualDisplay display;
        private ProjectionService service;
        private String stream="";
        private byte[] config=new byte[0];
        private int applied;
        Encoder(String source,int width,int height,int kbps){this.source=source;this.width=width;this.height=height;this.kbps=kbps;}
        void start(Integer result,Intent consent){Thread thread=new Thread(()->encode(result,consent),"vch-"+source);thread.setDaemon(true);captures.add(thread);thread.start();}
        private void encode(Integer result,Intent consent){
            try {
                MediaFormat format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE,kbps*600);format.setInteger(MediaFormat.KEY_FRAME_RATE,8);format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,0);
                format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                format.setInteger(MediaFormat.KEY_LEVEL,MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);input=codec.createInputSurface();codec.start();
                cameraThread=new HandlerThread("vch-capture-callbacks");cameraThread.start();Handler handler=new Handler(cameraThread.getLooper());
                if(source.equals("camera"))openCamera(handler);else{
                    projection=service.getSystemService(MediaProjectionManager.class).getMediaProjection(result,consent);
                    projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){stopped=true;status.accept("Screen share stopped");}},handler);
                    display=projection.createVirtualDisplay("VCH Screen",width,height,context.getResources().getDisplayMetrics().densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,input,null,handler);
                }
                if(stopped||closed)return;
                stream=str(client.call("/media/video/start",json("source",source,"kbps",kbps)),"stream");applied=kbps;
                status.accept("Sharing "+source+" · "+codec.getName());
                MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long lastFrame=0,window=System.nanoTime();int charged=0;
                while(!stopped&&!closed){
                    Bundle request=new Bundle();request.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);
                    if(applied!=kbps){client.call("/media/video/configure",json("stream",stream,"kbps",kbps));request.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,kbps*600);applied=kbps;}
                    codec.setParameters(request);
                    int index=codec.dequeueOutputBuffer(info,100000);
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){ByteArrayOutputStream bytes=new ByteArrayOutputStream();MediaFormat f=codec.getOutputFormat();for(String key:new String[]{"csd-0","csd-1"})if(f.containsKey(key)){ByteBuffer b=f.getByteBuffer(key).duplicate();byte[] v=new byte[b.remaining()];b.get(v);bytes.write(v);}config=bytes.toByteArray();continue;}
                    if(index<0)continue;
                    ByteBuffer output=codec.getOutputBuffer(index);byte[] bytes=new byte[info.size];if(output!=null){output.position(info.offset);output.limit(info.offset+info.size);output.get(bytes);}codec.releaseOutputBuffer(index,false);
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0){config=bytes;continue;}
                    long now=System.nanoTime();if((info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)==0||now-lastFrame<125_000_000L)continue;
                    lastFrame=now;byte[] header=utf(GSON.toJson(json("codec","avc1.42001f","width",width,"height",height,"timestamp",info.presentationTimeUs)));
                    ByteBuffer packet=ByteBuffer.allocate(4+header.length+config.length+bytes.length).order(ByteOrder.BIG_ENDIAN);packet.putInt(header.length).put(header).put(config).put(bytes);byte[] payload=packet.array();
                    if(now-window>1_100_000_000L){window=now;charged=0;}
                    int cost=payload.length+256;if(payload.length>125000||charged+cost>kbps*125)continue;charged+=cost;
                    JsonObject envelope=client.call("/media/video/encode",json("stream",stream,"data",b64(payload)));
                    if(!stopped&&!closed)client.call("/media/video/media",json("stream",stream,"envelope",envelope));
                }
            }catch(Exception error){if(!stopped&&!closed)status.accept(source+": "+error.getMessage());}
            finally {stopped=true;cleanup();}
        }
        @android.annotation.SuppressLint("MissingPermission")
        private void openCamera(Handler handler)throws Exception {
            CameraManager manager=context.getSystemService(CameraManager.class);String[] ids=manager.getCameraIdList();if(ids.length==0)throw new IOException("This model has no camera");
            manager.openCamera(ids[0],new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice camera){if(stopped||closed){camera.close();return;}device=camera;
                    try {camera.createCaptureSession(Collections.singletonList(input),new CameraCaptureSession.StateCallback(){
                        @Override public void onConfigured(CameraCaptureSession capture){if(stopped||closed){capture.close();return;}session=capture;try{CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);request.addTarget(input);request.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);capture.setRepeatingRequest(request.build(),null,handler);}catch(Exception e){stopped=true;status.accept(e.getMessage());}}
                        @Override public void onConfigureFailed(CameraCaptureSession capture){stopped=true;status.accept("Camera configuration failed");}
                    },handler);}catch(Exception e){stopped=true;status.accept(e.getMessage());}
                }
                @Override public void onDisconnected(CameraDevice camera){camera.close();stopped=true;}
                @Override public void onError(CameraDevice camera,int error){camera.close();stopped=true;status.accept("Camera error: "+error);}
            },handler);
        }
        private void cleanup(){
            if(session!=null)session.close();if(device!=null)device.close();if(display!=null)display.release();if(projection!=null)projection.stop();
            if(codec!=null){try{codec.stop();}catch(IllegalStateException ignored){}codec.release();}if(input!=null)input.release();if(cameraThread!=null)cameraThread.quitSafely();
            if(!stream.isEmpty())try{client.call("/media/video/stop",json("stream",stream));}catch(Exception ignored){}
            if(service!=null)service.stopSelf();
            if(source.equals("screen"))status.accept("Screen share stopped");
        }
        @Override public void close(){stopped=true;}
    }
    private static final class Decoder implements AutoCloseable {
        final Stream stream;private final Surface surface;private MediaCodec codec;private String current="";
        Decoder(Stream stream,Surface surface){this.stream=stream;this.surface=surface;}
        synchronized void render(byte[] packet)throws Exception {
            if(packet.length<5||packet.length>125000)throw new IOException("Invalid video packet");ByteBuffer buffer=ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);int length=buffer.getInt();if(length<2||length>1024||length>=buffer.remaining())throw new IOException("Invalid video header");
            byte[] header=new byte[length];buffer.get(header);JsonObject h=JsonParser.parseString(new String(header,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            String type=str(h,"codec");int width=h.get("width").getAsInt(),height=h.get("height").getAsInt();long timestamp=h.get("timestamp").getAsLong();
            if(!(type.equals("avc1.42001f")||type.equals("vp8"))||width<2||height<2||width>1920||height>1080||timestamp<0)throw new IOException("Invalid video dimensions or codec");
            String signature=type+":"+width+":"+height;
            if(!signature.equals(current)){close();codec=MediaCodec.createDecoderByType(type.equals("vp8")?MediaFormat.MIMETYPE_VIDEO_VP8:MediaFormat.MIMETYPE_VIDEO_AVC);MediaFormat format=MediaFormat.createVideoFormat(type.equals("vp8")?MediaFormat.MIMETYPE_VIDEO_VP8:MediaFormat.MIMETYPE_VIDEO_AVC,width,height);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,125000);codec.configure(format,surface,null,0);codec.start();current=signature;}
            int input=codec.dequeueInputBuffer(0);if(input>=0){ByteBuffer target=codec.getInputBuffer(input);if(target==null||target.remaining()<buffer.remaining())throw new IOException("Video input too large");int count=buffer.remaining();target.put(buffer);codec.queueInputBuffer(input,0,count,timestamp,MediaCodec.BUFFER_FLAG_KEY_FRAME);}
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();for(int i=0;i<4;i++){int output=codec.dequeueOutputBuffer(info,0);if(output<0)break;codec.releaseOutputBuffer(output,true);}
        }
        @Override public synchronized void close(){if(codec!=null){try{codec.stop();}catch(IllegalStateException ignored){}codec.release();codec=null;}current="";}
    }
}
