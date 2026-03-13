package com.xiaofan.commen;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RtpOpusSender implements VoiceSink {
    private final Logger logger;
    private final InetSocketAddress target;
    private final int ssrc;
    private final int payloadType;
    private DatagramSocket socket;
    private int rtpSeq = 0;
    private int timestamp = 0;

    public RtpOpusSender(String ip, int port, int ssrc, int payloadType, Logger logger) {
        this.logger = logger;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            this.target = new InetSocketAddress(addr, port);
        } catch (Exception e) {
            throw new ConfigurationException("ConfigurationException! invalid KOOK ip/port: " + ip + ":" + port);
        }
        this.ssrc = ssrc;
        this.payloadType = payloadType & 0x7F;
    }

    public synchronized void close() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    @Override
    public void writeOpusFrame(String username, int chunkSeq, int chunkTotal, byte[] opusPayload) {
        sendVoicePacket(username, chunkSeq, chunkTotal, opusPayload);
    }

    public synchronized void sendVoicePacket(String username, int chunkSeq, int chunkTotal, byte[] opusPayload) {
        if (opusPayload == null || opusPayload.length == 0) {
            return;
        }
        try {
            if (socket == null || socket.isClosed()) {
                socket = new DatagramSocket();
            }
            byte[] packet = buildRtpPacket(opusPayload);
            DatagramPacket dp = new DatagramPacket(packet, packet.length, target);
            socket.send(dp);
            if (logger != null) {
                logger.info("[RTP] sent packet: user=" + username
                        + ", chunk=" + chunkSeq + "/" + chunkTotal
                        + ", len=" + opusPayload.length);
            }
        } catch (SocketException e) {
            if (logger != null) {
                logger.log(Level.WARNING, "[RTP] socket error: " + e.getMessage(), e);
            }
        } catch (IOException e) {
            if (logger != null) {
                logger.log(Level.WARNING, "[RTP] send error: " + e.getMessage(), e);
            }
        }
    }

    private byte[] buildRtpPacket(byte[] payload) {
        byte[] header = new byte[12];
        // V=2, P=0, X=0, CC=0
        header[0] = (byte) 0x80;
        // M=0, PT=payloadType
        header[1] = (byte) (payloadType & 0x7F);

        int seq = rtpSeq & 0xFFFF;
        header[2] = (byte) ((seq >>> 8) & 0xFF);
        header[3] = (byte) (seq & 0xFF);
        rtpSeq = (rtpSeq + 1) & 0xFFFF;

        // Opus RTP timestamp: 48000 Hz, assume ~20ms per packet => +960
        timestamp += 960;
        header[4] = (byte) ((timestamp >>> 24) & 0xFF);
        header[5] = (byte) ((timestamp >>> 16) & 0xFF);
        header[6] = (byte) ((timestamp >>> 8) & 0xFF);
        header[7] = (byte) (timestamp & 0xFF);

        header[8] = (byte) ((ssrc >>> 24) & 0xFF);
        header[9] = (byte) ((ssrc >>> 16) & 0xFF);
        header[10] = (byte) ((ssrc >>> 8) & 0xFF);
        header[11] = (byte) (ssrc & 0xFF);

        byte[] packet = new byte[header.length + payload.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(payload, 0, packet, header.length, payload.length);
        return packet;
    }
}

