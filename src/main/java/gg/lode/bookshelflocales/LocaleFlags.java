package gg.lode.bookshelflocales;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * Flag heads for the common languages, as skin texture hashes from minecraft-heads.com.
 *
 * <p>These are only defaults. A locale file can set its own with a {@code locale.head} key, and
 * that always wins, so an owner can swap a flag or give one to a language that isn't listed here.
 */
public final class LocaleFlags {

    private static final Map<String, String> FLAGS = Map.ofEntries(
            Map.entry("en_us", "cd91456877f54bf1ace251e4cee40dba597d2cc40362cb8f4ed711e50b0be5b3"),
            Map.entry("es_es", "32bd4521983309e0ad76c1ee29874287957ec3d96f8d889324da8c887e485ea8"),
            Map.entry("pt_br", "9a46475d5dcc815f6c5f2859edbb10611f3e861c0eb14f088161b3c0ccb2b0d9"),
            Map.entry("fr_fr", "6903349fa45bdd87126d9cd3c6c0abba7dbd6f56fb8d78701873a1e7c8ee33cf"),
            Map.entry("de_de", "5e7899b4806858697e283f084d9173fe487886453774626b24bd8cfecc77b3f"),
            Map.entry("it_it", "85ce89223fa42fe06ad65d8d44ca412ae899c831309d68924dfe0d142fdbeea4"),
            Map.entry("nl_nl", "c23cf210edea396f2f5dfbced69848434f93404eefeabf54b23c073b090adf"),
            Map.entry("pl_pl", "921b2af8d2322282fce4a1aa4f257a52b68e27eb334f4a181fd976bae6d8eb"),
            Map.entry("ru_ru", "16eafef980d6117dabe8982ac4b4509887e2c4621f6a8fe5c9b735a83d775ad"),
            Map.entry("tr_tr", "6bbeaf52e1c4bfcd8a1f4c6913234b840241aa48829c15abc6ff8fdf92cd89e"),
            Map.entry("ja_jp", "d640ae466162a47d3ee33c4076df1cab96f11860f07edb1f0832c525a9e33323"),
            Map.entry("ko_kr", "ca12913d7df640d18bcc7a45a8172c68ffa04756e84c6f0a2eda3da45e00dadd"),
            Map.entry("zh_cn", "7f9bc035cdc80f1ab5e1198f29f3ad3fdd2b42d9a69aeb64de990681800b98dc"),
            Map.entry("zh_tw", "702a4afb2e1e2e3a1894a8b74272f95cfa994ce53907f9ac140bd3c932f9f")
    );

    private LocaleFlags() {
    }

    /**
     * The flag for a locale code, or for another region of the same language when there's no
     * exact match, so {@code es_mx} still gets a flag. Null when nothing fits.
     */
    public static @Nullable String forLocale(@Nullable String code) {
        if (code == null) return null;
        String lower = code.toLowerCase(Locale.ROOT);
        String exact = FLAGS.get(lower);
        if (exact != null) return exact;

        int split = lower.indexOf('_');
        String language = split < 0 ? lower : lower.substring(0, split);
        return FLAGS.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(language + "_"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
