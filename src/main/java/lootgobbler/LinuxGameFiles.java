package lootgobbler;

import assets.AssetExtractor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On Linux the game runs in a Windows prefix (Steam's Proton, Wine, Lutris, Bottles), so its resources.assets is
 * inside drive_c of that prefix. Finds it and hands the path to the asset extractor; "--path" still wins.
 */
public final class LinuxGameFiles {
    private static final String IN_LOCAL_APPDATA = "AppData/Local/RealmOfTheMadGod/Production/RotMG Exalt_Data/resources.assets";
    private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");

    private LinuxGameFiles() {
    }

    /** Sets the asset extractor's game file path when running on Linux and the file is found. */
    public static void apply() {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) return;
        Path found = find();
        if (found != null) {
            AssetExtractor.setRealmResPath(found.toString());
            System.out.println("Game files: " + found);
        } else {
            System.out.println("Game files not found in any Steam/Wine prefix; use --path <resources.assets>");
        }
    }

    /** The newest resources.assets in any known prefix, or null. */
    public static Path find() {
        Path best = null;
        for (Path prefix : prefixes()) {
            for (Path file : inPrefix(prefix)) {
                try {
                    if (best == null || Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(best)) > 0) {
                        best = file;
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return best;
    }

    private static List<Path> inPrefix(Path prefix) {
        List<Path> out = new ArrayList<>();
        Path users = prefix.resolve("drive_c/users");
        if (!Files.isDirectory(users)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(users)) {
            for (Path user : ds) {
                Path file = user.resolve(IN_LOCAL_APPDATA);
                if (Files.isRegularFile(file)) out.add(file);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static Set<Path> prefixes() {
        Path home = Path.of(System.getProperty("user.home"));
        Set<Path> prefixes = new LinkedHashSet<>();
        String winePrefix = System.getenv("WINEPREFIX");
        if (winePrefix != null && !winePrefix.isBlank()) prefixes.add(Path.of(winePrefix));
        prefixes.add(home.resolve(".wine"));

        // Steam (native and Flatpak): every library folder, every Proton prefix (the Steam app or a non-Steam shortcut)
        Set<Path> steamRoots = new LinkedHashSet<>(List.of(
                home.resolve(".local/share/Steam"),
                home.resolve(".steam/steam"),
                home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam"),
                home.resolve(".var/app/com.valvesoftware.Steam/data/Steam")));
        Set<Path> libraries = new LinkedHashSet<>();
        for (Path root : steamRoots) {
            if (!Files.isDirectory(root)) continue;
            libraries.add(real(root));
            Path vdf = root.resolve("steamapps/libraryfolders.vdf");
            try {
                Matcher m = VDF_PATH.matcher(Files.readString(vdf));
                while (m.find()) libraries.add(real(Path.of(m.group(1).replace("\\\\", "\\"))));
            } catch (IOException ignored) {
            }
        }
        for (Path library : libraries) {
            Path compat = library.resolve("steamapps/compatdata");
            prefixes.add(compat.resolve("200210/pfx"));   // Realm of the Mad God Exalt on Steam
            children(compat).forEach(p -> prefixes.add(p.resolve("pfx")));
        }

        // Lutris and Bottles keep one prefix per game or bottle
        children(home.resolve("Games")).forEach(prefixes::add);
        children(home.resolve(".local/share/bottles/bottles")).forEach(prefixes::add);
        children(home.resolve(".var/app/com.usebottles.bottles/data/bottles/bottles")).forEach(prefixes::add);
        return prefixes;
    }

    private static List<Path> children(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            ds.forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
