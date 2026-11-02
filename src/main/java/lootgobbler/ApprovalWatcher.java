package lootgobbler;

import lootgobbler.GobblerServer.State;
import lootgobbler.GobblerServer.User;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * For moderators and admins: checks once a minute (and right after logging in) whether new players wait for
 * approval, and keeps a notification about them in the bell. A player not notified about before makes it count as
 * new (the red dot).
 */
public final class ApprovalWatcher {
    private static final String KEY = "approvals";
    private static final long INTERVAL_S = 60;
    private static final ApprovalWatcher INSTANCE = new ApprovalWatcher();

    private final GobblerServer server = GobblerServer.get();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "approval-watcher");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> seen = new HashSet<>();   // players already notified about, for this account
    private volatile String account = "";
    private Runnable open = () -> {
    };
    private boolean started;

    public static ApprovalWatcher get() {
        return INSTANCE;
    }

    private ApprovalWatcher() {
    }

    /** Starts watching; open shows the approval queue (when the notification is clicked). Called once on start. */
    public synchronized void start(Runnable open) {
        if (started) return;
        started = true;
        this.open = open;
        server.onChange(() -> exec.execute(this::accountChanged));
        exec.scheduleWithFixedDelay(this::check, 5, INTERVAL_S, TimeUnit.SECONDS);
    }

    private void accountChanged() {
        User u = moderator();
        String now = u == null ? "" : server.url() + "|" + u.id;
        if (now.equals(account)) return;
        account = now;
        synchronized (seen) {
            seen.clear();
        }
        Notifications.get().remove(KEY);
        check();
    }

    private User moderator() {
        User u = server.state() == State.ONLINE ? server.user() : null;
        return u != null && u.atLeastModerator() ? u : null;
    }

    private void check() {
        if (moderator() == null) return;
        server.pendingRequests().thenAccept(this::update).exceptionally(e -> null);
    }

    /** The current approval queue (also passed in by the Server tab whenever it loads it). */
    public void update(List<User> pending) {
        if (moderator() == null) return;
        if (pending.isEmpty()) {
            Notifications.get().remove(KEY);
            return;
        }
        boolean fresh = false;
        synchronized (seen) {
            for (User p : pending) fresh |= seen.add(p.id);
        }
        String title = pending.size() == 1 ? pending.get(0).ign + " is waiting for approval"
                : pending.size() + " players are waiting for approval";
        String names = String.join(", ", pending.stream().limit(5).map(p -> p.ign).toList())
                + (pending.size() > 5 ? ", ..." : "");
        Notifications.get().post(KEY, title, pending.size() == 1 ? "Accept or deny them under Server > Moderation"
                : names, open, fresh);
    }
}
