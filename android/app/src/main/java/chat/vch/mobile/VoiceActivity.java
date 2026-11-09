package chat.vch.mobile;

import android.app.*;
import android.os.*;
import android.webkit.*;
import android.view.*;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import chat.vch.*;
import java.nio.file.*;
import java.io.*;

public final class VoiceActivity extends Activity {
    private WebView web;
    private VoiceSession voice;
    private VideoCapture video;
    private boolean capturePrompt, background;
    private int pendingScreenKbps=500,pendingCameraKbps=500;
    private final BroadcastReceiver screenOff=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){finish();}};
    protected void onCreate(Bundle b){
        super.onCreate(b);getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
        if(AppSession.core==null){finish();return;}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);else open();
    }
    public void onRequestPermissionsResult(int request,String[] permissions,int[] granted){
        super.onRequestPermissionsResult(request,permissions,granted);
        if(request==2){capturePrompt=false;if(granted.length>0&&granted[0]==PackageManager.PERMISSION_GRANTED&&video!=null)video.camera(pendingCameraKbps);else nativeStopped("camera","Camera permission denied");return;}
        if(granted.length>0&&granted[0]==PackageManager.PERMISSION_GRANTED)open();else finish();
    }
    private void nativeStopped(String source,String error){runOnUiThread(()->{
        if(web!=null)web.evaluateJavascript("window.VchVideo?.nativeStopped("+Json.GSON.toJson(source)+","+Json.GSON.toJson(error)+")",null);
        if(source.equals("screen")&&background&&!capturePrompt)finish();
    });}
    private void open(){try{
        Path models=getFilesDir().toPath().resolve("models");Files.createDirectories(models);
        for(String f:new String[]{"dac.json","dac-encoder.onnx","dac-decoder.onnx"})if(!Files.exists(models.resolve(f)))try(InputStream in=getAssets().open(f)){Files.copy(in,models.resolve(f));}
        voice=new VoiceSession(AppSession.core,AppSession.server,AppSession.channel,models);video=new VideoCapture(this,voice,this::nativeStopped);
        web=new WebView(this);web.setBackgroundColor(0xfff4f4f4);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(false);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(),"VchNative");
        web.setWebViewClient(new WebViewClient(){
            public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                String path=request.getUrl().getPath();
                if("https://vch.local".equals(request.getUrl().getScheme()+"://"+request.getUrl().getHost())&&("/video.js".equals(path)||"/fonts/IBMPlexMono-Regular.ttf".equals(path)))try{
                    return new WebResourceResponse(path.endsWith(".js")?"text/javascript":"font/ttf",path.endsWith(".js")?"UTF-8":null,getAssets().open(path.substring(1)));
                }catch(IOException ignored){}
                return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
            public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
        });
        web.setWebChromeClient(new WebChromeClient(){public void onPermissionRequest(PermissionRequest request){runOnUiThread(()->{
            if(request.getOrigin().toString().equals("https://vch.local/")&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});else request.deny();
        });}});
        byte[] html;try(InputStream in=getAssets().open("voice.html")){html=Io.read(in,100000);}
        setContentView(web);web.loadDataWithBaseURL("https://vch.local/",new String(html,java.nio.charset.StandardCharsets.UTF_8),"text/html","UTF-8",null);
    }catch(Exception e){new AlertDialog.Builder(this).setTitle("Voice unavailable").setMessage(e.getMessage()).setPositiveButton("Close",(d,w)->finish()).show();}}
    public final class Bridge {
        @JavascriptInterface public String request(String path,String json){try{
            if(json.length()>250000)throw new IOException("Voice request too large");return Json.GSON.toJson(voice.request(path,Json.parse(Crypto.utf(json))));
        }catch(Exception e){return Json.GSON.toJson(Json.obj("error",e.getMessage()));}}
        @JavascriptInterface public String startVideo(String source,int kbps){
            if(video==null||kbps<50||kbps>1000||!(source.equals("screen")||source.equals("camera")))return Json.GSON.toJson(Json.obj("error","Invalid video settings"));
            runOnUiThread(()->{
                if(source.equals("camera")){
                    pendingCameraKbps=kbps;
                    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){capturePrompt=true;requestPermissions(new String[]{Manifest.permission.CAMERA},2);}else video.camera(kbps);
                }else{capturePrompt=true;pendingScreenKbps=kbps;startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),3);}
            });return "{}";
        }
        @JavascriptInterface public void stopVideo(String source){if(video!=null)video.stop(source);}
        @JavascriptInterface public String configureVideo(String source,int kbps){if(kbps<50||kbps>1000)return Json.GSON.toJson(Json.obj("error","Choose 50-1000 kbps"));if(video!=null)video.configure(source,kbps);return "{}";}
    }
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=3)return;capturePrompt=false;
        if(result!=RESULT_OK||data==null||video==null){nativeStopped("screen","Screen sharing permission denied");return;}
        VideoCaptureService.capture=video;
        startForegroundService(new Intent(this,VideoCaptureService.class).putExtra("result",result).putExtra("data",data).putExtra("kbps",pendingScreenKbps));
    }
    public boolean onKeyDown(int code,KeyEvent event){if(web!=null&&((AppSession.core!=null&&code==AppSession.core.state.hardwarePttCode)||code==KeyEvent.KEYCODE_HEADSETHOOK)){web.evaluateJavascript("begin()",null);return true;}return super.onKeyDown(code,event);}
    public boolean onKeyUp(int code,KeyEvent event){if(web!=null&&((AppSession.core!=null&&code==AppSession.core.state.hardwarePttCode)||code==KeyEvent.KEYCODE_HEADSETHOOK)){web.evaluateJavascript("end()",null);return true;}return super.onKeyUp(code,event);}
    protected void onResume(){super.onResume();background=false;if(video!=null)video.background(false);}
    protected void onStop(){
        background=true;if(web!=null)web.evaluateJavascript("end()",null);
        if(video!=null)video.background(true);
        if(!capturePrompt&&(video==null||!video.sharingScreen()))finish();
        else if(video!=null)video.stop("camera");
        super.onStop();
    }
    protected void onDestroy(){
        try{unregisterReceiver(screenOff);}catch(IllegalArgumentException ignored){}
        if(video!=null){video.close();if(VideoCaptureService.capture==video)VideoCaptureService.capture=null;}
        stopService(new Intent(this,VideoCaptureService.class));
        if(web!=null){web.removeJavascriptInterface("VchNative");web.destroy();web=null;}
        if(voice!=null)voice.close();AppSession.lock();super.onDestroy();
    }
}
