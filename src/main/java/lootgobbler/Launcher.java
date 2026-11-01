package lootgobbler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The packaged LootGobbler.exe keeps its data (settings, collection, characters, assets...) next to itself. All of
 * that is read and written relative to the working directory, so the exe must run in its own folder.
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
            Path dir = Path.of(exe).toAbsolutePath().getParent();
            Path cwd = Path.of("").toAbsolutePath();
            if (dir == null || !Files.isDirectory(dir) || Files.isSameFile(dir, cwd)) return false;
            List<String> cmd = new ArrayList<>();
            cmd.add(exe);
            cmd.addAll(List.of(args));
            new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
            return true;
        } catch (Exception e) {
            System.err.println("Could not restart next to the exe, using " + Path.of("").toAbsolutePath() + ": " + e);
            return false;
        }
    }
}
