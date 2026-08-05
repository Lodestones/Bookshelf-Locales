package gg.lode.bookshelflocales.source;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Locales bundled inside the developer's own jar, e.g. {@code /locales/en_us.json}.
 *
 * <p>A jar can't be listed reliably at runtime the way a folder can, so the language codes to look
 * for are declared up front. These are the defaults that ship with the plugin — registered before
 * any folder source so the server owner's files win.
 */
public final class ResourceLocaleSource implements LocaleSource {

    private final ClassLoader classLoader;
    private final String basePath;
    private final Set<String> languageCodes;

    /**
     * @param classLoader   the loader holding the resources — normally {@code getClass().getClassLoader()}
     * @param basePath      folder inside the jar, e.g. {@code locales}
     * @param languageCodes codes to look for, without the {@code .json} extension
     */
    public ResourceLocaleSource(ClassLoader classLoader, String basePath, Collection<String> languageCodes) {
        this.classLoader = classLoader;
        this.basePath = trim(basePath);
        this.languageCodes = new LinkedHashSet<>(languageCodes);
    }

    public Set<String> languageCodes() {
        return languageCodes;
    }

    /** The in-jar path a given locale is read from. */
    public String resourcePath(String languageCode) {
        return basePath.isEmpty() ? languageCode + ".json" : basePath + "/" + languageCode + ".json";
    }

    /** Opens a bundled locale, or null when the jar doesn't carry it. */
    public InputStream open(String languageCode) {
        return classLoader.getResourceAsStream(resourcePath(languageCode));
    }

    @Override
    public Map<String, Map<String, String>> load(Consumer<String> problems) {
        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        for (String languageCode : languageCodes) {
            String path = resourcePath(languageCode);
            try (InputStream stream = classLoader.getResourceAsStream(path)) {
                if (stream == null) {
                    problems.accept("Bundled locale " + path + " is missing from the jar");
                    continue;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    loaded.put(languageCode.toLowerCase(), LocaleJson.parse(reader));
                }
            } catch (Exception e) {
                problems.accept("Could not read bundled locale " + path + ": " + e.getMessage());
            }
        }
        return loaded;
    }

    @Override
    public String describe() {
        return "bundled resources " + (basePath.isEmpty() ? "/" : "/" + basePath);
    }

    private static String trim(String path) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }
}
