package lootgobbler;

import com.google.gson.JsonElement;
import lootgobbler.GobblerServer.DataItem;
import lootgobbler.GobblerServer.ServerException;
import lootgobbler.GobblerServer.User;
import util.PropertiesManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the local data (collection, characters, exalts, pets, DPS history, profile) in sync with the Loot Gobbler server,
 * which is the main store.
 * <ul>
 * <li>After logging in: download everything, merge it into the local data, upload the merged result.</li>
 * <li>Local changes are uploaded at most once a minute. If the server is not reachable, they stay in the local
 * files and are uploaded on the next retry.</li>
 * <li>If another PC uploaded in between (revision conflict), the server's version is merged in and uploaded again.</li>
 * </ul>
 * The local files belong to one account: logging in to a different one pauses syncing until the player confirms,
 * so two players' data never get mixed by accident.
 */
public final class ServerSync {
    public enum Phase {OFF, SYNCING, UP_TO_DATE, WAITING, OFFLINE, OTHER_ACCOUNT}

    /** Sync state of one kind of data. */
    public static final class Kind {
        public final Syncable store;
        private volatile long revision;         // server revision our data is based on, 0 = none yet
        private volatile JsonElement synced;    // what the server has at that revision (to skip unchanged uploads)
        private volatile long syncedAt;
        private volatile long lastDown;         // server data last changed the local data (epoch ms), 0 = never
        private volatile long lastUp;           // local data last uploaded (epoch ms), 0 = never
        private volatile long checkedAt;        // start of the latest sync run that looked at this kind, 0 = none
        private volatile boolean dirty;

        Kind(Syncable store) {
            this.store = store;
        }

        public long revision() {
            return revision;
        }

        /** When data from the server last changed the local data (server -> client), 0 = not since login. */
        public long lastDown() {
            return lastDown;
        }

        /** When local data was last uploaded (client -> server), 0 = not since login. */
        public long lastUp() {
            return lastUp;
        }

        /** Start of the latest sync run that looked at this kind (0 = none); transfers since then are from it. */
        public long checkedAt() {
            return checkedAt;
        }

        /** When this kind last matched the server (epoch ms), 0 = not yet. */
        public long syncedAt() {
            return syncedAt;
        }
    }

    private static final long UPLOAD_DELAY_MS = 60_000;   // changes come in bursts while playing
    private static final long RETRY_MS = 60_000;
    private static final int MAX_CONFLICT_RETRIES = 3;
    private static final String KEY_OWNER = "serverSyncOwner";   // "<server url>|<user id>|<ign>" the files belong to
    private static final ServerSync INSTANCE = new ServerSync();

