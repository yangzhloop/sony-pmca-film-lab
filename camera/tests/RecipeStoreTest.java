// SPDX-License-Identifier: MIT
import com.filmlab.app.Recipe;
import com.filmlab.app.RecipeMath;
import com.filmlab.app.RecipeStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Host tests exercise real files, readback, and generated coefficients; no Android mocks. */
public final class RecipeStoreTest {
    private static int assertions;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Action action, Class<?> type, String message) throws Exception {
        try { action.run(); }
        catch (Exception failure) {
            check(type.isInstance(failure), message + ": " + failure);
            return;
        }
        throw new AssertionError(message + " did not fail");
    }
    private static File temp(String name) throws IOException {
        File root = File.createTempFile("recipe-" + name, "-test");
        if (!root.delete() || !root.mkdir()) throw new IOException("temp directory");
        return root;
    }
    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }
    private static void write(File file, byte[] bytes) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try { out.write(bytes); } finally { out.close(); }
    }

    private static void catalogAndOwnership() throws Exception {
        File dir = temp("catalog");
        try {
            final RecipeStore store = new RecipeStore(dir);
            check(store.list("builtin").size() == 15, "actual 15 profiles");
            check(store.list("user").size() == 0, "no sample user data");
            check(store.getActive() == null, "no unverified initial active recipe");
            Recipe builtin = store.get("fuji-chrome"); builtin.name = "changed"; builtin.color = 0;
            check(store.get("fuji-chrome").color == 100, "get returns a copy");
            List<Recipe> list = store.list("builtin"); list.get(0).color = 0; list.clear();
            check(store.list("builtin").size() == 15 && store.get("pop-color").color == 100,
                "list does not expose records or collection");
            rejects(new Action() { public void run() throws Exception { store.save(store.get("fuji-chrome")); } },
                IllegalArgumentException.class, "builtin save");
            rejects(new Action() { public void run() throws Exception { store.rename("fuji-chrome", "No"); } },
                IllegalArgumentException.class, "builtin rename");
            rejects(new Action() { public void run() throws Exception { store.delete("fuji-chrome"); } },
                IllegalArgumentException.class, "builtin delete");
            Recipe draft = store.duplicate("fuji-chrome");
            check(draft.user && draft.id == null && store.list("user").isEmpty(), "duplicate is cancellable draft");
            draft.name = "雨后街角"; draft.kelvin = 6200; draft.ab = 2; draft.wb = "kelvin";
            Recipe saved = store.save(draft);
            check(saved.id.startsWith("user-") && saved.revision == 1, "new user identity");
            check(draft.id == null, "save does not mutate draft");
            check(store.getActive() == null, "save does not activate");
            check("雨后街角".equals(saved.displayName("en")), "custom name independent of UI language");
            check("经典正片".equals(store.get("fuji-chrome").displayName("zh")), "localized builtin");
            saved.color = 20;
            check(store.get(saved.id).color == 100, "save result does not expose internal record");
            Recipe loaded = new RecipeStore(dir).get(saved.id);
            check(loaded.kelvin == 6200 && loaded.ab == 2 && "kelvin".equals(loaded.wb)
                && "雨后街角".equals(loaded.name), "all settings and Unicode survive restart");
            store.setFavorite(saved.id, true); store.setFavorite("fuji-chrome", true);
            check(store.list("favorites").size() == 2, "builtin and user favorites");
            store.toggleFavorite("fuji-chrome");
            check(new RecipeStore(dir).list("favorites").size() == 1, "favorite removal persists");
        } finally { remove(dir); }
    }

    private static void activeSnapshots() throws Exception {
        File dir = temp("active");
        try {
            final RecipeStore store = new RecipeStore(dir);
            Recipe draft = store.createDraft("fuji-eterna"); draft.name = "Evening";
            final Recipe original = store.save(draft);
            store.activate(original);
            Recipe active = store.getActive(); active.tone = 0;
            check(store.getActive().tone == 100, "active getter detached");
            Recipe changed = original.copy(); changed.tone = 40;
            Recipe revised = store.save(changed);
            check(revised.revision == 2 && store.getActive().tone == 100 && store.getActive().revision == 1,
                "editing saved recipe does not change applied snapshot");
            rejects(new Action() { public void run() throws Exception { store.save(original); } },
                IllegalArgumentException.class, "stale editor cannot silently overwrite newer save");
            store.rename(original.id, "Dusk");
            check("Dusk".equals(store.getActive().name) && store.getActive().tone == 100
                && store.getActive().revision == 1, "rename updates name without changing applied settings/revision");
            store.setFavorite(original.id, true);
            store.delete(original.id);
            check(store.get(original.id) == null && !store.isFavorite(original.id), "delete removes user and favorite");
            active = new RecipeStore(dir).getActive();
            check(active.id == null && "fuji-eterna".equals(active.baseId) && "Dusk".equals(active.name)
                && active.tone == 100, "delete preserves detached active snapshot across restart");
            store.activate(store.get("fuji-acros"));
            check("fuji-acros".equals(new RecipeStore(dir).getActive().id), "builtin active roundtrip");
        } finally { remove(dir); }
    }

    private static void failureAndRecovery() throws Exception {
        File dir = temp("failure");
        try {
            final RecipeStore store = new RecipeStore(dir);
            Recipe draft = store.createDraft("pop-color"); draft.name = "First";
            final Recipe first = store.save(draft);
            store.activate(first);
            File blocked = new File(dir, "recipes.new");
            check(blocked.mkdir(), "block pending path");
            write(new File(blocked, "keep"), new byte[] {1});
            rejects(new Action() { public void run() throws Exception { store.rename(first.id, "Uncommitted"); } },
                IOException.class, "failed write reported");
            check("First".equals(store.get(first.id).name) && store.get(first.id).revision == 1,
                "failed write cannot change in-memory library");
            check("First".equals(new RecipeStore(dir).get(first.id).name), "failed write cannot change disk library");
            rejects(new Action() { public void run() throws Exception { store.delete(first.id); } },
                IOException.class, "failed delete reported");
            check(first.id.equals(store.getActive().id) && store.get(first.id) != null,
                "failed delete preserves library and active together");
            rejects(new Action() { public void run() throws Exception { store.activate(store.get("fuji-velvia")); } },
                IOException.class, "failed activation persistence reported");
            check(first.id.equals(store.getActive().id), "failed activation leaves stored active unchanged");
            remove(blocked);
            store.rename(first.id, "Second");
            write(new File(dir, "recipes.bin"), new byte[] {0, 1, 2});
            RecipeStore recovered = new RecipeStore(dir);
            check("recovered_backup".equals(recovered.getRecoveryNotice()), "corruption is surfaced");
            check("First".equals(recovered.get(first.id).name) && first.id.equals(recovered.getActive().id),
                "previous-good backup recovered coherently");
            recovered.rename(first.id, "Recovered");
            check("Recovered".equals(new RecipeStore(dir).get(first.id).name), "recovery can commit again");
            write(new File(dir, "recipes.bin"), new byte[] {0});
            write(new File(dir, "recipes.bak"), new byte[] {0});
            final File corrupt = dir;
            rejects(new Action() { public void run() throws Exception { new RecipeStore(corrupt); } },
                IOException.class, "both copies corrupt fails closed");
            check(new File(dir, "recipes.bin").length() == 1 && new File(dir, "recipes.bak").length() == 1,
                "opening corrupt store never erases recoverable bytes");
        } finally { remove(dir); }
    }

    private static void validation() throws Exception {
        check("中文 名称".equals(RecipeStore.validName("  中文 名称  ")), "trimmed Unicode name");
        StringBuilder maximum = new StringBuilder();
        for (int i = 0; i < 32; i++) maximum.append("\ud83c\udf9e");
        final String max = maximum.toString();
        check(RecipeStore.validName(max).length() == 64, "limit counts Unicode codepoints");
        rejects(new Action() { public void run() { RecipeStore.validName(max + "a"); } },
            IllegalArgumentException.class, "long name");
        rejects(new Action() { public void run() { RecipeStore.validName("a\nb"); } },
            IllegalArgumentException.class, "control character");
        rejects(new Action() { public void run() { RecipeStore.validName("a\ud800"); } },
            IllegalArgumentException.class, "unpaired surrogate");
        final Recipe recipe = RecipeStore.builtins().get(0); recipe.user = true; recipe.id = null;
        recipe.color = 101;
        rejects(new Action() { public void run() { RecipeStore.validated(recipe); } },
            IllegalArgumentException.class, "setting bounds");
        recipe.color = 20; recipe.baseId = "fuji-acros";
        check(RecipeStore.validated(recipe).color == 100 && recipe.color == 20, "mono stays mono without mutating draft");
        recipe.baseId = "unavailable";
        rejects(new Action() { public void run() { RecipeStore.validated(recipe); } },
            IllegalArgumentException.class, "unknown profile");
    }

    private static int sample(byte[] curve, int index) {
        return (curve[2 * index] & 255) | ((curve[2 * index + 1] & 255) << 8);
    }

    private static void math() throws Exception {
        int[] matrix = {1210, -150, -36, -40, 1100, -36, 30, -70, 1064};
        check(Arrays.equals(matrix, RecipeMath.matrix(matrix, 100)), "100 color exact source coefficients");
        check(Arrays.equals(new int[] {1024, 0, 0, 0, 1024, 0, 0, 0, 1024}, RecipeMath.matrix(matrix, 0)),
            "0 color identity in actual Sony units");
        int[] originalMatrix = matrix.clone();
        RecipeMath.matrix(matrix, 31);
        check(Arrays.equals(matrix, originalMatrix), "matrix input immutable");
        boolean neutral = true;
        for (int strength = 0; strength <= 100; strength++) {
            int[] blended = RecipeMath.matrix(matrix, strength);
            for (int row = 0; row < 3; row++)
                neutral &= blended[row * 3] + blended[row * 3 + 1] + blended[row * 3 + 2] == 1024;
        }
        check(neutral, "neutral source rows remain neutral at every strength");
        int[] tinted = {1100, 0, 0, 0, 980, 0, 0, 0, 900};
        check(Arrays.equals(tinted, RecipeMath.matrix(tinted, 100)), "intentional tint not normalized away");
        byte[] curve = new byte[2048];
        for (int i = 0; i < 1024; i++) {
            int y = (int) Math.round(Math.pow(i / 1023.0, 1.25) * 1023.0);
            curve[2 * i] = (byte) y; curve[2 * i + 1] = (byte) (y >> 8);
        }
        byte[] originalCurve = curve.clone();
        check(Arrays.equals(curve, RecipeMath.gamma(curve, 100, 0, 0)), "100 tone exact source bytes");
        byte[] identity = RecipeMath.gamma(curve, 0, 0, 0);
        boolean identityExact = true;
        for (int i = 0; i < 1024; i++) identityExact &= sample(identity, i) == i;
        check(identityExact, "0 tone identity");
        int[] tones = {0, 37, 100};
        for (int t = 0; t < tones.length; t++) for (int shadows = -3; shadows <= 3; shadows++)
            for (int highlights = -3; highlights <= 3; highlights++) {
                byte[] adjusted = RecipeMath.gamma(curve, tones[t], shadows, highlights);
                boolean monotonic = true;
                for (int i = 1; i < 1024; i++) monotonic &= sample(adjusted, i) >= sample(adjusted, i - 1)
                    && sample(adjusted, i) <= 1023;
                check(monotonic && sample(adjusted, 0) == 0 && sample(adjusted, 1023) == 1023,
                    "curve bounds/endpoints/monotonic tone=" + tones[t] + " shadows=" + shadows + " highlights=" + highlights);
            }
        check(Arrays.equals(curve, originalCurve), "curve input immutable");
        check(sample(RecipeMath.gamma(curve, 0, 3, 0), 256) > 256
            && sample(RecipeMath.gamma(curve, 0, -3, 0), 256) < 256, "shadow sign matches control");
        check(sample(RecipeMath.gamma(curve, 0, 0, 3), 768) > 768
            && sample(RecipeMath.gamma(curve, 0, 0, -3), 768) < 768, "highlight sign matches control");
        final byte[] invalid = curve.clone(); invalid[8] = (byte) 255; invalid[9] = (byte) 255;
        rejects(new Action() { public void run() { RecipeMath.gamma(invalid, 100, 0, 0); } },
            IllegalArgumentException.class, "invalid source curve rejected");
    }

    public static void main(String[] args) throws Exception {
        catalogAndOwnership(); activeSnapshots(); failureAndRecovery(); validation(); math();
        System.out.println("RecipeStoreTest: 5 scenarios / " + assertions + " assertions passed");
    }
}
