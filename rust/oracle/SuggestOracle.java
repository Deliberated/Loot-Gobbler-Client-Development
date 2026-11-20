package oracle;

import lootgobbler.CharacterStore;
import lootgobbler.ItemDb;
import lootgobbler.enchant.EnchantPlanner;
import lootgobbler.enchant.Enchantment;

import java.lang.reflect.Method;
import java.util.*;

/**
 * The suggested loadout as the Java app works it out (CharacterDetail.ideal, without its window): for every living
 * character and several settings, per slot the item, where its copy is, its enchantments and the planned ones.
 * "suggest <charId> <scenario> <slot> <item> <where> <enchants> <planned> <score>". Run in a copy of the app's data.
 */
public class SuggestOracle {
    static final Class<?> CD;
    static {
        try {
            CD = Class.forName("lootgobbler.ui.CharacterDetail");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Method m(String name, Class<?>... types) throws Exception {
        Method x = CD.getDeclaredMethod(name, types);
        x.setAccessible(true);
        return x;
    }

    static Object call(Object o, String name) throws Exception {
        Method x = o.getClass().getDeclaredMethod(name);
        x.setAccessible(true);
        return x.invoke(o);
    }

    public static final String[][] CHOSEN = {{"MAXHP", "DPS", "HPREGEN"}, {"ATT", "DEX"}, {"DPS"}, {"LOOT"}, {"DEF", "MAXHP"}, {"WIS", "MPREGEN"}, {}};

    public static void main(String[] args) throws Exception {
        Method pool = m("pool", CharacterStore.Char.class);
        Class<?> aimC = EnchantPlanner.Aim.class;
        Method bestPick = null, aim = null, worn = null, locked = null, lockedItem = m("lockedItem", int.class, int.class);
        for (Method x : CD.getDeclaredMethods()) {
            x.setAccessible(true);
            if (x.getName().equals("bestPick")) bestPick = x;
            if (x.getName().equals("aim")) aim = x;
            if (x.getName().equals("wornEnchants")) worn = x;
            if (x.getName().equals("locked")) locked = x;
        }
        Method preferred = m("preferred", int.class, int.class);
        String[] stats = {"MAXHP", "MAXMP", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"};
        List<CharacterStore.Char> chars = new ArrayList<>(CharacterStore.get().all());
        chars.sort(Comparator.comparingInt(c -> c.charId));
        StringBuilder out = new StringBuilder();
        for (CharacterStore.Char c : chars) {
            if (!c.alive) continue;
            int[] types = lootgobbler.ClassSlots.of(c.classType);
            int[] max = lootgobbler.ClassSlots.maxStats(c.classType);
            if (types == null) continue;
            @SuppressWarnings("unchecked") List<Integer> pl = (List<Integer>) pool.invoke(null, c);
            out.append("pool ").append(c.charId).append(' ').append(pl).append('\n');
            for (int k = 0; k < CHOSEN.length; k++) for (int scope : new int[]{0, 1, 2}) for (int second : new int[]{0, 2}) for (double def : new double[]{0, 40}) {
                if (def > 0 && !Arrays.asList(CHOSEN[k]).contains("DPS")) continue;
                Set<String> chosen = new LinkedHashSet<>(Arrays.asList(CHOSEN[k]));
                String scen = k + "/" + scope + "/" + second + "/" + (int) def;
                int[] now = new int[8];
                double[][] wornItems = new double[4][], wornEnch = new double[4][];
                ItemDb.Item[] w = new ItemDb.Item[4];
                for (int s = 0; s < 4; s++) {
                    int cur = c.equipment != null && s < c.equipment.length ? c.equipment[s] : -1;
                    w[s] = cur > 0 ? ItemDb.get().item(cur) : null;
                    int[] one = new int[8];
                    add(now, w[s], stats);
                    add(one, w[s], stats);
                    wornItems[s] = d(one);
                }
                List<List<Enchantment>> wornLists = new ArrayList<>();
                for (int s = 0; s < 4; s++) {
                    @SuppressWarnings("unchecked") List<Integer> we = (List<Integer>) worn.invoke(null, c, s);
                    wornLists.add(EnchantPlanner.of(we));
                    wornEnch[s] = EnchantPlanner.gains(wornLists.get(s), d(now));
                }
                Object[] picks = new Object[4];
                int[] suggested = new int[8];
                for (int s = 0; s < 4; s++) {
                    Object a = aim.invoke(null, max, wornItems, wornEnch, wornLists, s, w[0], wornLists.get(0), def);
                    picks[s] = bestPick.invoke(null, c, s, types[s], pl, chosen, d(now), a, scope,
                            lockedItem.invoke(null, c.charId, s), preferred.invoke(null, s, second));
                    add(suggested, (ItemDb.Item) call(picks[s], "item"), stats);
                }
                double[][] sugItems = new double[4][], sugEnch = new double[4][];
                List<List<Enchantment>> sugLists = new ArrayList<>();
                for (int s = 0; s < 4; s++) {
                    EnchantPlanner.Plan plan = (EnchantPlanner.Plan) call(picks[s], "plan");
                    @SuppressWarnings("unchecked") List<Integer> ench = (List<Integer>) call(picks[s], "enchants");
                    sugLists.add(plan.picks().isEmpty() ? EnchantPlanner.of(ench) : plan.picks());
                    int[] one = new int[8];
                    add(one, (ItemDb.Item) call(picks[s], "item"), stats);
                    sugItems[s] = d(one);
                    sugEnch[s] = plan.picks().isEmpty() ? EnchantPlanner.gains(EnchantPlanner.of(ench), d(suggested)) : plan.gains();
                }
                List<Enchantment> sugWeapon = sugLists.get(0);
                for (int s = 0; s < 4; s++) {
                    Object pk = picks[s];
                    EnchantPlanner.Aim a = (EnchantPlanner.Aim) aim.invoke(null, max, sugItems, sugEnch, sugLists, s, call(picks[0], "item"), sugWeapon, def);
                    boolean equipped = (boolean) call(pk, "equipped");
                    @SuppressWarnings("unchecked") List<Enchantment> lk = scope == 0 && equipped ? (List<Enchantment>) locked.invoke(null, c.charId, s, pk) : List.of();
                    int slots = (int) call(pk, "slots");
                    ItemDb.Item item = (ItemDb.Item) call(pk, "item");
                    EnchantPlanner.Plan plan = slots > 0 ? EnchantPlanner.best(item, slots, chosen, d(suggested), s == 0 ? a.onWeapon(item) : a, lk) : EnchantPlanner.Plan.NONE;
                    List<Integer> planned = new ArrayList<>();
                    for (Enchantment e : plan.picks()) planned.add(e.type());
                    out.append(String.format(Locale.ROOT, "suggest %d %s %d %d %s %s %s %.6f%n", c.charId, scen, s, (int) call(pk, "id"),
                            ((String) call(pk, "where")).replace(' ', '_'), call(pk, "enchants"), planned, plan.score()));
                }
            }
        }
        System.out.print(out.toString().replace(", ", ","));
        System.exit(0);
    }

    static void add(int[] into, ItemDb.Item it, String[] stats) {
        if (it == null) return;
        for (ItemDb.Stat st : it.onEquip) {
            int i = Arrays.asList(stats).indexOf(st.stat());
            if (i >= 0) into[i] += st.amount();
        }
    }

    static double[] d(int[] a) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i];
        return r;
    }
}
