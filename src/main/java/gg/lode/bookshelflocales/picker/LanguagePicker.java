package gg.lode.bookshelflocales.picker;

import gg.lode.bookshelfapi.api.compat.DialogCompat;
import gg.lode.bookshelfapi.api.compat.HeadCompat;
import gg.lode.bookshelfapi.api.util.MiniMessageHelper;
import gg.lode.bookshelflocales.LocaleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A ready-made language picker dialog any plugin can open over its own {@link LocaleManager}.
 *
 * <p>It lists an "automatic" choice first, meaning each player sees their own game language,
 * then every loaded language by its own name, in the files' sort order, each with its flag head
 * on 1.21.9 and newer. The current choice is highlighted. What picking actually changes is up to
 * the caller, which gets the chosen code, or {@link #AUTOMATIC}.
 *
 * <pre>{@code
 * LanguagePicker.open(player, locales, config.getString("language"),
 *         "Language", "Automatic", (who, code) -> config.set("language", code));
 * }</pre>
 */
public final class LanguagePicker {

    /** Passed to the callback when the player picks the automatic choice. */
    public static final String AUTOMATIC = "auto";

    private LanguagePicker() {
    }

    /**
     * @param current        the code currently in use, or {@link #AUTOMATIC}; null counts as automatic
     * @param title          the dialog title, MiniMessage
     * @param automaticLabel the label for the automatic choice, MiniMessage
     * @param onPick         gets the player and the chosen code, or {@link #AUTOMATIC}
     * @return false when the server can't show dialogs (before 1.21.7)
     */
    public static boolean open(Player player, LocaleManager locales, @Nullable String current,
                               String title, String automaticLabel, BiConsumer<Player, String> onPick) {
        if (!DialogCompat.isSupported()) return false;
        String selected = current == null || !locales.hasLocale(current) ? AUTOMATIC : current.toLowerCase();

        List<DialogCompat.Button> buttons = new ArrayList<>();
        buttons.add(DialogCompat.Button.of(
                highlight(MiniMessageHelper.deserialize(automaticLabel), AUTOMATIC.equals(selected)), null,
                (response, clicker) -> onPick.accept(clicker, AUTOMATIC)));

        for (String code : locales.getLocales()) {
            Component name = highlight(Component.text(locales.getDisplayName(code)), code.equals(selected));
            Component head = HeadCompat.texture(locales.getHead(code));
            Component label = head == null ? name : Component.empty().append(head).append(Component.space()).append(name);
            buttons.add(DialogCompat.Button.of(label, null, (response, clicker) -> onPick.accept(clicker, code)));
        }

        return DialogCompat.multiAction(player, title, null, null, buttons, 2, true);
    }

    private static Component highlight(Component label, boolean selected) {
        return selected ? label.colorIfAbsent(NamedTextColor.GREEN) : label;
    }
}
