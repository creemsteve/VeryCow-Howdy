package chat.vch.flip;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import static chat.vch.flip.NativeClient.*;

/** Framework-only runner: real Android widgets/JNI, no Google test-services dependency. */
public final class FlipInstrumentation extends Instrumentation {
    private int current,failures;
    private static final int TOTAL=4;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        test("keypadMultitapModesAndDeletion",this::keypad);
        test("manifestNonTouchContract",this::manifest);
        test("rustJniVaultAndSecretBoundary",this::nativeVault);
        test("smallScreenDpadNavigation",this::navigation);
        Bundle out=new Bundle();out.putString("stream",failures==0?"\nOK ("+TOTAL+" tests)\n":"\nFAILURES: "+failures+"\n");out.putInt("failures",failures);finish(failures==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);
    }
    private interface Test{void run()throws Exception;}
    private void test(String name,Test test){current++;Bundle status=new Bundle();status.putString("class",getClass().getName());status.putString("test",name);status.putInt("numtests",TOTAL);status.putInt("current",current);sendStatus(1,status);
        try{test.run();sendStatus(0,status);}catch(Throwable error){failures++;StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));status.putString("stack",trace.toString());sendStatus(-2,status);}}
    private static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    private void keypad(){runOnMainSync(()->{
        KeypadEditText edit=new KeypadEditText(getTargetContext(),"Test",false);long time=1000;
        for(int key:new int[]{KeyEvent.KEYCODE_2,KeyEvent.KEYCODE_2,KeyEvent.KEYCODE_2}){edit.onKeyDown(key,new KeyEvent(time,time,KeyEvent.ACTION_DOWN,key,0));time+=100;}
        require(edit.getText().toString().equals("c"),"Repeated 2 must cycle to c");
        time+=1000;edit.onKeyDown(KeyEvent.KEYCODE_2,new KeyEvent(time,time,0,KeyEvent.KEYCODE_2,0));require(edit.getText().toString().equals("ca"),"Timeout must commit the prior letter");
        edit.onKeyDown(KeyEvent.KEYCODE_POUND,new KeyEvent(0,0,0,KeyEvent.KEYCODE_POUND,0));require(edit.inputMode().equals("ABC"),"Uppercase mode");
        time+=100;edit.onKeyDown(KeyEvent.KEYCODE_3,new KeyEvent(time,time,0,KeyEvent.KEYCODE_3,0));require(edit.getText().toString().equals("caD"),"Uppercase character");
        edit.onKeyDown(KeyEvent.KEYCODE_POUND,new KeyEvent(0,0,0,KeyEvent.KEYCODE_POUND,0));time+=100;edit.onKeyDown(KeyEvent.KEYCODE_9,new KeyEvent(time,time,0,KeyEvent.KEYCODE_9,0));require(edit.getText().toString().equals("caD9"),"Numeric mode");
        edit.onKeyDown(KeyEvent.KEYCODE_STAR,new KeyEvent(0,0,0,KeyEvent.KEYCODE_STAR,0));require(edit.getText().toString().equals("caD"),"Star deletes");
    });}
    private void manifest()throws Exception{PackageInfo info=getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),PackageManager.GET_CONFIGURATIONS|PackageManager.GET_PERMISSIONS);
        for(FeatureInfo feature:info.reqFeatures)if(feature.name!=null&&(feature.name.startsWith("android.hardware.camera")||feature.name.equals("android.hardware.microphone")||feature.name.equals("android.hardware.touchscreen")||feature.name.equals("android.hardware.faketouch")))require((feature.flags&FeatureInfo.FLAG_REQUIRED)==0,"Touch/camera/microphone must be optional");
        require(info.applicationInfo.minSdkVersion==29,"Minimum Android 10");require(Arrays.asList(info.requestedPermissions).contains("android.permission.INTERNET"),"Internet permission");
    }
    private void nativeVault()throws Exception{
        File directory=new File(getTargetContext().getCacheDir(),"native-test-"+System.nanoTime());require(directory.mkdirs(),"Test vault directory");
        Context context=new ContextWrapper(getTargetContext()){@Override public File getFilesDir(){return directory;}};
        try(NativeClient client=new NativeClient(context,"synthetic vault password".toCharArray())){
            require(!client.call("/state",json()).has("privateKey"),"Private key must stay native");
            client.call("/profile",json("name","Native Android","bio","Keypad test","pronouns","","avatar",""));
            client.press(false);try{client.call("/media/media",json());throw new AssertionError("Unpressed PTT must fail");}catch(IOException expected){require(expected.getMessage().contains("released"),"PTT gate checked before media");}
        }
        try(NativeClient client=new NativeClient(context,"synthetic vault password".toCharArray())){require(client.state.name.equals("Native Android"),"Rust vault must reopen on Android");}
    }
    private void navigation()throws Exception{
        Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        runOnMainSync(()->{View decor=activity.getWindow().getDecorView();require(decor.getWidth()>0&&decor.getHeight()>0,"Layout has dimensions");require(activity.getCurrentFocus()!=null,"Initial keyboard focus");});
        for(int i=0;i<4;i++){sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);waitForIdleSync();runOnMainSync(()->{View focus=activity.getCurrentFocus();Rect visible=new Rect();require(focus!=null&&focus.getGlobalVisibleRect(visible),"D-pad focus remains visible");});}
        runOnMainSync(()->{
            try {View view=activity.getWindow().getDecorView();Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));try(OutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),"instrumentation-layout.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
            catch(IOException error){throw new RuntimeException(error);}activity.finish();
        });waitForIdleSync();
    }
}
