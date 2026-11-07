package chat.vch;
import java.nio.*;
import java.nio.file.*;
public final class CodecTest {
    public static void main(String[] args)throws Exception{
        try(DacCodec codec=new DacCodec(Path.of("build/models"))){
            for(int length:new int[]{8000,8320,16000}){
                ByteBuffer b=ByteBuffer.allocate(length*4).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<length;i++)b.putFloat((float)(.2*Math.sin(2*Math.PI*220*i/16000)));
                long start=System.nanoTime();var encoded=codec.request(Json.obj("op","encode","data",Crypto.b64(b.array()),"bitRate",6000));var decoded=codec.request(Json.obj("op","decode","data",Json.str(encoded,"data")));byte[] output=Crypto.unb64(Json.str(decoded,"data"));
                if(output.length!=length*4)throw new AssertionError("Wrong sample count");FloatBuffer values=ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();double energy=0;while(values.hasRemaining()){float v=values.get();if(!Float.isFinite(v))throw new AssertionError("Non-finite sample");energy+=v*v;}if(energy<.0001)throw new AssertionError("Silent output");
                System.out.printf("PASS Java DAC round trip: %d samples, %d-byte packet, %.1f ms encode+decode, rms %.4f%n",length,Crypto.unb64(Json.str(encoded,"data")).length,(System.nanoTime()-start)/1e6,Math.sqrt(energy/length));
            }
            boolean failed=false;try{codec.request(Json.obj("op","decode","data",Crypto.b64(new byte[64])));}catch(Exception e){failed=true;}if(!failed)throw new AssertionError("Invalid packet accepted");System.out.println("PASS malformed DAC packet rejected");
        }
    }
}
