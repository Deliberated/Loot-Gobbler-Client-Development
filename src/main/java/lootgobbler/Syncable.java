package lootgobbler;

import com.google.gson.JsonElement;

/**
 * Local data that is kept in sync with the Loot Gobbler server as one JSON document (see {@link ServerSync}).
 * Merging must never lose progress: whatever either side has is kept.
 */
public interface Syncable {
    /**
     * Name of the server document, e.g. "collection". Never change it: the server stores the data under it.
     */
    String syncKind();

    /**
     * Name shown in the Server tab.
     */
    String syncLabel();

    /**
     * How many entries there are, shown in the Server tab.
     */
    int syncCount();

    /**
     * The current data, in the same shape as the local file.
     */
    JsonElement exportSync();

    /**
     * Merges a document from the server (or another PC) into the local data, then saves and notifies listeners if
     * anything changed.
     *
     * @return True if the local data changed.
     */
    boolean mergeSync(JsonElement remote);

    void onChange(Runnable listener);
}
