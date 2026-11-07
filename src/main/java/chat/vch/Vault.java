package chat.vch;

import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import com.google.gson.JsonObject;

/** Versioned, authenticated, atomic AES-256-GCM storage. No plaintext journal or backup. */
public final class Vault implements AutoCloseable {
    private static final byte[] MAGIC = Crypto.utf("VCHVAULT1");
    private final Path path;
    private final byte[] salt;
    private final byte[] key;
    private JsonObject data;
    private FileChannel lockChannel;
    private FileLock lock;
    public Vault(Path path, char[] password) throws Exception {
        this.path = path;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            lockChannel=FileChannel.open(path.resolveSibling(path.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            try{lock=lockChannel.tryLock();}catch(java.nio.channels.OverlappingFileLockException e){throw new IOException("This vault is already open in another application");}
            if(lock==null)throw new IOException("This vault is already open in another application");
            if (Files.exists(path)) {
                byte[] b = Files.readAllBytes(path);
                if (b.length < MAGIC.length + 16 + 28 || !Arrays.equals(MAGIC, Arrays.copyOf(b, MAGIC.length))) throw new IOException("Unsupported vault");
                salt = Arrays.copyOfRange(b, MAGIC.length, MAGIC.length + 16); key = Crypto.derive(password, salt);
                try { data = Json.parse(Crypto.open(key, Arrays.copyOfRange(b, MAGIC.length + 16, b.length), MAGIC)); }
                catch (Exception e) { Arrays.fill(key, (byte) 0); throw new IOException("Wrong password or damaged vault", e); }
            } else {
                if (password.length < 12) throw new IOException("Use at least 12 characters for a new vault");
                salt = Crypto.random(16); key = Crypto.derive(password, salt); data = new JsonObject(); save();
            }
        } catch(Exception e){if(lock!=null)lock.release();if(lockChannel!=null)lockChannel.close();throw e;}
        finally { Arrays.fill(password, '\0'); }
    }
    public synchronized JsonObject data() { return data; }
    public synchronized void save() throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        byte[] cipher = Crypto.seal(key, Json.bytes(data), MAGIC);
        byte[] bytes = ByteBuffer.allocate(MAGIC.length + salt.length + cipher.length).put(MAGIC).put(salt).put(cipher).array();
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
    public synchronized void close() { Arrays.fill(key, (byte) 0); data = new JsonObject();try{if(lock!=null)lock.release();if(lockChannel!=null)lockChannel.close();}catch(IOException ignored){} }
}
