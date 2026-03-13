package com.xiaofan.commen;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal ws:// WebSocket client (Java 8, no external deps).
 * Read-only: logs serverstatus/heartbeat and voice packet headers.
 */
public final class SimpleComWsClient {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Logger logger;
    private VoiceSink voiceSink;
    private volatile boolean running;
    private Thread thread;
    private volatile boolean compressionEnabled = true;  // Default to true
    private VoiceSinkInitializer voiceSinkInitializer;
    
    @FunctionalInterface
    public interface VoiceSinkInitializer {
        void initialize(boolean compressionEnabled);
    }

    public SimpleComWsClient(Logger logger, VoiceSink voiceSink, VoiceSinkInitializer initializer) {
        this.logger = logger;
        this.voiceSink = voiceSink;
        this.voiceSinkInitializer = initializer;
    }

    public SimpleComWsClient(Logger logger, VoiceSink voiceSink) {
        this(logger, voiceSink, null);
    }

    public void setVoiceSinkInitializer(VoiceSinkInitializer initializer) {
        this.voiceSinkInitializer = initializer;
    }

    public void setVoiceSink(VoiceSink sink) {
        this.voiceSink = sink;
    }

    public synchronized void start(String voiceApiHostPort, String token) {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop(voiceApiHostPort, token);
            }
        }, "SimpleComWsClient");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runLoop(String voiceApiHostPort, String token) {
        long backoffMs = 1000L;
        while (running) {
            try {
                connectAndRead(voiceApiHostPort, token);
                backoffMs = 1000L;
            } catch (ConfigurationException e) {
                log(Level.SEVERE, e.getMessage());
                return;
            } catch (Exception e) {
                log(Level.WARNING, "WS disconnected: " + e.getMessage());
            }

            if (!running) {
                return;
            }
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ignored) {
                // ignore
            }
            backoffMs = Math.min(backoffMs * 2L, 30000L);
        }
    }

    private void connectAndRead(String voiceApiHostPort, String token) throws Exception {
        HostPort hp = parseHostPort(voiceApiHostPort);
        if (token == null || token.trim().isEmpty() || "''".equals(token.trim())) {
            throw new ConfigurationException("ConfigurationException! token is empty.");
        }

        String path = "/voice-api?token=" + urlEncode(token.trim());
        String secKey = randomSecWebSocketKey();

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(hp.host, hp.port), 5000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(0);

        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());

            String hostHeader = hp.host + ":" + hp.port;
            String req = ""
                    + "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + secKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            HttpResponse resp = readHttpResponse(in);
            if (resp.statusCode != 101) {
                throw new IOException("Handshake failed: HTTP " + resp.statusCode);
            }

            String accept = resp.getHeader("sec-websocket-accept");
            String expected = computeAccept(secKey);
            if (accept == null || !accept.trim().equals(expected)) {
                throw new IOException("Handshake failed: Sec-WebSocket-Accept mismatch.");
            }

            log(Level.INFO, "Connected to SimpleCom WSAPI: ws://" + hostHeader + path);

            readFramesLoop(in, out);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private void readFramesLoop(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream messageBuf = null;
        int messageOpcode = -1;

        while (running) {
            Frame f = readFrame(in);
            if (f == null) {
                throw new EOFException("EOF");
            }

            if (f.opcode == 8) { // close
                log(Level.INFO, "WS closed by server.");
                return;
            }
            if (f.opcode == 9) { // ping
                writePong(out, f.payload);
                continue;
            }
            if (f.opcode == 10) { // pong
                continue;
            }

            if (f.opcode == 0) { // continuation
                if (messageBuf == null) {
                    continue;
                }
                messageBuf.write(f.payload);
                if (f.fin) {
                    dispatchMessage(messageOpcode, messageBuf.toByteArray());
                    messageBuf = null;
                    messageOpcode = -1;
                }
                continue;
            }

            if (f.opcode == 1 || f.opcode == 2) {
                if (f.fin) {
                    dispatchMessage(f.opcode, f.payload);
                } else {
                    messageOpcode = f.opcode;
                    messageBuf = new ByteArrayOutputStream();
                    messageBuf.write(f.payload);
                }
            }
        }
    }

    private void dispatchMessage(int opcode, byte[] payload) {
        if (opcode == 1) {
            String text = new String(payload, StandardCharsets.UTF_8);
            handleText(text);
        } else if (opcode == 2) {
            handleBinary(payload);
        }
    }

    private void handleText(String text) {
        if (text == null) {
            return;
        }
        String t = text.trim();
        
        if (t.contains("\"type\":\"serverstatus\"")) {
            Boolean compressionEncoder = parseJsonBoolean(t, "compressionEncoder");
            Boolean lowLatency = parseJsonBoolean(t, "lowLatency");
            this.compressionEnabled = compressionEncoder != null && compressionEncoder.booleanValue();
            
            // 初始化 VoiceSink（如果还没初始化）
            if (voiceSinkInitializer != null) {
                voiceSinkInitializer.initialize(this.compressionEnabled);
            } else {
                log(Level.WARNING, "voiceSinkInitializer is null!");
            }
            return;
        }
        if (t.contains("\"type\":\"heartbeat\"")) {
            return;
        }
    }

    private void handleBinary(byte[] payload) {
        try {
            VoicePacket packet = parseVoicePacket(payload);
            if (packet == null) {
                return;
            }
            
            if (voiceSink == null || packet.opusPayload == null || packet.opusPayload.length == 0) {
                return;
            }
            
            // When compressionEnabled=false, payload is raw PCM, don't extract frames
            // When compressionEnabled=true, payload is Opus, extract frames
            if (!compressionEnabled) {
                // Raw PCM: pass entire payload as one frame
                voiceSink.writeOpusFrame(packet.username, packet.seq, packet.total, packet.opusPayload);
            } else {
                // Opus: extract individual frames
                java.util.List<byte[]> opusFrames = extractOpusFrames(packet.opusPayload);
                if (opusFrames != null && !opusFrames.isEmpty()) {
                    for (int i = 0; i < opusFrames.size(); i++) {
                        byte[] frame = opusFrames.get(i);
                        if (frame != null && frame.length > 0) {
                            voiceSink.writeOpusFrame(packet.username, packet.seq, packet.total, frame);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log(Level.WARNING, "WS voice packet parse error: " + e.getMessage());
        }
    }

    /**
     * SimpleCom-core 在启用 Opus 压缩时，会把多个 Opus 帧拼接成：
     * [2字节 big-endian 长度][Opus帧]...[重复]
     * 这里把它拆成单帧列表；如果看起来不像该格式，则退化为“整段当作一帧”。
     */
    private static java.util.List<byte[]> extractOpusFrames(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return java.util.Collections.emptyList();
        }
        // Heuristic: if it starts with a plausible 2-byte length and that length fits,
        // assume it's the framed format (can contain multiple frames).
        if (payload.length >= 3) {
            int len = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            if (len > 0 && 2 + len <= payload.length) {
                java.util.ArrayList<byte[]> frames = new java.util.ArrayList<byte[]>();
                int off = 0;
                while (off + 2 <= payload.length) {
                    int l = ((payload[off] & 0xFF) << 8) | (payload[off + 1] & 0xFF);
                    off += 2;
                    if (l <= 0 || off + l > payload.length) {
                        // Not a clean framed stream; fallback to single frame
                        return java.util.Collections.singletonList(payload);
                    }
                    byte[] f = new byte[l];
                    System.arraycopy(payload, off, f, 0, l);
                    frames.add(f);
                    off += l;
                    if (off == payload.length) {
                        return frames;
                    }
                }
                return frames;
            }
        }
        return java.util.Collections.singletonList(payload);
    }

    private static VoicePacket parseVoicePacket(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        int off = 0;
        int usernameLen = readInt32BE(data, off);
        off += 4;
        if (usernameLen < 0 || usernameLen > 1024 || off + usernameLen + 8 > data.length) {
            return null;
        }
        String username = new String(data, off, usernameLen, StandardCharsets.UTF_8);
        off += usernameLen;
        int seq = readInt32BE(data, off);
        off += 4;
        int total = readInt32BE(data, off);
        off += 4;
        int payloadLen = data.length - off;
        if (payloadLen <= 0) {
            return new VoicePacket(username, seq, total, new byte[0]);
        }
        byte[] opus = new byte[payloadLen];
        System.arraycopy(data, off, opus, 0, payloadLen);
        return new VoicePacket(username, seq, total, opus);
    }

    private static int readInt32BE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static Boolean parseJsonBoolean(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        if (c < 0) return null;
        String tail = json.substring(c + 1).trim().toLowerCase(Locale.ROOT);
        if (tail.startsWith("true")) return Boolean.TRUE;
        if (tail.startsWith("false")) return Boolean.FALSE;
        return null;
    }

    private static Long parseJsonLong(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        if (c < 0) return null;
        int p = c + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        int start = p;
        while (p < json.length() && (json.charAt(p) == '-' || Character.isDigit(json.charAt(p)))) p++;
        if (p <= start) return null;
        try {
            return Long.valueOf(json.substring(start, p));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void writePong(OutputStream out, byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        writeClientFrame(out, 10, payload);
    }

    private static void writeClientFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        // Client-to-server frames MUST be masked.
        if (payload == null) payload = new byte[0];
        int len = payload.length;
        int b0 = 0x80 | (opcode & 0x0F);
        out.write(b0);

        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);

        if (len <= 125) {
            out.write(0x80 | len);
        } else if (len <= 65535) {
            out.write(0x80 | 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            long l = len & 0xFFFFFFFFL;
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write((int) ((l >>> 24) & 0xFF));
            out.write((int) ((l >>> 16) & 0xFF));
            out.write((int) ((l >>> 8) & 0xFF));
            out.write((int) (l & 0xFF));
        }

        out.write(mask);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        out.write(masked);
        out.flush();
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null;
        int b1 = in.read();
        if (b1 == -1) return null;

        boolean fin = (b0 & 0x80) != 0;
        int opcode = (b0 & 0x0F);
        boolean masked = (b1 & 0x80) != 0;
        long len = (b1 & 0x7F);

        if (len == 126) {
            int b2 = readByte(in);
            int b3 = readByte(in);
            len = ((b2 & 0xFF) << 8) | (b3 & 0xFF);
        } else if (len == 127) {
            long l = 0;
            for (int i = 0; i < 8; i++) {
                l = (l << 8) | (readByte(in) & 0xFF);
            }
            len = l;
        }

        if (len < 0 || len > Integer.MAX_VALUE) {
            throw new IOException("Frame too large: " + len);
        }

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }

        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (masked && mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }
        return new Frame(fin, opcode, payload);
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) throw new EOFException("EOF");
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r == -1) throw new EOFException("EOF");
            off += r;
        }
    }

    private static HttpResponse readHttpResponse(InputStream in) throws IOException {
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new EOFException("EOF");
            headerBuf.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = 0;
            if (headerBuf.size() > 32 * 1024) {
                throw new IOException("HTTP header too large.");
            }
        }
        String headerText = new String(headerBuf.toByteArray(), StandardCharsets.US_ASCII);
        return HttpResponse.parse(headerText);
    }

    private static String computeAccept(String secKey) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] dig = sha1.digest((secKey + WS_GUID).getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(dig);
    }

    private static String randomSecWebSocketKey() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static String urlEncode(String s) {
        // Minimal encode for tokens: encode space and % and ? and & and #.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (int j = 0; j < bytes.length; j++) {
                    sb.append('%');
                    String hx = Integer.toHexString(bytes[j] & 0xFF).toUpperCase(Locale.ROOT);
                    if (hx.length() == 1) sb.append('0');
                    sb.append(hx);
                }
            }
        }
        return sb.toString();
    }

    private HostPort parseHostPort(String hostPort) {
        if (hostPort == null) {
            throw new ConfigurationException("ConfigurationException! voiceAPIHOST is missing.");
        }
        String hp = hostPort.trim();
        if (hp.isEmpty()) {
            throw new ConfigurationException("ConfigurationException! voiceAPIHOST is empty.");
        }
        int colon = hp.lastIndexOf(':');
        if (colon <= 0 || colon == hp.length() - 1) {
            throw new ConfigurationException("ConfigurationException! voiceAPIHOST must be host:port, got: " + hp);
        }
        String host = hp.substring(0, colon).trim();
        String portStr = hp.substring(colon + 1).trim();
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            throw new ConfigurationException("ConfigurationException! invalid port in voiceAPIHOST: " + hp);
        }
        return new HostPort(host, port);
    }

    private void log(Level level, String msg) {
        if (logger != null) {
            logger.log(level, msg);
        }
    }

    private static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private static final class VoicePacket {
        final String username;
        final int seq;
        final int total;
        final byte[] opusPayload;

        VoicePacket(String username, int seq, int total, byte[] opusPayload) {
            this.username = username;
            this.seq = seq;
            this.total = total;
            this.opusPayload = opusPayload;
        }
    }

    private static final class HttpResponse {
        final int statusCode;
        final java.util.Map<String, String> headers;

        HttpResponse(int statusCode, java.util.Map<String, String> headers) {
            this.statusCode = statusCode;
            this.headers = headers;
        }

        String getHeader(String nameLower) {
            return headers.get(nameLower.toLowerCase(Locale.ROOT));
        }

        static HttpResponse parse(String headerText) throws IOException {
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0) {
                throw new IOException("Bad HTTP response.");
            }
            String status = lines[0];
            String[] parts = status.split(" ");
            if (parts.length < 2) {
                throw new IOException("Bad status line: " + status);
            }
            int code;
            try {
                code = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("Bad status code: " + status);
            }
            java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line == null || line.isEmpty()) continue;
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String k = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(c + 1).trim();
                headers.put(k, v);
            }
            return new HttpResponse(code, headers);
        }
    }
}

