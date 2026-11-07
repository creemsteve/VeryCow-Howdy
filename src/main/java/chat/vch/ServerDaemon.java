package chat.vch;

/** Optional central host entry point; administration remains in the server GUI. */
public final class ServerDaemon {
    public static void main(String[] args)throws Exception{
        java.io.Console console=System.console();
        if(console==null)throw new IllegalStateException("Open a terminal to enter the server vault password securely");
        char[] password=console.readPassword("VCH server vault password: ");
        Vault vault=new Vault(Launcher.dataDir("server").resolve("data.vault"),password);ChatServer server=new ChatServer(vault);
        if(server.state.passwordVerifier==null)server.setPassword(console.readPassword("Create server join password (12+ characters): "));
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.close();vault.close();}));
        server.log=System.out::println;server.start(args.length==0?"0.0.0.0":args[0]);
        System.out.println("Server identity: "+server.state.id+"\nCertificate fingerprint: "+Tls.fingerprint(server.state.tlsStore));
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
