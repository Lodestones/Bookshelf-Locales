package gg.lode.bookshelflocales.source;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Somewhere translations can be read from — a folder of JSON files, resources bundled inside the
 * plugin jar, or an in-memory map.
 *
 * <p>A {@link gg.lode.bookshelflocales.LocaleManager} loads every source it was given, in the order
 * they were added, and a later source's value for a key wins. That ordering is what lets a server
 * owner override a single line of the developer's bundled locale by writing only that one key into
 * their own file.
 */
public interface LocaleSource {

    /**
     * Reads every locale this source can offer, keyed by language code (e.g. {@code en_us}).
     *
     * <p>Implementations must not throw for a missing or malformed file: a broken locale should cost
     * that locale, not the whole plugin's startup. Return whatever could be read and hand the rest to
     * {@code problems}.
     */
    Map<String, Map<String, String>> load(Consumer<String> problems);

    /** Short human-readable name, used in load warnings. */
    String describe();
}
