package gg.lode.bookshelflocales;

import gg.lode.bookshelfapi.api.util.MiniMessageHelper;
import gg.lode.bookshelfapi.api.util.VariableContext;
import gg.lode.bookshelflocales.source.FolderLocaleSource;
import gg.lode.bookshelflocales.source.LocaleSource;
import gg.lode.bookshelflocales.source.RemoteLocaleSource;
import gg.lode.bookshelflocales.source.ResourceLocaleSource;
import gg.lode.bookshelflocales.text.TextTransformations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The library's entry point. Build one per plugin, hold onto it, read from it.
 *
 * <pre>
 * LocaleManager locales = LocaleManager.builder()
 *         .defaultLocale("en_us")
 *         .bundled(getClass().getClassLoader(), "locales", "en_us", "ja_jp") // developer's defaults
 *         .remote("https://cdn.example.com/locales/manifest.json")           // optional hosted defaults
 *         .folder(getDataFolder().toPath().resolve("locales"))               // server owner's overrides
 *         .exportBundledDefaults(true)
 *         .logger(getLogger()::warning)
 *         .build();
 *
 * player.sendMessage(locales.get("myplugin.welcome", "ja_jp", VariableContext.of("player", name)));
 * </pre>
 *
 * <p>Sources are read in the order they were added and a later source wins per key, so the
 * precedence above reads: server owner beats hosted beats bundled. Anything nobody translated falls
 * back to the default locale, and finally to the raw key.
 */
public final class LocaleManager implements LocaleService {

    /** Key a locale uses to name itself in a language picker. */
    public static final String DISPLAY_NAME_KEY = "locale.display_name";
    /** Key controlling where a locale sorts in {@link #getLocales()}. Lower comes first. */
    public static final String SORT_ORDER_KEY = "locale.sort_order";

    /** Nested translations: a value may embed another key as {@code {other.key}}. */
    private static final Pattern KEY_PATTERN = Pattern.compile("\\{(.+?)}");

    private final List<LocaleSource> sources;
    private final Consumer<String> logger;
    private final Path exportFolder;
    private final boolean exportBundledDefaults;

    /** Loaded translations, replaced wholesale on reload so a reader never sees a half-built map. */
    private volatile Map<String, Map<String, String>> locales = Map.of();
    /**
     * What each source returned the last time it was read, kept so a partial reload can re-merge
     * against the sources it didn't touch instead of dropping them.
     */
    private final Map<LocaleSource, Map<String, Map<String, String>>> snapshots = new ConcurrentHashMap<>();
    /** Serializes reloads — two at once would race on {@link #snapshots} and could publish a stale merge. */
    private final Object reloadLock = new Object();
    /** Resolved (nested keys expanded, transformations applied) values, keyed {@code locale:key}. */
    private final Map<String, String> resolved = new ConcurrentHashMap<>();

    private volatile String defaultLocale;

