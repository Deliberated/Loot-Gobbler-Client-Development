package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import util.PropertiesManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Discord webhooks loot cards are posted to. Saved in the settings file as JSON.
 * Each hook's connection status is checked on load and after saving.
 */
public final class Webhooks {
    public enum Status {UNKNOWN, CHECKING, CONNECTED, FAILED}

    public static final class Hook {
        public String url = "";
        public String name = "";
        transient Status status = Status.UNKNOWN;
        transient String detail = "";

        public Status status() {
            return status;
        }

        /**
         * Discord's name for the webhook when connected, otherwise the error.
         */
        public String detail() {
            return detail;
        }

        public String title() {
            if (!name.isBlank()) return name;
            if (status == Status.CONNECTED && !detail.isBlank()) return detail;
            return "Discord webhook";
        }
    }

    private static final String KEY = "discordWebhooks";
    private static final String LEGACY_KEY = "discordWebhook";
    private static final Gson GSON = new Gson();
    private static final Webhooks INSTANCE = new Webhooks();

    private final List<Hook> hooks = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static Webhooks get() {
        return INSTANCE;
    }

    private Webhooks() {
        String json = PropertiesManager.getProperty(KEY);
        if (json != null && !json.isBlank()) {
            try {
                List<Hook> loaded = GSON.fromJson(json, new TypeToken<ArrayList<Hook>>() {
                }.getType());
                if (loaded != null) for (Hook h : loaded) add(h);
            } catch (Exception e) {
                System.err.println("Could not read Discord webhooks: " + e.getMessage());
            }
        }
        String legacy = PropertiesManager.getProperty(LEGACY_KEY);
        if (hooks.isEmpty() && Discord.isWebhookUrl(legacy)) {
            Hook h = new Hook();
            h.url = legacy;
            add(h);
            save();
        }
        hooks.forEach(this::check);
    }

    public List<Hook> all() {
        return List.copyOf(hooks);
    }

    public List<Hook> active() {
        return hooks.stream().filter(h -> Discord.isWebhookUrl(h.url)).toList();
    }

    public void put(Hook hook, String url, String name) {
        hook.url = url.trim();
        hook.name = name.trim();
        if (!hooks.contains(hook)) hooks.add(hook);
        save();
        check(hook);
    }

    public void remove(Hook hook) {
        hooks.remove(hook);
        save();
    }

    public void check(Hook hook) {
        hook.status = Status.CHECKING;
        hook.detail = "";
        fire();
        Discord.fetchName(hook.url).whenComplete((name, err) -> {
            hook.status = err == null ? Status.CONNECTED : Status.FAILED;
            hook.detail = err == null ? name : "Not reachable - check the URL";
            fire();
        });
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    private void add(Hook h) {
        if (h.url == null) h.url = "";
        if (h.name == null) h.name = "";
        h.status = Status.UNKNOWN;
        h.detail = "";
        hooks.add(h);
    }

    private void save() {
        PropertiesManager.setProperties(KEY, GSON.toJson(new ArrayList<>(hooks)));
        fire();
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }
}
