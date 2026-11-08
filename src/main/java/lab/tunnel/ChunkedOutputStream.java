package lab.tunnel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes HTTP/1.1 chunked framing over a raw stream.
 *
 * One write() call becomes one or more chunks, and every write is flushed
 * immediately: JRMP writes a request and then blocks waiting for the reply,
 * so a buffered chunk that never leaves the process deadlocks both ends.
 */
public final class ChunkedOutputStream extends OutputStream {
    private static final int MAX_CHUNK = 32 * 1024;
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    private final OutputStream raw;
    private boolean closed;

    public ChunkedOutputStream(OutputStream raw) {
        this.raw = raw;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("chunked stream closed");
        }
        int pos = off;
        int end = off + len;
        while (pos < end) {
            int n = Math.min(MAX_CHUNK, end - pos);
            raw.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            raw.write(b, pos, n);
            raw.write(CRLF);
            pos += n;
        }
        raw.flush();
    }

    @Override
    public void flush() throws IOException {
        raw.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        raw.write(LAST_CHUNK);
        raw.flush();
        raw.close();
    }
}
