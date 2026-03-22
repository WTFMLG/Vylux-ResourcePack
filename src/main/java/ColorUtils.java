// src/main/java/com/vylux/vyluxresourcepack/ColorUtils.java
package com.vylux.vyluxresourcepack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Преобразование цветовых кодов:
 * - '&#RRGGBB' -> '§x§R§R§G§G§B§B'
 * - '&' -> '§'
 *
 * Безопасно использует Matcher.quoteReplacement для appendReplacement.
 */
public final class ColorUtils {
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private ColorUtils() {
        // no instantiation
    }

    public static String translate(String input) {
        if (input == null || input.isEmpty()) return "";

        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder repl = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                repl.append('§').append(c);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(repl.toString()));
        }

        matcher.appendTail(sb);
        return sb.toString().replace("&", "§");
    }
}