package lootgobbler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tomato.version.Version;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Update check and in-app update from the GitHub releases of Loot-Gobbler-Client.
 * <p>
 * Checks shortly after start and then every hour. Updating downloads LootGobbler-portable.zip from the latest
 * release (the same program files the setup installs, without any user data), checks it against GitHub's SHA-256,
 * unpacks it to update\new next to the exe and starts update.ps1. That waits for Loot Gobbler to close, swaps the
 * program files (exe, app\, runtime\, license files) for the new ones, and starts Loot Gobbler again. Settings,
 * collection and the other data files next to the exe are not touched. If the swap fails, the old files are put back.
 */
public final class Updater {
    public enum State {IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, RESTARTING, FAILED}

    public record Release(String version, String pageUrl, String zipUrl, long size, String sha256) {
    }

    private static final String API = System.getProperty("lootgobbler.updateApi",   // overridable for testing
            "https://api.github.com/repos/Deliberated/Loot-Gobbler-Client/releases/latest");
    private static final String RELEASES_PAGE = "https://github.com/Deliberated/Loot-Gobbler-Client/releases/latest";
    private static final String ZIP_NAME = "LootGobbler-portable.zip";
    private static final long FIRST_CHECK_S = 2, INTERVAL_S = 60 * 60;
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static volatile State state = State.IDLE;
    private static volatile Release latest;
    private static volatile double progress;
    private static volatile String message = "";
    private static ScheduledExecutorService schedule;

    private Updater() {
    }

    public static State state() {
        return state;
    }

    public static Release latest() {
        return latest;
    }

    /**
     * Download progress, 0 to 1.
     */
    public static double progress() {
        return progress;
    }

    /**
     * What went wrong for FAILED, otherwise a short description of the state.
     */
    public static String message() {
        return message;
    }

    /**
     * Called on a background thread whenever the state or the download progress changes.
     */
    public static void onChange(Runnable r) {
        listeners.add(r);
    }

