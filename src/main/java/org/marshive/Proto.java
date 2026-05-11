package org.marshive;

import java.io.*;

public final class Proto {
    private Proto() {}

    public static int readU8(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    public static int readIntBE(InputStream in) throws IOException {
        int b1 = readU8(in), b2 = readU8(in), b3 = readU8(in), b4 = readU8(in);
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    public static int readU16BE(InputStream in) throws IOException {
        int hi = readU8(in);
        int lo = readU8(in);
        return (hi << 8) | lo;
    }

    public static void writeIntBE(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public static void writeU16BE(OutputStream out, int v) throws IOException {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    public static void sendFrame(OutputStream out, byte type, byte[] payload) throws IOException {
        int len = payload == null ? 0 : payload.length;
        if (len > 65535) throw new IOException("payload too large: " + len);

        out.write(type);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        if (len > 0) out.write(payload);
        out.flush();
    }
}
