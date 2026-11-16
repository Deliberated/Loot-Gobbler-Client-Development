package packets.packetcapture.sniff.gui;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Linux counterpart of MissingNpcapGUI: explains what is missing for capturing (libpcap, or the permission to
 * capture) and shows the commands that fix it for the program that is running right now.
 */
public final class LinuxCaptureGUI {
    private static final String CAPS = "cap_net_raw,cap_net_admin=eip";

    private LinuxCaptureGUI() {
    }

    /**
     * The portable app folder: one click on "Grant permission" asks for the password (polkit), gives the launcher
     * the capabilities and starts Loot Gobbler again, since capabilities only apply to a newly started program.
     */
    private static void offerGrant(String launcher, String setcap, String detail) {
        System.out.println("[X] No permission to capture packets" + suffix(detail) + ", offering to grant it");
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            String text = "<html><body style='width:380px'>Loot Gobbler is not allowed to read network packets yet."
                    + "<br><br>Linux only lets programs do that with a one-time permission. Click <b>Grant permission"
                    + "</b> and enter your password; Loot Gobbler then restarts and the sniffer can run."
                    + "<br><br>After an update it asks once more. The installed package (rpm) does this by itself."
                    + "</body></html>";
            Object[] options = {"Grant permission", "Not now"};
            int choice = JOptionPane.showOptionDialog(null, text, "Allow packet capture", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (choice != 0) return;
            new Thread(() -> grant(launcher, setcap), "grant-capture").start();
        });
    }

    private static void grant(String launcher, String setcap) {
        int exit;
        String output;
        try {
            Process p = new ProcessBuilder("/usr/bin/pkexec", setcap, CAPS, launcher).redirectErrorStream(true).start();
            output = new String(p.getInputStream().readAllBytes()).trim();
            exit = p.waitFor();
        } catch (Exception e) {
            exit = -1;
            output = e.toString();
        }
        if (exit == 0) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null, "Permission granted. Loot Gobbler restarts now.",
                        "Allow packet capture", JOptionPane.INFORMATION_MESSAGE);
                restart(launcher);
            });
            return;
        }
        // 126: the password prompt was closed, 127: not authorized
        String why = exit == 126 ? "The password prompt was closed." : exit == 127 ? "Not authorized."
                : output.isEmpty() ? "setcap failed (" + exit + ")." : output;
        show("Packet capture not allowed", why + "\nYou can also allow it in a terminal, then restart Loot Gobbler:",
                "sudo setcap " + CAPS + " " + quote(launcher));
    }

    /** Starts the launcher again once this instance has saved and exited (like the in-app updater's restart). */
    private static void restart(String launcher) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(launcher);
                lootgobbler.Launcher.cleanLauncherEnvironment(pb, Path.of(launcher));
                pb.start();
            } catch (Exception e) {
                System.err.println("Restart failed: " + e);
            }
        }));
        Window w = null;
        for (Frame f : Frame.getFrames()) if (f.isVisible()) w = f;
        if (w != null) w.dispatchEvent(new java.awt.event.WindowEvent(w, java.awt.event.WindowEvent.WINDOW_CLOSING));
        System.exit(0);
    }

    private static String setcap() {
        for (String p : new String[]{"/usr/sbin/setcap", "/usr/bin/setcap", "/sbin/setcap"}) {
            if (Files.isExecutable(Path.of(p))) return p;
        }
        return null;
    }

    /** libpcap could not be loaded. */
    public static void missingLibpcap() {
        show("Missing libpcap",
                "Loot Gobbler needs libpcap to read the game's network traffic.\nInstall it, then start the sniffer again:",
                "sudo dnf install libpcap        # Fedora\nsudo apt install libpcap0.8     # Debian / Ubuntu");
    }

    /** libpcap is there, but this process may not capture packets. */
    public static void noPermission(String detail) {
        String launcher = System.getProperty("jpackage.app-path");   // set by the jpackage launcher only
        String text;
        String commands;
        if (launcher != null) {
            String setcap = setcap();
            if (setcap != null && Files.isExecutable(Path.of("/usr/bin/pkexec"))) {
                offerGrant(launcher, setcap, detail);
                return;
            }
            text = "Loot Gobbler is not allowed to capture network packets" + suffix(detail) + ".\n"
                    + "Allow it once (again after every update), then restart Loot Gobbler:";
            commands = "sudo setcap " + CAPS + " " + quote(launcher);
        } else {
            // started from source: the capabilities go on java itself. java finds libjli.so through $ORIGIN,
            // which Linux ignores for programs with capabilities, so its lib folder has to be known to ld.so.
            Path java = Path.of(ProcessHandle.current().info().command().orElse("java"));
            try {
                java = java.toRealPath();
            } catch (Exception ignored) {
            }
            Path lib = java.getParent() == null ? null : java.getParent().resolveSibling("lib");
            text = "Java is not allowed to capture network packets" + suffix(detail) + ".\n"
                    + "Allow it once (again after every Java update), then restart Loot Gobbler.\n"
                    + "Note: this lets every Java program on this PC capture packets.";
            StringBuilder sb = new StringBuilder();
            if (lib != null && Files.isRegularFile(lib.resolve("libjli.so"))) {
                sb.append("echo ").append(quote(lib.toString()))
                        .append(" | sudo tee /etc/ld.so.conf.d/lootgobbler-java.conf && sudo ldconfig\n");
            }
            sb.append("sudo setcap " + CAPS + " ").append(quote(java.toString()));
            commands = sb.toString();
        }
        show("Packet capture not allowed", text, commands);
    }

    private static String suffix(String detail) {
        return detail == null || detail.isBlank() ? "" : " (" + detail.trim() + ")";
    }

    private static String quote(String path) {
        return path.matches("[A-Za-z0-9_./+-]+") ? path : "'" + path.replace("'", "'\\''") + "'";
    }

    private static void show(String title, String text, String commands) {
        System.out.println("[X] " + title + ": " + text.replace('\n', ' ') + "\n" + commands);
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            JTextArea area = new JTextArea(commands);
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            area.setRows(commands.split("\n").length + 1);
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.add(new JLabel("<html>" + text.replace("&", "&amp;").replace("<", "&lt;")
                    .replace("\n", "<br>") + "</html>"), BorderLayout.NORTH);
            panel.add(new JScrollPane(area), BorderLayout.CENTER);
            JOptionPane.showMessageDialog(null, panel, title, JOptionPane.WARNING_MESSAGE);
        });
    }
}
