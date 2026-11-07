package chat.vch;

import ai.onnxruntime.*;
import com.google.gson.JsonObject;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Persistent CPU inference with optimized graphs and densely packed 10-bit tokens. */
public final class DacCodec implements AutoCloseable {
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final OrtSession encoder,decoder;
    private final int books,hop,sampleRate;
    public DacCodec(Path directory)throws Exception{
        JsonObject info=Json.parse(Files.readAllBytes(directory.resolve("dac.json")));books=(int)Json.num(info,"codebooks",12);hop=(int)Json.num(info,"hop",320);sampleRate=(int)Json.num(info,"sampleRate",16000);
        try(OrtSession.SessionOptions opts=new OrtSession.SessionOptions()){
            opts.setIntraOpNumThreads(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()/2)));opts.setInterOpNumThreads(1);
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);opts.setMemoryPatternOptimization(true);
            encoder=env.createSession(directory.resolve("dac-encoder.onnx").toString(),opts);
            try{decoder=env.createSession(directory.resolve("dac-decoder.onnx").toString(),opts);}catch(Exception e){encoder.close();throw e;}
        }
    }
    public synchronized JsonObject request(JsonObject in)throws Exception{
        String op=Json.str(in,"op");if(op.equals("init")||op.isEmpty())return Json.obj("ready",true,"sampleRate",sampleRate,"hop",hop);
        byte[] data=Crypto.unb64(Json.str(in,"data"));
        if(op.equals("encode")){
            if(data.length<4||data.length>64000||data.length%4!=0)throw new IOException("Invalid PCM length");
            FloatBuffer input=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();int samples=input.remaining(),padded=(samples+hop+7)/hop*hop;
            float[] pcm=new float[padded];input.get(pcm,0,samples);for(int i=0;i<samples;i++){if(!Float.isFinite(pcm[i]))throw new IOException("Invalid PCM samples");pcm[i]=Math.max(-1,Math.min(1,pcm[i]));}
            int n=Math.max(1,Math.min(books,(int)Math.round(Json.num(in,"bitRate",6000)*(double)hop/(10*sampleRate))));
            long start=System.nanoTime();byte[] packet;
            try(OnnxTensor t=OnnxTensor.createTensor(env,FloatBuffer.wrap(pcm),new long[]{1,1,padded});OrtSession.Result result=encoder.run(Collections.singletonMap("pcm",t))){
                long[][][] codes=(long[][][])result.get(0).getValue();int frames=codes[0][0].length;
                ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(Crypto.utf("VCD1")).putShort((short)n).putShort((short)frames).putInt(samples).array());
                int acc=0,bits=0;for(int q=0;q<n;q++)for(long code:codes[0][q]){acc=(acc<<10)|(int)code;bits+=10;while(bits>=8){bits-=8;out.write((acc>>>bits)&255);}}if(bits>0)out.write((acc<<(8-bits))&255);packet=out.toByteArray();
            }
            return Json.obj("data",Crypto.b64(packet),"ms",(System.nanoTime()-start)/1000000.0);
        }
        if(op.equals("decode")){
            if(data.length<12||data.length>32000)throw new IOException("Invalid DAC packet");ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);byte[] magic=new byte[4];b.get(magic);int n=Short.toUnsignedInt(b.getShort()),frames=Short.toUnsignedInt(b.getShort()),samples=b.getInt();
            if(!Arrays.equals(magic,Crypto.utf("VCD1"))||n<1||n>books||frames<1||frames>256||samples<1||samples>16000||data.length!=12+(n*frames*10+7)/8)throw new IOException("Invalid DAC dimensions");
            long[] codes=new long[books*frames];float[] mask=new float[books];Arrays.fill(mask,0,n,1);int acc=0,bits=0,index=0;
            while(b.hasRemaining()){acc=(acc<<8)|Byte.toUnsignedInt(b.get());bits+=8;while(bits>=10&&index<n*frames){bits-=10;codes[index++]=(acc>>>bits)&1023;}}
            if(index!=n*frames)throw new IOException("Truncated codes");
            try(OnnxTensor t=OnnxTensor.createTensor(env,LongBuffer.wrap(codes),new long[]{1,books,frames});OnnxTensor m=OnnxTensor.createTensor(env,FloatBuffer.wrap(mask),new long[]{books});OrtSession.Result result=decoder.run(Map.of("codes",t,"mask",m))){
                float[][][] pcm=(float[][][])result.get(0).getValue();if(pcm[0][0].length<samples)throw new IOException("Truncated PCM output");ByteBuffer out=ByteBuffer.allocate(samples*4).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<samples;i++)out.putFloat(pcm[0][0][i]);return Json.obj("data",Crypto.b64(out.array()),"sampleRate",sampleRate);
            }
        }
        throw new IOException("Unknown codec operation");
    }
    public synchronized void close(){try{encoder.close();decoder.close();}catch(Exception ignored){}}
}
