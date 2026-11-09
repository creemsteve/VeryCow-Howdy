package chat.vch;

import chat.vch.Models.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class VoiceBridge implements AutoCloseable {
    private final String token=Crypto.b64(Crypto.random(32));
    private final HttpServer http;
    private final ExecutorService executor=Executors.newFixedThreadPool(4);
    private final VoiceSession voice;
    private final boolean ownsSession;
    public VoiceBridge(ClientCore core,SavedServer server,Channel channel)throws Exception{
        Path app=Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().getParent();
        Path models=Path.of(System.getProperty("vch.models",Files.exists(app.resolve("models/dac.json"))?app.resolve("models").toString():"build/models"));
        this.voice=new VoiceSession(core,server,channel,models);this.ownsSession=true;
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);http.setExecutor(executor);http.createContext("/",this::handle);http.start();
    }
    VoiceBridge(VoiceSession voice)throws Exception{
        this.voice=voice;this.ownsSession=false;
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);http.setExecutor(executor);http.createContext("/",this::handle);http.start();
    }
    public URI videoUri(){return URI.create(uri().toString().replace("/#","/video.html#"));}
    public URI uri(){return URI.create("http://127.0.0.1:"+http.getAddress().getPort()+"/#"+URLEncoder.encode(token,java.nio.charset.StandardCharsets.UTF_8));}
    private void handle(HttpExchange x)throws IOException{
        int status=200;byte[] response;String type="application/json";
        try{
            String origin="http://127.0.0.1:"+http.getAddress().getPort();
            if(!x.getRemoteAddress().getAddress().isLoopbackAddress()||!("127.0.0.1:"+http.getAddress().getPort()).equals(x.getRequestHeaders().getFirst("Host")))throw new IOException("Invalid host");
            String path=x.getRequestURI().getPath();
            if((path.equals("/")||path.equals("/video.html")||path.equals("/video.js"))&&x.getRequestMethod().equals("GET")){
                type=path.endsWith(".js")?"text/javascript; charset=utf-8":"text/html; charset=utf-8";try(InputStream in=getClass().getResourceAsStream(path.equals("/")?"/voice.html":path)){response=Objects.requireNonNull(in).readAllBytes();}
                x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; object-src 'none'; frame-ancestors 'none'");
            }else if(path.equals("/fonts/IBMPlexMono-Regular.ttf")&&x.getRequestMethod().equals("GET")){
                type="font/ttf";try(InputStream in=getClass().getResourceAsStream(path)){response=Objects.requireNonNull(in).readAllBytes();}
            }else{
                if(!x.getRequestMethod().equals("POST")||!token.equals(x.getRequestHeaders().getFirst("X-VCH-Token")))throw new IOException("Unauthorized voice request");
                String supplied=x.getRequestHeaders().getFirst("Origin");if(supplied!=null&&!origin.equals(supplied))throw new IOException("Invalid origin");
                byte[] b=x.getRequestBody().readNBytes(250001);if(b.length>250000)throw new IOException("Voice request too large");response=Json.bytes(voice.request(path,Json.parse(b)));
            }
        }catch(Exception e){status=400;response=Json.bytes(Json.obj("error",e.getMessage()==null?"Voice failure":e.getMessage()));}
        x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        x.sendResponseHeaders(status,response.length);try(OutputStream out=x.getResponseBody()){out.write(response);}finally{x.close();}
    }
    public void close(){http.stop(0);executor.shutdownNow();if(ownsSession)voice.close();}
}