    /**
     * Starts the automatic checks: right after start (the button shows CHECKING... until then), then every hour.
     */
    public static synchronized void start() {
        if (schedule != null) return;
        set(State.CHECKING, "Checking for updates");
        schedule = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "update-check");
            t.setDaemon(true);
            return t;
        });
        schedule.scheduleWithFixedDelay(() -> check(false), FIRST_CHECK_S, INTERVAL_S, TimeUnit.SECONDS);
    }

    /**
     * The status bar's Update button: updates when one is available, otherwise checks now.
     */
    public static void clicked(Window window) {
        switch (state) {
            case AVAILABLE -> install(window);
            case IDLE, UP_TO_DATE, FAILED -> runInBackground(() -> check(true));
            default -> {   // busy
            }
        }
    }

    /**
     * Info -> About's "Check for updates": checks now and answers in a dialog.
     */
    public static void check(Component parent) {
        Window window = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        runInBackground(() -> {
            check(true);
            SwingUtilities.invokeLater(() -> {
                String text = switch (state) {
                    case AVAILABLE -> "Loot Gobbler " + latest.version() + " is out (you have " + Version.VERSION
                            + ").\n\nClick UPDATE AVAILABLE at the bottom right to update and restart.";
                    case FAILED -> "Could not check for updates: " + message;
                    default -> "You have the newest version, Loot Gobbler " + Version.VERSION + ".";
                };
                JOptionPane.showMessageDialog(window, text, "Check for updates", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    /**
     * Asks GitHub for the latest release. An automatic check that fails keeps the button as it was, except for the
     * first one (then it falls back to IDLE, "check updates").
     */
    private static synchronized void check(boolean manual) {
        if (state == State.DOWNLOADING || state == State.RESTARTING) return;
        State before = state;
        if (manual) set(State.CHECKING, "Checking for updates");
        try {
            Release r = fetchLatest();
            if (r != null && compare(r.version(), Version.VERSION) > 0) {
                latest = r;
                set(State.AVAILABLE, "Loot Gobbler " + r.version() + " is out. Click to update and restart.");
            } else {
                set(State.UP_TO_DATE, "You have the newest version (" + Version.VERSION + "). Click to check again.");
            }
        } catch (Exception e) {
            if (manual) set(State.FAILED, describe(e));
            else if (before == State.CHECKING) set(State.IDLE, "Could not check for updates: " + describe(e));
        }
    }

    private static Release fetchLatest() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "LootGobbler/" + Version.VERSION).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return null;   // no release published yet
        if (res.statusCode() != 200) throw new IOException("GitHub answered " + res.statusCode());
        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
        String tag = json.get("tag_name").getAsString();
        String page = json.has("html_url") ? json.get("html_url").getAsString() : RELEASES_PAGE;
        for (JsonElement el : json.getAsJsonArray("assets")) {
            JsonObject a = el.getAsJsonObject();
            if (!ZIP_NAME.equals(a.get("name").getAsString())) continue;
            String digest = a.has("digest") && !a.get("digest").isJsonNull() ? a.get("digest").getAsString() : null;
            String sha = digest != null && digest.startsWith("sha256:") ? digest.substring(7) : null;
            return new Release(tag, page, a.get("browser_download_url").getAsString(), a.get("size").getAsLong(), sha);
        }
        return new Release(tag, page, null, 0, null);   // release without the zip: only the page can be offered
    }

    public record Notes(String version, String published, String body, String pageUrl) {
    }

    /**
     * The release notes of every published version after "from" up to and including "to", newest first.
     */
    public static List<Notes> releaseNotes(String from, String to) throws IOException, InterruptedException {
        String list = API.replaceFirst("/latest$", "") + "?per_page=50";
        HttpRequest req = HttpRequest.newBuilder(URI.create(list)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "LootGobbler/" + Version.VERSION).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("GitHub answered " + res.statusCode());
        List<Notes> notes = new java.util.ArrayList<>();
        for (JsonElement el : JsonParser.parseString(res.body()).getAsJsonArray()) {
            JsonObject r = el.getAsJsonObject();
            if (r.has("draft") && r.get("draft").getAsBoolean()) continue;
            String tag = r.get("tag_name").getAsString();
            if (compare(tag, from) <= 0 || compare(tag, to) > 0) continue;
            notes.add(new Notes(tag, text(r, "published_at"), text(r, "body"), text(r, "html_url")));
        }
        notes.sort((a, b) -> compare(b.version(), a.version()));
        return notes;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    public static String releasesPage() {
        return RELEASES_PAGE.replaceFirst("/latest$", "");
    }

    /**
     * Compares versions like "v0.4.0" and "0.10.1" number by number.
     */
    public static int compare(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("[.\\-+]"), y = b.replaceFirst("^[vV]", "").split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(number(x, i), number(y, i));
            if (c != 0) return c;
        }
        return 0;
    }

    private static int number(String[] parts, int i) {
        try {
            return i < parts.length ? Integer.parseInt(parts[i]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Downloads and unpacks the new version, then hands over to update.ps1 and closes the app. Started from source
     * (no LootGobbler.exe), or for a release without the zip, the release page is opened instead.
     */
    private static void install(Window window) {
        Release r = latest;
        String exe = System.getProperty("jpackage.app-path");   // set by the jpackage launcher only
        if (r == null) return;
        if (exe == null || r.zipUrl() == null) {
            openPage(r.pageUrl());
            return;
        }
        set(State.DOWNLOADING, "Downloading Loot Gobbler " + r.version());
        runInBackground(() -> {
            try {
                Path appDir = Path.of(exe).toAbsolutePath().getParent();
                Path updateDir = appDir.resolve("update");
                deleteTree(updateDir);
                Files.createDirectories(updateDir);
                Path zip = updateDir.resolve(ZIP_NAME);
                download(r, zip);
                Path newDir = updateDir.resolve("new");
                unzip(zip, newDir);
                if (!Files.isRegularFile(newDir.resolve("LootGobbler.exe")) || !Files.isDirectory(newDir.resolve("app"))) {
                    throw new IOException("the download does not contain Loot Gobbler");
                }
                // a release built before the version was raised would install the old version again, and the app
                // would offer the same update after every restart
                String inside = packagedVersion(newDir.resolve("app").resolve("LootGobbler.cfg"));
                if (inside != null && compare(inside, r.version()) != 0) {
                    throw new IOException("release " + r.version() + " contains version " + inside
                            + " - it has to be rebuilt with the right version");
                }
                Path script = updateDir.resolve("update.ps1");
                try (InputStream in = Updater.class.getResourceAsStream("/lootgobbler/update.ps1")) {
                    if (in == null) throw new IOException("update.ps1 is missing from the app");
                    Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
                }
                set(State.RESTARTING, "Restarting with Loot Gobbler " + r.version());
                new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-WindowStyle", "Hidden", "-File", script.toString(),
                        "-ProcessId", Long.toString(ProcessHandle.current().pid()),
                        "-AppDir", appDir.toString(), "-NewDir", newDir.toString())
                        .directory(new java.io.File(System.getProperty("java.io.tmpdir")))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                Thread.sleep(800);   // let RESTARTING show for a moment
                SwingUtilities.invokeLater(() -> close(window));
            } catch (Exception e) {
                set(State.FAILED, "Update failed: " + describe(e));
                latest = r;
            }
        });
    }

    /**
     * The version a jpackage app image was built as (its LootGobbler.cfg), or null if it cannot be read.
     */
    private static String packagedVersion(Path cfg) {
        try {
            for (String line : Files.readAllLines(cfg)) {
                int i = line.indexOf("jpackage.app-version=");
                if (i >= 0) return line.substring(i + "jpackage.app-version=".length()).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static void download(Release r, Path target) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(r.zipUrl())).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "LootGobbler/" + Version.VERSION).build();
        HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("download answered " + res.statusCode());
        long total = r.size() > 0 ? r.size() : res.headers().firstValueAsLong("Content-Length").orElse(0);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0, lastReport = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                if (total > 0 && done - lastReport > total / 200) {   // about every half percent
                    lastReport = done;
                    progress = Math.min(1, (double) done / total);
                    fire();
                }
            }
        }
        progress = 1;
        fire();
        if (r.sha256() != null && !HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(r.sha256())) {
            throw new IOException("the download is damaged (checksum does not match)");
        }
    }

    /**
     * Unpacks the zip into dir, dropping its top folder (LootGobbler\...).
     */
    private static void unzip(Path zip, Path dir) throws IOException {
        Files.createDirectories(dir);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (slash < 0 || slash == name.length() - 1) continue;   // the top folder itself
                Path out = dir.resolve(name.substring(slash + 1)).normalize();
                if (!out.startsWith(dir)) throw new IOException("bad path in the download: " + name);
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /**
     * Closes the main window the normal way, so everything that saves on close still runs.
     */
    private static void close(Window window) {
        Window w = window;
        if (w == null) {
            for (Frame f : Frame.getFrames()) if (f.isVisible()) w = f;
        }
        if (w != null) {
            w.dispatchEvent(new java.awt.event.WindowEvent(w, java.awt.event.WindowEvent.WINDOW_CLOSING));
        }
        System.exit(0);
    }

    private static void openPage(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            set(State.FAILED, "Open " + url + " to download the update");
        }
    }

    private static String describe(Exception e) {
        if (e instanceof java.net.ConnectException || e instanceof java.net.http.HttpConnectTimeoutException
                || e instanceof java.nio.channels.UnresolvedAddressException) {
            return "no connection to GitHub";
        }
        if (e instanceof AccessDeniedException) return "Loot Gobbler's folder is not writable";
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private static void runInBackground(Runnable r) {
        Thread t = new Thread(r, "updater");
        t.setDaemon(true);
        t.start();
    }

    private static void set(State s, String text) {
        state = s;
        message = text;
        if (s != State.DOWNLOADING) progress = 0;
        fire();
    }

    private static void fire() {
        for (Runnable r : listeners) r.run();
    }
}
