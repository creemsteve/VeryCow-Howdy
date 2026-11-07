package chat.vch.mobile;

import android.app.*;
import android.os.*;
import android.webkit.*;
import android.view.*;
import android.Manifest;
import android.content.pm.PackageManager;
import chat.vch.*;
import java.nio.file.*;
import java.io.*;

public final class VoiceActivity extends Activity {
    private WebView web;
    private VoiceSession voice;
    protected void onCreate(Bundle b){super.onCreate(b);getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);if(AppSession.core==null){finish();return;}if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);else open();}
    public void onRequestPermissionsResult(int request,String[] permissions,int[] granted){super.onRequestPermissionsResult(request,permissions,granted);if(granted.length>0&&granted[0]==PackageManager.PERMISSION_GRANTED)open();else finish();}
    private void open(){try{Path models=getFilesDir().toPath().resolve("models");Files.createDirectories(models);for(String f:new String[]{"dac.json","dac-encoder.onnx","dac-decoder.onnx"})if(!Files.exists(models.resolve(f)))try(InputStream in=getAssets().open(f)){Files.copy(in,models.resolve(f));}voice=new VoiceSession(AppSession.core,AppSession.server,AppSession.channel,models);
        web=new WebView(this);web.setBackgroundColor(0xfff4f4f4);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(false);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(),"VchNative");web.setWebViewClient(new WebViewClient(){public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}});web.setWebChromeClient(new WebChromeClient(){public void onPermissionRequest(PermissionRequest request){runOnUiThread(()->{if(request.getOrigin().toString().equals("https://vch.local/"))request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});else request.deny();});}});
        byte[] html;try(InputStream in=getAssets().open("voice.html")){html=Io.read(in,100000);}setContentView(web);web.loadDataWithBaseURL("https://vch.local/",new String(html,java.nio.charset.StandardCharsets.UTF_8),"text/html","UTF-8",null);
    }catch(Exception e){new AlertDialog.Builder(this).setTitle("Voice unavailable").setMessage(e.getMessage()).setPositiveButton("Close",(d,w)->finish()).show();}}
    public final class Bridge {@JavascriptInterface public String request(String path,String json){try{if(json.length()>100000)throw new IOException("Voice request too large");return Json.GSON.toJson(voice.request(path,Json.parse(Crypto.utf(json))));}catch(Exception e){return Json.GSON.toJson(Json.obj("error",e.getMessage()));}}}
    public boolean onKeyDown(int code,KeyEvent event){if(web!=null&&((AppSession.core!=null&&code==AppSession.core.state.hardwarePttCode)||code==KeyEvent.KEYCODE_HEADSETHOOK)){web.evaluateJavascript("begin()",null);return true;}return super.onKeyDown(code,event);}
    public boolean onKeyUp(int code,KeyEvent event){if(web!=null&&((AppSession.core!=null&&code==AppSession.core.state.hardwarePttCode)||code==KeyEvent.KEYCODE_HEADSETHOOK)){web.evaluateJavascript("end()",null);return true;}return super.onKeyUp(code,event);}
    protected void onStop(){if(web!=null){web.evaluateJavascript("leave()",null);web.removeJavascriptInterface("VchNative");web.destroy();web=null;}if(voice!=null)voice.close();AppSession.lock();super.onStop();finish();}
}
