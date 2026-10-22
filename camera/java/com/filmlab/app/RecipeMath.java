// SPDX-License-Identifier: MIT
package com.filmlab.app;

/** Sony coefficients: 3x3 matrix in units of 1/1024 and 1024 little-endian 10-bit samples. */
public final class RecipeMath {
    public static final int MATRIX_ONE = 1024;
    private RecipeMath() { }

    /** Blend color independently from the tone curve. Never mutates the supplied profile. */
    public static int[] matrix(int[] base, int color) {
        if (base == null || base.length != 9) throw new IllegalArgumentException("matrix_size");
        range(color, 0, 100);
        int[] result = new int[9];
        for (int i = 0; i < 9; i++) {
            if (base[i] < -32768 || base[i] > 32767) throw new IllegalArgumentException("matrix_range");
            int identity = i % 4 == 0 ? MATRIX_ONE : 0;
            result[i] = (int) Math.round(identity + (base[i] - identity) * (color / 100.0));
        }
        // Preserve a neutral source row exactly; deliberately tinted rows retain
        // their interpolated tint, including the exact original 100% endpoint.
        for (int row = 0; row < 3; row++) {
            int start = row * 3;
            if (base[start] + base[start + 1] + base[start + 2] == MATRIX_ONE)
                result[start + row] += MATRIX_ONE - result[start] - result[start + 1] - result[start + 2];
        }
        return result;
    }

    /**
     * Tone blends the exact source curve with identity. Shadow/highlight controls add
     * smooth x(1-x)^2 and x^2(1-x) terms. With coefficients bounded to +/-0.42,
     * their combined derivative stays positive; black/white endpoints are fixed.
     */
    public static byte[] gamma(byte[] base, int tone, int shadows, int highlights) {
        if (base == null || base.length != 2048) throw new IllegalArgumentException("gamma_size");
        range(tone, 0, 100); range(shadows, -3, 3); range(highlights, -3, 3);
        byte[] result = new byte[2048];
        int previousSource = -1, previous = -1;
        for (int i = 0; i < 1024; i++) {
            int source = (base[2 * i] & 255) | ((base[2 * i + 1] & 255) << 8);
            if (source > 1023 || source < previousSource) throw new IllegalArgumentException("gamma_range");
            previousSource = source;
            double value = (i + (source - i) * (tone / 100.0)) / 1023.0;
            double inverse = 1.0 - value;
            value += 0.14 * shadows * value * inverse * inverse
                + 0.14 * highlights * value * value * inverse;
            int output = (int) Math.round(value * 1023.0);
            output = Math.max(previous, Math.max(0, Math.min(1023, output)));
            result[2 * i] = (byte) (output & 255);
            result[2 * i + 1] = (byte) (output >> 8);
            previous = output;
        }
        return result;
    }

    private static void range(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException("setting_range");
    }
}
