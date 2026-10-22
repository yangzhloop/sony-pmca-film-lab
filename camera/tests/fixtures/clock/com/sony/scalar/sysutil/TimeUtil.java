package com.sony.scalar.sysutil;

/** Host-only public field shapes from Sony ScalarCalendar's verified calls. */
public final class TimeUtil {
    public static int mode;
    public static PlainCalendar getCurrentCalendar() {
        PlainCalendar result = new PlainCalendar();
        if (mode == 1) result.year = 1970;
        if (mode == 2) { result.month = 2; result.day = 30; }
        return result;
    }
    public static PlainTimeZone getCurrentTimeZone() {
        if (mode == 4) throw new IllegalStateException("PRIVATE_EXCEPTION_TEXT");
        PlainTimeZone result = new PlainTimeZone();
        if (mode == 3) result.gmtDiff = 1000;
        if (mode == 5) result.gmtDiff = -300;
        if (mode == 6) result.summerTimeDiff = 60;
        return result;
    }
    public static final class PlainCalendar {
        public int year = 2026;
        public int month = 10;
        public int day = 18;
        public int hour = 13;
        public int minute = 6;
        public int second = 7;
    }
    public static final class PlainTimeZone {
        public int gmtDiff = 480;
        public int summerTimeDiff;
    }
}
