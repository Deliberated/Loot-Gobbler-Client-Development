package lootgobbler;

import packets.incoming.MapInfoPacket;
import tomato.backend.data.Entity;
import tomato.realmshark.enums.LootBags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Posts UT and ST items as loot cards to every configured webhook.
 */
public class DiscordAlerts {

    public static void bagDropped(MapInfoPacket map, Entity bag, Entity player, List<LootEvents.Drop> drops) {
        if (!Discord.ENABLED || Webhooks.get().active().isEmpty()) return;

        // only real UT/ST gear (the game's UT/ST collection tabs); consumables also carry a "UT" label
        List<LootEvents.Drop> tops = new ArrayList<>();
        for (LootEvents.Drop d : drops) {
            ItemDb.Item item = ItemDb.get().item(d.id());
            if (item != null && item.isCollectible()) tops.add(d);
        }
        if (tops.isEmpty()) return;

        String bagName = LootEvents.bagName(bag.objectType);
        String who = player != null && player.name() != null ? player.name() : "Someone";
        LootCard.Card card = new LootCard.Card(
                headline(bagName) + " DROP",
                who + " got " + article(bagName) + " " + bagName.toLowerCase(Locale.ROOT) + (isBasket(bagName) ? "" : " bag"),
                LootEvents.mapName(map) + "  ·  " + count(tops.size()),
                tops);
        send(card, err -> {
            if (err != null) System.err.println("Discord post failed: " + err);
        });
    }

    /**
     * Posts a sample white bag with four random collectible items, one per rarity.
     */
    public static void sendTest(Consumer<String> done) {
        if (!Discord.ENABLED) {
            done.accept("Discord webhooks are under construction.");
            return;
        }
        CompletableFuture.runAsync(() -> {
            List<ItemDb.Item> pool = new ArrayList<>(ItemDb.get().collectible());
            if (pool.isEmpty()) {
                done.accept("No item data yet - extract assets first.");
                return;
            }
            Collections.shuffle(pool);
            List<LootEvents.Drop> items = new ArrayList<>();
            for (int i = 0; i < Math.min(4, pool.size()); i++) items.add(new LootEvents.Drop(pool.get(i).id, i + 1));
            LootCard.Card card = new LootCard.Card("TEST  -  WHITE BAG", "White Bag dropped",
                    "Sniffer Test  ·  dropped by Test Loot Button  ·  " + count(items.size()), items);
            send(card, done);
        });
    }

    private static void send(LootCard.Card card, Consumer<String> done) {
        List<Webhooks.Hook> hooks = Webhooks.get().active();
        if (hooks.isEmpty()) {
            done.accept("No webhook configured.");
            return;
        }
        CompletableFuture.runAsync(() -> {
            byte[] png;
            try {
                png = LootCard.png(card);
            } catch (Exception e) {
                done.accept("Could not render card: " + e.getMessage());
                return;
            }
            List<CompletableFuture<String>> posts = hooks.stream()
                    .map(h -> Discord.postImage(h.url, png, "loot.png")).toList();
            CompletableFuture.allOf(posts.toArray(new CompletableFuture[0])).thenRun(() -> {
                String firstError = posts.stream().map(CompletableFuture::join).filter(e -> e != null).findFirst().orElse(null);
                done.accept(firstError);
            });
        });
    }

    private static boolean isBasket(String bagName) {
        return bagName.contains("Basket");
    }

    private static String headline(String bagName) {
        return (isBasket(bagName) ? bagName : bagName + " Bag").toUpperCase(Locale.ROOT);
    }

    private static String article(String word) {
        return "AEIOUaeiou".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
    }

    private static String count(int n) {
        return n + (n == 1 ? " item" : " items");
    }

}
