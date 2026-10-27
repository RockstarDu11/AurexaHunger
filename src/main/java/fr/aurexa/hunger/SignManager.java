package fr.aurexa.hunger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Panneaux "Rejoindre <arène>" (mis à jour en direct) et "Quitter", enregistrés dans arenas.yml. */
public final class SignManager {

    private final AurexaHunger plugin;
    private final Map<String, String> join = new LinkedHashMap<String, String>(); // clé du bloc -> arène (minuscules)
    private final Set<String> leave = new LinkedHashSet<String>();
    private final Map<String, String> cache = new HashMap<String, String>();

    public SignManager(AurexaHunger plugin) { this.plugin = plugin; }

    public void load() {
        join.clear(); leave.clear(); cache.clear();
        FileConfiguration c = plugin.arenasConfig();
        for (String s : c.getStringList("signs.join")) {
            String[] p = s.split("\\|", 2);
            if (p.length == 2) join.put(p[1], p[0]);
        }
        leave.addAll(c.getStringList("signs.leave"));
    }

    private void save() {
        FileConfiguration c = plugin.arenasConfig();
        List<String> j = new ArrayList<String>();
        for (Map.Entry<String, String> e : join.entrySet()) j.add(e.getValue() + "|" + e.getKey());
        c.set("signs.join", j);
        c.set("signs.leave", new ArrayList<String>(leave));
        plugin.saveArenas();
    }

    private static String key(Block b) { return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ(); }
    private static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    public String joinArena(Block b) { return join.isEmpty() ? null : join.get(key(b)); }
    public boolean isLeave(Block b) { return !leave.isEmpty() && leave.contains(key(b)); }
    public boolean isSign(Block b) { return joinArena(b) != null || isLeave(b); }

    public void addJoin(Block b, String arena) {
        String k = key(b);
        leave.remove(k);
        join.put(k, arena.toLowerCase());
        cache.remove(k);
        save();
    }

    public void addLeave(Block b) {
        String k = key(b);
        join.remove(k);
        leave.add(k);
        save();
        if (b.getState() instanceof Sign) {
            Sign s = (Sign) b.getState();
            String[] l = {c("&8[&cAurexaHunger&8]"), c("&cQuitter"), "", c("&7la partie")};
            for (int i = 0; i < 4; i++) s.setLine(i, l[i]);
            s.update();
        }
    }

    public boolean remove(Block b) {
        String k = key(b);
        boolean r = join.remove(k) != null | leave.remove(k);
        cache.remove(k);
        if (r) save();
        return r;
    }

    public void removeArena(String arena) {
        Iterator<Map.Entry<String, String>> it = join.entrySet().iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            if (e.getValue().equalsIgnoreCase(arena)) { cache.remove(e.getKey()); it.remove(); changed = true; }
        }
        if (changed) save();
    }

    public int countFor(String arena) {
        int n = 0;
        for (String a : join.values()) if (a.equalsIgnoreCase(arena)) n++;
        return n;
    }

    private String[] joinLines(Game g) {
        String name = g.arena().name();
        if (name.length() > 13) name = name.substring(0, 13);
        String st;
        boolean open = false;
        if (!g.ready()) {
            st = "&4Non prête";
        } else {
            switch (g.state()) {
                case WAITING:  st = "&aEn attente"; open = true; break;
                case STARTING: st = "&eDémarrage"; open = true; break;
                case RUNNING:  st = "&cEn cours"; break;
                default:       st = "&6Terminée"; break;
            }
        }
        open = open && g.participantCount() < g.maxPlayers();
        return new String[]{c("&8[&cAurexaHunger&8]"), c("&e&l" + name), c(st),
                c("&f" + g.participantCount() + "/" + g.maxPlayers() + " " + (open ? "&2Jouer" : "&cFermé"))};
    }

    /** Appelé 1x / seconde : ne réécrit un panneau que si son texte a changé. */
    public void updateAll() {
        if (join.isEmpty()) return;
        for (Map.Entry<String, String> e : join.entrySet()) {
            Game g = plugin.arenas().get(e.getValue());
            if (g == null) continue;
            String[] l = joinLines(g);
            String joined = l[0] + "|" + l[1] + "|" + l[2] + "|" + l[3];
            if (joined.equals(cache.get(e.getKey()))) continue;

            String[] p = e.getKey().split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) continue;
            int x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]), z = Integer.parseInt(p[3]);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, y, z);
            if (b.getState() instanceof Sign) {
                Sign s = (Sign) b.getState();
                for (int i = 0; i < 4; i++) s.setLine(i, l[i]);
                s.update();
                cache.put(e.getKey(), joined);
            }
        }
    }
}
