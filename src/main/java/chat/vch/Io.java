package chat.vch;
import java.io.*;
public final class Io {
    public static byte[] read(InputStream in,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[Math.min(8192,limit)];int n;
        while((n=in.read(buf,0,Math.min(buf.length,limit-out.size())))>0){out.write(buf,0,n);if(out.size()==limit)break;}return out.toByteArray();
    }
}
