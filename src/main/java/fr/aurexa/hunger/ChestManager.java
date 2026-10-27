package fr.aurexa.hunger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Coffres aléatoires + loot. Toutes les opérations sur les blocs passent par une file de jobs
 * traitée à raison de "per-tick" coffres par tick : aucun pic de lag, même sur une grande arène.
 */
public final class ChestManager {

    private static final int PLACE = 0, REFILL = 1, REMOVE = 2;

    private static final class Job {
        final int type, zone, tier;
        final Location loc;
        Job(int type, int zone, int tier, Location loc) { this.type = type; this.zone = zone; this.tier = tier; this.loc = loc; }
    }

    private static final class Entry {
        final Material mat;
        final int min, max, weight;
        final short data;
        Entry(Material mat, int min, int max, int weight, short data) {
            this.mat = mat; this.min = min; this.max = max; this.weight = weight; this.data = data;
        }
        ItemStack create(Random r) {
            return new ItemStack(mat, min + r.nextInt(max - min + 1), data);
        }
    }

    private static final class Table {
        final List<Entry> entries = new ArrayList<Entry>();
        int total;
        void add(Entry e) { entries.add(e); total += e.weight; }
        Entry pick(Random r) {
            if (total <= 0) return null;
            int n = r.nextInt(total);
            for (Entry e : entries) { n -= e.weight; if (n < 0) return e; }
            return null;
        }
    }

    private final AurexaHunger plugin;
    private final Game game;
    private final Map<Location, Integer> chests = new HashMap<Location, Integer>(); // emplacement -> tier (0 normal, 1 rare)
    private final Deque<Job> jobs = new ArrayDeque<Job>();

    private boolean enabled;
    private int perZone, centerCount, centerRadius, perTick, refillInterval;
    private int minNormal = 3, maxNormal = 6, minRare = 6, maxRare = 10;
    private Table normal = new Table(), rare = new Table();

    public ChestManager(AurexaHunger plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("chests.enabled", true);
        perZone = c.getInt("chests.per-zone", 3);
        centerCount = c.getInt("chests.center-count", 6);
        centerRadius = Math.max(1, c.getInt("chests.center-radius", 4));
        perTick = Math.max(1, c.getInt("chests.per-tick", 3));
        refillInterval = c.getInt("chests.refill-interval", 300);

        List<Integer> n = c.getIntegerList("chests.items-normal");
        if (n.size() >= 2) { minNormal = n.get(0); maxNormal = Math.max(n.get(0), n.get(1)); }
        List<Integer> r = c.getIntegerList("chests.items-rare");
        if (r.size() >= 2) { minRare = r.get(0); maxRare = Math.max(r.get(0), r.get(1)); }

        normal = load(c.getStringList("loot.normal"));
        rare = load(c.getStringList("loot.rare"));
    }

    /** Format d'une ligne : MATERIAL;quantité(ou min-max);poids[;data] */
    private Table load(List<String> lines) {
        Table t = new Table();
        for (String line : lines) {
            try {
                String[] p = line.split(";");
                Material m = Material.matchMaterial(p[0].trim());
                if (m == null) { plugin.getLogger().warning("Loot : matériau inconnu '" + p[0] + "'"); continue; }
                String[] a = p[1].trim().split("-");
                int min = Integer.parseInt(a[0].trim());
                int max = a.length > 1 ? Integer.parseInt(a[1].trim()) : min;
                int w = p.length > 2 ? Integer.parseInt(p[2].trim()) : 1;
                short d = p.length > 3 ? Short.parseShort(p[3].trim()) : 0;
                t.add(new Entry(m, Math.max(1, min), Math.max(Math.max(1, min), max), Math.max(1, w), d));
            } catch (Exception ex) {
                plugin.getLogger().warning("Loot : ligne invalide '" + line + "'");
            }
        }
        return t;
    }

    public boolean enabled() { return enabled; }
    public int refillInterval() { return refillInterval; }
    public int count() { return chests.size(); }
    public boolean isChest(Block b) { return !chests.isEmpty() && chests.containsKey(b.getLocation()); }

    // ------------------------------------------------------------ planification
    /** Met en file la pose des coffres (cornucopia d'abord, puis zones mélangées). */
    public void generate() {
        clearNow();
        if (!enabled) return;
        for (int i = 0; i < centerCount; i++) jobs.add(new Job(PLACE, 0, 1, null));
        List<Job> list = new ArrayList<Job>();
        for (int z = 1; z <= game.zones().count(); z++) {
            for (int k = 0; k < perZone; k++) list.add(new Job(PLACE, z, 0, null));
        }
        Collections.shuffle(list);
        jobs.addAll(list);
    }