    private LocaleManager(Builder builder) {
        this.sources = List.copyOf(builder.sources);
        this.logger = builder.logger;
        this.defaultLocale = builder.defaultLocale.toLowerCase();
        this.exportFolder = builder.exportFolder;
        this.exportBundledDefaults = builder.exportBundledDefaults;

        reload();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ loading

    @Override
    public void reload() {
        reload(source -> true);
    }

    /**
     * Re-reads only the local sources — bundled defaults, the server owner's folder, in-memory
     * locales. Remote sources keep whatever they last returned, so an admin editing a file gets their
     * change applied without a network round trip (and without the reload failing when the host is
     * down).
     */
    @Override
    public void reloadFromDisk() {
        reload(source -> !source.isRemote());
    }

    /**
     * Re-fetches only the remote (cloud-loader) sources, leaving files on disk alone. Blocks on HTTP —
     * call {@link #reloadFromCloudAsync()} from anything latency-sensitive, like a command handler on
     * the main thread.
     */
    @Override
    public void reloadFromCloud() {
        if (sources.stream().noneMatch(LocaleSource::isRemote)) {
            logger.accept("[Locales] reloadFromCloud() had nothing to do: no remote source is configured");
            return;
        }
        reload(LocaleSource::isRemote);
    }

    /**
     * Re-reads the sources {@code filter} accepts and re-merges them over the last result of the ones
     * it doesn't, preserving the configured precedence either way.
     */
    public void reload(Predicate<LocaleSource> filter) {
        synchronized (reloadLock) {
            if (exportBundledDefaults && sources.stream().anyMatch(source -> !source.isRemote() && filter.test(source))) {
                exportBundledDefaults();
            }

            for (LocaleSource source : sources) {
                if (!filter.test(source)) continue;
                try {
                    Map<String, Map<String, String>> loaded = source.load(problem -> logger.accept("[Locales] " + problem));
                    if (loaded.isEmpty() && !snapshots.getOrDefault(source, Map.of()).isEmpty()) {
                        // The source went from having locales to having none — an unreachable host with no
                        // cache, or a folder that just got emptied. Keeping the last good copy is always
                        // better than dropping to raw keys, and the warning above says why it's stale.
                        logger.accept("[Locales] Source " + source.describe() + " returned nothing; keeping the previous copy");
                        continue;
                    }
                    snapshots.put(source, loaded);
                } catch (Exception e) {
                    // A source that throws outright still must not take the plugin down with it — nor
                    // discard what it gave us last time.
                    logger.accept("[Locales] Source " + source.describe() + " failed to load: " + e.getMessage());
                }
            }

            // Merged in configured order every time, so a partial reload can't reshuffle precedence.
            Map<String, Map<String, String>> merged = new LinkedHashMap<>();
            for (LocaleSource source : sources) {
                snapshots.getOrDefault(source, Map.of()).forEach((languageCode, translations) ->
                        merged.computeIfAbsent(languageCode.toLowerCase(), key -> new LinkedHashMap<>()).putAll(translations));
            }

            this.locales = Collections.unmodifiableMap(merged);
            this.resolved.clear();
        }
    }

    /** Reloads off the calling thread — the point of it is {@link RemoteLocaleSource}, which blocks on HTTP. */
    public CompletableFuture<Void> reloadAsync() {
        return CompletableFuture.runAsync(this::reload);
    }

    /** {@link #reloadFromDisk()} off the calling thread. */
    public CompletableFuture<Void> reloadFromDiskAsync() {
        return CompletableFuture.runAsync(this::reloadFromDisk);
    }

    /** {@link #reloadFromCloud()} off the calling thread. This is the one you normally want. */
    public CompletableFuture<Void> reloadFromCloudAsync() {
        return CompletableFuture.runAsync(this::reloadFromCloud);
    }

    /**
     * Writes bundled locales into the export folder when they aren't there yet, so a server owner has
     * a real file to edit instead of having to invent one. Existing files are never overwritten —
     * that folder is the owner's, and their edits outrank the developer's defaults.
     */
    private void exportBundledDefaults() {
        if (exportFolder == null) return;
        try {
            Files.createDirectories(exportFolder);
        } catch (Exception e) {
            logger.accept("[Locales] Could not create locale folder " + exportFolder + ": " + e.getMessage());
            return;
        }

        for (LocaleSource source : sources) {
            if (!(source instanceof ResourceLocaleSource resourceSource)) continue;
            for (String languageCode : resourceSource.languageCodes()) {
                Path target = exportFolder.resolve(languageCode.toLowerCase() + ".json");
                if (Files.exists(target)) continue;
                try (InputStream stream = resourceSource.open(languageCode)) {
                    if (stream == null) continue;
                    Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    logger.accept("[Locales] Could not write default locale " + languageCode + ": " + e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------------ reading

    @Override
    public Component get(String translationKey, String languageCode) {
        return render(_get(translationKey, languageCode));
    }

    @Override
    public Component get(String translationKey) {
        return get(translationKey, defaultLocale);
    }

    @Override
    public Component get(String translationKey, String languageCode, VariableContext context) {
        return render(_get(translationKey, languageCode, context));
    }

    @Override
    public String _get(String translationKey, String languageCode) {
        String locale = languageCode == null ? defaultLocale : languageCode.toLowerCase();
        String cacheKey = locale + ":" + translationKey;

        String cached = resolved.get(cacheKey);
        if (cached != null) return cached;

        String value = resolveKey(translationKey, locale, new HashSet<>());
        resolved.put(cacheKey, value);
        return value;
    }

    @Override
    public String _get(String translationKey) {
        return _get(translationKey, defaultLocale);
    }

    @Override
    public String _get(String translationKey, String languageCode, VariableContext context) {
        String value = _get(translationKey, languageCode);
        return context == null ? value : context.replace(value);
    }

    @Override
    public List<Component> getIntoList(String translationKey) {
        return getIntoList(translationKey, defaultLocale);
    }

    @Override
    public List<Component> getIntoList(String translationKey, String languageCode) {
        return getIntoList(translationKey, languageCode, null);
    }

    @Override
    public List<Component> getIntoList(String translationKey, String languageCode, VariableContext context) {
        List<Component> components = new ArrayList<>();
        for (String line : _getIntoList(translationKey, languageCode, context)) components.add(render(line));
        return components;
    }

    @Override
    public List<String> _getIntoList(String translationKey) {
        return _getIntoList(translationKey, defaultLocale);
    }

    @Override
    public List<String> _getIntoList(String translationKey, String languageCode) {
        return _getIntoList(translationKey, languageCode, null);
    }

    @Override
    public List<String> _getIntoList(String translationKey, String languageCode, VariableContext context) {
        return new ArrayList<>(Arrays.asList(_get(translationKey, languageCode, context).split("\n")));
    }

    @Override
    public boolean hasLocale(String languageCode) {
        return languageCode != null && locales.containsKey(languageCode.toLowerCase());
    }

    @Override
    public boolean hasKey(String translationKey, String languageCode) {
        if (languageCode == null) return false;
        Map<String, String> translations = locales.get(languageCode.toLowerCase());
        return translations != null && translations.containsKey(translationKey);
    }

    @Override
    public List<String> getLocales() {
        return locales.keySet().stream()
                .sorted(Comparator.comparingInt(this::sortOrderOf)
                        .thenComparing(this::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    @Override
    public String getDisplayName(String languageCode) {
        if (languageCode == null) return defaultLocale;
        Map<String, String> translations = locales.get(languageCode.toLowerCase());
        if (translations == null) return languageCode;
        return translations.getOrDefault(DISPLAY_NAME_KEY, languageCode);
    }

    @Override
    public String getDefaultLocale() {
        return defaultLocale;
    }

    @Override
    public void setDefaultLocale(String languageCode) {
        this.defaultLocale = languageCode == null ? "en_us" : languageCode.toLowerCase();
        // Fallbacks were resolved against the old default, so every cached value is now suspect.
        this.resolved.clear();
    }

    /**
     * The locale to read for a player, given whatever code your platform hands you: the code itself
     * when it loaded, otherwise the default. Lets callers pass {@code player.locale().toString()}
     * straight through without checking first.
     */
    public String localeOrDefault(String languageCode) {
        return hasLocale(languageCode) ? languageCode.toLowerCase() : defaultLocale;
    }

    private Component render(String value) {
        // Locale text is written for chat and for item lore alike; lore renders italic unless told
        // otherwise, which no translator expects to have to opt out of.
        return MiniMessageHelper.deserialize(value).decoration(TextDecoration.ITALIC, false);
    }

    private int sortOrderOf(String languageCode) {
        Map<String, String> translations = locales.get(languageCode);
        if (translations == null) return Integer.MAX_VALUE;
        String raw = translations.get(SORT_ORDER_KEY);
        if (raw == null) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Looks a key up, expands any {@code {nested.key}} it contains, and applies casing tags.
     *
     * @param visited keys currently being expanded — a locale that references itself would otherwise
     *                recurse forever
     */
    private String resolveKey(String translationKey, String languageCode, Set<String> visited) {
        if (!visited.add(translationKey)) return "";

        Map<String, String> translations = locales.getOrDefault(languageCode, Map.of());
        String value = translations.get(translationKey);
        if (value == null) {
            // Untranslated in this locale: try the default, and failing that show the key itself so
            // the gap is visible rather than silently blank.
            value = locales.getOrDefault(defaultLocale, Map.<String, String>of()).getOrDefault(translationKey, translationKey);
        }

        Matcher matcher = KEY_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String nestedKey = matcher.group(1);
            String replacement = resolveKey(nestedKey, languageCode, visited);
            // An unresolved nested key keeps its braces — it may well be a VariableContext placeholder
            // that gets filled in later, and mangling it would break that.
            boolean unresolved = replacement.isEmpty() || replacement.equals(nestedKey);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(unresolved ? "{" + nestedKey + "}" : replacement));
        }
        matcher.appendTail(sb);

        visited.remove(translationKey);
        return TextTransformations.apply(sb.toString().replace("<br>", "\n"));
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {

        private final List<LocaleSource> sources = new ArrayList<>();
        private String defaultLocale = "en_us";
        private Consumer<String> logger = message -> {
        };
        private Path exportFolder;
        private boolean exportBundledDefaults;

        private Builder() {
        }

        /** Locale used when none is given, and as the fallback for keys another locale hasn't translated. */
        public Builder defaultLocale(String languageCode) {
            this.defaultLocale = languageCode;
            return this;
        }

        /** Where load warnings go — normally {@code getLogger()::warning}. Silent by default. */
        public Builder logger(Consumer<String> logger) {
            this.logger = logger == null ? message -> {
            } : logger;
            return this;
        }

        /** Adds a source directly. Later sources win per key. */
        public Builder source(LocaleSource source) {
            sources.add(source);
            return this;
        }

        /** Locales shipped inside your jar, e.g. {@code locales/en_us.json}. */
        public Builder bundled(ClassLoader classLoader, String basePath, String... languageCodes) {
            return bundled(classLoader, basePath, Arrays.asList(languageCodes));
        }

        public Builder bundled(ClassLoader classLoader, String basePath, Collection<String> languageCodes) {
            return source(new ResourceLocaleSource(classLoader, basePath, languageCodes));
        }

        /** Locales fetched from a manifest you host. See {@link RemoteLocaleSource}. */
        public Builder remote(String manifestUrl) {
            return source(new RemoteLocaleSource(manifestUrl));
        }

        /** As above, with the source exposed so you can set headers, a timeout, or an offline cache. */
        public Builder remote(String manifestUrl, Consumer<RemoteLocaleSource> configurer) {
            RemoteLocaleSource source = new RemoteLocaleSource(manifestUrl);
            configurer.accept(source);
            return source(source);
        }

        /**
         * The server owner's folder of {@code *.json} locales. Also the folder bundled defaults are
         * exported into when {@link #exportBundledDefaults(boolean)} is on.
         */
        public Builder folder(Path folder) {
            this.exportFolder = folder;
            return source(new FolderLocaleSource(folder));
        }

        /**
         * Writes bundled locales into the {@link #folder(Path)} on load when the file isn't there,
         * giving server owners something to edit. Never overwrites an existing file.
         */
        public Builder exportBundledDefaults(boolean exportBundledDefaults) {
            this.exportBundledDefaults = exportBundledDefaults;
            return this;
        }

        public LocaleManager build() {
            return new LocaleManager(this);
        }
    }
}
