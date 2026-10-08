package me.tsukieru.raidpvp.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private Text() {}

    public static Component color(String text) { return LEGACY.deserialize(text == null ? "" : text); }

    public static String replace(String text, String... pairs) {
        String result = text == null ? "" : text;
        for (int i = 0; i + 1 < pairs.length; i += 2) result = result.replace(pairs[i], pairs[i + 1]);
        return result;
    }
}
