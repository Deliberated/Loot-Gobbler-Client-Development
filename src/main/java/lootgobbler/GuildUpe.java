package lootgobbler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Checks a gUPE trade against the partner's own report on the Loot Gobbler server. The trade is fine if a member of
 * your guild with the partner's name reported the same trade (within two minutes of it, with you as their partner)
 * from a standing gUPE run, and everything they gave you was gathered in their run (dropped, crafted from their own
 * things, or traded in from another gUPE). A partner who is not in the guild, or whose report says otherwise, makes
 * the trade illegal at once; no report within REPORT_WAIT_MS of the trade (counted while the server could be asked)
 * makes it illegal too. Asked every ASK_EVERY_S seconds; trades still waiting are asked again after a restart.
 */
public final class GuildUpe {
    static final long MATCH_MS = 120_000, REPORT_WAIT_MS = 5 * 60_000;
    private static final long ASK_EVERY_S = 15;
    private static final GuildUpe INSTANCE = new GuildUpe();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gupe-check");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Long> waited = new HashMap<>();   // trade time -> ms waited while the server answered

    public static GuildUpe get() {
        return INSTANCE;
    }

    private GuildUpe() {
    }

    /** Checks the trades left waiting by an earlier run of the app. */
    public void resume() {
        for (Upe.Trade t : Upe.get().checking()) check(t);
    }

    /** Starts checking a trade (asks the server until it is decided). */
    public void check(Upe.Trade trade) {
        exec.schedule(() -> ask(trade), ASK_EVERY_S, TimeUnit.SECONDS);
    }

    private void ask(Upe.Trade trade) {
        GobblerServer server = GobblerServer.get();
        if (server.user() == null || server.state() != GobblerServer.State.ONLINE) {
            check(trade);   // not logged in: ask again later, the time does not count
            return;
        }
        server.guildUpe(trade.at - REPORT_WAIT_MS).whenComplete((g, e) -> {
            if (e != null || g == null) {
                check(trade);
                return;
            }
            Verdict v = verdict(trade, g.members);
            if (v == null) {
                long w = waited.merge(trade.at, ASK_EVERY_S * 1000, Long::sum);
                if (w < REPORT_WAIT_MS) {
                    check(trade);
                    return;
                }
                v = new Verdict(false, "no trade report from " + trade.partner
                        + " (not playing a gUPE in your Loot Gobbler guild, or Loot Gobbler was not running)");
            }
            waited.remove(trade.at);
            Upe.get().tradeChecked(trade.charId, trade.at, v.ok(), v.why());
        });
    }

    record Verdict(boolean ok, String why) {
    }

    /**
     * The verdict on a trade from the guild's reports, or null if the partner's report is not there (yet).
     */
    static Verdict verdict(Upe.Trade trade, List<GobblerServer.GuildUpeMember> members) {
        String partner = trade.partner.toLowerCase(Locale.ROOT);
        GobblerServer.GuildUpeMember m = members.stream()
                .filter(x -> x.ign != null && x.ign.toLowerCase(Locale.ROOT).equals(partner)).findFirst().orElse(null);
        if (m == null) return new Verdict(false, trade.partner + " is not in your Loot Gobbler guild");
        Upe.Trade theirs = null;
        for (Upe.Trade t : m.trades == null ? List.<Upe.Trade>of() : m.trades) {
            if (Math.abs(t.at - trade.at) > MATCH_MS) continue;
            if (!trade.me.isBlank() && t.partner != null && !t.partner.equalsIgnoreCase(trade.me)) continue;
            theirs = t;
            break;
        }
        if (theirs == null) return null;
        if (!Upe.guild(theirs.mode) || !theirs.standing) {
            return new Verdict(false, trade.partner + " was not playing a standing guild run (gPPE, gUPE...)");
        }
        // everything that came in must be among what they gave, and gathered in their run
        List<Upe.TradeItem> gave = new ArrayList<>(theirs.gave);
        for (Upe.TradeItem got : trade.got) {
            Upe.TradeItem match = gave.stream().filter(x -> x.item == got.item).findFirst().orElse(null);
            if (match == null) return new Verdict(false, trade.partner + "'s report does not list an item you got (" + got.item + ")");
            gave.remove(match);
            if (!match.clean) return new Verdict(false, trade.partner + " gave an item their run had not gathered itself");
        }
        return new Verdict(true, null);
    }
}