    private final GobblerServer server = GobblerServer.get();
    private final List<Kind> kinds = List.of(new Kind(CollectionStore.get()), new Kind(CharacterStore.get()),
            new Kind(ExaltStore.get()), new Kind(PetStore.get()), new Kind(DpsHistory.get()),
            new Kind(PlayerProfile.get()));
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "server-sync");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> scheduled;
    private volatile String account = "";   // "<url>|<user id>" while logged in, else ""
    private volatile Phase phase = Phase.OFF;
    private volatile String message = "";
    private volatile long lastSync;
    private boolean started;

    public static ServerSync get() {
        return INSTANCE;
    }

    private ServerSync() {
    }

    /** Starts following logins and local changes. Called once on start. */
    public synchronized void start() {
        if (started) return;
        started = true;
        server.onChange(this::serverChanged);
        for (Kind k : kinds) k.store.onChange(() -> localChanged(k));
        serverChanged();
    }

    public List<Kind> kinds() {
        return kinds;
    }

    public Phase phase() {
        return phase;
    }

    /** Details for the current phase (error, or which account the files belong to). */
    public String message() {
        return message;
    }

    /** When everything last matched the server (epoch ms), 0 = not yet. */
    public long lastSync() {
        return lastSync;
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** Downloads, merges and uploads everything now. */
    public void syncNow() {
        exec.execute(this::fullSync);
    }

    /**
     * The files belong to another account: the player confirmed they are theirs, so they become this account's
     * and are merged with it.
     */
    public void adoptForThisAccount() {
        User u = server.user();
        if (u == null || account.isEmpty()) return;
        PropertiesManager.setProperties(KEY_OWNER, account + "|" + u.ign);
        syncNow();
    }

    private void serverChanged() {
        User u = server.state() == GobblerServer.State.ONLINE ? server.user() : null;
        String now = u == null ? "" : server.url() + "|" + u.id;
        if (now.equals(account)) return;
        account = now;
        for (Kind k : kinds) {
            k.revision = 0;
            k.synced = null;
            k.syncedAt = 0;
            k.lastDown = 0;
            k.lastUp = 0;
            k.checkedAt = 0;
        }
        if (now.isEmpty()) {
            set(Phase.OFF, "");
        } else {
            syncNow();
        }
    }

    private void localChanged(Kind k) {
        k.dirty = true;
        if (account.isEmpty() || phase == Phase.OTHER_ACCOUNT || phase == Phase.SYNCING) return;
        schedule(UPLOAD_DELAY_MS);
    }

    private synchronized void schedule(long delayMs) {
        if (scheduled != null && !scheduled.isDone()) return;
        scheduled = exec.schedule((Runnable) this::upload, delayMs, TimeUnit.MILLISECONDS);
        if (phase == Phase.UP_TO_DATE) set(Phase.WAITING, "");
    }

    // --- on the sync thread ---

    /** Called first by every run, so a change during the run can schedule the next one. */
    private synchronized void running() {
        scheduled = null;
    }

    private void fullSync() {
        running();
        long runStart = System.currentTimeMillis();
        String acc = account;
        if (acc.isEmpty()) return;
        String owner = PropertiesManager.getProperty(KEY_OWNER);
        if (owner != null && !owner.isBlank() && !owner.startsWith(acc + "|")) {
            String[] parts = owner.split("\\|");
            set(Phase.OTHER_ACCOUNT, parts.length >= 3 ? parts[2] + " on " + parts[0] : owner);
            return;
        }
        set(Phase.SYNCING, "");
        try {
            Map<String, DataItem> remote = server.data().join();
            if (!acc.equals(account)) return;   // logged out or switched account meanwhile
            for (Kind k : kinds) {
                k.checkedAt = runStart;
                DataItem item = remote.get(k.store.syncKind());
                if (item != null && item.data != null) {
                    if (k.store.mergeSync(item.data)) k.lastDown = System.currentTimeMillis();
                    k.revision = item.revision;
                    k.synced = item.data;
                } else {
                    k.revision = 0;
                    k.synced = null;
                }
                k.dirty = true;   // upload decides whether there is anything new
            }
            User u = server.user();
            PropertiesManager.setProperties(KEY_OWNER, acc + "|" + (u == null ? "" : u.ign));
            upload(runStart);
        } catch (Exception e) {
            failed(e);
        }
    }

    private void upload() {
        upload(System.currentTimeMillis());
    }

    /**
     * @param runStart when this run started; transfers from then on belong to it (shown in the Server tab).
     */
    private void upload(long runStart) {
        running();
        if (account.isEmpty() || phase == Phase.OTHER_ACCOUNT) return;
        try {
            for (Kind k : kinds) {
                if (!k.dirty) continue;
                if (k.checkedAt < runStart) k.checkedAt = runStart;
                uploadKind(k, 0);
            }
            lastSync = System.currentTimeMillis();
            set(kinds.stream().anyMatch(k -> k.dirty) ? Phase.WAITING : Phase.UP_TO_DATE, "");
            if (phase == Phase.WAITING) schedule(UPLOAD_DELAY_MS);   // changed again while uploading
        } catch (Exception e) {
            failed(e);
        }
    }

    private void uploadKind(Kind k, int attempt) {
        k.dirty = false;   // a change from now on marks it again
        JsonElement data = k.store.exportSync();
        if (data.equals(k.synced)) {
            k.syncedAt = System.currentTimeMillis();
            return;
        }
        try {
            k.revision = server.putData(k.store.syncKind(), k.revision, data).join();
            k.synced = data;
            k.syncedAt = System.currentTimeMillis();
            k.lastUp = k.syncedAt;
        } catch (Exception e) {
            Throwable cause = GobblerServer.unwrap(e);
            if (cause instanceof ServerException se && "revision_conflict".equals(se.code) && se.current != null
                    && attempt < MAX_CONFLICT_RETRIES) {
                // another PC uploaded in between: take its version in, then upload the combination
                if (k.store.mergeSync(se.current.data)) k.lastDown = System.currentTimeMillis();
                k.revision = se.current.revision;
                k.synced = se.current.data;
                uploadKind(k, attempt + 1);
                return;
            }
            k.dirty = true;
            throw e;
        }
    }

    private void failed(Exception e) {
        Throwable cause = GobblerServer.unwrap(e);
        if (!(cause instanceof ServerException)) {   // not the server's answer: a bug or a connection problem
            AppLog.log("sync", "Failed:");
            cause.printStackTrace();
        }
        if (cause instanceof ServerException se && se.status == 401) {
            set(Phase.OFF, "");   // logged out; GobblerServer already reported it
            return;
        }
        set(Phase.OFFLINE, GobblerServer.message(cause));
        synchronized (this) {
            if (scheduled == null || scheduled.isDone()) {
                scheduled = exec.schedule(this::fullSync, RETRY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void set(Phase p, String msg) {
        if (p != phase || !msg.equals(message)) AppLog.log("sync", p + (msg.isEmpty() ? "" : ": " + msg));
        phase = p;
        message = msg;
        for (Runnable r : listeners) r.run();
    }
}
