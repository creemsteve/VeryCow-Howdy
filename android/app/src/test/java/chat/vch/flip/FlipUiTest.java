package chat.vch.flip;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w240dp-h320dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class FlipUiTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    @Before public void setup(){
        RuntimeEnvironment.getApplication().getApplicationInfo().nativeLibraryDir=new File(System.getProperty("vch.root"),"build/models").getAbsolutePath();
        controller=Robolectric.buildActivity(MainActivity.class).setup();activity=controller.get();idle();layout();
    }
    @After public void close()throws Exception{if(controller!=null){controller.pause().stop().destroy();((ExecutorService)get("worker")).awaitTermination(10,TimeUnit.SECONDS);}}
    private Object get(String field)throws Exception{Field f=MainActivity.class.getDeclaredField(field);f.setAccessible(true);return f.get(activity);}
    private void idle(){shadowOf(Looper.getMainLooper()).idle();}
    private void layout(){View view=activity.getWindow().getDecorView();view.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY));view.layout(0,0,240,320);idle();}
    private List<View> views(){List<View> out=new ArrayList<>();walk(activity.getWindow().getDecorView(),out);return out;}
    private void walk(View view,List<View> out){out.add(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)walk(((ViewGroup)view).getChildAt(i),out);}
    private Button button(String text){for(View view:views())if(view instanceof Button&&((Button)view).getText().toString().equals(text))return (Button)view;throw new AssertionError("Missing button: "+text);}
    private KeypadEditText field(String label){for(View view:views())if(view instanceof KeypadEditText&&label.contentEquals(view.getContentDescription()))return (KeypadEditText)view;throw new AssertionError("Missing field: "+label);}
    private boolean hasText(String text){for(View view:views())if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;return false;}
    private void click(String title){assertTrue(button(title).performClick());idle();layout();}
    private void await(String text)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(!hasText(text)&&System.nanoTime()<end){idle();Thread.sleep(10);}assertTrue("Waiting for "+text,hasText(text));layout();}
    private void unlock()throws Exception{field("Vault password").setText("synthetic vault password");field("Confirm password").setText("synthetic vault password");click("Create vault");await("Communities");}
    private void key(KeypadEditText edit,int code,long time){edit.onKeyDown(code,new KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0));edit.onKeyUp(code,new KeyEvent(time,time,KeyEvent.ACTION_UP,code,0));}

    @Test public void keypadAcceptsLettersDigitsPunctuationAndSelection(){
        KeypadEditText edit=new KeypadEditText(activity,"Entry",false);
        key(edit,KeyEvent.KEYCODE_2,1000);key(edit,KeyEvent.KEYCODE_2,1100);key(edit,KeyEvent.KEYCODE_2,1200);assertEquals("c",edit.getText().toString());
        key(edit,KeyEvent.KEYCODE_2,2500);assertEquals("ca",edit.getText().toString());
        key(edit,KeyEvent.KEYCODE_POUND,2600);key(edit,KeyEvent.KEYCODE_3,2700);assertEquals("caD",edit.getText().toString());
        key(edit,KeyEvent.KEYCODE_POUND,2800);key(edit,KeyEvent.KEYCODE_9,2900);assertEquals("caD9",edit.getText().toString());
        key(edit,KeyEvent.KEYCODE_STAR,3000);assertEquals("caD",edit.getText().toString());edit.setSelection(0,3);key(edit,KeyEvent.KEYCODE_7,3100);assertEquals("7",edit.getText().toString());
        edit.setText("");key(edit,KeyEvent.KEYCODE_POUND,3200);Set<Character> symbols=new HashSet<>();for(int i=0;i<40;i++){key(edit,KeyEvent.KEYCODE_1,3300+i*10);symbols.add(edit.getText().charAt(0));}assertTrue(symbols.contains('#'));assertTrue(symbols.contains('*'));assertTrue(symbols.contains('$'));assertTrue(symbols.contains('"'));
        assertFalse(edit.getShowSoftInputOnFocus());
    }
    @Test public void lockedLayoutHasVisibleKeyboardFocusAt240x320(){
        assertTrue(hasText("Create vault"));assertNotNull(activity.getCurrentFocus());
        for(int i=0;i<3;i++){
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_DOWN));idle();layout();
            View focused=activity.getCurrentFocus();Rect visible=new Rect();assertNotNull(focused);assertTrue(focused.getGlobalVisibleRect(visible));assertTrue(visible.bottom<=320);assertTrue(visible.top>=0);
        }
        assertFalse(field("Vault password").getShowSoftInputOnFocus());
    }
    @Test public void realNativeVaultProfileLockAndReopen()throws Exception{
        field("Vault password").setText("synthetic vault password");field("Confirm password").setText("different password");click("Create vault");assertTrue(hasText("Passwords do not match"));
        unlock();click("Your profile");field("Display name").setText("Keypad member");field("Pronouns").setText("they/them");field("Bio").setText("Current Rust core");click("Save");await("Communities");
        click("Lock vault");field("Vault password").setText("wrong vault password");click("Unlock");await("Wrong password");
        field("Vault password").setText("synthetic vault password");click("Unlock");await("Communities");click("Your profile");assertEquals("Keypad member",field("Display name").getText().toString());assertEquals("Current Rust core",field("Bio").getText().toString());
    }
    @Test public void profileAndAudioDraftsSurviveSubpageNavigation()throws Exception{
        unlock();click("Your profile");field("Display name").setText("Unsaved draft");click("Choose profile image");assertEquals(Intent.ACTION_OPEN_DOCUMENT,shadowOf(activity).getNextStartedActivityForResult().intent.getAction());
        controller.pause().stop();activity.onActivityResult(100,Activity.RESULT_CANCELED,null);controller.restart().start().resume().visible();idle();layout();assertEquals("Unsaved draft",field("Display name").getText().toString());
        click("Back");click("Audio / Appearance");field("Input gain % (0–300)").setText("175");click("Microphone / Default");click("Default");assertEquals("175",field("Input gain % (0–300)").getText().toString());
        click("Map PTT key (138)");activity.onKeyDown(KeyEvent.KEYCODE_BACK,new KeyEvent(0,0,0,KeyEvent.KEYCODE_BACK,0));assertTrue(hasText("Save preferences"));click("Save preferences");await("Communities");
        assertEquals(175,((NativeClient)get("core")).state.inputGain);
    }
    @Test public void backgroundingLocksAndStopsPolling()throws Exception{
        unlock();NativeClient client=(NativeClient)get("core");controller.pause().stop();assertNull(get("core"));controller.restart().start().resume().visible();idle();assertTrue(hasText("Unlock"));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(client.state!=null&&System.nanoTime()<end)Thread.sleep(10);assertNull(client.state);
    }
}
