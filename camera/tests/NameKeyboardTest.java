package com.filmlab.app;

import java.io.FileInputStream;
import java.util.List;

/** Drives the same directional/candidate actions available on the camera wheel. */
public final class NameKeyboardTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void select(NameKeyboard keyboard, String label) {
        List<String> cells = keyboard.cells();
        int target = cells.indexOf(label);
        if (target < 0) throw new AssertionError("Missing cell: " + label);
        while (keyboard.selected() < target) keyboard.move(1, 0);
        while (keyboard.selected() > target) keyboard.move(-1, 0);
    }
    private static int choose(NameKeyboard keyboard, String label) {
        select(keyboard, label);
        return keyboard.choose();
    }
    public static void main(String[] args) throws Exception {
        check(PinyinDictionary.load(new FileInputStream(args[0])), "dictionary available");
        NameKeyboard latin = new NameKeyboard(null, "", false);
        latin.move(0, -1); check(latin.selected() == 0, "top boundary");
        latin.move(0, 1); check(latin.selected() == 8, "down moves one grid row");
        latin.move(0, -1); check(latin.selected() == 0, "up returns to first row");
        choose(latin, "a"); choose(latin, "b"); choose(latin, "Delete");
        check("a".equals(latin.value()), "Latin editing and deletion");
        choose(latin, "ABC"); choose(latin, "Z");
        check("aZ".equals(latin.value()), "uppercase naming");
        check(choose(latin, "Done") == 1, "explicit Done accepts name");
        NameKeyboard chinese = new NameKeyboard(null, "", true);
        choose(chinese, "拼音"); choose(chinese, "y"); choose(chinese, "u");
        check("yu".equals(chinese.syllable()), "physical pinyin composition");
        check(chinese.value().length() == 0, "composition does not append unchosen text");
        choose(chinese, "选字"); check(chinese.isCandidateMode(), "candidate selection");
        check(chinese.cells().contains("雨"), "common recipe naming character on first page");
        choose(chinese, "雨"); check("雨".equals(chinese.value()), "chosen Chinese character appended");
        check(!chinese.isCandidateMode() && chinese.syllable().length() == 0, "composition clears after selection");
        choose(chinese, "h"); choose(chinese, "o"); choose(chinese, "u"); choose(chinese, "选字"); choose(chinese, "后");
        check("雨后".equals(chinese.value()), "multiple Chinese characters");
        choose(chinese, "英文"); choose(chinese, "a");
        check("雨后a".equals(chinese.value()), "mixed-language recipe name");
        check(choose(chinese, "完成") == 1, "Chinese Done");
        NameKeyboard pending = new NameKeyboard(null, "Keep", false);
        choose(pending, "Pinyin"); choose(pending, "y");
        check(choose(pending, "Done") == 0, "unfinished pinyin blocks Done");
        check("Keep".equals(pending.value()), "uncommitted composition preserves name");
        choose(pending, "Delete"); choose(pending, "z"); choose(pending, "z"); choose(pending, "Choose");
        check(!pending.isCandidateMode() && pending.error().length() > 0, "invalid reading remains editable");
        check(choose(pending, "Cancel") == 2, "explicit cancel");
        NameKeyboard limited = new NameKeyboard(null, "12345678901234567890123456789012", false);
        choose(limited, "a");
        check(limited.value().length() == 32 && limited.error().length() > 0, "32 character limit");
        NameKeyboard supplementary = new NameKeyboard(null, "A\ud83d\ude00", false);
        choose(supplementary, "Delete");
        check("A".equals(supplementary.value()), "deletion never splits surrogate pair");
        NameKeyboard empty = new NameKeyboard(null, "", false);
        check(choose(empty, "Done") == 0, "empty name rejected");
        System.out.println("NameKeyboardTest: " + assertions + " assertions passed");
    }
}
