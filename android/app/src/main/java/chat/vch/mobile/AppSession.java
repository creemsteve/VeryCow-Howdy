package chat.vch.mobile;
import chat.vch.*;
import chat.vch.Models.*;
final class AppSession {
    static ClientCore core;
    static SavedServer server;
    static Channel channel;
    static synchronized void lock(){if(core!=null)core.close();core=null;server=null;channel=null;}
}
