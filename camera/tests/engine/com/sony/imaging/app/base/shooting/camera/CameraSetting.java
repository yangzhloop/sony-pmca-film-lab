package com.sony.imaging.app.base.shooting.camera;
import android.util.Pair;
/** API-shaped fake of the pinned CameraSetting's maintained cache, not the native camera itself. */
public final class CameraSetting {
    private static final CameraSetting INSTANCE = new CameraSetting();
    public String mode = "auto";
    public int ab, gm, kelvin = 5500, parameterWrites;
    public int[] matrix;
    public byte[] gamma;
    public static CameraSetting getInstance() { return INSTANCE; }
    public Pair<Parameters, Modifier> getParameters() {
        return new Pair<Parameters, Modifier>(new Parameters(mode), new Modifier(ab, gm, kelvin));
    }
    public Pair<Parameters, Modifier> getEmptyParameters() { return getParameters(); }
    public void setParameters(Pair<Parameters, Modifier> pair) {
        parameterWrites++;
        ab = pair.second.ab; gm = pair.second.gm; kelvin = pair.second.kelvin;
    }
    public void reset() { mode = "auto"; ab = gm = parameterWrites = 0; kelvin = 5500; matrix = null; gamma = null; }
    public static final class Parameters {
        private final String mode;
        Parameters(String mode) { this.mode = mode; }
        public String getWhiteBalance() { return mode.startsWith("custom") ? "custom" : mode; }
    }
    public static final class Modifier {
        public int ab, gm, kelvin;
        Modifier(int a, int g, int k) { ab = a; gm = g; kelvin = k; }
        public int getLightBalanceForWhiteBalance() { return ab; }
        public int getColorCompensationForWhiteBalance() { return gm; }
        public int getColorTemperatureForWhiteBalance() { return kelvin; }
    }
}
