package gg.lode.bookshelflocales.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Locales fetched over HTTP from a manifest the developer hosts themselves — the "cloud loader"
 * path. A plugin hard-codes one manifest URL and ships no locale files at all; new languages and
 * fixed typos reach servers on the next reload without a plugin update.
 *
 * <p>The manifest is a flat JSON object of language code to download URL:
 * <pre>
 * {
 *   "en_us": "https://cdn.example.com/locales/en_us.json",
 *   "ja_jp": "https://cdn.example.com/locales/ja_jp.json"
 * }
 * </pre>
 * Each linked document is an ordinary locale file, parsed by {@link LocaleJson}. Relative URLs are
 * resolved against the manifest's own URL, so a manifest can just list {@code "en_us.json"}.
 *
 * <p>Fetched locales are written to a cache folder when one is configured, and that cache is what
 * gets served if the host is unreachable later — a CDN outage must not leave a server with no text
 * at all. Registered before the server owner's folder source, since these are still <em>defaults</em>:
 * a key the owner wrote locally outranks the hosted copy.
 */
public final class RemoteLocaleSource implements LocaleSource {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 500;

    private final String manifestUrl;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private Duration timeout = DEFAULT_TIMEOUT;
    private Path cacheFolder;
    private boolean background;
    private HttpClient client;

    public RemoteLocaleSource(String manifestUrl) {
        this.manifestUrl = manifestUrl;
    }

    /** Adds a request header sent with both the manifest and every locale download (e.g. an auth token). */
    public RemoteLocaleSource header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /** Per-request timeout. Applies to the manifest and to each locale separately. */
    public RemoteLocaleSource timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Folder holding the last successful download of each locale. Serving this cache is what keeps a
     * server running normally while the host is down.
     */
    public RemoteLocaleSource cacheFolder(Path cacheFolder) {
        this.cacheFolder = cacheFolder;
        return this;
    }

    /**
     * Leaves this source out of the load that runs while the manager is built, and fetches it right
     * after on another thread instead. Startup then never waits on the host; until the fetch lands,
     * the bundled defaults are what players see.
     */
    public RemoteLocaleSource background(boolean background) {
        this.background = background;
        return this;
    }

    public boolean isBackground() {
        return background;
    }

    public boolean hasCacheFolder() {
        return cacheFolder != null;
    }

    public String manifestUrl() {
        return manifestUrl;
    }

