package lootgobbler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The packaged LootGobbler.exe keeps its data (settings, collection, characters, assets...) next to itself. All of
 * that is read and written relative to the working directory, so the exe must run in its own folder.
 * <p>
 * On Linux the same goes for the portable folder (tar.gz). An installed package (rpm) lives in /opt, which the user
 * can't write to, so its data goes to ~/.local/share/LootGobbler instead.
 */
public final class Launcher {
    private Launcher() {
    }

    /**
     * When started as LootGobbler.exe from another working directory, starts the exe again in its own folder.
     *
     * @return true if a new instance was started and this one should quit
     */
    public static boolean relaunchedNextToExe(String[] args) {
        String exe = System.getProperty("jpackage.app-path");   // set by the jpackage launcher only
        if (exe == null) return false;
        try {
            Path dir = dataDir(Path.of(exe).toAbsolutePath().getParent());
            Path cwd = Path.of("").toAbsolutePath();
            if (dir == null || !Files.isDirectory(dir) || Files.isSameFile(dir, cwd)) return false;
            List<String> cmd = new ArrayList<>();
            cmd.add(exe);
            cmd.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO();
            cleanLauncherEnvironment(pb, Path.of(exe));
            pb.start();
            return true;
        } catch (Exception e) {
            System.err.println("Could not restart next to the exe, using " + Path.of("").toAbsolutePath() + ": " + e);
            return false;
        }
    }

    private static java.nio.channels.FileChannel lockChannel;   // held open (and locked) while the app runs

    /**
     * Two instances on the same data folder would overwrite each other's files. Call once the working directory is
     * final.
     *
     * @return true if another Loot Gobbler already runs in this folder (this one should quit)
     */
    public static boolean alreadyRunning() {
        try {
            lockChannel = java.nio.channels.FileChannel.open(Path.of("lootgobbler.lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            // a restart (Grant permission, updates) starts the new instance while the old one is still closing
            for (int i = 0; i < 50; i++) {
                if (lockChannel.tryLock() != null) return false;
                Thread.sleep(100);
            }
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return true;
        } catch (Exception e) {
            System.err.println("Could not check for another running Loot Gobbler: " + e);
            return false;   // can't tell: better to start than to refuse
        }
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            javax.swing.JOptionPane.showMessageDialog(null, "Loot Gobbler is already running.", "Loot Gobbler",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
        }
        return true;
    }

    /**
     * The jpackage launcher sets _JPACKAGE_LAUNCHER (a launcher that inherits it acts as a plain "java" command and
     * only prints java's usage, at least on Linux) and adds its lib/app folder to LD_LIBRARY_PATH. A launcher started
     * from here must see neither.
     */
    public static void cleanLauncherEnvironment(ProcessBuilder pb, Path launcher) {
        java.util.Map<String, String> env = pb.environment();
        env.remove("_JPACKAGE_LAUNCHER");
        String ld = env.get("LD_LIBRARY_PATH");
        Path appRoot = launcher.toAbsolutePath().getParent() == null ? null : launcher.toAbsolutePath().getParent().getParent();
        if (ld != null && appRoot != null) {
            String kept = java.util.Arrays.stream(ld.split(":"))
                    .filter(p -> !p.isEmpty() && !p.startsWith(appRoot.toString()))
                    .collect(java.util.stream.Collectors.joining(":"));
            if (kept.isEmpty()) env.remove("LD_LIBRARY_PATH");
            else env.put("LD_LIBRARY_PATH", kept);
        }
    }

    /** The launcher's own folder, or on Linux the user's data folder when the launcher's folder is read-only. */
    private static Path dataDir(Path launcherDir) throws java.io.IOException {
        if (launcherDir == null || !System.getProperty("os.name").toLowerCase().contains("linux")
                || Files.isWritable(launcherDir)) {
            return launcherDir;
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = xdg != null && xdg.startsWith("/") ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return Files.createDirectories(base.resolve("LootGobbler"));
    }
}
