package gg.lode.bookshelflocales;

import gg.lode.bookshelfapi.api.util.VariableContext;
import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Reads translations. Mirrors the shape Lodestone plugins already use: {@code get(...)} returns a
 * rendered {@link Component}, {@code _get(...)} returns the raw MiniMessage string.
 *
 * <p>Every lookup falls back the same way: the requested locale, then the default locale, then the
 * translation key itself. A plugin with no locale files at all is therefore still usable — it just
 * renders raw keys, which is also the fastest way for a translator to see what needs writing.
 */
public interface LocaleService {

    /** Rendered translation in {@code languageCode}. */
    Component get(String translationKey, String languageCode);

    /** Rendered translation in the default locale. */
    Component get(String translationKey);

    Component get(String translationKey, String languageCode, VariableContext context);

    /** Raw MiniMessage string in {@code languageCode}. */
    String _get(String translationKey, String languageCode);

    /** Raw MiniMessage string in the default locale. */
    String _get(String translationKey);

    String _get(String translationKey, String languageCode, VariableContext context);

    /** One component per line — a locale value splits on {@code \n} and on {@code <br>}. */
    List<Component> getIntoList(String translationKey);

    List<Component> getIntoList(String translationKey, String languageCode);

    List<Component> getIntoList(String translationKey, String languageCode, VariableContext context);

    List<String> _getIntoList(String translationKey);

    List<String> _getIntoList(String translationKey, String languageCode);

    List<String> _getIntoList(String translationKey, String languageCode, VariableContext context);

    /** Whether {@code languageCode} was loaded at all. */
    boolean hasLocale(String languageCode);

    /** Whether {@code languageCode} defines {@code translationKey} itself (no fallback). */
    boolean hasKey(String translationKey, String languageCode);

    /**
     * Locale codes that loaded, ordered by each locale's {@code locale.sort_order} then display name.
     * This is the order a language-picker menu should list them in.
     */
    List<String> getLocales();

    /** The locale's own name for itself ({@code locale.display_name}), or its code when unset. */
    String getDisplayName(String languageCode);

    String getDefaultLocale();

    void setDefaultLocale(String languageCode);

    /** Re-reads every source. Safe to call at runtime; readers never see a half-loaded state. */
    void reload();
}
