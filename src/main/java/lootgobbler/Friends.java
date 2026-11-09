package lootgobbler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your in-game friends, by name (lower case): asked from the game's web API (friends/getList) with the login token
 * the sniffer sees when you connect, and kept in friends.txt so they are known before the next login too.
 */
public final class Friends {
    private static final Path FILE = Path.of("friends.txt");
    private static final Path RAW = Path.of("friends-list.xml");   // the last answer as it came, to check its form
    private static final long ASK_EVERY_MS = 10 * 60_000L;
    private static final Friends INSTANCE = new Friends();

    private volatile Set<String> names = Set.of();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile long asked;

    private Friends() {
        try {
            if (Files.exists(FILE)) {
                Set<String> s = new HashSet<>();
                for (String line : Files.readAllLines(FILE)) if (!line.isBlank()) s.add(line.trim().toLowerCase(Locale.ROOT));
                names = Set.copyOf(s);
            }
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    public static Friends get() {
        return INSTANCE;
    }

    public boolean isFriend(String name) {
        return name != null && names.contains(name.toLowerCase(Locale.ROOT));
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** Asks the game for your friend list in the background (at most every 10 minutes). */
    public void fetch(String token) {
        long now = System.currentTimeMillis();
        if (token == null || now - asked < ASK_EVERY_MS) return;
        asked = now;
        Thread t = new Thread(() -> {
            try {
                String xml = tomato.realmshark.HttpCharListRequest.webRequest(token, "friends/getList");
                if (xml == null || xml.isBlank() || xml.contains("<Error")) {
                    AppLog.log("game", "Friend list refused: " + (xml == null ? "no answer" : xml.strip()));
                    return;
                }
                Files.writeString(RAW, xml);
                Set<String> found = parse(xml);
                AppLog.log("game", "Friend list: " + found.size() + " friends");
                if (found.equals(names)) return;
                names = Set.copyOf(found);
                Files.write(FILE, found.stream().sorted().toList());
                for (Runnable r : listeners) r.run();
            } catch (Exception e) {
                AppLog.log("game", "Friend list failed: " + e.getMessage());
            }
        }, "friend-list");
        t.setDaemon(true);
        t.start();
    }

    /** The names in the answer: each friend's <Name> element (or a name="" attribute). */
    static Set<String> parse(String xml) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("<Name>\\s*([^<\\s]+)\\s*</Name>|\\bname=\"([^\"]+)\"").matcher(xml);
        while (m.find()) out.add((m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT));
        return out;
    }
}
