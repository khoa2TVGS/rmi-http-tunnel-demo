package lab.common;

import java.io.Serializable;

/**
 * A reduced-colour image plus the palette it was reduced to. The palette is
 * what makes it a useful demo: the client can render the colours exactly
 * instead of guessing them back from the pixels.
 */
public record PaletteResult(byte[] png, int[] colors, int width, int height, long computeMillis)
        implements Serializable {}
