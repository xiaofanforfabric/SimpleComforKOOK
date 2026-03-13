package com.xiaofan.commen;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts ffmpeg with Ogg Opus on pipe:0 and tee output to KOOK RTP.
 * Command shape: ffmpeg -f opus -i pipe:0 -acodec copy -ab &lt;bitrate&gt;k -ac 1 -ar 48000
 *   -f tee '[select=a:f=rtp:ssrc=SSRC:payload_type=PT]rtp://IP:PORT?rtcpport=RTCPPORT'
 * (omit ?rtcpport when rtcp_mux is true)
 */
public final class FfmpegRtpStreamer implements VoiceSink {
    private final Logger logger;
    private final Process process;
    private final OggOpusPipeWriter oggWriter;

    /**
     * @param join       KOOK voice/join result
     * @param logger     optional logger
     * @param ffmpegPath path to ffmpeg executable (if null/empty, uses "ffmpeg" or "ffmpeg.exe" from PATH)
     */
    public FfmpegRtpStreamer(KookVoiceApiClient.JoinResult join, Logger logger, String ffmpegPath) throws IOException {
        this.logger = logger;
        List<String> cmd = buildFfmpegCommand(join, ffmpegPath);
        if (logger != null) {
            logger.info("[ffmpeg] Starting: " + String.join(" ", cmd));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        this.process = pb.start();
        
        // Consume stderr in background so process doesn't block
        Thread errReader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buf = new byte[512];
                    int r;
                    while ((r = process.getInputStream().read(buf)) != -1) {
                        if (logger != null && r > 0) {
                            String line = new String(buf, 0, r).trim();
                            if (!line.isEmpty()) {
                                logger.warning("[ffmpeg stderr] " + line);
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }, "FfmpegRtpStreamer-err");
        errReader.setDaemon(true);
        errReader.start();

        // Wait a bit to check if process died immediately
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        
        if (!process.isAlive()) {
            int exitCode = process.exitValue();
            throw new IOException("ffmpeg process died immediately with exit code: " + exitCode);
        }

        OutputStream stdin = process.getOutputStream();
        this.oggWriter = new OggOpusPipeWriter(stdin);
        if (logger != null) {
            logger.info("[ffmpeg] Process started successfully (PID check passed).");
        }
    }

    @Override
    public void writeOpusFrame(String username, int chunkSeq, int chunkTotal, byte[] opusPayload) {
        if (opusPayload == null || opusPayload.length == 0) {
            return;
        }
        try {
            if (!process.isAlive()) {
                if (logger != null) {
                    logger.warning("[ffmpeg] Process is dead, cannot write frame.");
                }
                return;
            }
            oggWriter.writeOpusFrame(opusPayload);
            
            // When chunkTotal=0, it means this is the last frame of the voice segment
            // Flush to ensure ffmpeg processes and sends the data immediately
            if (chunkTotal == 0) {
                process.getOutputStream().flush();
                if (logger != null) {
                    logger.info("[ffmpeg] Voice segment complete (user=" + username + ", seq=" + chunkSeq + "), flushed to KOOK.");
                }
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.log(Level.WARNING, "[ffmpeg] write error: " + e.getMessage());
            }
        }
    }

    @Override
    public synchronized void close() {
        try {
            oggWriter.close();
        } catch (IOException e) {
            if (logger != null) {
                logger.log(Level.WARNING, "[ffmpeg] close writer: " + e.getMessage());
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        if (logger != null) {
            logger.info("[ffmpeg] Stopped.");
        }
    }

    private static List<String> buildFfmpegCommand(KookVoiceApiClient.JoinResult join, String ffmpegPath) {
        int bitrateK = (join.bitrate != null && join.bitrate > 0) ? (join.bitrate / 1000) : 48;
        String rtpUrl = "rtp://" + join.ip + ":" + join.port;
        if (!join.rtcpMux && join.rtcpPort != null) {
            rtpUrl += "?rtcpport=" + join.rtcpPort;
        }

        List<String> cmd = new ArrayList<String>();
        String ffmpeg = (ffmpegPath != null && !ffmpegPath.trim().isEmpty())
                ? ffmpegPath.trim()
                : (isWindows() ? "ffmpeg.exe" : "ffmpeg");
        cmd.add(ffmpeg);
        cmd.add("-re");  // Read input at native frame rate
        cmd.add("-i");
        cmd.add("pipe:0");
        cmd.add("-map");
        cmd.add("0:a:0");
        cmd.add("-acodec");
        cmd.add("libopus");
        cmd.add("-ab");
        cmd.add(bitrateK + "k");
        cmd.add("-ac");
        cmd.add("2");  // Stereo (KOOK requirement)
        cmd.add("-ar");
        cmd.add("48000");
        cmd.add("-f");
        cmd.add("tee");
        cmd.add("[select=a:f=rtp:ssrc=" + join.audioSsrc + ":payload_type=" + join.audioPt + "]" + rtpUrl);
        return cmd;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}
