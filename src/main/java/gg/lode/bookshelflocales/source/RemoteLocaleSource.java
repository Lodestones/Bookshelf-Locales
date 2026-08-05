package gg.lode.bookshelflocales.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    private final String manifestUrl;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private Duration timeout = DEFAULT_TIMEOUT;
    private Path cacheFolder;
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

    public String manifestUrl() {
        return manifestUrl;
    }

    @Override
    public Map<String, Map<String, String>> load(Consumer<String> problems) {
        Map<String, String> manifest = fetchManifest(problems);
        if (manifest == null) return loadCache(problems, "manifest could not be fetched");

        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            String languageCode = entry.getKey().toLowerCase();
            String url = resolve(entry.getValue());
            try {
                String body = get(url);
                Map<String, String> translations = LocaleJson.parse(new StringReader(body));
                loaded.put(languageCode, translations);
                writeCache(languageCode, translations, problems);
            } catch (Exception e) {
                problems.accept("Could not fetch locale " + languageCode + " from " + url + ": " + reason(e));
                Map<String, String> cached = readCache(languageCode, problems);
                if (cached != null) loaded.put(languageCode, cached);
            }
        }
        return loaded;
    }

    @Override
    public String describe() {
        return "remote manifest " + manifestUrl;
    }

    private Map<String, String> fetchManifest(Consumer<String> problems) {
        try {
            JsonElement root = JsonParser.parseString(get(manifestUrl));
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
            problems.accept("Could not fetch locale manifest " + manifestUrl + ": " + reason(e));
            return null;
        }
    }

    /** Connection failures carry no message, and "null" tells an admin nothing about why. */
    private static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private String get(String url) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET();
        headers.forEach(request::header);

        HttpResponse<String> response = client().send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body();
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
        problems.accept("Serving cached remote locales (" + reason + ")");
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
