package lab.common;

import java.io.Serializable;

/**
 * Everything the caller needs to verify one round trip:
 * the rendered PNG, its dimensions, how long the server spent computing it,
 * and hashes of both the bytes the server received and the bytes it produced.
 */
public record ImageResult(byte[] png,
                          int width,
                          int height,
                          long computeMillis,
                          String sourceSha256,
                          String pngSha256) implements Serializable {
}
