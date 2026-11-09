package chat.vch;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** HTTPS through a local Tor SOCKS5 listener. Never resolves or connects to the destination locally. */
final class TorTransport {
    private static final int LIMIT = 24000000;
    record Reply(int status, byte[] body) {}

    static void validatePort(int port) throws IOException {
        if (port < 1 || port > 65535) throw new IOException("Tor SOCKS port must be between 1 and 65535");
    }

    static Reply post(URL url, int socksPort, SSLSocketFactory factory, byte[] body, String token) throws IOException {
        validatePort(socksPort);
        // An explicit direct socket prevents JVM/system proxy settings from rerouting the loopback connection.
        try (Socket tunnel = new Socket(Proxy.NO_PROXY)) {
            tunnel.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), socksPort), 7000);
            tunnel.setSoTimeout(60000);
            connect(tunnel, url.getHost(), url.getPort());
            try (SSLSocket tls = (SSLSocket)factory.createSocket(tunnel, url.getHost(), url.getPort(), true)) {
                tls.setSoTimeout(60000);
                tls.startHandshake(); // Uses the same bootstrap/pinned trust managers as direct HTTPS.
                OutputStream out = tls.getOutputStream();
                if (token != null && (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)) throw new IOException("Invalid server session token");
                String headers = "POST " + url.getFile() + " HTTP/1.1\r\nHost: " + url.getAuthority()
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n" + (token == null ? "" : "Authorization: Bearer " + token + "\r\n") + "\r\n";
                out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
                return readReply(new BufferedInputStream(tls.getInputStream()));
            }
        } catch (IOException e) {
            throw new IOException("Tor-only connection failed through 127.0.0.1:" + socksPort
                + ". Start Tor and check its SOCKS port. No direct connection was attempted. " + e.getMessage(), e);
        }
    }

    private static void connect(Socket socket, String host, int port) throws IOException {
        InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
        out.write(new byte[]{5,1,0}); out.flush();
        if (octet(in) != 5 || octet(in) != 0) throw new IOException("Tor SOCKS5 listener refused unauthenticated access");
        // ATYP=domain delegates all destination DNS (including .onion) to Tor.
        // IPv6 literals are encoded as IPv6 bytes; parsing a colon-containing literal performs no DNS.
        String destination = host.startsWith("[") ? host.substring(1,host.length()-1) : host;
        if (destination.contains(":")) {
            if (destination.contains("%")) throw new IOException("Scoped IPv6 addresses are not supported over Tor");
            byte[] address = InetAddress.getByName(destination).getAddress();
            if (address.length != 16) throw new IOException("Invalid IPv6 address");
            out.write(new byte[]{5,1,0,4}); out.write(address);
        } else {
            byte[] name = destination.getBytes(StandardCharsets.US_ASCII);
            if (name.length < 1 || name.length > 255) throw new IOException("Tor destination hostname is too long");
            out.write(new byte[]{5,1,0,3,(byte)name.length}); out.write(name);
        }
        out.write(port >>> 8); out.write(port & 255); out.flush();
        if (octet(in) != 5) throw new IOException("Invalid SOCKS5 reply");
        int status = octet(in);
        if (status != 0) throw new IOException("Tor SOCKS5 refused the destination (code " + status + ")");
        if (octet(in) != 0) throw new IOException("Invalid SOCKS5 reserved byte");
        int type = octet(in);
        int length = switch (type) { case 1 -> 4; case 4 -> 16; case 3 -> octet(in); default -> throw new IOException("Invalid SOCKS5 address type"); };
        exact(in,length + 2);
    }

    // Small bounded HTTP/1.1 reader: redirects are returned to ClientCore as errors, never followed.
    static Reply readReply(InputStream in) throws IOException {
        int[] budget = {65536};
        String statusLine = line(in,budget);
        if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) throw new IOException("Invalid HTTP status from Tor destination");
        int status = Integer.parseInt(statusLine.substring(9,12));
        Map<String,String> headers = new HashMap<>();
        for (String header; !(header = line(in,budget)).isEmpty();) {
            int colon = header.indexOf(':');
            if (colon < 1) throw new IOException("Invalid HTTP header");
            String name = header.substring(0,colon).toLowerCase(Locale.ROOT), value = header.substring(colon+1).trim();
            if (headers.putIfAbsent(name,value) != null) throw new IOException("Duplicate HTTP header");
        }
        String transfer = headers.get("transfer-encoding"), length = headers.get("content-length");
        byte[] body;
        if (transfer != null) {
            if (!transfer.equalsIgnoreCase("chunked") || length != null) throw new IOException("Unsupported HTTP response framing");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (;;) {
                String sizeLine = line(in,budget), size = sizeLine.split(";",2)[0];
                if (!size.matches("[0-9a-fA-F]{1,8}")) throw new IOException("Invalid HTTP chunk size");
                long count = Long.parseLong(size,16);
                if (count > LIMIT - bytes.size()) throw new IOException("Server response exceeds limit");
                if (count == 0) { while (!line(in,budget).isEmpty()) { /* bounded trailers */ } break; }
                bytes.write(exact(in,(int)count));
                if (octet(in) != '\r' || octet(in) != '\n') throw new IOException("Invalid HTTP chunk terminator");
            }
            body = bytes.toByteArray();
        } else if (length != null) {
            if (!length.matches("[0-9]{1,10}")) throw new IOException("Invalid HTTP content length");
            long count = Long.parseLong(length);
            if (count > LIMIT) throw new IOException("Server response exceeds limit");
            body = exact(in,(int)count);
        } else {
            body = Io.read(in,LIMIT+1);
            if (body.length > LIMIT) throw new IOException("Server response exceeds limit");
        }
        return new Reply(status,body);
    }

    private static String line(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (;;) {
            if (--budget[0] < 0) throw new IOException("HTTP response headers exceed limit");
            int b = octet(in);
            if (b == '\r') {
                if (--budget[0] < 0 || octet(in) != '\n') throw new IOException("Invalid HTTP line ending");
                return new String(bytes.toByteArray(),StandardCharsets.US_ASCII);
            }
            if (b == '\n' || bytes.size() >= 8192) throw new IOException("Invalid HTTP line");
            bytes.write(b);
        }
    }
    private static int octet(InputStream in) throws IOException {
        int b = in.read(); if (b < 0) throw new EOFException("Connection closed before response completed"); return b;
    }
    private static byte[] exact(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        try { new DataInputStream(in).readFully(bytes); }
        catch (EOFException e) { throw new EOFException("Truncated response"); }
        return bytes;
    }
}
