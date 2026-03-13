package com.xiaofan.commen;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receives raw PCM frames from multiple users, mixes them, and encodes to Opus for RTP streaming.
 * Used when compressionEncoder=false (SimpleCom sends raw PCM instead of Opus).
 */
public final class PcmMixingVoiceSink implements VoiceSink {
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 1;
    private static final int FRAME_SAMPLES = 960; // 20ms @ 48kHz
    private static final int BITRATE = 64000;

    private final VoiceSink delegate;
    private final Logger logger;

    // Per-user PCM queue (assuming input is already PCM, not Opus)
    private final Map<String, ArrayDeque<short[]>> userPcmQueues = new ConcurrentHashMap<>();

    // Mixing output encoder
    private final OpusEncoder encoder;

    private volatile boolean running;
    private Thread mixThread;
    private int outSeq;

    public PcmMixingVoiceSink(VoiceSink delegate, Logger logger) throws OpusException {
        this.delegate = delegate;
        this.logger = logger;
        this.encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP);
        this.encoder.setBitrate(BITRATE);
        this.outSeq = 0;
        this.running = true;

        // Start mixing thread
        this.mixThread = new Thread(this::mixingLoop, "PcmMixingVoiceSink-mix");
        this.mixThread.setDaemon(true);
        this.mixThread.start();

        if (logger != null) {
            logger.info("[PCM-Mix] Started: mixing raw PCM from multiple users, encoding to Opus.");
        }
    }

    @Override
    public synchronized void writeOpusFrame(String username, int chunkSeq, int chunkTotal, byte[] opusPayload) {
        if (opusPayload == null || opusPayload.length == 0) {
            return;
        }

        // Interpret opusPayload as raw PCM (16-bit signed, little-endian).
        // SimpleCom-core 可能会把多个 20ms PCM 帧拼到一个包里（例如 9600 字节 ≈ 4800 采样 ≈ 5 帧），
        // 这里需要按 FRAME_SAMPLES=960 拆成多帧，否则 mixingLoop 里 length!=960 的帧会被直接丢弃。
        short[] pcmAll = bytesToShorts(opusPayload);
        if (pcmAll == null || pcmAll.length == 0) {
            return;
        }

        ArrayDeque<short[]> q = userPcmQueues.computeIfAbsent(username, k -> new ArrayDeque<short[]>());
        int totalSamples = pcmAll.length;
        int offset = 0;
        while (offset + FRAME_SAMPLES <= totalSamples) {
            short[] frame = new short[FRAME_SAMPLES];
            System.arraycopy(pcmAll, offset, frame, 0, FRAME_SAMPLES);
            q.offer(frame);
            offset += FRAME_SAMPLES;
        }
        // 如果剩余不足一帧，为了避免节奏错位这里直接丢弃尾巴。
    }

    @Override
    public synchronized void close() {
        running = false;
        if (mixThread != null) {
            mixThread.interrupt();
        }
        if (logger != null) {
            logger.info("[PCM-Mix] Stopped.");
        }
    }

    private void mixingLoop() {
        byte[] opusBuf = new byte[4000];
        int loopCount = 0;
        
        while (running) {
            try {
                Thread.sleep(20); // 20ms tick
                loopCount++;

                // Collect one frame from each user
                short[] mixedPcm = new short[FRAME_SAMPLES];
                boolean hasAudio = false;

                for (Map.Entry<String, ArrayDeque<short[]>> entry : userPcmQueues.entrySet()) {
                    ArrayDeque<short[]> queue = entry.getValue();
                    short[] userFrame = queue.poll();
                    if (userFrame != null && userFrame.length == FRAME_SAMPLES) {
                        // Mix: add samples with clipping
                        for (int i = 0; i < FRAME_SAMPLES; i++) {
                            int sum = (int) mixedPcm[i] + (int) userFrame[i];
                            // Clip to [-32768, 32767]
                            if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE;
                            if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE;
                            mixedPcm[i] = (short) sum;
                        }
                        hasAudio = true;
                    }
                }

                // Encode and send
                if (hasAudio) {
                    try {
                        int len = encoder.encode(mixedPcm, 0, FRAME_SAMPLES, opusBuf, 0, opusBuf.length);
                        if (len > 0 && delegate != null) {
                            byte[] out = new byte[len];
                            System.arraycopy(opusBuf, 0, out, 0, len);
                            delegate.writeOpusFrame("mixed", outSeq++, -1, out);
                        }
                    } catch (Exception e) {
                        if (logger != null) {
                            logger.log(Level.WARNING, "[PCM-Mix] encode error: " + e.getMessage());
                        }
                    }
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private short[] bytesToShorts(byte[] data) {
        if (data == null || data.length % 2 != 0) {
            return null;
        }
        short[] shorts = new short[data.length / 2];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = (short) (((data[2 * i + 1] & 0xFF) << 8) | (data[2 * i] & 0xFF));
        }
        return shorts;
    }
}
