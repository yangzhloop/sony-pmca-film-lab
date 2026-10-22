package com.filmlab.diagnostics;

import android.view.KeyEvent;

/**
 * Host tests for the menu key translator, not Android/camera integration tests.
 * Run in two fresh JVMs so CameraKeys initializes with and without ScalarInput.
 */
public final class CameraKeysTest {
    private static int assertions;

    private CameraKeysTest() {}

    private static void mapping(int scan, int androidKey, int expected) {
        int actual = CameraKeys.navigation(new KeyEvent(scan, androidKey));
        if (actual != expected) {
            throw new AssertionError("scan=" + scan + " key=" + androidKey
                    + " expected=" + expected + " actual=" + actual);
        }
        assertions++;
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1 || !("fallback".equals(arguments[0])
                || "reflected".equals(arguments[0]))) {
            throw new IllegalArgumentException("Expected fallback or reflected");
        }
        boolean reflected = "reflected".equals(arguments[0]);
        /* The reflected fixture deliberately differs from the fallback table,
           proving that runtime constants are read instead of merely present. */
        int offset = reflected ? 1000 : 0;

        mapping(103 + offset, 0, CameraKeys.UP);
        mapping(608 + offset, 0, CameraKeys.UP);
        mapping(108 + offset, 0, CameraKeys.DOWN);
        mapping(105 + offset, 0, CameraKeys.LEFT);
        mapping(106 + offset, 0, CameraKeys.RIGHT);
        mapping(232 + offset, 0, CameraKeys.CENTER);
        mapping(514 + offset, 0, CameraKeys.MENU);
        mapping(229 + offset, 0, CameraKeys.MENU);

        mapping(0, 19, CameraKeys.UP);
        mapping(0, 20, CameraKeys.DOWN);
        mapping(0, 21, CameraKeys.LEFT);
        mapping(0, 22, CameraKeys.RIGHT);
        mapping(0, 23, CameraKeys.CENTER);
        mapping(0, 66, CameraKeys.CENTER);
        mapping(0, 82, CameraKeys.MENU);
        mapping(0, 4, CameraKeys.MENU);

        mapping(0, 0, CameraKeys.NONE);
        mapping(7777, 29, CameraKeys.NONE);
        mapping(232 + offset, 19, CameraKeys.CENTER);

        if (CameraKeys.usesFallback() == reflected) {
            throw new AssertionError("Unexpected mapping provenance for " + arguments[0]);
        }
        assertions++;
        System.out.println("CameraKeysTest (" + arguments[0] + "): "
                + assertions + " assertions passed");
    }
}
