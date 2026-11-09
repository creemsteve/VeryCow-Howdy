package chat.vch;

import java.util.*;

public final class Models {
    public static final class Channel {
        public String id = UUID.randomUUID().toString(), name = "general", type = "text";
        public int bitRate = 6000, maxMembers = 16, slowModeSeconds = 0;
        public Set<String> allow = new HashSet<>();
        public Map<String,Map<String,Boolean>> roleOverrides = new HashMap<>();
        public boolean permits(String hash) { return allow.isEmpty() || allow.contains(hash); }
        public String toString() { return (type.equals("voice") ? "◉  " : "#  ") + name; }
    }
    public static final class Account {
        public String hash, publicKey, name = "Member", bio = "", avatar = "", pronouns = "";
    }
    public static final class Role {
        public String id = UUID.randomUUID().toString(), name = "New role";
        public Set<String> permissions = new HashSet<>(), members = new HashSet<>();
        public String toString() { return name; }
    }
    public static final class Envelope {
        public String id, server, channel, author, kind, cipher, signature;
        public long time, sequence;
        public long keyRevision;
        public byte[] signedBytes() { return Crypto.utf((keyRevision>0?"VCH/message/2|":"VCH/message/1|") + server + "|" + channel + "|" + author + "|" + id + "|" + time + "|" + kind + "|" + cipher + (keyRevision>0?"|"+keyRevision:"")); }
        public byte[] aad() { return Crypto.utf((keyRevision>0?"VCH/payload/2|":"VCH/payload/1|") + server + "|" + channel + "|" + author + "|" + id + "|" + kind + (keyRevision>0?"|"+keyRevision:"")); }
    }
    public static final class ChannelKeySet {
        public String current = Crypto.b64(Crypto.random(32)), accessPolicy;
        public long revision = 1;
        public List<String> previous = new ArrayList<>();
    }
    public static final class CallInvite {
        public String id, from, to, channel;
        public long expires;
    }
    public static final class Friendship {
        public String first, second, textChannel, voiceChannel;
        public boolean includes(String account) { return account.equals(first) || account.equals(second); }
    }
    public static final class ServerState {
        public String id = UUID.randomUUID().toString(), name = "My community", description = "", image = "", passwordSalt, passwordVerifier, tlsStore;
        public Set<String> admins = new HashSet<>();
        // Ordered from lowest to highest. The implicit everyone role is below all of them.
        public List<Role> roles = new ArrayList<>();
        public Set<String> everyonePermissions = new HashSet<>(Arrays.asList("view", "send", "connect"));
        public Map<String,Set<String>> friendRequests = new HashMap<>();
        public List<Friendship> friendships = new ArrayList<>();
        public int port = ServerAddress.DEFAULT_PORT, hashBits = 16, joinProtocol = 2;
        public List<Channel> channels = new ArrayList<>();
        public Map<String,ChannelKeySet> channelKeys = new HashMap<>();
        public Map<String,Account> accounts = new HashMap<>();
        public Set<String> bannedIps = new HashSet<>(), bannedHashes = new HashSet<>();
        public List<Envelope> messages = new ArrayList<>();
        public long nextSequence = 1;
    }
    public static final class SavedServer {
        public String address, fingerprint, name, id, passwordKey, description = "", image = "";
        public boolean admin, roleManager;
        public boolean torOnly;
        public int torSocksPort = 9050;
        public List<Friendship> friendships = new ArrayList<>();
        public Set<String> friendRequests = new HashSet<>();
        public int joinProtocol;
        public Map<String,String> channelKeys = new HashMap<>();
        public Map<String,Long> keyRevisions = new HashMap<>();
        public Map<String,List<String>> previousChannelKeys = new HashMap<>();
        public List<Channel> channels = new ArrayList<>(), adminChannels = new ArrayList<>();
        public List<Role> roles = new ArrayList<>();
        public Set<String> everyonePermissions = new HashSet<>();
        public Map<String,Account> accounts = new HashMap<>();
        public List<Envelope> messages = new ArrayList<>();
        public long lastSequence;
        public String toString() { return (name == null ? address : name) + (torOnly ? " [Tor only]" : ""); }
    }
    public static final class ClientState {
        public String name = "New member", bio = "", avatar = "", pronouns = "", publicKey, privateKey;
        public String microphone = "Default", speakers = "Default", pttKey = "SPACE";
        public int inputGain = 100, outputGain = 100, outgoingBitRate = 6000;
        public int hardwarePttCode = 138;
        public boolean darkMode;
        public List<SavedServer> servers = new ArrayList<>();
    }
}
