package com.xiaofan.commen;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class KookVoiceApiClient {
    private static final String BASE_URL = "https://www.kookapp.cn/api";
    private static final String JOIN_PATH = "/v3/voice/join";
    private static final String KEEP_ALIVE_PATH = "/v3/voice/keep-alive";
    private static final String LEAVE_PATH = "/v3/voice/leave";

    private KookVoiceApiClient() {}

    public static final class JoinResult {
        public final String ip;
        public final int port;
        public final boolean rtcpMux;
        public final Integer rtcpPort;
        public final Integer bitrate;
        public final int audioSsrc;
        public final int audioPt;

        public JoinResult(String ip, int port, boolean rtcpMux, Integer rtcpPort, Integer bitrate, int audioSsrc, int audioPt) {
            this.ip = ip;
            this.port = port;
            this.rtcpMux = rtcpMux;
            this.rtcpPort = rtcpPort;
            this.bitrate = bitrate;
            this.audioSsrc = audioSsrc;
            this.audioPt = audioPt;
        }
    }

    public static JoinResult joinVoiceChannel(String botToken, String channelId, Logger logger) {
        if (isBlank(botToken)) {
            throw new ConfigurationException("ConfigurationException! KOOKBOTtoken is empty.");
        }
        if (isBlank(channelId)) {
            throw new ConfigurationException("ConfigurationException! channel_id is empty.");
        }
        if (logger == null) {
            logger = Logger.getLogger("KookVoiceApiClient");
        }

        String urlStr = BASE_URL + JOIN_PATH;
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String auth = "Bot " + botToken.trim();
            conn.setRequestProperty("Authorization", auth);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");

            String body = "channel_id=" + URLEncoder.encode(channelId.trim(), "UTF-8")
                    + "&rtcp_mux=0";  // 0=separate RTP and RTCP ports
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();

            int code = conn.getResponseCode();
            String resp = readBody(conn, code);

            logger.info("[KOOK] POST " + urlStr + " -> HTTP " + code);

            if (code != 200) {
                logger.warning("[KOOK] Non-200 response: " + code + ", body=" + resp);
                return null;
            }

            if (resp == null) {
                logger.warning("[KOOK] Empty response body.");
                return null;
            }

            resp = resp.trim();
            logger.info("[KOOK] Raw response: " + resp);

            Integer topCode = parseJsonInt(resp, "code");
            if (topCode == null || topCode.intValue() != 0) {
                logger.warning("[KOOK] API returned non-zero code: " + topCode);
                return null;
            }

            String ip = parseJsonStringInData(resp, "ip");
            Integer port = parseJsonIntInData(resp, "port");
            Boolean rtcpMux = parseJsonBooleanInData(resp, "rtcp_mux");
            Integer rtcpPort = parseJsonIntInData(resp, "rtcp_port");
            Integer bitrate = parseJsonIntInData(resp, "bitrate");
            Integer audioSsrc = parseJsonIntInData(resp, "audio_ssrc");
            Integer audioPt = parseJsonIntInData(resp, "audio_pt");

            logger.info("[KOOK] voice/join result: ip=" + ip
                    + ", port=" + port
                    + ", rtcp_mux=" + rtcpMux
                    + ", rtcp_port=" + rtcpPort
                    + ", bitrate=" + bitrate
                    + ", audio_ssrc=" + audioSsrc
                    + ", audio_pt=" + audioPt);

            if (ip == null || port == null || audioSsrc == null || audioPt == null) {
                logger.warning("[KOOK] Missing required fields in voice/join response.");
                return null;
            }
            boolean mux = rtcpMux != null && rtcpMux.booleanValue();
            return new JoinResult(ip, port.intValue(), mux, rtcpPort, bitrate, audioSsrc.intValue(), audioPt.intValue());
        } catch (IOException e) {
            logger.log(Level.WARNING, "[KOOK] HTTP error: " + e.getMessage(), e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 调用 KOOK /voice/keep-alive 接口，保持语音连接活跃。
     *
     * @return true 表示接口返回 code=0，false 表示失败（包括 HTTP 非 200 或业务 code!=0）
     */
    public static boolean keepAlive(String botToken, String channelId, Logger logger) {
        if (isBlank(botToken)) {
            throw new ConfigurationException("ConfigurationException! KOOKBOTtoken is empty.");
        }
        if (isBlank(channelId)) {
            throw new ConfigurationException("ConfigurationException! channel_id is empty.");
        }
        if (logger == null) {
            logger = Logger.getLogger("KookVoiceApiClient");
        }

        String urlStr = BASE_URL + KEEP_ALIVE_PATH;
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String auth = "Bot " + botToken.trim();
            conn.setRequestProperty("Authorization", auth);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");

            String body = "channel_id=" + URLEncoder.encode(channelId.trim(), "UTF-8");
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();

            int code = conn.getResponseCode();
            String resp = readBody(conn, code);

            logger.info("[KOOK] POST " + urlStr + " -> HTTP " + code);

            if (code != 200) {
                logger.warning("[KOOK] keep-alive non-200 response: " + code + ", body=" + resp);
                return false;
            }
            if (resp == null) {
                logger.warning("[KOOK] keep-alive empty response body.");
                return false;
            }

            resp = resp.trim();
            logger.info("[KOOK] keep-alive raw response: " + resp);

            Integer topCode = parseJsonInt(resp, "code");
            if (topCode == null || topCode.intValue() != 0) {
                logger.warning("[KOOK] keep-alive API returned non-zero code: " + topCode);
                return false;
            }
            return true;
        } catch (IOException e) {
            logger.log(Level.WARNING, "[KOOK] keep-alive HTTP error: " + e.getMessage(), e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 调用 KOOK /voice/leave 接口，让机器人离开语音频道。
     *
     * @return true 表示接口返回 code=0，false 表示失败（包括 HTTP 非 200 或业务 code!=0）
     */
    public static boolean leaveChannel(String botToken, String channelId, Logger logger) {
        if (isBlank(botToken)) {
            throw new ConfigurationException("ConfigurationException! KOOKBOTtoken is empty.");
        }
        if (isBlank(channelId)) {
            throw new ConfigurationException("ConfigurationException! channel_id is empty.");
        }
        if (logger == null) {
            logger = Logger.getLogger("KookVoiceApiClient");
        }

        String urlStr = BASE_URL + LEAVE_PATH;
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String auth = "Bot " + botToken.trim();
            conn.setRequestProperty("Authorization", auth);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");

            String body = "channel_id=" + URLEncoder.encode(channelId.trim(), "UTF-8");
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();

            int code = conn.getResponseCode();
            String resp = readBody(conn, code);

            logger.info("[KOOK] POST " + urlStr + " -> HTTP " + code);

            if (code != 200) {
                logger.warning("[KOOK] leave non-200 response: " + code + ", body=" + resp);
                return false;
            }
            if (resp == null) {
                logger.warning("[KOOK] leave empty response body.");
                return false;
            }

            resp = resp.trim();
            logger.info("[KOOK] leave raw response: " + resp);

            Integer topCode = parseJsonInt(resp, "code");
            if (topCode == null || topCode.intValue() != 0) {
                logger.warning("[KOOK] leave API returned non-zero code: " + topCode);
                return false;
            }
            return true;
        } catch (IOException e) {
            logger.log(Level.WARNING, "[KOOK] leave HTTP error: " + e.getMessage(), e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readBody(HttpsURLConnection conn, int code) throws IOException {
        InputStream is;
        if (code >= 200 && code < 400) {
            is = conn.getInputStream();
        } else {
            is = conn.getErrorStream();
            if (is == null) {
                return null;
            }
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Integer parseJsonInt(String json, String key) {
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
            return Integer.valueOf(json.substring(start, p));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseJsonIntInData(String json, String key) {
        String data = extractDataObject(json);
        if (data == null) return null;
        return parseJsonInt(data, key);
    }

    private static String parseJsonStringInData(String json, String key) {
        String data = extractDataObject(json);
        if (data == null) return null;
        int i = data.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int c = data.indexOf(':', i);
        if (c < 0) return null;
        int p = c + 1;
        while (p < data.length() && Character.isWhitespace(data.charAt(p))) p++;
        if (p >= data.length() || data.charAt(p) != '\"') return null;
        int start = ++p;
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        while (p < data.length()) {
            char ch = data.charAt(p);
            if (escape) {
                sb.append(ch);
                escape = false;
            } else if (ch == '\\') {
                escape = true;
            } else if (ch == '\"') {
                break;
            } else {
                sb.append(ch);
            }
            p++;
        }
        return sb.toString();
    }

    private static Boolean parseJsonBooleanInData(String json, String key) {
        String data = extractDataObject(json);
        if (data == null) return null;
        int i = data.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int c = data.indexOf(':', i);
        if (c < 0) return null;
        int p = c + 1;
        while (p < data.length() && Character.isWhitespace(data.charAt(p))) p++;
        String tail = data.substring(p).toLowerCase(Locale.ROOT);
        if (tail.startsWith("true")) return Boolean.TRUE;
        if (tail.startsWith("false")) return Boolean.FALSE;
        return null;
    }

    private static String extractDataObject(String json) {
        int i = json.indexOf("\"data\"");
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        if (c < 0) return null;
        int p = c + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '{') return null;
        int depth = 0;
        int start = p;
        while (p < json.length()) {
            char ch = json.charAt(p);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, p + 1);
                }
            }
            p++;
        }
        return null;
    }
}

