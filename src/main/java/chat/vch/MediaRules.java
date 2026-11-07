package chat.vch;

import java.util.Arrays;
public final class MediaRules {
    public static boolean isWebp(byte[] b) { return b.length>=12 && Arrays.equals(Arrays.copyOfRange(b,0,4),Crypto.utf("RIFF")) && Arrays.equals(Arrays.copyOfRange(b,8,12),Crypto.utf("WEBP")); }
}
