package chat.vch;

import java.net.*;
import java.io.IOException;
import java.util.Locale;

/** Human-facing host names become HTTPS endpoints internally. */
public final class ServerAddress {
    public static final int DEFAULT_PORT = 585;
    private ServerAddress() {}
    public static String normalize(String input) throws IOException {
        try {
            String text = input.trim();
            if (text.isEmpty() || text.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException();
            if (!text.contains("://")) {
                if (!text.startsWith("[") && text.chars().filter(c -> c == ':').count() > 1) text = "[" + text + "]";
                text = "https://" + text;
            }
            URI uri = new URI(text);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
            int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            return new URI("https", null, host.toLowerCase(Locale.ROOT), port, null, null, null).toASCIIString();
        } catch (Exception e) { throw new IOException("Enter an IP address or domain, such as 192.168.1.20 or chat.example.com", e); }
    }
    public static String migrateLegacy(String input) throws IOException {
        String address = normalize(input);
        URI uri = URI.create(address);
        if (uri.getPort() != 8443) return address;
        try { return new URI("https", null, uri.getHost(), DEFAULT_PORT, null, null, null).toASCIIString(); }
        catch (URISyntaxException e) { throw new IOException(e); }
    }
}
