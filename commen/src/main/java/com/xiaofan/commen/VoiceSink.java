package com.xiaofan.commen;

/**
 * Sink for voice Opus payloads (e.g. send to KOOK via RTP or ffmpeg).
 */
public interface VoiceSink {
    void writeOpusFrame(String username, int chunkSeq, int chunkTotal, byte[] opusPayload);
    void close();
}
