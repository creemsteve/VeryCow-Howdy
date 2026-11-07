package chat.vch;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class JoinAuthTest {
    private static int checks;
    private static void check(boolean pass,String label){if(!pass)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static void rejects(String address)throws Exception{boolean failed=false;try{ServerAddress.normalize(address);}catch(Exception e){failed=true;}check(failed,"invalid join address rejected: "+address);}
    public static void main(String[] args)throws Exception{
        check(ServerAddress.normalize("chat.example.com").equals("https://chat.example.com:585"),"domain alone uses encrypted port 585");
        check(ServerAddress.normalize("192.168.1.10").equals("https://192.168.1.10:585"),"IPv4 alone uses port 585");
        check(ServerAddress.normalize("::1").equals("https://[::1]:585"),"bare IPv6 uses port 585");
        check(ServerAddress.normalize("[2001:db8::1]").equals("https://[2001:db8::1]:585"),"bracketed IPv6 address supported");
        check(ServerAddress.normalize(" HTTPS://CHAT.EXAMPLE.COM/ ").equals("https://chat.example.com:585"),"pasted secure address is normalized");
        check(ServerAddress.normalize("localhost:12345").equals("https://localhost:12345"),"optional advanced port retained");
        check(ServerAddress.migrateLegacy("https://localhost:8443").equals("https://localhost:585"),"legacy default address migrates to 585");
        for(String bad:new String[]{"", "http://example.com", "https://user:pass@example.com", "example.com/path", "example.com?x=1", "example.com#fragment", "example.com:70000"})rejects(bad);
        AtomicLong clock=new AtomicLong(1000);JoinAttemptLimiter limiter=new JoinAttemptLimiter(clock::get);
        check(limiter.acquire("one")==0,"first IP attempt admitted");
        check(limiter.acquire("one")==10,"immediate retry delayed ten seconds");
        check(limiter.acquire("two")==0,"another IP has an independent cooldown");
        clock.addAndGet(9999);check(limiter.acquire("one")==1,"attempt still blocked at 9.999 seconds");
        clock.incrementAndGet();check(limiter.acquire("one")==0,"attempt allowed exactly at ten seconds");
        clock.addAndGet(9000);limiter.continued("one");clock.addAndGet(1000);check(limiter.acquire("one")==9,"delayed password proof renews the same attempt cooldown");
        ExecutorService pool=Executors.newFixedThreadPool(8);try{List<Future<Integer>> results=new ArrayList<>();for(int i=0;i<20;i++)results.add(pool.submit(()->limiter.acquire("simultaneous")));int admitted=0;for(Future<Integer> result:results)if(result.get()==0)admitted++;check(admitted==1,"concurrent requests cannot bypass the IP cooldown");}finally{pool.shutdownNow();}
        byte[] salt=Crypto.random(16),key=Crypto.random(32);String serverId="test-server",fingerprint=Crypto.hex(Crypto.random(32));
        var server=JoinAuth.server(salt,JoinAuth.identity(serverId,fingerprint),key);var client=JoinAuth.client();
        var b=server.generateServerCredentials();var a=client.generateClientCredentials(salt,JoinAuth.identity(serverId,fingerprint),key);client.calculateSecret(b);server.calculateSecret(a);
        check(server.verifyClientEvidenceMessage(client.calculateClientEvidenceMessage()),"SRP verifies the client password proof");
        check(client.verifyServerEvidenceMessage(server.calculateServerEvidenceMessage()),"SRP verifies the server password proof");
        check(Arrays.equals(JoinAuth.sessionKey(client.calculateSessionKey()),JoinAuth.sessionKey(server.calculateSessionKey())),"mutually authenticated session keys match");
        for(boolean wrongCertificate:new boolean[]{false,true}){
            var rogue=JoinAuth.server(salt,JoinAuth.identity(serverId,fingerprint),key);var victim=JoinAuth.client();var rb=rogue.generateServerCredentials();
            var va=victim.generateClientCredentials(salt,JoinAuth.identity(serverId,wrongCertificate?Crypto.hex(Crypto.random(32)):fingerprint),wrongCertificate?key:Crypto.random(32));
            victim.calculateSecret(rb);rogue.calculateSecret(va);
            check(!rogue.verifyClientEvidenceMessage(victim.calculateClientEvidenceMessage()),wrongCertificate?"TLS certificate substitution breaks the password proof":"wrong password fails SRP authentication");
        }
        boolean zeroRejected=false;try{JoinAuth.client().calculateSecret(java.math.BigInteger.ZERO);}catch(Exception e){zeroRejected=true;}check(zeroRejected,"invalid SRP public value rejected");
        System.out.println(checks+" join protocol checks passed");
    }
}
