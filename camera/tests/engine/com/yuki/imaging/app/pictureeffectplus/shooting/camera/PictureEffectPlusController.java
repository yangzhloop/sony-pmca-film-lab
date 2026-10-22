package com.yuki.imaging.app.pictureeffectplus.shooting.camera;
import com.filmlab.app.RecipeMath;
import com.filmlab.app.ShootingBridge;
import com.sony.imaging.app.base.shooting.camera.CameraSetting;
/** Simulates the patched inherited hook boundaries; null gamma is rejected before acceptance. */
public final class PictureEffectPlusController {
    private static final PictureEffectPlusController INSTANCE = new PictureEffectPlusController();
    public boolean invalidGammaOnce, rejectOnce;
    public int writes, sourceStrength;
    public static PictureEffectPlusController getInstance() { return INSTANCE; }
    public static int[] baseMatrix(String id) {
        return "pop-color".equals(id) ? new int[] {1124, -80, -20, -40, 1084, -20, 0, -80, 1104}
            : new int[] {1234, -180, -30, -90, 1164, -50, -60, -120, 1204};
    }
    public static byte[] baseGamma(String id) {
        byte[] result = new byte[2048];
        double exponent = "pop-color".equals(id) ? 1.1 : 1.4;
        for (int i = 0; i < 1024; i++) {
            int sample = (int) Math.round(Math.pow(i / 1023.0, exponent) * 1023.0);
            result[2 * i] = (byte) sample; result[2 * i + 1] = (byte) (sample >> 8);
        }
        return result;
    }
    public void setValue(String item, String id) {
        writes++;
        sourceStrength = ShootingBridge.strength(30);
        int[] matrix = ShootingBridge.matrix(id, RecipeMath.matrix(baseMatrix(id), sourceStrength));
        if (matrix == null || rejectOnce) {
            rejectOnce = false; ShootingBridge.applyResult(id, false); return;
        }
        CameraSetting camera = CameraSetting.getInstance(); camera.matrix = matrix;
        byte[] source = RecipeMath.gamma(baseGamma(id), sourceStrength, 0, 0);
        if (invalidGammaOnce) { invalidGammaOnce = false; source[1] = (byte) 127; }
        byte[] gamma = ShootingBridge.gamma(id, source);
        if (gamma == null) { ShootingBridge.applyResult(id, false); return; }
        camera.gamma = gamma;
        ShootingBridge.applyResult(id, true);
    }
    public void reset() { invalidGammaOnce = rejectOnce = false; writes = sourceStrength = 0; }
}
