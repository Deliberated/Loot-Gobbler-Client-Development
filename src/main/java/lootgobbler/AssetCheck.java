package lootgobbler;

import assets.AssetExtractor;
import util.PropertiesManager;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.CRC32C;

/**
 * Decides on start whether the game assets must be extracted again, in front of RealmShark's AssetExtractor (which
 * stays unchanged). Upstream asks whenever resources.assets has a new file time or this app has a new version; that
 * is every Loot Gobbler release and every time the launcher rewrites the file, even with the same contents.
 * <p>
 * Here only the game file's contents count: after an extraction its size, time and a checksum are remembered
 * ("assetsSource"). A new file time with the same checksum (or a new app version) needs no extraction; then
 * upstream's own stamp is set so it does not ask. Only real changes (or missing assets) go to upstream's dialog.
 */
public final class AssetCheck {
    private static final String KEY = "assetsSource";
    private static final String UPSTREAM_KEY = "lastModifiedTime";   // AssetExtractor's stamp: "<file time>-<app version>"
    // raise when the extraction itself changes (e.g. a newer RealmShark), so assets made the old way are redone
    private static final int FORMAT = 1;

    private AssetCheck() {
    }

    /** Called on start instead of AssetExtractor.checkForExtraction(version). */
    public static void run(String version) throws Throwable {
        File game = AssetExtractor.assetFile();
        if (game == null) {   // the game was not found: upstream asks for its folder
            AssetExtractor.checkForExtraction(version);
            return;
        }
        BasicFileAttributes attr = Files.readAttributes(game.toPath(), BasicFileAttributes.class);
        String time = attr.lastModifiedTime().toString();
        long size = attr.size();
        boolean extracted = new File(AssetExtractor.ASSETS_OBJECT_FILE_DIR_PATH).exists()
                && new File(AssetExtractor.ASSETS_TILE_FILE_DIR_PATH).exists();

        if (extracted && upToDate(game, time, size)) {
            // nothing to do: give upstream a matching stamp so its check passes without asking
            PropertiesManager.setProperties(UPSTREAM_KEY, AssetExtractor.lastEdited(version));
            return;
        }
        // really changed (or nothing extracted yet): upstream's "Extract / Ignore" dialog
        PropertiesManager.setProperties(UPSTREAM_KEY, "");
        AssetExtractor.checkForExtraction(version);
        if (AssetExtractor.lastEdited(version).equals(PropertiesManager.getProperty(UPSTREAM_KEY))) {   // extracted
            remember(time, size, checksum(game));
        }
    }

    /**
     * Whether the assets on disk came from this game file: the same size and time as remembered, or else the same
     * checksum (then the new time is remembered). Assets extracted before this check existed count if upstream's
     * stamp names this file time (whatever app version it carries).
     */
    private static boolean upToDate(File game, String time, long size) throws Exception {
        String[] saved = split(PropertiesManager.getProperty(KEY));
        if (saved != null) {
            if (!saved[0].equals(String.valueOf(FORMAT)) || Long.parseLong(saved[2]) != size) return false;
            if (saved[1].equals(time)) return true;
            long t0 = System.currentTimeMillis();
            long crc = checksum(game);
            boolean same = Long.toHexString(crc).equals(saved[3]);
            System.out.println("[Assets] game file has a new time; contents " + (same ? "unchanged" : "changed")
                    + " (checked in " + (System.currentTimeMillis() - t0) + " ms)");
            if (same) remember(time, size, crc);
            return same;
        }
        String upstream = PropertiesManager.getProperty(UPSTREAM_KEY);
        if (upstream != null && upstream.startsWith(time + "-")) {   // extracted from this very file by an older version
            remember(time, size, checksum(game));
            return true;
        }
        return false;
    }

    private static void remember(String time, long size, long crc) {
        PropertiesManager.setProperties(KEY, FORMAT + "|" + time + "|" + size + "|" + Long.toHexString(crc));
    }

    /** "format|time|size|crc" as its four parts, or null. */
    private static String[] split(String saved) {
        if (saved == null) return null;
        String[] parts = saved.split("\\|");
        return parts.length == 4 ? parts : null;
    }

    /** A checksum of the whole game file (CRC32C: fast, a few hundred MB in about a second). */
    private static long checksum(File file) throws Exception {
        CRC32C crc = new CRC32C();
        ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
        try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            while (ch.read(buf) > 0) {
                buf.flip();
                crc.update(buf);
                buf.clear();
            }
        }
        return crc.getValue();
    }
}
