package com.sony.scalar.sysutil;

/**
 * Host reflection fixture; not real Sony scan codes. Adding 1000 to each
 * documented fallback makes a test fail if reflection silently stops working.
 * Never include this class in an APK.
 */
public final class ScalarInput {
    public static final int ISV_KEY_UP = 1103;
    public static final int ISV_KEY_DOWN = 1108;
    public static final int ISV_KEY_LEFT = 1105;
    public static final int ISV_KEY_RIGHT = 1106;
    public static final int ISV_KEY_ENTER = 1232;
    public static final int ISV_KEY_MENU = 1514;
    public static final int ISV_KEY_SK1 = 1229;
    public static final int ISV_KEY_DISP = 1608;

    private ScalarInput() {}
}
