package chat.vch.mobile;

import android.content.*;
import android.hardware.camera2.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.view.Surface;
import android.util.Range;
import chat.vch.*;
import com.google.gson.JsonObject;
import java.nio.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera2 / MediaProjection surfaces feed a device hardware AVC encoder directly. */
final class VideoCapture implements AutoCloseable {
    private final Context context;
    private final VoiceSession voice;
    private final BiConsumer<String,String> stopped;
    private final HandlerThread thread = new HandlerThread("vch-video");
    private final Handler handler;
    private final Map<String, Stream> streams = new HashMap<>();
    private final ThreadPoolExecutor network=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(3),r->{Thread t=new Thread(r,"vch-video-relay");t.setDaemon(true);return t;});
    private final AtomicBoolean heartbeatBusy=new AtomicBoolean();
    private volatile boolean closed, sharingScreen, background;
    private record Charge(long time, int bytes) {}
    private static final class Stream {
        String source, id; volatile int kbps; int width, height; volatile boolean sending, stopped;
        MediaCodec encoder; Surface surface; CameraDevice camera; CameraCaptureSession cameraSession;
        MediaProjection projection; VirtualDisplay display; byte[] config = new byte[0];
        final Deque<Charge> charges = new ArrayDeque<>();
    }
    VideoCapture(Context context, VoiceSession voice, BiConsumer<String,String> stopped) {
        this.context=context;this.voice=voice;this.stopped=stopped;thread.start();handler=new Handler(thread.getLooper());
        handler.postDelayed(new Runnable(){public void run(){
            if(closed)return;
            // A foreground screen share may continue while another app is visible. WebView timers can be suspended.
            if(background&&sharingScreen&&heartbeatBusy.compareAndSet(false,true))try{network.execute(()->{
                try{if(!closed){voice.request("/signal",Json.obj("action","heartbeat"));voice.request("/video/poll",Json.obj());}}
                catch(Exception e){handler.post(()->stopNow("screen",e.getMessage()));}finally{heartbeatBusy.set(false);}
            });}catch(RejectedExecutionException busy){heartbeatBusy.set(false);}
            handler.postDelayed(this,1000);
        }},1000);
    }
    boolean sharingScreen(){return sharingScreen;}
    void background(boolean value){background=value;}
    void camera(int kbps){handler.post(()->{try{
        if(closed)return;
        CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        String chosen=null;for(String id:manager.getCameraIdList()){if(chosen==null)chosen=id;if(Objects.equals(manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_FRONT)){chosen=id;break;}}
        if(chosen==null)throw new IllegalStateException("No webcam available");
        android.util.Size[] sizes=manager.getCameraCharacteristics(chosen).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(MediaCodec.class);
        android.util.Size size=Arrays.stream(sizes).filter(s->s.getWidth()<=1280&&s.getHeight()<=720).min(Comparator.comparingLong(s->Math.abs((long)s.getWidth()*s.getHeight()-640L*480))).orElseThrow(()->new IllegalStateException("No supported camera size"));
        Stream s=create("camera",kbps,size.getWidth(),size.getHeight());
        Range<Integer>[] ranges=manager.getCameraCharacteristics(chosen).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> fps=ranges==null?null:Arrays.stream(ranges).min(Comparator.comparingInt(r->Math.abs(r.getUpper()-8))).orElse(null);
        if(context.checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Camera permission denied");
        manager.openCamera(chosen,new CameraDevice.StateCallback(){
            public void onOpened(CameraDevice camera){if(closed||streams.get("camera")!=s){camera.close();return;}s.camera=camera;
                try{camera.createCaptureSession(List.of(s.surface),new CameraCaptureSession.StateCallback(){
                    public void onConfigured(CameraCaptureSession session){if(closed||streams.get("camera")!=s){session.close();return;}s.cameraSession=session;
                        try{CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);request.addTarget(s.surface);if(fps!=null)request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps);session.setRepeatingRequest(request.build(),null,handler);}catch(Exception e){stopNow("camera",e.getMessage());}}
                    public void onConfigureFailed(CameraCaptureSession session){stopNow("camera","Camera capture configuration failed");}
                },handler);}catch(Exception e){stopNow("camera",e.getMessage());}}
            public void onDisconnected(CameraDevice camera){camera.close();if(streams.get("camera")==s)stopNow("camera","Camera disconnected");}
            public void onError(CameraDevice camera,int error){camera.close();if(streams.get("camera")==s)stopNow("camera","Camera unavailable ("+error+")");}
        },handler);
    }catch(Exception e){stopNow("camera",e.getMessage());}});}
    void screen(int result,Intent data,int kbps){handler.post(()->{try{
        if(closed)return;
        Stream s=create("screen",kbps,960,540);
        MediaProjectionManager manager=(MediaProjectionManager)context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        s.projection=manager.getMediaProjection(result,data);
        s.projection.registerCallback(new MediaProjection.Callback(){public void onStop(){if(streams.get("screen")==s)stopNow("screen","");}},handler);
        s.display=s.projection.createVirtualDisplay("VCH screen share",s.width,s.height,context.getResources().getDisplayMetrics().densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,s.surface,null,handler);
        sharingScreen=true;
    }catch(Exception e){stopNow("screen",e.getMessage());}});}
    private Stream create(String source,int kbps,int width,int height)throws Exception{
        if(closed)throw new IllegalStateException("Call ended");
        if(streams.containsKey(source))throw new IllegalStateException("Stream already active");
        Stream s=new Stream();s.source=source;s.kbps=kbps;s.width=width;s.height=height;streams.put(source,s);
        MediaFormat format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE,kbps*750);format.setInteger(MediaFormat.KEY_FRAME_RATE,8);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,0);
        format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        format.setInteger(MediaFormat.KEY_LEVEL,MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,8);
        String encoder=null;
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(info.isEncoder()&&info.isHardwareAccelerated()&&Arrays.asList(info.getSupportedTypes()).contains(MediaFormat.MIMETYPE_VIDEO_AVC)
                &&info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format)){encoder=info.getName();break;}
        }
        if(encoder==null)throw new IllegalStateException("No hardware AVC encoder supports this capture size");
        s.id=Json.str(voice.request("/video/start",Json.obj("source",source,"kbps",kbps)),"stream");
        if(closed)throw new IllegalStateException("Call ended");
        s.encoder=MediaCodec.createByCodecName(encoder);
        s.encoder.setCallback(new MediaCodec.Callback(){
            public void onInputBufferAvailable(MediaCodec codec,int index){}
            public void onError(MediaCodec codec,MediaCodec.CodecException e){if(streams.get(source)==s)stopNow(source,e.getMessage());}
            public void onOutputFormatChanged(MediaCodec codec,MediaFormat out){
                java.io.ByteArrayOutputStream config=new java.io.ByteArrayOutputStream();
                for(String name:List.of("csd-0","csd-1")){ByteBuffer b=out.getByteBuffer(name);if(b!=null){byte[] bytes=new byte[b.remaining()];b.duplicate().get(bytes);config.write(bytes,0,bytes.length);}}
                s.config=config.toByteArray();
            }
            public void onOutputBufferAvailable(MediaCodec codec,int index,MediaCodec.BufferInfo info){
                byte[] bytes=null;
                try{if(streams.get(source)==s&&(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                    ByteBuffer buffer=codec.getOutputBuffer(index);if(buffer!=null){buffer.position(info.offset);buffer.limit(info.offset+info.size);bytes=new byte[info.size];buffer.get(bytes);}
                }}finally{codec.releaseOutputBuffer(index,false);}
                if(bytes!=null)send(s,bytes,info.presentationTimeUs);
            }
        },handler);
        s.encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);s.surface=s.encoder.createInputSurface();s.encoder.start();return s;
    }
    private void send(Stream s,byte[] bytes,long timestamp){
        if(s.sending||s.stopped||closed)return;
        try{
            byte[] header=Json.bytes(Json.obj("codec","avc1.42001f","width",s.width,"height",s.height,"timestamp",timestamp));
            byte[] data=ByteBuffer.allocate(4+header.length+s.config.length+bytes.length).putInt(header.length).put(header).put(s.config).put(bytes).array();
            long now=SystemClock.elapsedRealtime();while(!s.charges.isEmpty()&&now-s.charges.peekFirst().time>=1100)s.charges.removeFirst();
            int cost=data.length+256;if(cost+s.charges.stream().mapToInt(Charge::bytes).sum()>s.kbps*125)return;
            s.charges.addLast(new Charge(now,cost));
            s.sending=true;try{network.execute(()->{try{
                if(s.stopped||closed)return;
                JsonObject envelope=voice.request("/video/encode",Json.obj("stream",s.id,"data",Crypto.b64(data)));
                if(!closed&&!s.stopped)voice.request("/video/media",Json.obj("stream",s.id,"envelope",envelope));
            }catch(ClientCore.ApiException e){if(e.status!=429)handler.post(()->{if(streams.get(s.source)==s)stopNow(s.source,e.getMessage());});}
            catch(Exception e){handler.post(()->{if(streams.get(s.source)==s)stopNow(s.source,e.getMessage());});}
            finally{s.sending=false;}});}catch(RejectedExecutionException busy){s.sending=false;}
        }
        catch(Exception e){stopNow(s.source,e.getMessage());}
    }
    void configure(String source,int kbps){handler.post(()->{Stream s=streams.get(source);if(s==null)return;try{
        network.execute(()->{try{
            if(s.stopped||closed)return;voice.request("/video/configure",Json.obj("stream",s.id,"kbps",kbps));
            handler.post(()->{if(streams.get(source)!=s)return;try{s.kbps=kbps;Bundle settings=new Bundle();settings.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,kbps*750);s.encoder.setParameters(settings);}catch(Exception e){stopNow(source,e.getMessage());}});
        }catch(Exception e){handler.post(()->{if(streams.get(source)==s)stopNow(source,e.getMessage());});}});
    }catch(Exception e){stopNow(source,e.getMessage());}});}
    void stop(String source){handler.post(()->stopNow(source,""));}
    private void stopNow(String source,String error){
        Stream s=streams.remove(source);
        if(s==null&&"".equals(error))return;
        if(source.equals("screen")){sharingScreen=false;context.stopService(new Intent(context,VideoCaptureService.class));}
        if(s!=null){
            s.stopped=true;
            if(s.cameraSession!=null)s.cameraSession.close();if(s.camera!=null)s.camera.close();
            if(s.display!=null)s.display.release();if(s.projection!=null)s.projection.stop();
            if(s.encoder!=null){try{s.encoder.stop();}catch(Exception ignored){}s.encoder.release();}if(s.surface!=null)s.surface.release();
            if(s.id!=null)try{network.execute(()->{try{voice.request("/video/stop",Json.obj("stream",s.id));}catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}
        }
        stopped.accept(source,error==null?"Video capture failed":error);
    }
    public void close(){closed=true;handler.post(()->{for(String source:new ArrayList<>(streams.keySet()))stopNow(source,"");network.shutdown();thread.quitSafely();});}
}
