package lab.tunnel;

import java.io.IOException;
import java.io.InputStream;

/** Decodes HTTP/1.1 chunked framing from a raw stream. */
public final class ChunkedInputStream extends InputStream {
    private final InputStream raw;
    private int remaining;
    private boolean afterChunk;
    private boolean eof;

    public ChunkedInputStream(InputStream raw) {
        this.raw = raw;
    }

    @Override
    public int read() throws IOException {
        if (!ensure()) {
            return -1;
        }
        int b = raw.read();
        if (b < 0) {
            throw new IOException("tunnel closed mid-chunk");
        }
        remaining--;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!ensure()) {
            return -1;
        }
        int n = raw.read(b, off, Math.min(len, remaining));
        if (n < 0) {
            throw new IOException("tunnel closed mid-chunk");
        }
        remaining -= n;
        return n;
    }

    @Override
    public int available() throws IOException {
        return Math.min(remaining, raw.available());
    }

    /** Guarantees at least one payload byte is pending; false at end of stream. */
    private boolean ensure() throws IOException {
        if (eof) {
            return false;
        }
        if (remaining > 0) {
            return true;
        }
        if (afterChunk) {
            readCrlf();
            afterChunk = false;
        }
        int size = readChunkSize();
        if (size == 0) {
            eof = true;
            readTrailer();
            return false;
        }
        remaining = size;
        afterChunk = true;
        return true;
    }

    private int readChunkSize() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = raw.read();
            if (c < 0) {
                throw new IOException("tunnel closed while reading a chunk size");
            }
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > 16) {
                throw new IOException("chunk size line too long: " + sb);
            }
        }
        String line = sb.toString().trim();
        int semi = line.indexOf(';');
        if (semi >= 0) {
            line = line.substring(0, semi);
        }
        try {
            return Integer.parseInt(line, 16);
        } catch (NumberFormatException e) {
            throw new IOException("malformed chunk size: '" + line + "'");
        }
    }

    private void readCrlf() throws IOException {
        int c = raw.read();
        if (c == '\r') {
            c = raw.read();
        }
        if (c != '\n') {
            throw new IOException("missing CRLF after chunk data, saw " + c);
        }
    }

    private void readTrailer() throws IOException {
        while (true) {
            StringBuilder sb = new StringBuilder();
            while (true) {
                int c = raw.read();
                if (c < 0) {
                    return;
                }
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    sb.append((char) c);
                }
            }
            if (sb.length() == 0) {
                return;
            }
        }
    }
}
