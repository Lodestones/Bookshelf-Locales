package gg.lode.bookshelflocales.source;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Translations held in memory. Useful for a plugin that generates its keys, and for tests.
 */
public final class MapLocaleSource implements LocaleSource {

    private final Map<String, Map<String, String>> locales = new LinkedHashMap<>();

    public MapLocaleSource put(String languageCode, Map<String, String> translations) {
        locales.computeIfAbsent(languageCode.toLowerCase(), key -> new LinkedHashMap<>()).putAll(translations);
        return this;
    }

    public MapLocaleSource put(String languageCode, String translationKey, String value) {
        locales.computeIfAbsent(languageCode.toLowerCase(), key -> new LinkedHashMap<>()).put(translationKey, value);
        return this;
    }

    @Override
    public Map<String, Map<String, String>> load(Consumer<String> problems) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        locales.forEach((languageCode, translations) -> copy.put(languageCode, new LinkedHashMap<>(translations)));
        return copy;
    }

    @Override
    public String describe() {
        return "in-memory locales";
    }
}
