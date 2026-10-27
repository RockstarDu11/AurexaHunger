package fr.aurexa.hunger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scoreboard par joueur sans clignotement. En 1.8 chaque ligne = Team (prefix 16 + suffix 16 caractères),
 * seules les lignes dont le texte a changé sont renvoyées au client.
 */
public final class BoardManager {

    private static final int MAX = 15;
    private static final String SEP = "&8===========";

    private static final class PBoard {
        final Scoreboard sb;
        final Objective obj;
        final Team[] teams = new Team[MAX];
        final String[] entries = new String[MAX];
        final String[] cache = new String[MAX];
        int used = 0;

        PBoard(Scoreboard sb, Objective obj) { this.sb = sb; this.obj = obj; }
    }

    private final AurexaHunger plugin;
    private final Map<UUID, PBoard> boards = new HashMap<UUID, PBoard>();

    public BoardManager(AurexaHunger plugin) { this.plugin = plugin; }

    public void create(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("hg", "dummy");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.setDisplayName(color(plugin.getConfig().getString("scoreboard.title", "&c&lAurexaHunger")));

        PBoard b = new PBoard(sb, obj);
        for (int i = 0; i < MAX; i++) {
            b.entries[i] = "\u00a7" + Integer.toHexString(i) + "\u00a7r"; // entrée unique et invisible
            b.teams[i] = sb.registerNewTeam("l" + i);
            b.teams[i].addEntry(b.entries[i]);
        }
        boards.put(p.getUniqueId(), b);
        p.setScoreboard(sb);
    }

    public void remove(Player p) {
        boards.remove(p.getUniqueId());
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    /** Scoreboard uniquement pour les joueurs d'une partie ; retiré dès qu'ils en sortent. */
    public void updateAll() {
        Set<UUID> active = new HashSet<UUID>();
        for (Game g : plugin.arenas().all()) {
            for (Player p : g.players()) {
                active.add(p.getUniqueId());
                update(p, g);
            }
        }
        Iterator<Map.Entry<UUID, PBoard>> it = boards.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PBoard> en = it.next();
            if (!active.contains(en.getKey())) {
                Player p = Bukkit.getPlayer(en.getKey());
                if (p != null) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                it.remove();
            }
        }
    }

    public void shutdown() {
        for (UUID id : boards.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
    }

    private void update(Player p, Game game) {
        PBoard b = boards.get(p.getUniqueId());
        if (b == null) { create(p); b = boards.get(p.getUniqueId()); }

        List<String> lines = buildLines(p, game);
        int n = Math.min(lines.size(), MAX);
        boolean rescore = n != b.used;

        for (int i = 0; i < n; i++) {
            String s = color(lines.get(i));
            if (!s.equals(b.cache[i])) {
                String[] parts = split(s);
                b.teams[i].setPrefix(parts[0]);
                b.teams[i].setSuffix(parts[1]);
                b.cache[i] = s;
            }
            if (rescore) b.obj.getScore(b.entries[i]).setScore(n - i);
        }
        for (int i = n; i < b.used; i++) {
            b.sb.resetScores(b.entries[i]);
            b.cache[i] = null;
        }
        b.used = n;
    }

    /** Coupe en prefix/suffix de 16 caractères max en conservant la couleur. */
    private static String[] split(String s) {
        if (s.length() <= 16) return new String[]{s, ""};
        int cut = 16;
        if (s.charAt(cut - 1) == ChatColor.COLOR_CHAR) cut--;
        String prefix = s.substring(0, cut);
        String suffix = ChatColor.getLastColors(prefix) + s.substring(cut);
        if (suffix.length() > 16) suffix = suffix.substring(0, 16);
        return new String[]{prefix, suffix};
    }

    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private List<String> buildLines(Player p, Game game) {
        List<String> l = new ArrayList<String>(14);
        l.add("&aTributs &f" + game.tributes());
        l.add("&f" + p.getName());
        l.add(SEP);

        switch (game.state()) {
            case WAITING:
                l.add("&6Arène &f" + game.arena().name());
                l.add("&fAttente &7(" + game.participantCount() + "/" + game.minPlayers() + ")");
                l.add(SEP);
                break;
            case STARTING:
                l.add("&6Arène &f" + game.arena().name());
                l.add("&eDébut dans &f" + game.timer() + "s");
                l.add(SEP);
                break;
            case ENDING:
                l.add("&eStatut");
                l.add("&6Partie terminée");
                l.add(SEP);
                l.add("&6Kills &f" + game.kills(p));
                break;
            case RUNNING:
                int z = game.zones().zoneAt(p.getLocation());
                l.add("&aVotre zone");
                l.add("&fZone " + z + " " + game.zoneTag(z));
                l.add(SEP);
                if (game.eventEnabled()) {
                    l.add("&cZone d'Event");
                    l.add("&fZone " + game.eventZone());
                    l.add(game.eventActive()
                            ? "&4Actif &f" + Game.fmt(game.eventTimer())
                            : "&7Dans &f" + Game.fmt(game.eventTimer()));
                    l.add(SEP);
                }
                l.add(game.pvpEnabled() ? "&cPvP &aactivé" : "&ePvP dans &f" + Game.fmt(game.pvpTimer()));
                if (game.refillEnabled()) l.add("&bCoffres &f" + Game.fmt(game.refillTimer()));
                l.add("&6Kills &f" + game.kills(p));
                break;
        }
        return l;
    }
}
