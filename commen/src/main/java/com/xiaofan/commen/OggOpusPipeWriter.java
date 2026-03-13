package com.xiaofan.commen;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes raw Opus frames to an OutputStream as Ogg Opus (so ffmpeg -f opus -i pipe:0 can read).
 * RFC 7845: OpusHead on first page (BOS), then one Ogg page per Opus packet.
 */
public final class OggOpusPipeWriter {
    private static final int SERIAL = 0x12345678;
    private static final int SAMPLES_PER_FRAME_20MS = 960; // 48 kHz
    
    // Ogg CRC lookup table (polynomial 0x04C11DB7)
    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                if ((r & 0x80000000) != 0) {
                    r = (r << 1) ^ 0x04C11DB7;
                } else {
                    r <<= 1;
                }
            }
            CRC_TABLE[i] = r;
        }
    }

    private final OutputStream out;
    private int pageSequence;
    private long granulePosition;

    public OggOpusPipeWriter(OutputStream out) throws IOException {
        this.out = out;
        this.pageSequence = 0;
        this.granulePosition = 0;
        writeOpusHeadPage();
        writeOpusTagsPage();
    }

    private void writeOpusTagsPage() throws IOException {
        byte[] vendor = "SimpleCom".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int tagCount = 0;
        byte[] tags = new byte[8 + 4 + vendor.length + 4];
        tags[0] = 'O';
        tags[1] = 'p';
        tags[2] = 'u';
        tags[3] = 's';
        tags[4] = 'T';
        tags[5] = 'a';
        tags[6] = 'g';
        tags[7] = 's';
        tags[8] = (byte) (vendor.length & 0xFF);
        tags[9] = (byte) ((vendor.length >> 8) & 0xFF);
        tags[10] = (byte) ((vendor.length >> 16) & 0xFF);
        tags[11] = (byte) ((vendor.length >> 24) & 0xFF);
        System.arraycopy(vendor, 0, tags, 12, vendor.length);
        int off = 12 + vendor.length;
        tags[off++] = (byte) (tagCount & 0xFF);
        tags[off++] = (byte) ((tagCount >> 8) & 0xFF);
        tags[off++] = (byte) ((tagCount >> 16) & 0xFF);
        tags[off++] = (byte) ((tagCount >> 24) & 0xFF);
        writeOggPage(tags, false, false);
    }

    /** Write one raw Opus payload as a single Ogg page (one packet per page). */
    public synchronized void writeOpusFrame(byte[] opusPayload) throws IOException {
        if (opusPayload == null || opusPayload.length == 0) {
            return;
        }
        granulePosition += SAMPLES_PER_FRAME_20MS;
        writeOggPage(opusPayload, false, false);
    }

    public synchronized void close() throws IOException {
        // EOS page with no payload
        writeOggPage(new byte[0], false, true);
        out.flush();
    }

    private void writeOpusHeadPage() throws IOException {
        // OpusHead: "OpusHead" (8) + version 1 + channels 1 + pre-skip (2 LE) + rate (4 LE) + gain (2) + mapping 0
        byte[] head = new byte[19];
        head[0] = 'O';
        head[1] = 'p';
        head[2] = 'u';
        head[3] = 's';
        head[4] = 'H';
        head[5] = 'e';
        head[6] = 'a';
        head[7] = 'd';
        head[8] = 1;   // version
        head[9] = 1;   // channel count (mono)
        head[10] = (byte) (312 & 0xFF);   // pre-skip low (e.g. 312)
        head[11] = (byte) ((312 >> 8) & 0xFF);
        head[12] = (byte) (48000 & 0xFF);
        head[13] = (byte) ((48000 >> 8) & 0xFF);
        head[14] = (byte) ((48000 >> 16) & 0xFF);
        head[15] = (byte) ((48000 >> 24) & 0xFF);
        head[16] = 0;  // output gain low
        head[17] = 0;  // output gain high
        head[18] = 0;  // mapping family
        writeOggPage(head, true, false);
    }

    private void writeOggPage(byte[] payload, boolean bos, boolean eos) throws IOException {
        int segCount = (payload.length + 255) / 255;
        if (payload.length == 0) {
            segCount = 1;
        }
        int headerLen = 27 + segCount;
        int totalLen = headerLen + payload.length;

        byte[] header = new byte[headerLen];
        header[0] = 'O';
        header[1] = 'g';
        header[2] = 'g';
        header[3] = 'S';
        header[4] = 0;
        header[5] = (byte) ((bos ? 2 : 0) | (eos ? 4 : 0));
        // granule (6-13) LE
        long gp = bos || eos ? 0 : granulePosition;
        for (int i = 0; i < 8; i++) {
            header[6 + i] = (byte) (gp & 0xFF);
            gp >>= 8;
        }
        // serial (14-17)
        header[14] = (byte) (SERIAL & 0xFF);
        header[15] = (byte) ((SERIAL >> 8) & 0xFF);
        header[16] = (byte) ((SERIAL >> 16) & 0xFF);
        header[17] = (byte) ((SERIAL >> 24) & 0xFF);
        // sequence (18-21)
        header[18] = (byte) (pageSequence & 0xFF);
        header[19] = (byte) ((pageSequence >> 8) & 0xFF);
        header[20] = (byte) ((pageSequence >> 16) & 0xFF);
        header[21] = (byte) ((pageSequence >> 24) & 0xFF);
        pageSequence++;
        // CRC (22-25) filled below
        header[26] = (byte) segCount;
        int off = 27;
        int rem = payload.length;
        while (rem > 255) {
            header[off++] = (byte) 255;
            rem -= 255;
        }
        header[off] = (byte) rem;
        header[22] = header[23] = header[24] = header[25] = 0;

        int crc = oggCrc32(header, 0, header.length, 0);
        crc = oggCrc32(payload, 0, payload.length, crc);
        header[22] = (byte) (crc & 0xFF);
        header[23] = (byte) ((crc >> 8) & 0xFF);
        header[24] = (byte) ((crc >> 16) & 0xFF);
        header[25] = (byte) ((crc >> 24) & 0xFF);

        out.write(header);
        out.write(payload);
    }
    
    private static int oggCrc32(byte[] data, int offset, int length, int crc) {
        for (int i = 0; i < length; i++) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) & 0xFF) ^ (data[offset + i] & 0xFF)];
        }
        return crc;
    }
}