    /**
     * Never throws and never fails the load. A host that can't be reached costs one warning
     * naming what fell back, and the cached or bundled text carries on in its place.
     */
    @Override
    public Map<String, Map<String, String>> load(Consumer<String> problems) {
        Map<String, String> manifest;
        try {
            manifest = fetchManifest(problems);
        } catch (Exception e) {
            boolean cached = cacheFolder != null && Files.isDirectory(cacheFolder);
            problems.accept("Couldn't reach " + host() + " for translation updates (" + reason(e) + "). "
                    + (cached ? "Using the last downloaded copy." : "Using the built-in text."));
            return cached ? new FolderLocaleSource(cacheFolder).load(problems) : new LinkedHashMap<>();
        }
        if (manifest == null) return loadCache(problems, "the manifest isn't readable");

        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        Map<String, String> fromCache = new LinkedHashMap<>();
        Map<String, String> builtIn = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            String languageCode = entry.getKey().toLowerCase();
            try {
                Map<String, String> translations = LocaleJson.parse(new StringReader(get(resolve(entry.getValue()))));
                loaded.put(languageCode, translations);
                writeCache(languageCode, translations, problems);
            } catch (Exception e) {
                Map<String, String> cached = readCache(languageCode, problems);
                if (cached != null) {
                    loaded.put(languageCode, cached);
                    fromCache.put(languageCode, reason(e));
                } else {
                    builtIn.put(languageCode, reason(e));
                }
            }
        }
        if (!fromCache.isEmpty()) {
            problems.accept("Couldn't download " + describe(fromCache) + " from " + host() + ". Using the last downloaded copy.");
        }
        if (!builtIn.isEmpty()) {
            problems.accept("Couldn't download " + describe(builtIn) + " from " + host() + ". Using the built-in text until the next reload.");
        }
        return loaded;
    }

    /** {@code pl_pl (Connection reset)}, or {@code 3 languages (pl_pl, ru_ru, tr_tr: Connection reset)}. */
    private static String describe(Map<String, String> failures) {
        String codes = String.join(", ", failures.keySet());
        String reasons = String.join("; ", new LinkedHashSet<>(failures.values()));
        return failures.size() == 1 ? codes + " (" + reasons + ")"
                : failures.size() + " languages (" + codes + ": " + reasons + ")";
    }

    private String host() {
        try {
            String host = URI.create(manifestUrl).getHost();
            return host == null ? manifestUrl : host;
        } catch (Exception e) {
            return manifestUrl;
        }
    }

    @Override
    public String describe() {
        return "remote manifest " + manifestUrl;
    }

    @Override
    public boolean isRemote() {
        return true;
    }

    /** Null when the manifest arrived but can't be used; throws when it couldn't be fetched. */
    private Map<String, String> fetchManifest(Consumer<String> problems) throws Exception {
        String body = get(manifestUrl);
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) {
                problems.accept("Locale manifest at " + manifestUrl + " is not a JSON object of code -> url");
                return null;
            }
            Map<String, String> manifest = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || !value.isJsonPrimitive()) {
                    problems.accept("Locale manifest entry " + entry.getKey() + " is not a URL string");
                    continue;
                }
                manifest.put(entry.getKey(), value.getAsString());
            }
            return manifest;
        } catch (Exception e) {
            problems.accept("Locale manifest at " + manifestUrl + " isn't valid JSON: " + reason(e));
            return null;
        }
    }

    /** Connection failures carry no message, and "null" tells an admin nothing about why. */
    private static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * One GET, tried up to {@link #ATTEMPTS} times. A dropped connection, a timeout, a 429 or a
     * 5xx is usually gone a moment later, and one bad response shouldn't cost a language.
     */
    private String get(String url) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .GET();
                headers.forEach(request::header);

                HttpResponse<String> response = client().send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status / 100 == 2) return response.body();
                last = new IllegalStateException("HTTP " + status);
                if (status != 429 && status / 100 != 5) throw last;
            } catch (IOException e) {
                last = e;
            }
            if (attempt < ATTEMPTS) Thread.sleep(RETRY_DELAY_MS * attempt);
        }
        throw last;
    }

    private HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return client;
    }

    /** A manifest may list a bare file name; resolve it against the manifest's own location. */
    private String resolve(String url) {
        try {
            return URI.create(manifestUrl).resolve(url).toString();
        } catch (Exception e) {
            return url;
        }
    }

    private Map<String, Map<String, String>> loadCache(Consumer<String> problems, String reason) {
        if (cacheFolder == null || !Files.isDirectory(cacheFolder)) return new LinkedHashMap<>();
        problems.accept("Couldn't use the translation updates from " + host() + " (" + reason + "). Using the last downloaded copy.");
        return new FolderLocaleSource(cacheFolder).load(problems);
    }

    private Map<String, String> readCache(String languageCode, Consumer<String> problems) {
        if (cacheFolder == null) return null;
        Path file = cacheFolder.resolve(languageCode + ".json");
        if (!Files.isRegularFile(file)) return null;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return LocaleJson.parse(reader);
        } catch (Exception e) {
            problems.accept("Could not read cached locale " + languageCode + ": " + e.getMessage());
            return null;
        }
    }

    private void writeCache(String languageCode, Map<String, String> translations, Consumer<String> problems) {
        if (cacheFolder == null) return;
        try {
            Files.createDirectories(cacheFolder);
            Files.writeString(cacheFolder.resolve(languageCode + ".json"), LocaleJson.write(translations), StandardCharsets.UTF_8);
        } catch (Exception e) {
            problems.accept("Could not cache locale " + languageCode + ": " + e.getMessage());
        }
    }
}
