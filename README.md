# Bookshelf-Locales

Embeddable localization library for Lodestone plugins. Platform-agnostic — works on Paper, Velocity,
or any JVM plugin that already has Adventure on the classpath.

Locale files are flat JSON, the same shape as Minecraft's own lang files:

```json
{
  "locale.display_name": "English",
  "locale.sort_order": "0",
  "myplugin.welcome": "<green>Welcome, <player>!",
  "myplugin.motd": "<gray>Line one<br><gray>Line two"
}
```

Nested objects are flattened with dots (`{"menu": {"title": "Hi"}}` → `menu.title`), and arrays join
with newlines, which reads well for lore.

## Install

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.Lodestones</groupId>
    <artifactId>Bookshelf-Locales</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Setup

```java
LocaleManager locales = LocaleManager.builder()
        .defaultLocale("en_us")
        .bundled(getClass().getClassLoader(), "locales", "en_us", "ja_jp") // your defaults, in your jar
        .remote("https://cdn.example.com/locales/manifest.json")           // optional, see below
        .folder(getDataFolder().toPath().resolve("locales"))               // server owner's overrides
        .exportBundledDefaults(true)
        .logger(getLogger()::warning)
        .build();
```

Sources are read in the order they're added and **a later source wins per key**, so the order above
reads: server owner beats hosted beats bundled. A key nobody translated falls back to the default
locale, and then to the raw key itself — a plugin with no locale files at all still runs, it just
renders keys.

`exportBundledDefaults(true)` writes your bundled files into the folder when they aren't there yet,
so owners have something real to edit.

## Versioning

Give the bundled file a `locale.version` and bump it whenever you add or correct keys:

```json
{
  "locale.version": "2",
  "myplugin.welcome": "<green>Welcome, <player>!"
}
```

On start, an exported file with a lower version is brought up to date by a three-way merge against
`locales/.defaults/<code>.json`, a snapshot of what was last exported:

| In the owner's file | What happens |
|---|---|
| key missing | added |
| value still equal to the old default | replaced with the new default — nobody chose it |
| value differs from the old default | kept — somebody did |
| key you no longer ship | left alone |

That distinction needs the snapshot. Comparing against the *current* default can only say "this
differs", not whether the owner chose it — so a default that ships wrong, a broken placeholder or a
typo, could never be corrected without also stamping on real edits. Files exported before the
snapshot existed fall back to adding missing keys only.

A file whose version already matches is not read past the version check and never rewritten.

## Reading

```java
locales.get("myplugin.welcome", locale);                            // Component
locales.get("myplugin.welcome", locale, VariableContext.of("player", name));
locales._get("myplugin.welcome", locale);                           // raw MiniMessage string
locales.getIntoList("myplugin.motd", locale);                       // one Component per line
locales.localeOrDefault(player.locale().toString());                // code, or the default if unknown
```

`locales.getLocales()` returns loaded codes ordered by `locale.sort_order` then display name — the
order a language-picker menu should list them in. `locales.getDisplayName(code)` gives the locale's
own name for itself.

## Locale file features

| Syntax | Effect |
|---|---|
| `<player>`, `<count>` | `VariableContext` placeholders |
| `{other.key}` | Embeds another translation. Self-reference is detected, not looped |
| `<br>` | Line break |
| `<uppercase>`, `<lowercase>`, `<capitalize>` | Casing, applied before MiniMessage renders |
| MiniMessage tags | Colors, gradients, hovers — anything Adventure supports |

## Reloading

```java
locales.reloadFromDisk();   // bundled + folder + in-memory only. Fast, no network, works offline
locales.reloadFromCloud();  // remote manifest only. Blocks on HTTP
locales.reload();           // everything
```

Each has an async twin — `reloadFromDiskAsync()`, `reloadFromCloudAsync()`, `reloadAsync()` — returning
a `CompletableFuture<Void>`. Use `reloadFromCloudAsync()` from a command handler; never fetch on the
main thread.

A partial reload keeps what the other sources last returned and re-merges in the configured order, so
precedence never shifts. If a source suddenly returns nothing — host unreachable with no cache, folder
emptied — the last good copy is kept and a warning is logged, rather than the plugin dropping to raw
keys.

For a `/reloadlocales` command, `reloadFromDisk()` is usually what an admin means: they just edited a
file. Reach for `reloadFromCloud()` after publishing new hosted translations.

## Hosting your own locales (cloud loader)

Hard-code one manifest URL and ship no locale files at all. New languages and fixed typos then reach
servers on the next reload, with no plugin update:

```json
{
  "en_us": "https://cdn.example.com/locales/en_us.json",
  "ja_jp": "https://cdn.example.com/locales/ja_jp.json"
}
```

Relative entries (`"en_us.json"`) resolve against the manifest's own URL.

```java
.remote("https://cdn.example.com/locales/manifest.json", source -> source
        .header("Authorization", "Bearer " + token)
        .timeout(Duration.ofSeconds(5))
        .cacheFolder(getDataFolder().toPath().resolve("locales-cache")))
```

With a cache folder set, the last successful download is written to disk and served when the host is
unreachable — a CDN outage costs you fresh text, not all text.

`reload()` blocks on those HTTP calls; use `reloadAsync()` off the main thread.
