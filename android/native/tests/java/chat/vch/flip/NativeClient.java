package chat.vch.flip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Host-side JNI smoke test for the same entry points packaged in the APK. */
public final class NativeClient {
    private static native long create(String directory,byte[] password,String models,String nativeDirectory)throws IOException;
    private static native String exchange(long handle,String path,String input);
    private static native void gate(long handle,boolean pressed);
    private static native void destroy(long handle);
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        Path directory=Files.createTempDirectory(Path.of(args[1]),"jni-");byte[] password="synthetic JNI vault password".getBytes(StandardCharsets.UTF_8);
        long handle=create(directory.toString(),password,args[2],args[2]);check(handle>0,"Handle allocated");
        String state=exchange(handle,"/state","{}");check(!state.contains("privateKey")&&!state.contains("publicKey"),"Secrets stay native");
        check(!exchange(handle,"/profile","{\"name\":\"JNI member\",\"bio\":\"Host JNI verification\",\"pronouns\":\"\",\"avatar\":\"\"}").contains("error"),"Profile saved through JNI");
        gate(handle,false);check(exchange(handle,"/media/media","{}").contains("released"),"PTT release gate");
        gate(handle,true);check(exchange(handle,"/media/media","{}").contains("Join voice first"),"PTT press gate");
        destroy(handle);check(exchange(handle,"/state","{}").contains("Vault locked"),"Destroyed handles fail closed");
        try{create(directory.toString(),"wrong password".getBytes(StandardCharsets.UTF_8),args[2],args[2]);throw new AssertionError("Wrong password accepted");}catch(IOException expected){}
        handle=create(directory.toString(),password,args[2],args[2]);check(exchange(handle,"/state","{}").contains("JNI member"),"JNI archive survives reopen");destroy(handle);
        System.out.println("JNI smoke tests passed: create, profile, secret boundary, PTT gate, close, wrong password, reopen");
    }
}
