package lootgobbler.ui;

import lootgobbler.CharacterStore;
import lootgobbler.GobblerServer;
import lootgobbler.GobblerServer.IssuedToken;
import lootgobbler.GobblerServer.OpenToken;
import lootgobbler.GobblerServer.QuestTemplate;
import lootgobbler.GobblerServer.State;
import lootgobbler.GobblerServer.User;
import lootgobbler.ServerSync;
import lootgobbler.Themes;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Server tab: the Loot Gobbler server this client is connected to, logging in / registering, and for moderators
 * and admins the approval queue, members and moderator tokens.
 */
public class ServerPanel extends JPanel {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH);
    private static final String[] VALIDITY = {"1 hour", "24 hours", "3 days", "7 days"};
    private static final int[] VALIDITY_HOURS = {1, 24, 72, 168};

    private final GobblerServer server = GobblerServer.get();

    private final JPanel connectionBody = body();
    private final JPanel accountBody = body();
    private final JPanel syncBody = body();
    private final JPanel moderationBody = body();
    private final JPanel tokensBody = body();
    private final Section sync;
    private final Section moderation;
    private final Section tokens;
    private final Section pool;
    private final JPanel poolBody = body();
    private final JLabel poolFeedback = feedback();
    private List<QuestTemplate> questPool = List.of();
    private final JLabel connectionFeedback = feedback();
    private final JLabel accountFeedback = feedback();
    private final JLabel moderationFeedback = feedback();
    private final JLabel tokensFeedback = feedback();
    private final JPanel scoringBody = body();
    private final JLabel scoringFeedback = feedback();
    private final JSpinner feedWeight = weightSpinner(), difficultyWeight = weightSpinner(), rarityWeight = weightSpinner();
    private final Section scoring;
    private final JPanel guildBody = body();
    private final JLabel guildFeedback = feedback();
    private final JSpinner baselineHpm = new JSpinner(new SpinnerNumberModel(50.0, 1.0, 1000.0, 1.0));
    private final Section guild;
    private final JPanel testResults = column();   // checklist from the last "Test connection"

    // Kept across rebuilds, so typed text survives a status change.
    private final JTextField address = field(28, "loot.example.com");
    private final JTextField ign = field(14, "Your IGN");
    private String shownName = CharacterStore.get().playerName();   // the name the account form was built with
    private final JPasswordField password = new JPasswordField(14);
    private final JTextField registerToken = field(30, "Admin or moderator token (optional)");
    private final JTextField redeemToken = field(30, "LGM-... or LGA-...");
    private final JTextField tokenNote = field(18, "Who it is for (optional)");
    private final JComboBox<String> tokenValidity = new JComboBox<>(VALIDITY);

    private List<User> pending = List.of();
    private List<User> members = List.of();
    private List<OpenToken> openTokens = List.of();
    private IssuedToken lastIssued;
    private String loadedFor = "";   // account + role the moderation lists were loaded for

    public ServerPanel() {
        super(new BorderLayout());
        address.setText(server.url());
        address.addActionListener(e -> connect());
        password.addActionListener(e -> login());
        tokenValidity.setSelectedIndex(1);

        sync = new Section("Sync", Palette.GREEN, syncBody)
                .actions(button("Sync now", null, () -> ServerSync.get().syncNow()));
        moderation = new Section("Moderation", Palette.PURPLE, moderationBody)
                .actions(button("Refresh", null, this::refreshModeration));
        tokens = new Section("Moderator Tokens", Palette.PURPLE, tokensBody);
        pool = new Section("Quest Pool", Palette.GREEN, poolBody).actions(button("Refresh", null, this::refreshModeration));
        scoring = new Section("Item Scores", Palette.GOLD, scoringBody);
        buildScoring();
        showWeights();
        lootgobbler.ItemScore.get().onChange(() -> SwingUtilities.invokeLater(this::showWeights));
        guild = new Section("Guild", Palette.GREEN, guildBody);
        buildGuild();
        showGuildSettings();
        Guildmates.get().onChange(this::showGuildSettings);

        JPanel sections = new JPanel();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        sections.setBorder(new EmptyBorder(8, 8, 8, 8));
        sections.add(new Section("Connection", Palette.BLUE, connectionBody));
        sections.add(Box.createVerticalStrut(8));
        sections.add(new Section("Account", Palette.GOLD, accountBody));
        sections.add(Box.createVerticalStrut(8));
        sections.add(sync);
        sections.add(Box.createVerticalStrut(8));
        sections.add(moderation);
        sections.add(Box.createVerticalStrut(8));
        sections.add(tokens);
        sections.add(Box.createVerticalStrut(8));
        sections.add(pool);
        sections.add(Box.createVerticalStrut(8));
        sections.add(scoring);
        sections.add(Box.createVerticalStrut(8));
        sections.add(guild);

        JPanel holder = new JPanel(new BorderLayout());   // keeps sections at their natural height
        holder.add(sections, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(holder);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll);

        server.onChange(() -> SwingUtilities.invokeLater(this::rebuild));
        CharacterStore.get().onChange(() -> SwingUtilities.invokeLater(() -> {   // your name became known: registering opens
            String name = CharacterStore.get().playerName();
            if (!java.util.Objects.equals(name, shownName)) {
                shownName = name;
                if (server.user() == null) rebuild();
            }
        }));
        ServerSync.get().onChange(() -> SwingUtilities.invokeLater(this::rebuild));
        rebuild();
    }

    private void rebuild() {
        User u = server.state() == State.ONLINE ? server.user() : null;
        boolean mod = u != null && u.atLeastModerator();
        String key = mod ? u.id + ":" + u.rank() : "";
        if (!key.equals(loadedFor)) {
            loadedFor = key;
            pending = List.of();
            members = List.of();
            openTokens = List.of();
            questPool = List.of();
            lastIssued = null;
            if (mod) refreshModeration();
            if (mod && u.atLeastAdmin()) lootgobbler.ItemScore.get().reload();
            if (mod && u.atLeastAdmin()) Guildmates.get().reloadSettings();
        }
        sync.setVisible(u != null);
        moderation.setVisible(mod);
        tokens.setVisible(mod && u.atLeastAdmin());
        pool.setVisible(mod && u.atLeastAdmin());
        scoring.setVisible(mod && u.atLeastAdmin());
        guild.setVisible(mod && u.atLeastAdmin());

        buildConnection();
        buildAccount();
        buildSync();
        buildModeration();
        buildTokens();
        buildPool();
        revalidate();
        repaint();
    }

    // --- connection ---

    private void buildConnection() {
        connectionBody.removeAll();
        if (address.getText().isBlank()) address.setText(server.url());   // connected on start, or from elsewhere
        JPanel addressRow = row();
        addressRow.add(new JLabel("Server address"));
        addressRow.add(address);
        addressRow.add(button("Connect", "primary", this::connect));
        addressRow.add(button("Test connection", null, this::testConnection));
        if (!server.url().isEmpty()) addressRow.add(button("Forget", null, this::forget));
        connectionBody.add(addressRow);

        GobblerServer.Status st = server.status();
        switch (server.state()) {
            case DISCONNECTED -> connectionBody.add(statusLine(Palette.MUTED,
                    "Not connected. Enter the address the server's host gave you."));
            case CONNECTING -> connectionBody.add(statusLine(Palette.MUTED, "Connecting..."));
            case UNREACHABLE -> connectionBody.add(statusLine(Palette.RED, "Not reachable: " + server.error()));
            case ONLINE -> {
                connectionBody.add(statusLine(Palette.GREEN, server.encrypted() ? "Online"
                        : "Online - not encrypted (http://), only use this on your own network"));
                if (st != null) {
                    connectionBody.add(info("Server version", st.version));
                    connectionBody.add(info("Admin", st.adminClaimed ? "Claimed"
                            : "Not claimed yet - the host registers with the token from the server console"));
                    connectionBody.add(info("New accounts", "approval".equals(st.registration)
                            ? "Need to be accepted by a moderator or admin" : st.registration));
                    if (st.apiVersion != GobblerServer.API_VERSION) {
                        connectionBody.add(statusLine(Palette.RED, st.apiVersion > GobblerServer.API_VERSION
                                ? "This server needs a newer Loot Gobbler - please update"
                                : "This server is older than your Loot Gobbler - ask the host to update it"));
                    }
                }
            }
        }
        connectionBody.add(testResults);
        connectionBody.add(connectionFeedback);
    }

    private void connect() {
        connectionFeedback.setText(" ");
        testResults.removeAll();
        server.connect(address.getText()).thenRun(() -> SwingUtilities.invokeLater(() -> address.setText(server.url())));
    }

    /**
     * Connects to the typed address if it changed, then runs the server's connection test and lists what worked.
     */
    private void testConnection() {
        if (address.getText().isBlank()) {
            connectionFeedback.setText("Enter the server address first.");
            return;
        }
        connectionFeedback.setText(" ");
        testResults.removeAll();
        testResults.add(statusLine(Palette.MUTED, "Testing..."));
        revalidate();
        repaint();
        CompletableFuture<Void> ready = GobblerServer.normalize(address.getText()).equals(server.url())
                ? CompletableFuture.completedFuture(null) : server.connect(address.getText());
        ready.thenCompose(v -> server.test()).whenComplete((r, e) -> SwingUtilities.invokeLater(() -> {
            address.setText(server.url());
            showTest(r, e);
        }));
    }

    private void showTest(GobblerServer.TestResult r, Throwable e) {
        testResults.removeAll();
        testResults.add(heading("Connection test"));
        if (e != null) {
            testResults.add(statusLine(Palette.RED, "Server not reached: " + GobblerServer.message(e)));
            testResults.add(statusLine(Palette.MUTED, "Check the address, that the server is running (docker ps) "
                    + "and that nginx proxy manager forwards to it."));
        } else {
            testResults.add(statusLine(Palette.GREEN, "Server reached in " + r.millis + " ms (version "
                    + r.status.version + ")"));
            testResults.add(server.encrypted() ? statusLine(Palette.GREEN, "Connection is encrypted (https)")
                    : statusLine(Palette.GOLD, "Not encrypted (http) - only fine on your own network"));
            testResults.add(r.status.apiVersion == GobblerServer.API_VERSION
                    ? statusLine(Palette.GREEN, "Server and client speak the same API version")
                    : statusLine(Palette.RED, "API version differs (server " + r.status.apiVersion + ", client "
                    + GobblerServer.API_VERSION + ") - update the older one"));
            if (r.privateIp) {
                testResults.add(statusLine(Palette.GOLD, "The server sees you as " + r.yourIp + " (a local address)"));
                testResults.add(statusLine(Palette.MUTED, "Fine on your own network. Over the internet, the host "
                        + "should check TRUSTED_PROXIES."));
            } else {
                testResults.add(statusLine(Palette.GREEN, "The server sees you as " + r.yourIp));
            }
            if (r.user != null) {
                testResults.add(statusLine(Palette.GREEN, "Logged in as " + r.user.ign + " (" + r.user.roleName() + ")"));
            } else if (r.sessionSent) {
                testResults.add(statusLine(Palette.GOLD, "Your login expired - log in again below"));
            } else {
                testResults.add(statusLine(Palette.MUTED, "Not logged in"));
            }
            testResults.add(statusLine(Palette.MUTED, "The host sees this test in the server log (docker logs loot-gobbler)."));
        }
        revalidate();
        repaint();
    }

    private void forget() {
        int ok = JOptionPane.showConfirmDialog(this, "Log out and forget " + server.url() + "?", "Forget server",
                JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        server.disconnect();
        address.setText("");
    }

    // --- account ---

    private void buildAccount() {
        accountBody.removeAll();
        User u = server.user();
        if (server.state() != State.ONLINE) {
            accountBody.add(left(muted("Connect to a server to log in or register.")));
        } else if (u == null) {
            // your name as the game showed it: an account always carries your real IGN, so it can't be typed in
            String known = CharacterStore.get().playerName();
            boolean identified = known != null && !known.isBlank();
            if (identified) ign.setText(known);
            ign.setEnabled(!identified);   // greyed out: taken from the game
            ign.setToolTipText(identified ? "Your name, as the game shows it" : null);
            accountBody.add(formRow("IGN", ign));
            accountBody.add(formRow("Password", password));
            accountBody.add(formRow("Token", registerToken));
            JPanel buttons = row();
            buttons.add(button("Log in", "primary", this::login));
            JButton register = button("Register", null, this::register);
            register.setEnabled(identified);
            if (!identified) register.setToolTipText("Loot Gobbler needs to see your name in-game first");
            buttons.add(register);
            JButton forgot = button("Forgot password?", null, this::forgotPassword);
            forgot.setToolTipText("Set a new password with a reset code from a moderator or admin");
            buttons.add(forgot);
            accountBody.add(buttons);
            if (!identified) {
                accountBody.add(left(muted("To register, start the sniffer and enter the Nexus or your vault in-game:")));
                accountBody.add(left(muted("Loot Gobbler then knows your name and fills it in here.")));
            }
            accountBody.add(left(muted("New accounts wait until a moderator or admin accepts them.")));
            accountBody.add(left(muted("With an admin or moderator token, your new account is ready right away.")));
        } else {
            JLabel name = new JLabel(u.ign);
            name.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 36));
            name.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
            accountBody.add(left(name));
            accountBody.add(info("Role", u.roleName()));
            accountBody.add(info("Member since", date(u.createdAt)));
            if (u.lastLoginAt != null) accountBody.add(info("Last login", date(u.lastLoginAt)));

            JPanel redeemRow = row();
            redeemRow.add(new JLabel("Got a token?"));
            redeemRow.add(redeemToken);
            redeemRow.add(button("Redeem", null, this::redeem));
            accountBody.add(Box.createVerticalStrut(6));
            accountBody.add(redeemRow);

            JPanel buttons = row();
            buttons.add(button("Change password", null, this::changePassword));
            buttons.add(button("Log out", null, () -> {
                accountFeedback.setText(" ");
                server.logout();
            }));
            accountBody.add(buttons);
        }
        accountBody.add(accountFeedback);
    }

    private void login() {
        if (ign.getText().isBlank() || password.getPassword().length == 0) {
            accountFeedback.setText("Enter your IGN and password.");
            return;
        }
        accountFeedback.setText("Logging in...");
        server.login(ign.getText().trim(), new String(password.getPassword())).whenComplete((u, e) ->
                SwingUtilities.invokeLater(() -> {
                    if (e != null) {
                        accountFeedback.setText(GobblerServer.message(e));
                        return;
                    }
                    password.setText("");
                    accountFeedback.setText(" ");
                }));
    }

    private void register() {
        String known = CharacterStore.get().playerName();
        if (known == null || known.isBlank()) {   // only with the name the game showed
            accountFeedback.setText("Enter the Nexus or your vault in-game first, so Loot Gobbler knows your name.");
            return;
        }
        ign.setText(known);
        if (password.getPassword().length == 0) {
            accountFeedback.setText("Enter the IGN and a password (at least 8 characters) for your new account.");
            return;
        }
        accountFeedback.setText("Registering...");
        server.register(ign.getText().trim(), new String(password.getPassword()), registerToken.getText())
                .whenComplete((u, e) -> SwingUtilities.invokeLater(() -> {
                    if (e != null) {
                        accountFeedback.setText(GobblerServer.message(e));
                        return;
                    }
                    password.setText("");
                    registerToken.setText("");
                    accountFeedback.setText("active".equals(u.status) ? " "
                            : "Registered. A moderator or admin needs to accept " + u.ign + " before you can log in.");
                }));
    }

    /**
     * Forgot password: a moderator or admin made a reset code for you. With it, the IGN and a new password (twice),
     * the password is set, and you are logged in with it.
     */
    private void forgotPassword() {
        JTextField name = field(14, "Your IGN");
        name.setText(ign.getText().trim());
        JTextField code = field(20, "LGR-XXXX-XXXX-XXXX");
        JPasswordField pw = new JPasswordField(14), again = new JPasswordField(14);
        JPanel form = form(
                "Ask a moderator or admin of this server for a reset code. It works once, for your IGN, for 24 hours.",
                "IGN", name, "Reset code", code, "New password", pw, "New password again", again);
        if (JOptionPane.showConfirmDialog(this, form, "Forgot password", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String who = name.getText().trim(), newPw = new String(pw.getPassword());
        String problem = who.isEmpty() || code.getText().isBlank() ? "Enter your IGN and the reset code."
                : newPasswordProblem(newPw, new String(again.getPassword()));
        if (problem != null) {
            accountFeedback.setText(problem);
            return;
        }
        accountFeedback.setText("Setting the new password...");
        server.resetPassword(who, code.getText(), newPw)
                .thenCompose(v -> server.login(who, newPw))
                .whenComplete((u, e) -> SwingUtilities.invokeLater(() -> {
                    if (e != null) {
                        accountFeedback.setText(GobblerServer.message(e));
                        return;
                    }
                    password.setText("");
                    accountFeedback.setText("New password set. You are logged in.");
                }));
    }

    /** Change password: the current one, and the new one twice. Your other devices are logged out. */
    private void changePassword() {
        JPasswordField current = new JPasswordField(14), pw = new JPasswordField(14), again = new JPasswordField(14);
        JPanel form = form("Your other devices are logged out and log in again with the new password.",
                "Current password", current, "New password", pw, "New password again", again);
        if (JOptionPane.showConfirmDialog(this, form, "Change password", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String newPw = new String(pw.getPassword());
        String problem = current.getPassword().length == 0 ? "Enter your current password."
                : newPasswordProblem(newPw, new String(again.getPassword()));
        if (problem != null) {
            accountFeedback.setText(problem);
            return;
        }
        accountFeedback.setText("Changing the password...");
        server.changePassword(new String(current.getPassword()), newPw).whenComplete((v, e) ->
                SwingUtilities.invokeLater(() -> accountFeedback.setText(e != null ? GobblerServer.message(e)
                        : "Password changed. Your other devices need to log in again.")));
    }

    /** Why a new password cannot be used (as the server checks it), or null. */
    private static String newPasswordProblem(String pw, String again) {
        int n = pw.codePointCount(0, pw.length());
        if (n < 8 || n > 128) return "The new password must be 8 to 128 characters.";
        if (!pw.equals(again)) return "The two new passwords are not the same.";
        return null;
    }

    /** A small form for a dialog: a note on top, then label and field pairs. */
    private static JPanel form(String note, Object... labelsAndFields) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 10);
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        JLabel top = muted(note);
        top.setBorder(new EmptyBorder(0, 0, 6, 0));
        p.add(top, c);
        c.gridwidth = 1;
        for (int i = 0; i + 1 < labelsAndFields.length; i += 2) {
            c.gridy++;
            c.gridx = 0;
            p.add(new JLabel((String) labelsAndFields[i]), c);
            c.gridx = 1;
            p.add((Component) labelsAndFields[i + 1], c);
        }
        return p;
    }

    private void redeem() {
        if (redeemToken.getText().isBlank()) return;
        accountFeedback.setText("Redeeming...");
        server.redeem(redeemToken.getText()).whenComplete((u, e) -> SwingUtilities.invokeLater(() -> {
            if (e != null) {
                accountFeedback.setText(GobblerServer.message(e));
                return;
            }
            redeemToken.setText("");
            accountFeedback.setText("You are now " + u.roleName().toLowerCase(Locale.ROOT) + ".");
        }));
    }

    // --- sync ---

    private void buildSync() {
        syncBody.removeAll();
        ServerSync s = ServerSync.get();
        switch (s.phase()) {
            case OFF -> syncBody.add(statusLine(Palette.MUTED, "Starts once you are logged in."));
            case SYNCING -> syncBody.add(statusLine(Palette.MUTED, "Syncing..."));
            case UP_TO_DATE -> syncBody.add(statusLine(Palette.GREEN, "Up to date"
                    + (s.lastSync() > 0 ? " - last sync " + TIME.format(Instant.ofEpochMilli(s.lastSync())
                    .atZone(ZoneId.systemDefault())) : "")));
            case WAITING -> syncBody.add(statusLine(Palette.GOLD, "New changes are uploaded within a minute"));
            case OFFLINE -> {
                syncBody.add(statusLine(Palette.RED, "Server not reachable: " + s.message()));
                syncBody.add(statusLine(Palette.MUTED, "Your changes are kept on this PC and uploaded when it is back."));
            }
            case OTHER_ACCOUNT -> {
                syncBody.add(statusLine(Palette.GOLD, "The data on this PC was synced as " + s.message() + "."));
                syncBody.add(statusLine(Palette.MUTED, "Syncing is paused so two players' data are not mixed. "
                        + "If it is yours, merge it into this account."));
                JPanel buttons = row();
                buttons.add(button("Use for this account", "primary", () -> {
                    if (confirm("Merge the data on this PC into " + server.user().ign + "'s account?", "Use data")) {
                        s.adoptForThisAccount();
                    }
                }));
                syncBody.add(buttons);
            }
        }
        for (ServerSync.Kind k : s.kinds()) {
            syncBody.add(info(k.store.syncLabel(), k.store.syncCount() + " - " + transfers(k)));
        }
    }

    /**
     * What the latest sync did with this kind: which way data went (e.g. "server -> client 18:42:01, client ->
     * server 18:42:01"), or that both sides were already the same, with the last transfer before that.
     */
    private static String transfers(ServerSync.Kind k) {
        String rev = k.revision() > 0 ? "revision " + k.revision() : "not on the server yet";
        if (k.checkedAt() == 0) return rev;
        boolean down = k.lastDown() >= k.checkedAt(), up = k.lastUp() >= k.checkedAt();
        if (down || up) return rev + " - " + directions(k, down, up);
        String text = rev + " - already the same on both sides (checked " + time(k.syncedAt()) + ")";
        boolean everDown = k.lastDown() > 0, everUp = k.lastUp() > 0;
        return everDown || everUp ? text + ", last transfer " + directions(k, everDown, everUp) : text;
    }

    /**
     * The given directions with their times, oldest first.
     */
    private static String directions(ServerSync.Kind k, boolean down, boolean up) {
        String d = "server -> client " + time(k.lastDown());
        String u = "client -> server " + time(k.lastUp());
        if (down && up) return k.lastDown() <= k.lastUp() ? d + ", " + u : u + ", " + d;
        return down ? d : u;
    }

    private static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    // --- moderation ---

    private void buildModeration() {
        moderationBody.removeAll();
        User me = server.user();
        if (me == null) return;

        moderationBody.add(heading("Waiting for approval (" + pending.size() + ")"));
        if (pending.isEmpty()) moderationBody.add(left(muted("No one is waiting.")));
        for (User p : pending) {
            JPanel buttons = buttons();
            buttons.add(button("Accept", "primary", () -> act(server.accept(p.id), moderationFeedback,
                    p.ign + " can log in now.")));
            buttons.add(button("Deny", "danger", () -> {
                if (confirm("Deny " + p.ign + "'s registration?", "Deny registration")) {
                    act(server.deny(p.id), moderationFeedback, p.ign + "'s registration was denied.");
                }
            }));
            moderationBody.add(listRow(p.ign, "registered " + date(p.createdAt), buttons));
        }

        moderationBody.add(Box.createVerticalStrut(10));
        moderationBody.add(heading("Members (" + members.size() + ")"));
        for (User m : members) {
            JPanel buttons = buttons();
            boolean outranks = !m.root && m.id != me.id && me.rank() > m.rank();
            if (outranks && me.atLeastAdmin() && m.rank() == 1) {
                buttons.add(button("Demote", null, () -> act(server.setRole(m.id, "user"), moderationFeedback,
                        m.ign + " is a member again.")));
            }
            if (outranks && me.root && m.rank() == 2) {
                buttons.add(button("Demote", null, () -> act(server.setRole(m.id, "moderator"), moderationFeedback,
                        m.ign + " is a moderator now.")));
            }
            if (outranks) {
                buttons.add(button("Reset password", null, () -> {
                    if (confirm("Make a password reset code for " + m.ign + "?\nTheir current password keeps working"
                            + " until they use it.", "Reset password")) {
                        resetPasswordFor(m);
                    }
                }));
                buttons.add(button("Remove", "danger", () -> {
                    if (confirm("Remove " + m.ign + " and all their data from the server?", "Remove member")) {
                        act(server.remove(m.id), moderationFeedback, m.ign + " was removed.");
                    }
                }));
            }
            String sub = m.roleName() + (m.lastLoginAt != null ? " - last login " + date(m.lastLoginAt) : " - never logged in");
            moderationBody.add(listRow(m.ign + (m.id == me.id ? " (you)" : ""), sub, buttons));
        }
        moderationBody.add(moderationFeedback);
    }

    /** Makes a reset code for a member and shows it once (also copied), to pass on. */
    private void resetPasswordFor(User m) {
        moderationFeedback.setText("Making a reset code...");
        server.createPasswordReset(m.id).whenComplete((rc, e) -> SwingUtilities.invokeLater(() -> {
            if (e != null) {
                moderationFeedback.setText(GobblerServer.message(e));
                return;
            }
            moderationFeedback.setText("Reset code made for " + rc.ign + ".");
            JTextField shown = new JTextField(rc.code, 20);
            shown.setEditable(false);
            shown.setFont(shown.getFont().deriveFont(18f));
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(rc.code), null);
            JPanel p = column();
            p.add(left(new JLabel("Reset code for " + rc.ign + " (copied):")));
            p.add(Box.createVerticalStrut(6));
            p.add(left(shown));
            p.add(Box.createVerticalStrut(6));
            p.add(left(muted("Pass it on to " + rc.ign + " privately. In Loot Gobbler they choose \"Forgot password?\"")));
            p.add(left(muted("and enter their IGN, this code and a new password. Works once, until " + date(rc.expiresAt) + ".")));
            p.add(left(muted("It is shown only now. A new code replaces this one.")));
            JOptionPane.showMessageDialog(this, p, "Password reset code", JOptionPane.PLAIN_MESSAGE);
        }));
    }

    /** Reloads the approval queue and scrolls to it (e.g. from the notifications bell). */
    public void showModeration() {
        refreshModeration();
        SwingUtilities.invokeLater(() -> moderation.scrollRectToVisible(
                new Rectangle(0, 0, moderation.getWidth(), moderation.getHeight())));
    }

    private void refreshModeration() {
        User u = server.user();
        if (u == null || !u.atLeastModerator()) return;
        CompletableFuture<List<User>> p = server.pendingRequests();
        CompletableFuture<List<User>> m = server.members();
        CompletableFuture<List<OpenToken>> t = u.atLeastAdmin() ? server.moderatorTokens()
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<QuestTemplate>> qp = u.atLeastAdmin() ? server.questPool("")
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture.allOf(p, m, t, qp).whenComplete((v, e) -> SwingUtilities.invokeLater(() -> {
            if (e != null) {
                moderationFeedback.setText(GobblerServer.message(e));
            } else {
                pending = p.join();
                lootgobbler.ApprovalWatcher.get().update(pending);   // the bell follows accepts and denies
                members = m.join();
                openTokens = t.join();
                questPool = qp.join();
            }
            buildModeration();
            buildTokens();
            buildPool();
            revalidate();
            repaint();
        }));
    }

    /**
     * Runs an admin action, shows the outcome and reloads the lists.
     */
    private void act(CompletableFuture<?> action, JLabel feedback, String done) {
        feedback.setText("Working...");
        action.whenComplete((r, e) -> SwingUtilities.invokeLater(() -> {
            feedback.setText(e == null ? done : GobblerServer.message(e));
            refreshModeration();
        }));
    }

    // --- quest pool ---

    /**
     * Admins decide which suggested quests are used: as personal quests (each player rolls 3 a week) or as guild
     * quests (one a week, the same for the whole guild). Accepted quests can be taken out of the rotation again.
     */
    private void buildPool() {
        poolBody.removeAll();
        poolBody.add(left(muted("Members suggest quests in the Guild tab. Each week every player gets 3 personal quests "
                + "and each guild 1 guild quest from the accepted ones.")));
        List<QuestTemplate> suggested = questPool.stream().filter(q -> q.status.equals("pending")).toList();
        poolBody.add(Box.createVerticalStrut(8));
        poolBody.add(heading("Suggestions (" + suggested.size() + ")"));
        if (suggested.isEmpty()) poolBody.add(left(muted("No suggestions waiting.")));
        for (QuestTemplate q : suggested) {
            JTextField points = new JTextField(String.valueOf(q.points), 4);
            points.setToolTipText("Points for finishing it");
            JPanel buttons = buttons();
            buttons.add(points);
            buttons.add(new JLabel("pts"));
            buttons.add(button("Personal", "primary", () -> approve(q, "personal", points)));
            buttons.add(button("Guild", "primary", () -> approve(q, "guild", points)));
            buttons.add(button("Deny", "danger", () -> act(server.denyQuest(q.id), poolFeedback, "Denied \"" + q.title + "\".")));
            String by = q.proposedBy.isEmpty() ? "built-in suggestion" : "suggested by " + q.proposedBy;
            poolBody.add(listRow(q.title, by + " as a " + q.scope + " quest", buttons));
        }
        for (String scope : new String[]{"personal", "guild"}) {
            List<QuestTemplate> active = questPool.stream()
                    .filter(q -> q.status.equals("approved") && q.scope.equals(scope)).toList();
            poolBody.add(Box.createVerticalStrut(10));
            poolBody.add(heading((scope.equals("personal") ? "Personal" : "Guild") + " quests in rotation (" + active.size() + ")"));
            if (active.isEmpty()) poolBody.add(left(muted("None yet.")));
            for (QuestTemplate q : active) {
                JPanel buttons = buttons();
                buttons.add(button("Remove", "danger", () -> {
                    if (confirm("Take \"" + q.title + "\" out of the rotation? Quests already running this week keep going.",
                            "Remove quest")) {
                        act(server.denyQuest(q.id), poolFeedback, "Removed \"" + q.title + "\".");
                    }
                }));
                poolBody.add(listRow(q.title, q.points + " points", buttons));
            }
        }
        poolBody.add(poolFeedback);
    }

    private void approve(QuestTemplate q, String scope, JTextField pointsField) {
        int points;
        try {
            points = Integer.parseInt(pointsField.getText().trim());
        } catch (NumberFormatException e) {
            poolFeedback.setText("Points must be a number.");
            return;
        }
        if (points < 1 || points > 500) {
            poolFeedback.setText("Points must be between 1 and 500.");
            return;
        }
        act(server.approveQuest(q.id, scope, points), poolFeedback,
                "\"" + q.title + "\" is now a " + scope + " quest.");
    }

    // --- item scores ---

    /**
     * Admins (not moderators) set how much each part of an item's score counts, 0 to 1 each. The guild views
     * (loot caravan, recent drops, leaderboard) use them for everyone.
     */
    private void buildScoring() {
        scoringBody.add(left(muted("Every found item gets points: feed power weight × feed power / 100 + difficulty weight "
                + "× dungeon difficulty + rarity weight × rarity (0-4), rounded to one decimal.")));
        scoringBody.add(left(muted("The loot caravan, recent drops and leaderboard in the Guild tab show them, for everyone on this server.")));
        scoringBody.add(Box.createVerticalStrut(6));
        scoringBody.add(formRow("Feed power", feedWeight));
        scoringBody.add(formRow("Difficulty", difficultyWeight));
        scoringBody.add(formRow("Rarity", rarityWeight));
        JPanel actions = row();
        actions.add(button("Save", "primary", this::saveScoring));
        actions.add(button("Reset", null, this::showWeights));
        scoringBody.add(actions);
        scoringBody.add(scoringFeedback);
    }

    /** Shows the weights in use (after loading, saving or Reset). */
    private void showWeights() {
        GobblerServer.ScoreWeights w = lootgobbler.ItemScore.get().weights();
        feedWeight.setValue(w.feedPower);
        difficultyWeight.setValue(w.difficulty);
        rarityWeight.setValue(w.rarity);
    }

    private void saveScoring() {
        if (!commit(feedWeight, difficultyWeight, rarityWeight)) {
            scoringFeedback.setText("Each weight must be a number from 0 to 1.");
            return;
        }
        GobblerServer.ScoreWeights w = new GobblerServer.ScoreWeights();
        w.feedPower = ((Number) feedWeight.getValue()).doubleValue();
        w.difficulty = ((Number) difficultyWeight.getValue()).doubleValue();
        w.rarity = ((Number) rarityWeight.getValue()).doubleValue();
        scoringFeedback.setText("Saving...");
        server.putScoring(w).whenComplete((saved, e) -> SwingUtilities.invokeLater(() -> {
            if (e != null) {
                scoringFeedback.setText(GobblerServer.message(e));
                return;
            }
            lootgobbler.ItemScore.get().set(saved);
            scoringFeedback.setText("Saved. The Guild tab shows the new scores.");
        }));
    }

    /**
     * Takes what was typed into the spinners as their values. The buttons here take no focus, so a typed number is
     * not committed by leaving the field: without this, Save would send the value from before typing. False if a
     * text is not a valid number (that spinner then shows its value again).
     */
    private static boolean commit(JSpinner... spinners) {
        boolean ok = true;
        for (JSpinner s : spinners) {
            try {
                s.commitEdit();
            } catch (java.text.ParseException e) {
                ok = false;
                if (s.getEditor() instanceof JSpinner.DefaultEditor d) d.getTextField().setValue(s.getValue());
            }
        }
        return ok;
    }

    private static JSpinner weightSpinner() {
        JSpinner s = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 1.0, 0.05));
        s.setEditor(new JSpinner.NumberEditor(s, "0.00"));
        s.setPreferredSize(new Dimension(90, s.getPreferredSize().height));
        return s;
    }

    // --- guild ---

    /** Admins (not moderators) set what the guild part of a dungeon's details computes with, for everyone. */
    private void buildGuild() {
        baselineHpm.setEditor(new JSpinner.NumberEditor(baselineHpm, "0.#"));
        baselineHpm.setPreferredSize(new Dimension(90, baselineHpm.getPreferredSize().height));
        guildBody.add(left(muted("\"Zero Hit?\" in a dungeon's Guild part: hits per minute (HPM) = hits / minutes in the dungeon, "
                + "hit% = HPM / (HPM + baseline HPM) × 100. A player hit as often as the baseline gets 50%.")));
        guildBody.add(Box.createVerticalStrut(6));
        guildBody.add(formRow("Baseline HPM", baselineHpm));
        JPanel actions = row();
        actions.add(button("Save", "primary", this::saveGuild));
        actions.add(button("Reset", null, this::showGuildSettings));
        guildBody.add(actions);
        guildBody.add(guildFeedback);
    }

    /** Shows the guild settings in use (after loading, saving or Reset). */
    private void showGuildSettings() {
        baselineHpm.setValue(Guildmates.get().settings().baselineHpm);
    }

    private void saveGuild() {
        if (!commit(baselineHpm)) {
            guildFeedback.setText("The baseline must be a number from 1 to 1000.");
            return;
        }
        GobblerServer.GuildSettings g = new GobblerServer.GuildSettings();
        g.baselineHpm = ((Number) baselineHpm.getValue()).doubleValue();
        guildFeedback.setText("Saving...");
        server.putGuildSettings(g).whenComplete((saved, e) -> SwingUtilities.invokeLater(() -> {
            if (e != null) {
                guildFeedback.setText(GobblerServer.message(e));
                return;
            }
            Guildmates.get().setSettings(saved);
            guildFeedback.setText("Saved. Everyone's dungeon details use it.");
        }));
    }

    // --- moderator tokens ---

    private void buildTokens() {
        tokensBody.removeAll();
        tokensBody.add(left(muted("Give a token to someone you trust to make them a moderator. Each token works once.")));
        tokensBody.add(left(muted("They enter it when registering (no approval needed then) or under Account.")));
        JPanel create = row();
        create.add(new JLabel("For"));
        create.add(tokenNote);
        create.add(new JLabel("Valid for"));
        create.add(tokenValidity);
        create.add(button("Create token", "primary", this::createToken));
        tokensBody.add(create);

        if (lastIssued != null) {
            JTextField shown = new JTextField(lastIssued.token, 34);
            shown.setEditable(false);
            shown.setFont(shown.getFont().deriveFont(18f));
            String token = lastIssued.token;
            JPanel issued = row();
            issued.add(shown);
            issued.add(button("Copy", "primary", () -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(token), null);
                tokensFeedback.setText("Token copied.");
            }));
            tokensBody.add(issued);
            tokensBody.add(left(muted("Copy it now - it is shown only once. Valid until " + date(lastIssued.expiresAt) + ".")));
        }

        tokensBody.add(Box.createVerticalStrut(10));
        tokensBody.add(heading("Unused tokens (" + openTokens.size() + ")"));
        if (openTokens.isEmpty()) tokensBody.add(left(muted("None.")));
        for (OpenToken t : openTokens) {
            JPanel buttons = buttons();
            buttons.add(button("Revoke", "danger", () -> {
                if (confirm("Revoke this moderator token? It can no longer be used.", "Revoke token")) {
                    if (lastIssued != null && lastIssued.id == t.id) lastIssued = null;
                    act(server.revokeModeratorToken(t.id), tokensFeedback, "Token revoked.");
                }
            }));
            String title = t.note.isBlank() ? "Moderator token" : t.note;
            String sub = "created by " + (t.createdBy.isBlank() ? "?" : t.createdBy)
                    + (t.expiresAt != null ? " - valid until " + date(t.expiresAt) : "");
            tokensBody.add(listRow(title, sub, buttons));
        }
        tokensBody.add(tokensFeedback);
    }

    private void createToken() {
        int hours = VALIDITY_HOURS[Math.max(0, tokenValidity.getSelectedIndex())];
        tokensFeedback.setText("Creating...");
        server.createModeratorToken(tokenNote.getText(), hours).whenComplete((t, e) -> SwingUtilities.invokeLater(() -> {
            if (e != null) {
                tokensFeedback.setText(GobblerServer.message(e));
                return;
            }
            lastIssued = t;
            tokenNote.setText("");
            tokensFeedback.setText(" ");
            refreshModeration();
        }));
    }

    // --- building blocks ---

    private boolean confirm(String question, String title) {
        return JOptionPane.showConfirmDialog(this, question, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private static String date(long millis) {
        return DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    private static JComponent statusLine(Color color, String text) {
        JLabel l = new JLabel(text, new Section.Square(color, 10), SwingConstants.LEFT);
        l.setIconTextGap(10);
        l.setForeground(color);
        l.setBorder(new EmptyBorder(4, 6, 6, 6));
        return left(l);
    }

    /**
     * "Label: value" line with a fixed-width label column.
     */
    private static JComponent info(String label, String value) {
        JLabel l = new JLabel(label);
        l.setForeground(Palette.MUTED);
        l.setPreferredSize(new Dimension(150, l.getPreferredSize().height));
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(1, 6, 1, 6));
        p.add(l, BorderLayout.WEST);
        p.add(new JLabel(value), BorderLayout.CENTER);
        return fixHeight(left(p));
    }

    private static JComponent formRow(String label, JComponent field) {
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(120, l.getPreferredSize().height));
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        p.setOpaque(false);
        p.add(l);
        p.add(field);
        return left(p);
    }

    private static JComponent heading(String text) {
        JLabel l = new JLabel(text.toUpperCase(Locale.ROOT));
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setBorder(new EmptyBorder(2, 6, 4, 6));
        return left(l);
    }

    /**
     * Bordered row with a title, a muted second line and buttons on the right (like the Discord webhook rows).
     */
    private static JComponent listRow(String title, String sub, JPanel buttons) {
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(18f));
        JLabel s = muted(sub);
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(t);
        text.add(s);
        JPanel row = new JPanel(new BorderLayout(10, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(Section.color("TableHeader.background", getBackground()));
                setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(Section.color("Component.borderColor", Color.GRAY)),
                        new EmptyBorder(6, 10, 6, 8)));
            }
        };
        row.add(text, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);
        JPanel spaced = new JPanel(new BorderLayout());   // gap below each row
        spaced.setOpaque(false);
        spaced.setBorder(new EmptyBorder(0, 0, 6, 0));
        spaced.add(row);
        return fixHeight(left(spaced));
    }

    private static JPanel buttons() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        p.setOpaque(false);
        return p;
    }

    private static JComponent fixHeight(JComponent c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    private static JTextField field(int columns, String placeholder) {
        JTextField f = new JTextField(columns);
        f.putClientProperty("JTextField.placeholderText", placeholder);
        return f;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        return l;
    }

    private static JLabel feedback() {
        JLabel l = new JLabel(" ");
        l.setBorder(new EmptyBorder(6, 6, 0, 6));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private static JButton button(String text, String style, Runnable action) {
        JButton b = new JButton(text.toUpperCase(Locale.ROOT));
        if (style != null) b.putClientProperty("FlatLaf.styleClass", style);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel body() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(10, 12, 12, 12));
        return p;
    }

    private static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }
}
