package com.xiaofan.commen;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 将来自多个用户的 Opus 帧解码为 PCM，进行混音后，再重新编码为单路 Opus 流输出到下游 VoiceSink。
 *
 * 设计目标：
 * - 解决多人同时说话时简单“帧串联”导致的乱序/失真问题。
 * - 使用 48kHz, mono, 20ms 帧（960 samples），与 SimpleCom-core / KOOK 要求一致。
 *
 * 流程：
 *  - writeOpusFrame(username, ...) 被调用时：
 *      1. 按用户名获取解码器，将单帧 Opus 解码成 960 个样本的 PCM。
 *      2. 将 PCM 帧放入该用户的待混音队列。
 *  - 后台混音线程每 20ms tick：
 *      1. 从所有用户队列各取出一帧（没有则视为静音）。
 *      2. 对同一时间片上的各用户 PCM 做逐样本相加并限幅。
 *      3. 用单一编码器编码混合后的 PCM，作为一个 Opus 帧传给下游 VoiceSink。
 */
public final class OpusMixingVoiceSink implements VoiceSink {
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 1;
    private static final int FRAME_SAMPLES = 960; // 20ms @ 48kHz
    private static final int BITRATE = 64000;

    private final VoiceSink delegate;
    private final Logger logger;

    // 每个用户一个解码器和 PCM 队列
    private final Map<String, OpusDecoder> decoders = new ConcurrentHashMap<String, OpusDecoder>();
    private final Map<String, ArrayDeque<short[]>> userPcmQueues = new ConcurrentHashMap<String, ArrayDeque<short[]>>();

    // 混音输出编码器
    private final OpusEncoder encoder;

    private volatile boolean running;
    private Thread mixThread;
    private int outSeq;

    public OpusMixingVoiceSink(VoiceSink delegate, Logger logger) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate VoiceSink is null");
        }
        this.delegate = delegate;
        this.logger = logger;
        OpusEncoder enc;
        try {
            enc = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP);
            enc.setBitrate(BITRATE);
            enc.setSignalType(io.github.jaredmdobson.concentus.OpusSignal.OPUS_SIGNAL_VOICE);
            enc.setComplexity(5);
        } catch (OpusException e) {
            // 如果编码器初始化失败，就抛出运行时异常，防止静默失败
            throw new RuntimeException("Failed to init Opus encoder", e);
        }
        this.encoder = enc;

        startMixThread();
    }

    private void startMixThread() {
        running = true;
        mixThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runMixLoop();
            }
        }, "OpusMixingVoiceSink-Mix");
        mixThread.setDaemon(true);
        mixThread.start();
    }

    private OpusDecoder getDecoder(String username) throws OpusException {
        OpusDecoder existing = decoders.get(username);
        if (existing != null) {
            return existing;
        }
        OpusDecoder dec = new OpusDecoder(SAMPLE_RATE, CHANNELS);
        decoders.put(username, dec);
        return dec;
    }

    private ArrayDeque<short[]> getQueue(String username) {
        ArrayDeque<short[]> q = userPcmQueues.get(username);
        if (q != null) {
            return q;
        }
        q = new ArrayDeque<short[]>();
        userPcmQueues.put(username, q);
        return q;
    }

    @Override
    public void writeOpusFrame(String username, int chunkSeq, int chunkTotal, byte[] opusPayload) {
        if (opusPayload == null || opusPayload.length == 0) {
            return;
        }
        if (username == null) {
            username = "";
        }
        try {
            OpusDecoder decoder = getDecoder(username);
            short[] pcm = new short[FRAME_SAMPLES];
            int decoded = decoder.decode(opusPayload, 0, opusPayload.length, pcm, 0, FRAME_SAMPLES, false);
            if (decoded <= 0) {
                return;
            }
            // 如果少于一整帧，剩余部分填充静音
            if (decoded < FRAME_SAMPLES) {
                for (int i = decoded; i < FRAME_SAMPLES; i++) {
                    pcm[i] = 0;
                }
            }
            ArrayDeque<short[]> q = getQueue(username);
            synchronized (q) {
                q.addLast(pcm);
            }
        } catch (OpusException e) {
            if (logger != null) {
                logger.log(Level.WARNING, "[Mixer] Opus decode error for user " + username + ": " + e.getMessage());
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.log(Level.WARNING, "[Mixer] Unexpected error in writeOpusFrame: " + e.getMessage());
            }
        }
    }

    private void runMixLoop() {
        final long frameDurationMs = 20L;
        while (running) {
            long start = System.currentTimeMillis();
            try {
                mixOneFrame();
            } catch (Exception e) {
                if (logger != null) {
                    logger.log(Level.WARNING, "[Mixer] mixOneFrame error: " + e.getMessage(), e);
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            long sleepMs = frameDurationMs - elapsed;
            if (sleepMs < 5L) {
                sleepMs = 5L;
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ignored) {
                // ignore
            }
        }
    }

    /**
     * 从所有用户取出一帧 PCM 做混音并输出。
     * 如果当前没有任何用户提供帧，则不输出（保持静音，不发送空帧）。
     */
    private void mixOneFrame() {
        // snapshot queues to avoid长时间持有全局锁
        Map<String, ArrayDeque<short[]>> snapshot = new HashMap<String, ArrayDeque<short[]>>(userPcmQueues);
        if (snapshot.isEmpty()) {
            return;
        }

        short[] mixed = new short[FRAME_SAMPLES];
        boolean hasAny = false;

        for (Map.Entry<String, ArrayDeque<short[]>> e : snapshot.entrySet()) {
            ArrayDeque<short[]> q = e.getValue();
            short[] frame = null;
            synchronized (q) {
                frame = q.pollFirst();
            }
            if (frame == null) {
                continue;
            }
            hasAny = true;
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                int sum = mixed[i] + frame[i];
                if (sum > Short.MAX_VALUE) {
                    sum = Short.MAX_VALUE;
                } else if (sum < Short.MIN_VALUE) {
                    sum = Short.MIN_VALUE;
                }
                mixed[i] = (short) sum;
            }
        }

        if (!hasAny) {
            return;
        }

        try {
            byte[] opusBuf = new byte[4000];
            int len = encoder.encode(mixed, 0, FRAME_SAMPLES, opusBuf, 0, opusBuf.length);
            if (len <= 0) {
                return;
            }
            byte[] out = new byte[len];
            System.arraycopy(opusBuf, 0, out, 0, len);
            int seq = ++outSeq;
            delegate.writeOpusFrame("mixed", seq, 0, out);
        } catch (OpusException e) {
            if (logger != null) {
                logger.log(Level.WARNING, "[Mixer] Opus encode error: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (mixThread != null) {
            mixThread.interrupt();
        }
        if (delegate != null) {
            delegate.close();
        }
    }
}

