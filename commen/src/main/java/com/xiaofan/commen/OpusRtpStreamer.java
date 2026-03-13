package com.xiaofan.commen;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pure Java RTP streamer for Opus packets (no ffmpeg dependency).
 * Sends raw Opus frames directly to KOOK RTP endpoint.
 */
public final class OpusRtpStreamer implements VoiceSink {
    private final Logger logger;
    private final DatagramSocket socket;
    private final InetAddress destAddress;
    private final int destPort;
    private final int ssrc;
    private final int payloadType;
    private int sequenceNumber;
    private long timestamp;

    public OpusRtpStreamer(KookVoiceApiClient.JoinResult join, Logger logger) throws IOException {
        this.logger = logger;
        this.socket = new DatagramSocket();
        this.destAddress = InetAddress.getByName(join.ip);
        this.destPort = join.port;
        this.ssrc = join.audioSsrc;
        this.payloadType = join.audioPt;
        
        // Random initial values (RFC 3550)
        java.util.Random random = new java.util.Random();
        this.sequenceNumber = random.nextInt(0xFFFF);
        this.timestamp = random.nextInt() & 0xFFFFFFFFL;
        
        // Send a silent RTP packet immediately to punch through NAT/firewall
        // and let KOOK server bind our source IP:port
        sendSilentPunch();
        
        if (logger != null) {
            logger.info("[RTP] Started: dest=" + join.ip + ":" + join.port
                    + ", ssrc=" + ssrc + ", pt=" + payloadType
                    + ", localPort=" + socket.getLocalPort());
        }
    }

    /** Send a minimal silent Opus frame to punch through NAT and bind source address on KOOK server. */
    private void sendSilentPunch() throws IOException {
        // Minimal silent Opus frame (DTX/comfort noise, 1 byte: 0xF8 = silence)
        byte[] silentOpus = new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};
        byte[] rtpPacket = buildRtpPacket(silentOpus);
        DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length, destAddress, destPort);
        socket.send(packet);
        sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
        timestamp = (timestamp + 960) & 0xFFFFFFFFL;
    }

    @Override
    public synchronized void writeOpusFrame(String username, int chunkSeq, int chunkTotal, byte[] opusPayload) {
        if (opusPayload == null || opusPayload.length == 0) {
            return;
        }
        
        try {
            byte[] rtpPacket = buildRtpPacket(opusPayload);
            DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length, destAddress, destPort);
            socket.send(packet);
            
            // Increment after sending
            sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
            // Opus 20ms frame = 960 samples at 48kHz
            timestamp = (timestamp + 960) & 0xFFFFFFFFL;
        } catch (IOException e) {
            if (logger != null) {
                logger.log(Level.WARNING, "[RTP] Send error: " + e.getMessage());
            }
        }
    }

    @Override
    public synchronized void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (logger != null) {
            logger.info("[RTP] Stopped.");
        }
    }

    private byte[] buildRtpPacket(byte[] opusPayload) {
        // RTP header: 12 bytes
        // V=2, P=0, X=0, CC=0, M=0, PT=payloadType, Seq, Timestamp, SSRC
        byte[] packet = new byte[12 + opusPayload.length];
        
        // Byte 0: V(2) P(1) X(1) CC(4)
        packet[0] = (byte) 0x80;  // V=2, P=0, X=0, CC=0
        
        // Byte 1: M(1) PT(7)
        packet[1] = (byte) (payloadType & 0x7F);
        
        // Bytes 2-3: Sequence number (big-endian)
        packet[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        packet[3] = (byte) (sequenceNumber & 0xFF);
        
        // Bytes 4-7: Timestamp (big-endian)
        packet[4] = (byte) ((timestamp >> 24) & 0xFF);
        packet[5] = (byte) ((timestamp >> 16) & 0xFF);
        packet[6] = (byte) ((timestamp >> 8) & 0xFF);
        packet[7] = (byte) (timestamp & 0xFF);
        
        // Bytes 8-11: SSRC (big-endian)
        packet[8] = (byte) ((ssrc >> 24) & 0xFF);
        packet[9] = (byte) ((ssrc >> 16) & 0xFF);
        packet[10] = (byte) ((ssrc >> 8) & 0xFF);
        packet[11] = (byte) (ssrc & 0xFF);
        
        // Payload
        System.arraycopy(opusPayload, 0, packet, 12, opusPayload.length);
        
        return packet;
    }
}
