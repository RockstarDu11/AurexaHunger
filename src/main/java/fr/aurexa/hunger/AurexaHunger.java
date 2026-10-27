package fr.aurexa.hunger;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AurexaHunger extends JavaPlugin implements TabExecutor {

    public static final String PERM = "aurexahunger.admin";
    public static final String WAND_NAME = ChatColor.GOLD + "Sélecteur d'arène";
    private static final String PREFIX = ChatColor.GOLD + "AurexaHunger " + ChatColor.GOLD + ">> ";

    private ArenaManager arenas;
    private BoardManager boards;
    private SignManager signs;
    private File arenasFile;
    private FileConfiguration arenasCfg;
    private final Map<UUID, Location[]> selections = new HashMap<UUID, Location[]>();
    private final Map<UUID, String> pending = new HashMap<UUID, String>(); // "" = panneau Quitter

    @Override
    public void onEnable() {
        saveDefaultConfig();
        arenasFile = new File(getDataFolder(), "arenas.yml");
        arenasCfg = YamlConfiguration.loadConfiguration(arenasFile);

        arenas = new ArenaManager(this);
        boards = new BoardManager(this);
        signs = new SignManager(this);
        arenas.load();
        signs.load();
        getServer().getPluginManager().registerEvents(new GameListener(this, arenas, signs), this);

        getCommand("hg").setExecutor(this);
        getCommand("hg").setTabCompleter(this);

        // logique + scoreboards + panneaux, 1x / seconde
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override public void run() {
                arenas.tickAll();
                boards.updateAll();
                signs.updateAll();
            }
        }, 20L, 20L);

        // pose / rechargement / retrait des coffres, quelques coffres par tick (sans lag)
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override public void run() { arenas.processChests(); }
        }, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (arenas != null) arenas.shutdown();
        if (boards != null) boards.shutdown();
    }

    public ArenaManager arenas() { return arenas; }
    public BoardManager boards() { return boards; }
    public FileConfiguration arenasConfig() { return arenasCfg; }

    public void saveArenas() {
        try {
            getDataFolder().mkdirs();
            arenasCfg.save(arenasFile);
        } catch (IOException ex) {
            getLogger().warning("arenas.yml : " + ex.getMessage());
        }
    }

    /** Message depuis config.yml : %prefix% automatique, puis paires clé/valeur, puis couleurs &. */
    public String msg(String key, String... kv) {
        // sans valeur par défaut explicite : si la clé manque dans ton config.yml, on prend celle du jar
        String raw = getConfig().getString("messages." + key);
        if (raw == null) raw = "&cMessage manquant : " + key;
        String prefix = getConfig().getString("messages.prefix");
        raw = raw.replace("%prefix%", prefix == null ? "" : prefix);
        for (int i = 0; i + 1 < kv.length; i += 2) raw = raw.replace("%" + kv[i] + "%", kv[i + 1]);
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private static void say(CommandSender s, String legacy) {
        s.sendMessage(PREFIX + ChatColor.translateAlternateColorCodes('&', legacy));
    }

    // ------------------------------------------------------------ sélection / panneaux en attente
    public void setPos(Player p, int n, Location l) {
        Location[] s = selections.get(p.getUniqueId());
        if (s == null) { s = new Location[2]; selections.put(p.getUniqueId(), s); }
        s[n - 1] = l.getBlock().getLocation();
        p.sendMessage(msg("pos-set", "n", String.valueOf(n), "x", String.valueOf(l.getBlockX()),
                "y", String.valueOf(l.getBlockY()), "z", String.valueOf(l.getBlockZ())));
    }

    public void clearSelection(Player p) {
        selections.remove(p.getUniqueId());
        pending.remove(p.getUniqueId());
    }

    public String pendingSign(Player p) { return pending.get(p.getUniqueId()); }

    public void completeSign(Player p, Block b) {
        String arena = pending.get(p.getUniqueId());
        if (arena == null || !(b.getState() instanceof Sign)) return;
        Sign sign = (Sign) b.getState();

        boolean blank = true;
        for (int i = 0; i < 4; i++) if (!sign.getLine(i).trim().isEmpty()) blank = false;
        if (!blank && !signs.isSign(b)) {
            say(p, "&cCe panneau n'est pas vierge. &7Efface son texte ou choisis-en un autre.");
            return;
        }
        pending.remove(p.getUniqueId());
        if (arena.isEmpty()) {
            signs.addLeave(b);
            say(p, "&aPanneau &eQuitter &acréé.");
        } else {
            signs.addJoin(b, arena);
            signs.updateAll();
            say(p, "&aPanneau de l'arène &e" + arena + " &acréé.");
        }
    }

    // ------------------------------------------------------------ commandes
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase();
        boolean admin = s.hasPermission(PERM);
        Player pl = s instanceof Player ? (Player) s : null;

        // ---- commandes joueurs
        if (sub.equals("help") || sub.equals("?")) { help(s, admin); return true; }
        if (sub.equals("list")) { list(s); return true; }
        if (sub.equals("join")) {
            if (pl == null) return true;
            if (a.length < 2) { say(s, "&cUsage : /hg join <arène>"); return true; }
            Game g = arenas.get(a[1]);
            if (g == null) { unknown(s, a[1]); return true; }
            g.tryJoin(pl);
            return true;
        }
        if (sub.equals("leave")) {
            if (pl == null) return true;
            Game g = arenas.gameOf(pl);
            pl.sendMessage(g != null && g.leave(pl) ? msg("left") : msg("not-in-game"));
            return true;
        }

        // ---- commandes admin
        if (!admin) { s.sendMessage(ChatColor.RED + "Permission refusée."); return true; }

        if (sub.equals("reload")) {
            reloadConfig();
            arenas.shutdown();
            arenasCfg = YamlConfiguration.loadConfiguration(arenasFile);
            arenas.load();
            signs.load();
            say(s, "&aConfiguration et arènes rechargées.");
            return true;
        }
        if (sub.equals("wand")) {
            if (pl == null) return true;
            ItemStack it = new ItemStack(Material.WOOD_AXE);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(WAND_NAME);
            m.setLore(Arrays.asList(ChatColor.GRAY + "Clic gauche : position 1", ChatColor.GRAY + "Clic droit : position 2"));
            it.setItemMeta(m);
            pl.getInventory().addItem(it);
            pl.sendMessage(msg("wand-given"));
            return true;
        }
        if (sub.equals("pos1") || sub.equals("pos2")) {
            if (pl != null) setPos(pl, sub.equals("pos1") ? 1 : 2, pl.getLocation());
            return true;
        }
        if (sub.equals("leavesign")) {
            if (pl == null) return true;
            pending.put(pl.getUniqueId(), "");
            say(s, "&eClique droit sur un &fpanneau vierge &epour en faire un panneau &fQuitter&e.");
            return true;
        }
        if (sub.equals("create")) {
            if (a.length < 2) { say(s, "&cUsage : /hg create <nom>"); return true; }
            if (!a[1].matches("[A-Za-z0-9_-]{1,16}")) {
                say(s, "&cNom invalide (lettres, chiffres, - et _ ; 16 max).");
                return true;
            }
            if (arenas.get(a[1]) != null) { say(s, "&cUne arène &e" + a[1] + " &cexiste déjà."); return true; }
            arenas.create(a[1]);
            say(s, "&aArène &e" + a[1] + " &acréée. &7Étapes :");
            say(s, "&e1. &f/hg wand &7puis &f/hg setregion " + a[1] + " &7(zone de jeu)");
            say(s, "&e2. &f/hg setlobby " + a[1] + " &7(salle d'attente)");
            say(s, "&e3. &f/hg setspawn " + a[1] + " &7(un par joueur, répète)");
            say(s, "&e4. &f/hg sign " + a[1] + " &7puis clic droit sur un panneau vierge");
            return true;
        }

        // toutes les suivantes demandent un nom d'arène
        if (sub.equals("list") || a.length < 2) {
            if (sub.equals("start") || sub.equals("stop")) {
                // sans nom : l'arène du joueur
                Game own = pl != null ? arenas.gameOf(pl) : null;
                if (own != null) { startStop(s, sub, own); return true; }
            }
            say(s, "&cUsage : /hg " + sub + " <arène>   &7(/hg help)");
            return true;
        }
        Game g = arenas.get(a[1]);
        if (g == null) { unknown(s, a[1]); return true; }
        Arena ar = g.arena();

        if (sub.equals("start") || sub.equals("stop")) {
            startStop(s, sub, g);
        } else if (sub.equals("info")) {
            info(s, g);
        } else if (sub.equals("delete")) {
            g.forceStop();
            signs.removeArena(ar.name());
            arenas.delete(ar.name());
            say(s, "&aArène &e" + ar.name() + " &asupprimée.");
        } else if (sub.equals("sign")) {
            if (pl == null) return true;
            pending.put(pl.getUniqueId(), ar.name());
            say(s, "&eClique droit sur un &fpanneau vierge &epour le lier à l'arène &f" + ar.name() + "&e.");
        } else if (sub.equals("setplayers")) {
            if (!editable(s, g)) return true;
            try {
                int min = Integer.parseInt(a[2]), max = Integer.parseInt(a[3]);
                if (min < 1 || max < min) throw new NumberFormatException();
                ar.setPlayers(min, max);
                saveAndReload(g);
                say(s, "&aJoueurs de &e" + ar.name() + " &a: min &f" + min + " &amax &f" + max);
            } catch (Exception ex) {
                say(s, "&cUsage : /hg setplayers <nom> <min> <max>");
            }
        } else if (pl == null) {
            say(s, "&cCette commande se fait en jeu.");
        } else if (sub.equals("setlobby")) {
            if (!editable(s, g)) return true;
            ar.setLobby(new Arena.Loc(pl.getLocation()));
            saveAndReload(g);
            say(s, "&aLobby de &e" + ar.name() + " &adéfini ici.");
        } else if (sub.equals("setspawn")) {
            if (!editable(s, g)) return true;
            ar.addSpawn(new Arena.Loc(pl.getLocation()));
            saveAndReload(g);
            String warn = (ar.hasRegion() && !g.zones().contains(pl.getLocation()))
                    ? " &c(attention : hors de la région !)" : "";
            say(s, "&aSpawn &f#" + ar.spawns().size() + " &aajouté pour &e" + ar.name() + warn);
        } else if (sub.equals("delspawn")) {
            if (!editable(s, g)) return true;
            int n = ar.spawns().size();
            int idx = n;
            if (a.length >= 3) {
                try { idx = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { idx = -1; }
            }
            if (n == 0 || idx < 1 || idx > n) { say(s, "&cAucun spawn à ce numéro (il y en a &f" + n + "&c)."); return true; }
            ar.spawns().remove(idx - 1);
            saveAndReload(g);
            say(s, "&aSpawn &f#" + idx + " &aretiré. &7Il en reste &f" + ar.spawns().size() + "&7.");
        } else if (sub.equals("clearspawns")) {
            if (!editable(s, g)) return true;
            ar.clearSpawns();
            saveAndReload(g);
            say(s, "&aTous les spawns de &e" + ar.name() + " &aont été retirés.");
        } else if (sub.equals("setregion")) {
            if (!editable(s, g)) return true;
            Location[] sel = selections.get(pl.getUniqueId());
            if (sel == null || sel[0] == null || sel[1] == null) { pl.sendMessage(msg("selection-missing")); return true; }
            if (!sel[0].getWorld().getName().equals(sel[1].getWorld().getName())) { pl.sendMessage(msg("selection-world")); return true; }
            ar.setRegion(sel[0].getWorld().getName(), sel[0].getBlockX(), sel[0].getBlockY(), sel[0].getBlockZ(),
                    sel[1].getBlockX(), sel[1].getBlockY(), sel[1].getBlockZ());
            saveAndReload(g);
            say(s, "&aRégion de &e" + ar.name() + " &aenregistrée : &f" + g.zones().width() + "x" + g.zones().depth()
                    + " &ablocs, &f" + g.zones().count() + " &azones.");
        } else {
            help(s, true);
        }
        return true;
    }

    private void saveAndReload(Game g) {
        arenas.save();
        g.reload();
    }

    private boolean editable(CommandSender s, Game g) {
        if (g.state() != GameState.WAITING || g.participantCount() > 0) {
            say(s, "&cCette arène est utilisée. &7Fais &f/hg stop " + g.arena().name() + " &7avant de la modifier.");
            return false;
        }
        return true;
    }

    private void unknown(CommandSender s, String name) {
        say(s, "&cArène inconnue : &e" + name + "&c. &7/hg list");
    }

    private void startStop(CommandSender s, String sub, Game g) {
        if (sub.equals("start")) {
            String err = g.forceStart();
            if (err == null) say(s, "&aPartie lancée sur &e" + g.arena().name() + "&a.");
            else s.sendMessage(msg(err));
        } else {
            say(s, g.forceStop() ? "&eArène &f" + g.arena().name() + " &earrêtée, joueurs renvoyés."
                    : "&cAucune partie en cours sur cette arène.");
        }
    }

    private String stateText(Game g) {
        if (!g.ready()) return "&4Non prête";
        switch (g.state()) {
            case WAITING:  return "&aEn attente";
            case STARTING: return "&eDémarrage";
            case RUNNING:  return "&cEn cours";
            default:       return "&6Terminée";
        }
    }

    private void list(CommandSender s) {
        if (arenas.all().isEmpty()) { say(s, "&7Aucune arène pour le moment."); return; }
        say(s, "&eArènes :");
        for (Game g : arenas.all()) {
            say(s, "&f" + g.arena().name() + " &7- " + stateText(g) + " &7- &f" + g.participantCount() + "/" + g.maxPlayers()
                    + " &7joueurs");
        }
    }

    private void info(CommandSender s, Game g) {
        Arena a = g.arena();
        say(s, "&e--- Arène &f" + a.name() + " &e---");
        say(s, "&7État : " + stateText(g) + " &7- Joueurs : &f" + g.participantCount() + " &7(min &f" + a.minPlayers()
                + "&7, max &f" + a.maxPlayers() + "&7)");
        say(s, "&7Région : " + (a.hasRegion()
                ? "&a" + g.zones().width() + "x" + g.zones().depth() + " blocs &7(monde &f" + a.world() + "&7, " + g.zones().count() + " zones)"
                : "&cnon définie &7(/hg setregion)"));
        say(s, "&7Lobby : " + (a.hasLobby() ? "&a" + a.lobby() : "&cnon défini &7(/hg setlobby)"));
        int sp = a.spawns().size();
        say(s, "&7Spawns : " + (sp == 0 ? "&cnone &7(cercle automatique)" : "&a" + sp)
                + (sp > 0 && sp < a.maxPlayers() ? " &e(moins que le max de joueurs)" : ""));
        int n = signs.countFor(a.name());
        say(s, "&7Panneaux : " + (n == 0 ? "&cnone &7(/hg sign " + a.name() + ")" : "&a" + n));
    }

    private void help(CommandSender s, boolean admin) {
        String[][] player = {
                {"/hg join <arène>", "rejoindre une partie"},
                {"/hg leave", "quitter la partie"},
                {"/hg list", "liste des arènes"}};
        String[][] adm = {
                {"/hg create <nom>", "créer une arène"},
                {"/hg delete <nom>", "supprimer une arène"},
                {"/hg setlobby <nom>", "salle d'attente (où tu te tiens)"},
                {"/hg setspawn <nom>", "ajouter un spawn (où tu te tiens)"},
                {"/hg delspawn <nom> [n]", "retirer un spawn"},
                {"/hg clearspawns <nom>", "retirer tous les spawns"},
                {"/hg wand | pos1 | pos2", "(sélection de la zone de jeu)"},
                {"/hg setregion <nom>", "(zone de jeu, utilise la sélection)"},
                {"/hg sign <nom>", "(puis clic droit sur un panneau vierge)"},
                {"/hg leavesign", "(puis clic droit sur un panneau vierge)"},
                {"/hg setplayers <nom> <min> <max>", "nombre de joueurs"},
                {"/hg start|stop <nom>", "forcer / arrêter une partie"},
                {"/hg info <nom>", "informations sur l'arène"},
                {"/hg reload", "recharger la configuration"}};
        for (String[] l : player) s.sendMessage(PREFIX + ChatColor.YELLOW + l[0] + ChatColor.GOLD + " - " + l[1]);
        if (admin) {
            s.sendMessage(PREFIX + ChatColor.RED + "--- Admin ---");
            for (String[] l : adm) s.sendMessage(PREFIX + ChatColor.YELLOW + l[0] + ChatColor.GOLD + " - " + l[1]);
        }
    }

    // ------------------------------------------------------------ auto-complétion
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<String>();
        boolean admin = s.hasPermission(PERM);
        if (a.length == 1) {
            List<String> subs = new ArrayList<String>(Arrays.asList("help", "join", "leave", "list"));
            if (admin) subs.addAll(Arrays.asList("create", "delete", "setlobby", "setspawn", "delspawn", "clearspawns",
                    "wand", "pos1", "pos2", "setregion", "sign", "leavesign", "setplayers", "start", "stop", "info", "reload"));
            for (String x : subs) if (x.startsWith(a[0].toLowerCase())) out.add(x);
        } else if (a.length == 2) {
            String sub = a[0].toLowerCase();
            boolean needsArena = sub.equals("join") || (admin && Arrays.asList("delete", "setlobby", "setspawn", "delspawn",
                    "clearspawns", "setregion", "sign", "setplayers", "start", "stop", "info").contains(sub));
            if (needsArena) {
                for (Game g : arenas.all()) {
                    if (g.arena().name().toLowerCase().startsWith(a[1].toLowerCase())) out.add(g.arena().name());
                }
            }
        }
        return out;
    }
}
