package gg.lode.bookshelflocales.text;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The casing tags a locale file may use — {@code <uppercase>}, {@code <lowercase>},
 * {@code <capitalize>}. They're applied to the resolved text before MiniMessage sees it, which is
 * what lets a translator shout a word that arrived from a placeholder.
 */
public final class TextTransformations {

    private static final Pattern UPPERCASE = Pattern.compile("<uppercase>(.*?)</uppercase>", Pattern.DOTALL);
    private static final Pattern LOWERCASE = Pattern.compile("<lowercase>(.*?)</lowercase>", Pattern.DOTALL);
    private static final Pattern CAPITALIZE = Pattern.compile("<capitalize>(.*?)</capitalize>", Pattern.DOTALL);

    private TextTransformations() {
    }

    public static String apply(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = applyTag(text, UPPERCASE, String::toUpperCase);
        result = applyTag(result, LOWERCASE, String::toLowerCase);
        return applyTag(result, CAPITALIZE, TextTransformations::capitalize);
    }

    private static String applyTag(String text, Pattern pattern, Function<String, String> transformer) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String content = matcher.group(1);
            String transformed = transformer.apply(content);
            // Text that transforms to itself keeps its tag, so a placeholder that hasn't been filled in
            // yet still gets shouted once it is.
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    Objects.equals(transformed, content) ? matcher.group(0) : transformed));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) return text;

        String[] words = text.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) result.append(' ');
            if (words[i].isEmpty()) continue;
            result.append(Character.toUpperCase(words[i].charAt(0)))
                    .append(words[i].substring(1).toLowerCase());
        }
        return result.toString();
    }
}
