package com.sony.imaging.app.base.shooting.camera;
import android.util.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/** Reflective signatures and mutation order verified against pinned WhiteBalanceController.smali. */
public final class WhiteBalanceController {
    private static final WhiteBalanceController INSTANCE = new WhiteBalanceController();
    private final Map<String, WhiteBalanceParam> backups = new HashMap<String, WhiteBalanceParam>();
    public boolean available = true, failDetailOnce;
    public int detailWrites, backupWrites, modeWrites;
    public static WhiteBalanceController getInstance() { return INSTANCE; }
    public boolean isAvailable(String item) { return available; }
    public List<String> getSupportedValue(String item) {
        List<String> result = new ArrayList<String>();
        if ("WhiteBalance".equals(item)) {
            result.add("auto"); result.add("color-temp"); result.add("custom1"); result.add("custom2"); result.add("custom3");
        } else if ("color-temperature".equals(item)) {
            for (int n = 2500; n <= 10000; n += 100) result.add(String.valueOf(n));
        } else if ("light-balance".equals(item) || "color-compensation".equals(item)) {
            for (int n = -7; n <= 7; n++) result.add(String.valueOf(n));
        }
        return result;
    }
    public String getValue() { return CameraSetting.getInstance().mode; }
    public void setValue(String item, String value) {
        modeWrites++;
        CameraSetting camera = CameraSetting.getInstance();
        camera.mode = "custom".equals(value) ? "custom1" : value;
        WhiteBalanceParam options = getWBOptionFromBackUp(camera.mode);
        camera.ab = options.lightBalance; camera.gm = options.colorComp; camera.kelvin = options.colorTemp;
    }
    public void setDetailValue(Object input) {
        detailWrites++;
        WhiteBalanceParam value = (WhiteBalanceParam) input;
        CameraSetting camera = CameraSetting.getInstance();
        Pair<CameraSetting.Parameters, CameraSetting.Modifier> pair = camera.getEmptyParameters();
        boolean accepted = setWBOption(pair, value);
        setWBOptionToBackUp(getValue(), value); // Pinned Sony order: backup precedes hardware setter.
        if (failDetailOnce) { failDetailOnce = false; throw new IllegalStateException("injected native setter rejection"); }
        if (accepted) camera.setParameters(pair);
    }
    private boolean setWBOption(Pair<CameraSetting.Parameters, CameraSetting.Modifier> pair, WhiteBalanceParam value) {
        pair.second.ab = value.lightBalance; pair.second.gm = value.colorComp; pair.second.kelvin = value.colorTemp;
        return true;
    }
    public WhiteBalanceParam getWBOptionFromBackUp(String mode) {
        WhiteBalanceParam value = backups.get(mode);
        return value == null ? new WhiteBalanceParam(0, 0, 5500) : value.copy();
    }
    private void setWBOptionToBackUp(String mode, WhiteBalanceParam value) { backupWrites++; backups.put(mode, value.copy()); }
    public void seed(String mode, int ab, int gm, int kelvin) { backups.put(mode, new WhiteBalanceParam(ab, gm, kelvin)); }
    public void reset() { backups.clear(); available = true; failDetailOnce = false; detailWrites = backupWrites = modeWrites = 0; }
    public static final class WhiteBalanceParam {
        public final int lightBalance, colorComp, colorTemp;
        public WhiteBalanceParam(int a, int g, int k) { lightBalance = a; colorComp = g; colorTemp = k; }
        public WhiteBalanceParam copy() { return new WhiteBalanceParam(lightBalance, colorComp, colorTemp); }
    }
}
