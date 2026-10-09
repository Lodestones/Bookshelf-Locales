package gg.lode.bookshelflocales.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Every {@code *.json} in a folder, with the file name (minus extension) as the language code.
 *
 * <p>This is the server owner's side of the system: they drop {@code fr_fr.json} into the folder and
 * that locale exists, no developer change needed. Because a folder source is normally registered
 * last, a key present here overrides the same key in the developer's bundled defaults, and keys the
 * owner didn't write still fall back to those defaults.
 *
 * <p>An exported file holds every key, not only the ones the owner changed. Given the folder of
 * snapshots the export keeps, a key still matching its snapshot counts as untouched and is left
 * out, so it falls through to the sources below: a fix published to a hosted locale reaches the
 * server instead of being pinned by a copy nobody edited.
 */
public final class FolderLocaleSource implements LocaleSource {

    private final Path folder;
    private final Path snapshotFolder;

    public FolderLocaleSource(Path folder) {
        this(folder, null);
    }

    /** @param snapshotFolder the export's record of what it wrote, or null to keep every key */
    public FolderLocaleSource(Path folder, Path snapshotFolder) {
        this.folder = folder;
        this.snapshotFolder = snapshotFolder;
    }

    public Path folder() {
        return folder;
    }

    @Override
    public Map<String, Map<String, String>> load(Consumer<String> problems) {
        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        if (!Files.isDirectory(folder)) return loaded;

        try (Stream<Path> files = Files.list(folder)) {
            files.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        String languageCode = name.substring(0, name.length() - ".json".length()).toLowerCase();
                        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                            loaded.put(languageCode, withoutUntouched(languageCode, LocaleJson.parse(reader), problems));
                        } catch (Exception e) {
                            problems.accept("Could not read locale file " + name + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            problems.accept("Could not list locale folder " + folder + ": " + e.getMessage());
        }
        return loaded;
    }

    /** Drops the keys still equal to what the export wrote, keeping only the owner's own. */
    private Map<String, String> withoutUntouched(String languageCode, Map<String, String> translations,
                                                 Consumer<String> problems) {
        if (snapshotFolder == null) return translations;
        Path snapshot = snapshotFolder.resolve(languageCode + ".json");
        if (!Files.isRegularFile(snapshot)) return translations;
        Map<String, String> exported;
        try (BufferedReader reader = Files.newBufferedReader(snapshot, StandardCharsets.UTF_8)) {
            exported = LocaleJson.parse(reader);
        } catch (Exception e) {
            problems.accept("Could not read export snapshot for " + languageCode + ": " + e.getMessage());
            return translations;
        }
        Map<String, String> edited = new LinkedHashMap<>(translations);
        edited.entrySet().removeIf(entry -> entry.getValue().equals(exported.get(entry.getKey())));
        return edited;
    }

    @Override
    public String describe() {
        return "folder " + folder;
    }
}
