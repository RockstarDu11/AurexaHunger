package fr.aurexa.hunger;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class Game {

    /** État du joueur avant d'entrer dans la partie (restauré à la sortie). */
    private static final class Saved {
        ItemStack[] inv, armor;
        GameMode gm;
        Location loc;
        double health;
        int food, level;
        float exp;
    }

    private final AurexaHunger plugin;
    private final Arena arena;
    private final ChestManager chests;
    private ZoneManager zones;
    private GameState state = GameState.WAITING;

    private final Set<UUID> participants = new LinkedHashSet<UUID>(); // tous les joueurs de la partie
    private final Set<UUID> alive = new LinkedHashSet<UUID>();        // ceux encore en vie
    private final Map<UUID, Integer> kills = new HashMap<UUID, Integer>();
    private final Map<UUID, Location> deathLocs = new HashMap<UUID, Location>();
    private final Map<UUID, Saved> saved = new HashMap<UUID, Saved>();

    private int minPlayers, maxPlayers, lobbyCountdown, pvpDelay, endDelay, spawnRadius;
    private boolean eventEnabled, lightningFx;
    private int eventFirst, eventInterval, eventWarning, eventDuration;
    private double eventDamage;

    private int refillTimer, timer, pvpTimer, eventTimer, eventZone = 1, startedWith, tickCount;
    private boolean pvpEnabled, eventActive;

    public Game(AurexaHunger plugin, Arena arena) {
        this.plugin = plugin;
        this.arena = arena;
        this.chests = new ChestManager(plugin, this);
        reload();
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        zones = new ZoneManager(c, arena);
        minPlayers = arena.minPlayers();
        maxPlayers = arena.maxPlayers();
        lobbyCountdown = c.getInt("game.lobby-countdown", 30);
        pvpDelay = c.getInt("game.pvp-delay", 180);
        endDelay = c.getInt("game.end-delay", 15);
        spawnRadius = Math.max(1, c.getInt("arena.spawn-radius", 12));
        eventEnabled = c.getBoolean("event.enabled", true);
        eventFirst = c.getInt("event.first-delay", 240);
        eventInterval = c.getInt("event.interval", 120);
        eventWarning = c.getInt("event.warning", 20);
        eventDuration = c.getInt("event.duration", 60);
        eventDamage = c.getDouble("event.damage", 2.0);
        lightningFx = c.getBoolean("event.lightning", true);
        chests.reload();
    }

    // ------------------------------------------------------------ accès
    public GameState state() { return state; }
    public ZoneManager zones() { return zones; }
    public Arena arena() { return arena; }
    /** Région + lobby définis : l'arène peut accueillir une partie. */
    public boolean ready() { return zones.valid() && arena.lobbyLocation() != null; }
    public ChestManager chests() { return chests; }
    public int refillTimer() { return refillTimer; }
    public boolean refillEnabled() { return chests.enabled() && chests.refillInterval() > 0; }
    public boolean pvpEnabled() { return pvpEnabled; }
    public boolean eventActive() { return eventActive; }
    public int eventZone() { return eventZone; }
    public int eventTimer() { return eventTimer; }
    public int pvpTimer() { return pvpTimer; }
    public int timer() { return timer; }
    public int minPlayers() { return minPlayers; }
    public int maxPlayers() { return maxPlayers; }
    public boolean eventEnabled() { return eventEnabled; }
    public boolean isParticipant(Player p) { return participants.contains(p.getUniqueId()); }
    public boolean isAlive(Player p) { return alive.contains(p.getUniqueId()); }
    public int participantCount() { return participants.size(); }
    public int kills(Player p) { Integer k = kills.get(p.getUniqueId()); return k == null ? 0 : k; }
    public Location pollDeathLoc(Player p) { return deathLocs.remove(p.getUniqueId()); }

    public int tributes() {
        return (state == GameState.RUNNING || state == GameState.ENDING) ? alive.size() : participants.size();
    }

    public List<Player> players() {
        List<Player> l = new ArrayList<Player>(participants.size());
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) l.add(p);
        }
        return l;
    }

    public World world() {
        World w = zones.world();
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    public Location lobby() {
        Location l = arena.lobbyLocation();
        return l != null ? l : world().getSpawnLocation();
    }

    /** Point de repli au centre de l'arène (joueur coincé hors zone). */
    public Location arenaCenter() {
        World w = world();
        int x = (int) Math.floor(zones.centerX()), z = (int) Math.floor(zones.centerZ());
        return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5);
    }

    /** Étiquette (couleurs &) affichée à côté de la zone sur le scoreboard. */
    public String zoneTag(int zone) {
        if (eventActive && zone == eventZone) return "&4Event";
        if (!pvpEnabled || zones.isPassive(zone)) return "&aPassif";
        return "&cPvP";
    }

    public boolean isPvpZone(int zone) {
        return (eventActive && zone == eventZone) || !zones.isPassive(zone);
    }

    public boolean canPvp(Player attacker, Player victim) {
        if (state != GameState.RUNNING || !pvpEnabled) return false;
        if (!isAlive(attacker) || !isAlive(victim)) return false;
        return isPvpZone(zones.zoneAt(attacker.getLocation())) && isPvpZone(zones.zoneAt(victim.getLocation()));
    }

    // ------------------------------------------------------------ messages / sons (aux participants uniquement)
    private void broadcast(String msg) {
        for (Player p : players()) p.sendMessage(msg);
    }

    private void playAll(Sound s, float vol, float pitch) {
        for (Player p : players()) p.playSound(p.getLocation(), s, vol, pitch);
    }

    // ------------------------------------------------------------ rejoindre / quitter (panneaux)
    public void tryJoin(Player p) {
        if (plugin.arenas().gameOf(p) != null) { p.sendMessage(plugin.msg("already")); return; }
        if (!ready()) { p.sendMessage(plugin.msg("no-arena")); return; }
        if (state == GameState.RUNNING || state == GameState.ENDING) { p.sendMessage(plugin.msg("game-running")); return; }
        if (participants.size() >= maxPlayers) { p.sendMessage(plugin.msg("game-full")); return; }

        save(p);
        participants.add(p.getUniqueId());
        plugin.arenas().bind(p.getUniqueId(), this);
        toLobby(p);
        p.sendMessage(plugin.msg("you-joined"));
        broadcast(plugin.msg("join", "player", p.getName(),
                "count", String.valueOf(participants.size()), "max", String.valueOf(maxPlayers)));
    }

    /** @return false si le joueur n'était pas dans la partie. */
    public boolean leave(Player p) {
        UUID id = p.getUniqueId();
        if (!participants.contains(id)) return false;

        if (state == GameState.RUNNING && alive.contains(id)) {
            Location l = p.getLocation();
            for (ItemStack it : p.getInventory().getContents()) {
                if (it != null && it.getType() != Material.AIR) l.getWorld().dropItemNaturally(l, it);
            }
            for (ItemStack it : p.getInventory().getArmorContents()) {
                if (it != null && it.getType() != Material.AIR) l.getWorld().dropItemNaturally(l, it);
            }
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            handleDeath(p, l, "QUIT", null, null);
            deathLocs.remove(id);
        }
        participants.remove(id);
        alive.remove(id);
        if (state == GameState.WAITING || state == GameState.STARTING) {
            broadcast(plugin.msg("quit", "player", p.getName(),
                    "count", String.valueOf(participants.size()), "max", String.valueOf(maxPlayers)));
        }
        restore(p);
        return true;
    }

    private void save(Player p) {
        Saved s = new Saved();
        s.inv = cloneAll(p.getInventory().getContents());
        s.armor = cloneAll(p.getInventory().getArmorContents());
        s.gm = p.getGameMode();
        s.loc = p.getLocation();
        s.health = p.getHealth();
        s.food = p.getFoodLevel();
        s.level = p.getLevel();
        s.exp = p.getExp();
        saved.put(p.getUniqueId(), s);
    }

    private ItemStack[] cloneAll(ItemStack[] a) {
        ItemStack[] o = new ItemStack[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i] == null ? null : a[i].clone();
        return o;
    }

    private void restore(Player p) {
        if (plugin.boards() != null) plugin.boards().remove(p);
        plugin.arenas().unbind(p.getUniqueId());
        Saved s = saved.remove(p.getUniqueId());
        resetPlayer(p, s != null ? s.gm : GameMode.SURVIVAL);
        if (s == null) {
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            return;
        }
        p.getInventory().setContents(s.inv);
        p.getInventory().setArmorContents(s.armor);
        p.setHealth(s.health > 0 ? Math.min(s.health, p.getMaxHealth()) : p.getMaxHealth());
        p.setFoodLevel(s.food);
        p.setLevel(s.level);
        p.setExp(s.exp);
        p.updateInventory();
        p.teleport(s.loc);
    }

    // ------------------------------------------------------------ boucle (1x / seconde)
    public void tick() {
        tickCount++;
        switch (state) {
            case WAITING:
                if (ready() && participants.size() >= minPlayers) {
                    state = GameState.STARTING;
                    timer = lobbyCountdown;
                }
                break;
            case STARTING:
                if (participants.size() < minPlayers) {
                    state = GameState.WAITING;
                    broadcast(plugin.msg("not-enough"));
                } else if (timer <= 0) {
                    begin();
                } else {
                    if (timer % 10 == 0 || timer <= 5) {
                        broadcast(plugin.msg("countdown", "time", String.valueOf(timer)));
                        playAll(Sound.NOTE_STICKS, 1f, timer <= 5 ? 1.5f : 1f);
                    }
                    timer--;
                }
                break;
            case RUNNING:
                tickRunning();
                break;
            case ENDING:
                if (--timer <= 0) reset();
                break;
        }
    }

    private void tickRunning() {
        if (!pvpEnabled) {
            if (--pvpTimer <= 0) {
                pvpEnabled = true;
                broadcast(plugin.msg("pvp-enabled"));
                // gros titre au milieu de l'écran + chien enragé
                String t = plugin.msg("pvp-title"), st = plugin.msg("pvp-subtitle");
                for (Player p : players()) Packets.title(p, t, st, 5, 50, 15);
                playAll(Sound.WOLF_GROWL, 2f, 0.6f);
                Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                    @Override public void run() {
                        playAll(Sound.WOLF_BARK, 2f, 0.6f);
                        playAll(Sound.WOLF_GROWL, 2f, 0.5f);
                    }
                }, 12L);
            } else if (pvpTimer == 60 || pvpTimer == 30 || pvpTimer == 10 || pvpTimer <= 5) {
                broadcast(plugin.msg("pvp-soon", "time", String.valueOf(pvpTimer)));
            }
        }
        if (eventEnabled) tickEvent();
        if (refillEnabled()) {
            if (--refillTimer <= 0) {
                refillTimer = chests.refillInterval();
                chests.refill();
                broadcast(plugin.msg("chests-refill"));
                playAll(Sound.CHEST_OPEN, 1f, 1f);
            } else if (refillTimer == 30 || refillTimer == 10) {
                broadcast(plugin.msg("chests-soon", "time", String.valueOf(refillTimer)));
            }
        }
    }

    private void tickEvent() {
        eventTimer--;
        String zone = String.valueOf(eventZone);
        if (!eventActive) {
            if (eventTimer == eventWarning) {
                broadcast(plugin.msg("event-warning", "zone", zone, "time", String.valueOf(eventWarning)));
                playAll(Sound.NOTE_BASS, 1f, 0.7f);
            }
            if (eventTimer <= 0) {
                eventActive = true;
                eventTimer = eventDuration;
                broadcast(plugin.msg("event-start", "zone", zone));
                playAll(Sound.WITHER_SPAWN, 0.6f, 1f);
            }
            return;
        }
        if (eventTimer <= 0) {
            eventActive = false;
            eventTimer = eventInterval;
            pickEventZone();
            broadcast(plugin.msg("event-end", "zone", String.valueOf(eventZone)));
            return;
        }
        // copie : damage() peut tuer le joueur et modifier "alive"
        for (UUID id : new ArrayList<UUID>(alive)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && zones.zoneAt(p.getLocation()) == eventZone) p.damage(eventDamage);
        }
        if (lightningFx && tickCount % 2 == 0) {
            Location l = zones.randomIn(eventZone, world());
            if (l != null) world().strikeLightningEffect(l);
        }
    }

    private void pickEventZone() {
        int count = zones.count();
        if (count <= 1) { eventZone = 1; return; }
        int z;
        do { z = ThreadLocalRandom.current().nextInt(1, count + 1); } while (z == eventZone);
        eventZone = z;
    }

    // ------------------------------------------------------------ cycle de la partie
    /** @return null si lancée, sinon la clé du message d'erreur. */
    public String forceStart() {
        if (!ready()) return "no-arena";
        if (state != GameState.WAITING && state != GameState.STARTING) return "cannot-start";
        if (participants.isEmpty()) return "no-players";
        begin();
        return null;
    }

    public boolean forceStop() {
        if (state == GameState.WAITING && participants.isEmpty()) return false;
        reset();
        return true;
    }

    private void removeArenaItems() {
        for (Item i : world().getEntitiesByClass(Item.class)) {
            if (zones.containsXZ(i.getLocation())) i.remove(); // uniquement dans l'arène (pas le hub)
        }
    }

    private void begin() {
        List<Player> list = players();
        if (list.isEmpty() || !ready()) { state = GameState.WAITING; return; }

        World w = world();
        alive.clear(); kills.clear(); deathLocs.clear();
        removeArenaItems();

        double cx = zones.centerX(), cz = zones.centerZ();
        double r = Math.max(1, Math.min(spawnRadius, Math.min(zones.width(), zones.depth()) / 2.0 - 2));
        int n = list.size();
        // spawns posés par l'admin (/hg setspawn) ; s'il en manque, cercle autour du centre
        List<Location> spawns = arena.spawnLocations();
        Collections.shuffle(spawns);
        for (int i = 0; i < n; i++) {
            Player p = list.get(i);
            Location loc = i < spawns.size() ? spawns.get(i) : null;
            if (loc == null) {
                double a = 2 * Math.PI * i / n;
                double x = cx + r * Math.cos(a);
                double z = cz + r * Math.sin(a);
                loc = new Location(w, x, w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1, z);
                loc.setDirection(new Vector(cx - x, 0, cz - z));
            }

            resetPlayer(p, GameMode.SURVIVAL);
            p.teleport(loc);
            alive.add(p.getUniqueId());
            Packets.title(p, plugin.msg("start-title"),
                    plugin.msg("start-subtitle", "time", fmt(pvpDelay)), 6, 60, 12);
        }

        startedWith = n;
        pvpEnabled = pvpDelay <= 0;
        pvpTimer = pvpDelay;
        eventActive = false;
        eventTimer = eventFirst;
        pickEventZone();
        refillTimer = chests.refillInterval();
        chests.generate();
        state = GameState.RUNNING;
        playAll(Sound.ENDERDRAGON_GROWL, 0.8f, 1.2f);
    }

    private void end(Player winner, UUID winnerId) {
        state = GameState.ENDING;
        timer = endDelay;
        if (winnerId == null) {
            broadcast(plugin.msg("no-winner"));
            return;
        }
        String name = winner != null ? winner.getName() : String.valueOf(Bukkit.getOfflinePlayer(winnerId).getName());
        Integer k = kills.get(winnerId);
        // annonce de la victoire sur tout le serveur
        Bukkit.broadcastMessage(plugin.msg("winner-chat", "winner", name, "kills", String.valueOf(k == null ? 0 : k)));
        String t = plugin.msg("winner-title", "winner", name);
        String s = plugin.msg("winner-subtitle", "winner", name);
        for (Player p : players()) Packets.title(p, t, s, 6, 100, 20);
        playAll(Sound.LEVEL_UP, 1f, 1f);

        if (winner != null) {
            Firework f = winner.getWorld().spawn(winner.getLocation(), Firework.class);
            FireworkMeta m = f.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.RED, Color.YELLOW).withFade(Color.WHITE).build());
            m.setPower(1);
            f.setFireworkMeta(m);
        }
    }

    /** Fin de partie : coffres retirés, joueurs renvoyés là où ils étaient avec leurs affaires. */
    private void reset() {
        state = GameState.WAITING;
        pvpEnabled = false;
        eventActive = false;
        chests.clearQueued();
        removeArenaItems();
        for (Player p : players()) restore(p);
        participants.clear();
        alive.clear();
        kills.clear();
        deathLocs.clear();
    }

    /** Arrêt du plugin : on rend tout aux joueurs. */
    public void shutdown() {
        chests.clearNow();
        for (Player p : players()) restore(p);
        participants.clear();
        alive.clear();
    }

    private void toLobby(Player p) {
        resetPlayer(p, GameMode.ADVENTURE);
        p.teleport(lobby());
    }

    private void resetPlayer(Player p, GameMode gm) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setLevel(0);
        p.setExp(0f);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setGameMode(gm);
    }

    // ------------------------------------------------------------ mort
    public void handleDeath(Player victim, Location loc, String cause, Player killer, ItemStack weapon) {
        if (state != GameState.RUNNING || !alive.remove(victim.getUniqueId())) return;
        deathLocs.put(victim.getUniqueId(), loc.clone());

        // coup de tonnerre pour tous les participants
        loc.getWorld().strikeLightningEffect(loc);
        playAll(Sound.AMBIENCE_THUNDER, 1.5f, 1f);

        String left = String.valueOf(alive.size());
        String msg;
        if (killer != null && !killer.equals(victim)) {
            Integer k = kills.get(killer.getUniqueId());
            kills.put(killer.getUniqueId(), k == null ? 1 : k + 1);
            msg = plugin.msg("kill-player", "victim", victim.getName(), "killer", killer.getName(),
                    "weapon", weaponName(weapon), "left", left);
        } else {
            FileConfiguration c = plugin.getConfig();
            String reason = c.getString("death-reasons." + cause, c.getString("death-reasons.default", "est mort"));
            if (cause.equals("QUIT")) reason = "a quitté la partie";
            msg = plugin.msg("kill-other", "victim", victim.getName(), "reason", reason, "left", left);
        }
        broadcast(msg);

        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() { checkWin(); }
        });
    }

    private void checkWin() {
        if (state != GameState.RUNNING || startedWith < 2 || alive.size() > 1) return;
        if (alive.isEmpty()) { end(null, null); return; }
        UUID id = alive.iterator().next();
        end(Bukkit.getPlayer(id), id);
    }

    private String weaponName(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return "ses poings";
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) return it.getItemMeta().getDisplayName();
        String n = it.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public static String fmt(int s) { s = Math.max(0, s); return String.format("%d:%02d", s / 60, s % 60); }
}