    public void refill() {
        for (Location l : chests.keySet()) jobs.add(new Job(REFILL, 0, 0, l));
    }

    /** Suppression progressive (fin de partie). */
    public void clearQueued() {
        jobs.clear();
        for (Location l : chests.keySet()) jobs.add(new Job(REMOVE, 0, 0, l));
    }

    /** Suppression immédiate (arrêt du plugin / avant une nouvelle partie). */
    public void clearNow() {
        jobs.clear();
        for (Location l : chests.keySet()) removeChest(l);
        chests.clear();
    }

    // ------------------------------------------------------------ traitement (appelé chaque tick)
    public void process() {
        if (jobs.isEmpty()) return;
        boolean running = game.state() == GameState.RUNNING;
        for (int i = 0; i < perTick && !jobs.isEmpty(); i++) {
            Job j = jobs.poll();
            switch (j.type) {
                case PLACE:
                    if (running) place(j.zone, j.tier);
                    break;
                case REFILL:
                    if (running) refillOne(j.loc);
                    break;
                case REMOVE:
                    removeChest(j.loc);
                    chests.remove(j.loc);
                    break;
            }
        }
    }

    private void place(int zone, int tier) {
        World w = game.world();
        ZoneManager zm = game.zones();
        for (int attempt = 0; attempt < 10; attempt++) {
            int[] xz = zone == 0 ? zm.randomAroundCenter(centerRadius) : zm.randomBlock(zone);
            int y = w.getHighestBlockYAt(xz[0], xz[1]);
            if (y < 2 || y > 250) continue;

            Block target = w.getBlockAt(xz[0], y, xz[1]);
            Block ground = target.getRelative(BlockFace.DOWN);
            if (isPlant(ground.getType())) { // herbe haute, fleurs, neige : on remplace la plante
                target = ground;
                ground = ground.getRelative(BlockFace.DOWN);
            }
            Material gm = ground.getType();
            if (!gm.isSolid() || gm == Material.LEAVES || gm == Material.LEAVES_2 || gm == Material.CACTUS) continue;
            if (target.getType() != Material.AIR && !isPlant(target.getType())) continue;
            if (adjacentChest(target)) continue; // évite les double-coffres

            target.setType(Material.CHEST);
            BlockState st = target.getState();
            if (st instanceof Chest) {
                fill(((Chest) st).getInventory(), tier);
                chests.put(target.getLocation(), tier);
            }
            return;
        }
    }

    private void refillOne(Location loc) {
        Block b = loc.getBlock();
        Integer tier = chests.get(loc);
        if (tier != null && b.getType() == Material.CHEST && b.getState() instanceof Chest) {
            fill(((Chest) b.getState()).getInventory(), tier);
        } else {
            chests.remove(loc); // coffre détruit
        }
    }

    private void removeChest(Location l) {
        Block b = l.getBlock();
        if (b.getType() == Material.CHEST) {
            BlockState s = b.getState();
            if (s instanceof Chest) ((Chest) s).getInventory().clear(); // pas de drops
            b.setType(Material.AIR);
        }
    }

    private void fill(Inventory inv, int tier) {
        inv.clear();
        Random r = ThreadLocalRandom.current();
        int min = tier == 1 ? minRare : minNormal;
        int max = tier == 1 ? maxRare : maxNormal;
        int rolls = min + r.nextInt(Math.max(1, max - min + 1));

        List<Integer> slots = new ArrayList<Integer>(27);
        for (int i = 0; i < 27; i++) slots.add(i);
        Collections.shuffle(slots, r);

        for (int i = 0; i < rolls && i < 27; i++) {
            // coffre rare : 60 % de chance de tirer dans la table rare
            Table t = (tier == 1 && rare.total > 0 && r.nextInt(100) < 60) ? rare : normal;
            Entry e = t.pick(r);
            if (e != null) inv.setItem(slots.get(i), e.create(r));
        }
    }

    private boolean adjacentChest(Block b) {
        return b.getRelative(BlockFace.NORTH).getType() == Material.CHEST
                || b.getRelative(BlockFace.SOUTH).getType() == Material.CHEST
                || b.getRelative(BlockFace.EAST).getType() == Material.CHEST
                || b.getRelative(BlockFace.WEST).getType() == Material.CHEST;
    }

    private boolean isPlant(Material m) {
        return m == Material.LONG_GRASS || m == Material.DEAD_BUSH || m == Material.YELLOW_FLOWER
                || m == Material.RED_ROSE || m == Material.SNOW;
    }
}
