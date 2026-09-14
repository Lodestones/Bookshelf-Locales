package gg.lode.bookshelflocales.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One locale file, open for a migration step to change.
 *
 * <p>Operations here run against whatever is already on disk - including values a
 * server owner rewrote - which is what separates a migration from the default
 * merge. The merge can only offer a new default and let the owner's version win;
 * a migration can carry an edit across a rename, or rewrite the syntax inside it.
 */
public final class LocaleEdit {

    private final Map<String, String> translations;

    public LocaleEdit(Map<String, String> translations) {
        this.translations = translations;
    }

    /** Moves a key, keeping whatever value is there. Does nothing if it is absent. */
    public LocaleEdit rename(String from, String to) {
        String value = translations.remove(from);
        if (value != null) translations.putIfAbsent(to, value);
        return this;
    }

    /** Drops a key that no longer means anything. */
    public LocaleEdit remove(String key) {
        translations.remove(key);
        return this;
    }

    /** Sets a key outright, overwriting any value already there. */
    public LocaleEdit set(String key, String value) {
        translations.put(key, value);
        return this;
    }

    /**
     * Rewrites every value with a regular expression - for a change of syntax
     * rather than of wording, where an owner's own text needs the same treatment
     * as the defaults.
     */
    public LocaleEdit replaceInValues(String regex, String replacement) {
        Pattern pattern = Pattern.compile(regex);
        return editValues((key, value) -> pattern.matcher(value).replaceAll(replacement));
    }

    /** Rewrites every value with a function of its key and value. */
    public LocaleEdit editValues(BiFunction<String, String, String> edit) {
        List<String> keys = new ArrayList<>(translations.keySet());
        for (String key : keys) {
            String value = translations.get(key);
            if (value != null) translations.put(key, edit.apply(key, value));
        }
        return this;
    }

    /** The map being edited, for anything the helpers above do not cover. */
    public Map<String, String> translations() {
        return translations;
    }

    /** Escapes a literal for use as a {@link #replaceInValues} replacement. */
    public static String literal(String replacement) {
        return Matcher.quoteReplacement(replacement);
    }
}
