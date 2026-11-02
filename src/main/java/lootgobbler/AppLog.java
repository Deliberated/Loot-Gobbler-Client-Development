package lootgobbler;

import util.PropertiesManager;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Optional log files: when turned on in Settings, everything the app prints (System.out and System.err, including
 * errors and stack traces) is also written to one file per day, "LootGobbler-2026-11-02.log", in the chosen folder.
 * Off by default. Files older than the chosen number of days (14 unless changed, 0 = keep all) are deleted.
 */
public final class AppLog {
    public static final String ENABLED_KEY = "logging", DIR_KEY = "logDir", KEEP_KEY = "logKeepDays";
    public static final int DEFAULT_KEEP_DAYS = 14;   // 0 keeps every log
    private static final String PREFIX = "LootGobbler-", SUFFIX = ".log";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static volatile boolean enabled;
    private static volatile Path dir;
    private static Writer file;
    private static LocalDate fileDay;

    private AppLog() {
    }

    /**
     * Reads the settings and starts copying System.out and System.err. Call once, first thing at startup.
     */
    public static void install() {
        enabled = "true".equals(PropertiesManager.getProperty(ENABLED_KEY));
        dir = savedDir();
        System.setOut(new PrintStream(new Tee(System.out, ""), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(System.err, "ERR "), true, StandardCharsets.UTF_8));
        // anything that would end a thread silently (the Swing thread too) is written down with its stack trace
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("[crash] Uncaught in thread \"" + t.getName() + "\":");
            e.printStackTrace();
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("app", "Closing");
            flush();
        }, "log-flush"));
        banner();
    }

    /**
     * One log line, "[area] message", e.g. log("server", "GET /guild/caravan -> 200 (84 ms)").
     */
    public static void log(String area, String message) {
        System.out.println("[" + area + "] " + message);
    }

    /**
     * What a bug report needs first: version, Java, Windows, memory, where the app runs from, theme.
     */
    private static void banner() {
        Runtime rt = Runtime.getRuntime();
        log("app", "Loot Gobbler " + tomato.version.Version.VERSION + " starting");
        log("app", "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ", "
                + System.getProperty("sun.arch.data.model") + "-bit), " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + ", " + rt.availableProcessors() + " cores, max memory "
                + rt.maxMemory() / (1024 * 1024) + " MB");
        log("app", "Folder " + Path.of("").toAbsolutePath() + (System.getProperty("jpackage.app-path") == null
                ? " (started from source)" : ""));
        log("app", "Theme " + PropertiesManager.getProperty("theme") + ", sniffer autostart "
                + "T".equals(PropertiesManager.getProperty("sniffer")));
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        PropertiesManager.setProperties(ENABLED_KEY, Boolean.toString(on));
        synchronized (AppLog.class) {
            enabled = on;
            if (!on) close();
        }
        if (on) {
            log("app", "Logging turned on (" + dir + ")");
            banner();
        }
    }

    /**
     * How many days of logs are kept; 0 keeps them all.
     */
    public static int keepDays() {
        try {
            return Math.max(0, Integer.parseInt(PropertiesManager.getProperty(KEEP_KEY)));
        } catch (Exception e) {
            return DEFAULT_KEEP_DAYS;
        }
    }

    public static void setKeepDays(int days) {
        PropertiesManager.setProperties(KEEP_KEY, Integer.toString(Math.max(0, days)));
        synchronized (AppLog.class) {
            prune();
        }
    }

    /**
     * Deletes the log files of days older than keepDays(); today's file always stays. Days come from the file names.
     */
    private static void prune() {
        int keep = keepDays();
        if (keep == 0) return;
        LocalDate oldest = LocalDate.now().minusDays(keep - 1);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (!n.startsWith(PREFIX) || !n.endsWith(SUFFIX)) continue;
                try {
                    LocalDate day = LocalDate.parse(n.substring(PREFIX.length(), n.length() - SUFFIX.length()));
                    if (day.isBefore(oldest)) Files.deleteIfExists(p);
                } catch (Exception notADay) {   // someone else's file with a similar name: leave it
                }
            }
        } catch (IOException ignore) {   // no folder yet
        }
    }

    public static Path dir() {
        return dir;
    }

    public static void setDir(Path folder) {
        PropertiesManager.setProperties(DIR_KEY, folder.toAbsolutePath().toString());
        synchronized (AppLog.class) {
            close();   // the next line opens the file in the new folder
            dir = folder.toAbsolutePath();
        }
    }

    public static Path defaultDir() {
        return Path.of("logs").toAbsolutePath();   // next to the app's other files
    }

    private static Path savedDir() {
        String saved = PropertiesManager.getProperty(DIR_KEY);
        try {
            if (saved != null && !saved.isBlank()) return Path.of(saved);
        } catch (Exception ignore) {
        }
        return defaultDir();
    }

    /**
     * The log files in the log folder, newest first.
     */
    public static List<Path> files() {
        flush();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
            }).sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public static synchronized void flush() {
        try {
            if (file != null) file.flush();
        } catch (IOException ignore) {
        }
    }

    private static synchronized void write(String prefix, String line) {
        if (!enabled) return;
        try {
            LocalDate today = LocalDate.now();
            if (file == null || !today.equals(fileDay)) {
                close();
                Files.createDirectories(dir);
                prune();   // at start and at midnight
                file = Files.newBufferedWriter(dir.resolve(PREFIX + today + SUFFIX), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                fileDay = today;
            }
            file.write(LocalTime.now().format(TIME) + " " + prefix + line + System.lineSeparator());
            if (!prefix.isEmpty()) file.flush();   // errors right away, in case the app is about to die
        } catch (IOException e) {
            enabled = false;   // folder not writable: stop rather than fail on every line
        }
    }

    private static void close() {
        try {
            if (file != null) file.close();
        } catch (IOException ignore) {
        }
        file = null;
    }

    /**
     * Passes everything on to the console, and collects whole lines for the log file.
     */
    private static final class Tee extends OutputStream {
        private final PrintStream console;
        private final String prefix;
        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

        Tee(PrintStream console, String prefix) {
            this.console = console;
            this.prefix = prefix;
        }

        @Override
        public synchronized void write(int b) {
            console.write(b);
            collect(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            console.write(b, off, len);
            for (int i = off; i < off + len; i++) collect(b[i]);
        }

        private void collect(int b) {
            if (!enabled) {
                line.reset();
                return;
            }
            if (b == '\n') {
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                AppLog.write(prefix, text.endsWith("\r") ? text.substring(0, text.length() - 1) : text);
            } else if (line.size() < 64_000) {
                line.write(b);
            }
        }

        @Override
        public void flush() {
            console.flush();
        }
    }
}
