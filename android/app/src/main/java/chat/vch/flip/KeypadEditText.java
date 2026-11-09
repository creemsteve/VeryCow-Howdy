package chat.vch.flip;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.widget.EditText;

/** Hardware multi-tap fallback; QWERTY input remains available for USB keyboards. */
public final class KeypadEditText extends EditText {
    private static final String[] LETTERS = {" 0", symbols(), "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9"};
    private static String symbols(){StringBuilder value=new StringBuilder(".,?!@:/-_");for(char c=33;c<=126;c++)if(!Character.isLetterOrDigit(c)&&value.indexOf(String.valueOf(c))<0)value.append(c);return value.append('1').toString();}
    private int previous=-1, tap, position=-1, mode;
    private long last;
    private java.util.function.Consumer<String> modeChanged=ignored->{};
    public KeypadEditText(Context context){this(context,"",false);}
    public KeypadEditText(Context context, String hint, boolean secret) {
        super(context);
        setHint(hint);
        setTextSize(14);
        setSingleLine(true);
        setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        setShowSoftInputOnFocus(false);
        setSelectAllOnFocus(false);
        setContentDescription(hint);
        setOnFocusChangeListener((v, focused) -> {commit();if(focused)modeChanged.accept(inputMode());});
    }
    private void commit() { previous=-1; position=-1; tap=0; }
    public String inputMode() { return mode==2 ? "123" : mode==1 ? "ABC" : "abc"; }
    public void setModeListener(java.util.function.Consumer<String> listener){modeChanged=listener;}
    public void numeric(){mode=2;commit();}
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (code==KeyEvent.KEYCODE_POUND) {
            if(event.getRepeatCount()==0) { commit(); mode=(mode+1)%3; announceForAccessibility(inputMode());modeChanged.accept(inputMode()); }
            return true;
        }
        if (code==KeyEvent.KEYCODE_STAR) {
            commit(); int start=Math.max(0,getSelectionStart()), end=Math.max(start,getSelectionEnd());
            if(start!=end) getText().delete(start,end); else if(start>0)getText().delete(start-1,start);
            return true;
        }
        if(code>=KeyEvent.KEYCODE_0 && code<=KeyEvent.KEYCODE_9) {
            if(event.getRepeatCount()>0)return true;
            int digit=code-KeyEvent.KEYCODE_0, start=Math.max(0,getSelectionStart()), end=Math.max(start,getSelectionEnd());
            long now=event.getEventTime();
            if(mode==2) { commit(); getText().replace(start,end,Integer.toString(digit)); return true; }
            String letters=LETTERS[digit];
            if(previous==digit && now-last<900 && start==end && start==position+1) {
                tap=(tap+1)%letters.length(); start=position;
            } else { tap=0; position=start; }
            String value=String.valueOf(letters.charAt(tap));
            if(mode==1)value=value.toUpperCase(java.util.Locale.ROOT);
            getText().replace(start,end,value); setSelection(start+1);
            previous=digit; last=now; return true;
        }
        commit(); return super.onKeyDown(code,event);
    }
    @Override public boolean onKeyUp(int code, KeyEvent event) {
        if(code==KeyEvent.KEYCODE_POUND || code==KeyEvent.KEYCODE_STAR || (code>=KeyEvent.KEYCODE_0&&code<=KeyEvent.KEYCODE_9))return true;
        return super.onKeyUp(code,event);
    }
}
