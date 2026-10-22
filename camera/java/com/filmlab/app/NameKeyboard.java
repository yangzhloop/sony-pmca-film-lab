// SPDX-License-Identifier: MIT
package com.filmlab.app;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** A deterministic, physical-button keyboard; no touch or Android input method required. */
final class NameKeyboard {
    static final int COLUMNS = 8;
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz0123456789-_. ";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_. ";
    private final boolean chinese;
    private String value;
    private final String original;
    private String syllable = "";
    private String candidates = "";
    private boolean pinyin;
    private boolean upper;
    private boolean candidateMode;
    private int selected;
    private int candidatePage;
    private String error = "";
    private final boolean dictionaryReady;

    NameKeyboard(Context context, String initial, boolean chinese) {
        this.value = initial == null ? "" : initial;
        original = value;
        this.chinese = chinese;
        dictionaryReady = PinyinDictionary.load(context);
    }

    String value() { return value; }
    boolean hasChanges() { return !original.equals(value) || syllable.length() > 0; }
    String syllable() { return syllable; }
    String error() { return error; }
    boolean isPinyin() { return pinyin; }
    boolean isCandidateMode() { return candidateMode; }
    int selected() { return selected; }
    int candidatePage() { return candidatePage; }
    int pageCount() { return Math.max(1, (candidates.length() + 23) / 24); }

    List<String> cells() {
        ArrayList<String> result = new ArrayList<String>();
        if (candidateMode) {
            int start = candidatePage * 24;
            for (int i = start; i < Math.min(start + 24, candidates.length()); i++) {
                result.add(candidates.substring(i, i + 1));
            }
            // Fixed positions retain predictable up/down navigation on the last page.
            while (result.size() < 24) result.add("");
            result.add(chinese ? "上一页" : "Previous");
            result.add(chinese ? "下一页" : "Next");
            result.add(chinese ? "拼音" : "Pinyin");
            return result;
        }
        String letters = upper && !pinyin ? UPPER : LETTERS;
        for (int i = 0; i < letters.length(); i++) {
            String letter = letters.substring(i, i + 1);
            result.add(" ".equals(letter) ? (chinese ? "空格" : "Space") : letter);
        }
        result.add(pinyin ? (chinese ? "英文" : "ABC") : (chinese ? "拼音" : "Pinyin"));
        result.add(chinese ? "退格" : "Delete");
        result.add(pinyin ? (chinese ? "选字" : "Choose") : (upper ? "abc" : "ABC"));
        result.add(chinese ? "完成" : "Done");
        result.add(chinese ? "取消" : "Cancel");
        return result;
    }

    void move(int horizontal, int vertical) {
        // Arrow repeats do not need to allocate every key label again.
        int count = candidateMode ? 27 : LETTERS.length() + 5;
        int proposed = selected + horizontal + vertical * COLUMNS;
        if (proposed >= 0 && proposed < count) selected = proposed;
    }

    /** 0 keeps editing, 1 accepts text, 2 cancels. */
    int choose() {
        error = "";
        if (candidateMode) {
            if (selected < 24) {
                int index = candidatePage * 24 + selected;
                if (index < candidates.length() && append(candidates.substring(index, index + 1))) {
                    syllable = ""; candidateMode = false; selected = 0;
                }
            } else if (selected == 24) {
                candidatePage = Math.max(0, candidatePage - 1);
            } else if (selected == 25) {
                candidatePage = Math.min(pageCount() - 1, candidatePage + 1);
            } else { candidateMode = false; selected = 0; }
            return 0;
        }
        if (selected < LETTERS.length()) {
            char letter = (upper && !pinyin ? UPPER : LETTERS).charAt(selected);
            if (pinyin) {
                if (letter >= 'a' && letter <= 'z' && syllable.length() < 7) syllable += letter;
                else if (letter == ' ') chooseCandidates();
            } else append(String.valueOf(letter));
        } else {
            int action = selected - LETTERS.length();
            if (action == 0) {
                if (pinyin && syllable.length() > 0) { error = chinese ? "请先选字或删除拼音" : "Choose a character or delete the pinyin first"; }
                else { pinyin = !pinyin; selected = 0; }
            } else if (action == 1) {
                if (pinyin && syllable.length() > 0) syllable = syllable.substring(0, syllable.length() - 1);
                else if (value.length() > 0) {
                    int cut = value.length() - 1;
                    if (cut > 0 && Character.isLowSurrogate(value.charAt(cut))
                            && Character.isHighSurrogate(value.charAt(cut - 1))) cut--;
                    value = value.substring(0, cut);
                }
            } else if (action == 2) {
                if (pinyin) chooseCandidates(); else upper = !upper;
            } else if (action == 3) {
                if (syllable.length() > 0) error = chinese ? "请先选字" : "Choose a character first";
                else if (value.trim().length() == 0) error = chinese ? "请输入配方名称" : "Enter a recipe name";
                else return 1;
            } else if (action == 4) return 2;
        }
        return 0;
    }

    private void chooseCandidates() {
        if (!dictionaryReady) { error = chinese ? "拼音字典无法读取" : "Pinyin dictionary unavailable"; return; }
        candidates = PinyinDictionary.lookup(syllable);
        if (candidates.length() == 0) { error = chinese ? "无候选字，请检查拼音" : "No characters; check the pinyin"; return; }
        candidateMode = true; candidatePage = 0; selected = 0;
    }

    private boolean append(String text) {
        if (value.codePointCount(0, value.length()) + text.codePointCount(0, text.length()) > 32) {
            error = chinese ? "名称最多 32 个字符" : "Names can have up to 32 characters";
            return false;
        }
        value += text;
        return true;
    }
}
