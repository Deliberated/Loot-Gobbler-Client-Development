package oracle;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import lootgobbler.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Merging another PC's documents into the app's records, as the server sync does (each store's mergeSync, in
 * ServerSync's order), then what each would upload (exportSync): "<kind> <merged> <count>" and the document.
 * Runs with the clock agent, at -Doracle.now (ms since 1970).
 * Usage: SyncOracle <remote folder with kind.json files> <out file>. Run in a copy of the app's data: it saves there.
 */
public class SyncOracle {
    public static void main(String[] args) throws Exception {
        ClockAgent.now = Long.parseLong(System.getProperty("oracle.now"));   // the same moment as the Rust side
        Path remote = Path.of(args[0]);
        Syncable[] stores = {CollectionStore.get(), CharacterStore.get(), ExaltStore.get(), PetStore.get(),
                DpsHistory.get(), PlayerProfile.get(), DungeonVisits.get(), AmuletSaves.get(), Holdings.get(),
                Upe.get()};
        String[] merged = new String[stores.length];
        for (int i = 0; i < stores.length; i++) {
            Path f = remote.resolve(stores[i].syncKind() + ".json");
            if (!Files.exists(f)) {
                merged[i] = "-";
                continue;
            }
            try {
                merged[i] = String.valueOf(stores[i].mergeSync(JsonParser.parseString(Files.readString(f))));
            } catch (Exception e) {
                merged[i] = "thrown";
            }
        }
        Gson gson = new Gson();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < stores.length; i++) {
            Syncable s = stores[i];
            out.append(s.syncKind()).append(' ').append(merged[i]).append(' ').append(s.syncCount()).append('\n');
            out.append(gson.toJson(s.exportSync())).append('\n');
        }
        Files.writeString(Path.of(args[1]), out);
        System.exit(0);
    }
}
