package gg.lode.bookshelflocales.source;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses one locale file: a JSON object of {@code "key": "value"} pairs, the same shape Minecraft's
 * own lang files use.
 *
 * <p>Nested objects are flattened with dots ({@code {"menu":{"title":"Hi"}}} becomes
 * {@code menu.title}), so a developer can group keys visually without changing how they're looked
 * up. Non-string leaves (numbers, booleans) are read as their text form; arrays are joined with
 * newlines, which is how multi-line lore is written most naturally.
 */
public final class LocaleJson {

    private static final Gson GSON = new Gson();

    private LocaleJson() {
    }

    public static Map<String, String> parse(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        Map<String, String> flat = new LinkedHashMap<>();
        if (root == null || !root.isJsonObject()) return flat;
        flatten("", root.getAsJsonObject(), flat);
        return flat;
    }

    public static String write(Map<String, String> translations) {
        JsonObject object = new JsonObject();
        translations.forEach(object::addProperty);
        return GSON.toJson(object);
    }

    private static void flatten(String prefix, JsonObject object, Map<String, String> out) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();

            if (value.isJsonObject()) {
                flatten(key, value.getAsJsonObject(), out);
            } else if (value.isJsonArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonElement element : value.getAsJsonArray()) {
                    if (joined.length() > 0) joined.append('\n');
                    joined.append(element.isJsonNull() ? "" : element.getAsString());
                }
                out.put(key, joined.toString());
            } else if (!value.isJsonNull()) {
                out.put(key, value.getAsString());
            }
        }
    }
}
