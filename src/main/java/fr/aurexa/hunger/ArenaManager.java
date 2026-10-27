package fr.aurexa.hunger;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Toutes les arènes (une partie indépendante chacune) + savoir dans quelle partie est un joueur. */
public final class ArenaManager {

    private final AurexaHunger plugin;
    private final Map<String, Game> games = new LinkedHashMap<String, Game>();   // nom en minuscules -> partie
    private final Map<UUID, Game> byPlayer = new HashMap<UUID, Game>();

    public ArenaManager(AurexaHunger plugin) { this.plugin = plugin; }

    public void load() {
        games.clear();
        byPlayer.clear();
        FileConfiguration c = plugin.arenasConfig();
        ConfigurationSection root = c.getConfigurationSection("arenas");
        if (root == null) return;
        int defMin = plugin.getConfig().getInt("game.min-players", 2);
        int defMax = plugin.getConfig().getInt("game.max-players", 24);
        for (String name : root.getKeys(false)) {
            Arena a = Arena.load(name, root.getConfigurationSection(name), defMin, defMax);
            games.put(name.toLowerCase(), new Game(plugin, a));
        }
    }

    public void save() {
        FileConfiguration c = plugin.arenasConfig();
        c.set("arenas", null);
        for (Game g : games.values()) g.arena().save(c.createSection("arenas." + g.arena().name()));
        plugin.saveArenas();
    }

    public Game get(String name) { return name == null ? null : games.get(name.toLowerCase()); }
    public Collection<Game> all() { return games.values(); }

    public Game create(String name) {
        Arena a = new Arena(name, plugin.getConfig().getInt("game.min-players", 2),
                plugin.getConfig().getInt("game.max-players", 24));
        Game g = new Game(plugin, a);
        games.put(name.toLowerCase(), g);
        save();
        return g;
    }

    public void delete(String name) {
        games.remove(name.toLowerCase());
        save();
    }

    // joueur <-> partie
    public Game gameOf(Player p) { return byPlayer.isEmpty() ? null : byPlayer.get(p.getUniqueId()); }
    public void bind(UUID id, Game g) { byPlayer.put(id, g); }
    public void unbind(UUID id) { byPlayer.remove(id); }

    public boolean isChest(Block b) {
        for (Game g : games.values()) if (g.chests().isChest(b)) return true;
        return false;
    }

    public void tickAll() {
        for (Game g : new ArrayList<Game>(games.values())) g.tick();
    }

    public void processChests() {
        for (Game g : games.values()) g.chests().process();
    }

    public void shutdown() {
        for (Game g : games.values()) g.shutdown();
        byPlayer.clear();
    }
}
