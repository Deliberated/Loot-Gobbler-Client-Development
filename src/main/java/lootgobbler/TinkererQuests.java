package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Tinkerer's quests (the Daily Quest Room): hand in items for rewards. The game sends them when you enter the
 * room (QuestFetchResponse); kept in tinkerer-quests.json, so they show until the next visit.
 */
public final class TinkererQuests {
    private static final Path FILE = Path.of("tinkerer-quests.json");
    private static final Gson GSON = new Gson();
    public static final int TINKERER = 0x1754;   // the Tinkerer's object type (his sprite)

    public static final class Quest {
        public String id = "", name = "", description = "", expiration = "";
        public int category;
        public int[] requirements = new int[0], rewards = new int[0];   // item ids: to hand in / to get
        public boolean completed, itemOfChoice, repeatable;
    }

    private static final class Holder {
        static final TinkererQuests INSTANCE = new TinkererQuests();
    }

    private volatile List<Quest> quests = List.of();
    private volatile long seenAt;   // when the game last sent them (ms), 0 = never
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static TinkererQuests get() {
        return Holder.INSTANCE;
    }

    private TinkererQuests() {
        try {
            if (Files.exists(FILE)) {
                List<Quest> loaded = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), new TypeToken<ArrayList<Quest>>() {
                }.getType());
                if (loaded != null) quests = List.copyOf(loaded);
                seenAt = Files.getLastModifiedTime(FILE).toMillis();
            }
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    public List<Quest> quests() {
        return quests;
    }

    public long seenAt() {
        return seenAt;
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** The quests as the game sent them on entering the Daily Quest Room. */
    public void received(packets.data.QuestData[] data) {
        if (data == null) return;
        List<Quest> list = new ArrayList<>();
        for (packets.data.QuestData d : data) {
            if (d == null) continue;
            Quest q = new Quest();
            q.id = d.id == null ? "" : d.id;
            q.name = d.name == null ? "" : d.name;
            q.description = d.description == null ? "" : d.description;
            q.expiration = d.expiration == null ? "" : d.expiration;
            q.category = d.category;
            q.requirements = d.requirements == null ? new int[0] : d.requirements;
            q.rewards = d.rewards == null ? new int[0] : d.rewards;
            q.completed = d.completed;
            q.itemOfChoice = d.itemOfChoice;
            q.repeatable = d.repeatable;
            list.add(q);
        }
        quests = List.copyOf(list);
        seenAt = System.currentTimeMillis();
        try {
            Files.writeString(FILE, GSON.toJson(list), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Could not write " + FILE + ": " + e.getMessage());
        }
        AppLog.log("game", "Tinkerer quests: " + list.size());
        for (Runnable r : listeners) r.run();
    }
}
