package fr.aurexa.hunger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class GameListener implements Listener {

    private final AurexaHunger plugin;
    private final ArenaManager arenas;
    private final SignManager signs;
    private final Map<UUID, Long> lastWarn = new HashMap<UUID, Long>();

    public GameListener(AurexaHunger plugin, ArenaManager arenas, SignManager signs) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.signs = signs;
    }

    // ------------------------------------------------------------ connexion
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Game g = arenas.gameOf(p);
        if (g != null) g.leave(p); // rend l'inventaire / la position d'origine
        lastWarn.remove(p.getUniqueId());
        plugin.clearSelection(p);
    }

    // ------------------------------------------------------------ sélection d'arène + panneaux
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        Player p = e.getPlayer();

        if (a == Action.LEFT_CLICK_BLOCK || a == Action.RIGHT_CLICK_BLOCK) {
            ItemStack it = e.getItem();
            if (it != null && it.getType() == Material.WOOD_AXE && it.hasItemMeta()
                    && AurexaHunger.WAND_NAME.equals(it.getItemMeta().getDisplayName())
                    && p.hasPermission(AurexaHunger.PERM)) {
                e.setCancelled(true);
                plugin.setPos(p, a == Action.LEFT_CLICK_BLOCK ? 1 : 2, e.getClickedBlock().getLocation());
                return;
            }
        }
        if (a != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();

        // /hg sign <nom> ou /hg leavesign : le prochain clic droit sur un panneau l'enregistre
        if (plugin.pendingSign(p) != null && b.getState() instanceof Sign) {
            e.setCancelled(true);
            plugin.completeSign(p, b);
            return;
        }

        String arena = signs.joinArena(b);
        if (arena != null) {
            e.setCancelled(true);
            Game g = arenas.get(arena);
            if (g == null) p.sendMessage(plugin.msg("no-arena"));
            else g.tryJoin(p);
        } else if (signs.isLeave(b)) {
            e.setCancelled(true);
            Game g = arenas.gameOf(p);
            if (g != null && g.leave(p)) p.sendMessage(plugin.msg("left"));
            else p.sendMessage(plugin.msg("not-in-game"));
        }
    }

    // ------------------------------------------------------------ limite de l'arène
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return; // simple rotation de la tête

        Player p = e.getPlayer();
        Game g = arenas.gameOf(p);
        if (g == null || g.state() != GameState.RUNNING || !g.isAlive(p) || g.zones().contains(to)) return;

        if (g.zones().contains(from)) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        } else {
            p.teleport(g.arenaCenter()); // déjà dehors (ex : téléporté) : retour au centre
        }
        warnOutside(p);
    }

    /** Empêche de sortir via perle d'Ender ou téléportation. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Game g = arenas.gameOf(p);
        if (g == null || g.state() != GameState.RUNNING || !g.isAlive(p)) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        if (!g.zones().contains(e.getTo())) {
            e.setCancelled(true);
            warnOutside(p);
        }
    }

    private void warnOutside(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(p.getUniqueId());
        if (last == null || now - last > 1500) {
            lastWarn.put(p.getUniqueId(), now);
            Packets.actionBar(p, plugin.msg("arena-outside"));
        }
    }

    // ------------------------------------------------------------ combat
    /** PvP : bloqué avant l'activation, dans les zones passives et entre arènes différentes. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();

        Player attacker = null;
        if (e.getDamager() instanceof Player) {
            attacker = (Player) e.getDamager();
        } else if (e.getDamager() instanceof Projectile) {
            Object shooter = ((Projectile) e.getDamager()).getShooter();
            if (shooter instanceof Player) attacker = (Player) shooter;
        }
        if (attacker == null || attacker.equals(victim)) return;

        Game gv = arenas.gameOf(victim), ga = arenas.gameOf(attacker);
        if (gv == null && ga == null) return;
        Game g = gv != null ? gv : ga;

        if (gv != ga || !g.canPvp(attacker, victim)) {
            e.setCancelled(true);
            Packets.actionBar(attacker, plugin.msg(g.pvpEnabled() ? "passive-zone" : "pvp-disabled"));
        }
    }

    /** Aucun dégât pour un participant hors partie (lobby, fin de partie). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        Game g = arenas.gameOf(p);
        if (g == null) return;
        if (g.state() != GameState.RUNNING || !g.isAlive(p)) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID && g.state() != GameState.RUNNING) {
                p.teleport(g.lobby());
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        final Player v = e.getEntity();
        Game g = arenas.gameOf(v);
        if (g == null || g.state() != GameState.RUNNING || !g.isAlive(v)) return;

        e.setDeathMessage(null);
        Player killer = v.getKiller();
        EntityDamageEvent last = v.getLastDamageCause();
        String cause = last == null ? "default" : last.getCause().name();
        g.handleDeath(v, v.getLocation(), cause, killer,
                killer != null ? killer.getInventory().getItemInHand() : null);

        // respawn instantané (pas d'écran de mort)
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (v.isOnline() && v.isDead()) v.spigot().respawn();
            }
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        final Player p = e.getPlayer();
        Game g = arenas.gameOf(p);
        if (g == null) return;
        Location death = g.pollDeathLoc(p);
        if (death != null) {
            e.setRespawnLocation(death.add(0, 1, 0));
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override public void run() { p.setGameMode(GameMode.SPECTATOR); }
            });
        }
    }

    // ------------------------------------------------------------ protections
    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Game g = arenas.gameOf((Player) e.getEntity());
        if (g != null && g.state() != GameState.RUNNING) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();

        // panneaux du plugin : seuls les admins peuvent les casser
        if (signs.isSign(b)) {
            if (p.hasPermission(AurexaHunger.PERM)) {
                signs.remove(b);
                p.sendMessage(plugin.msg("sign-removed"));
            } else {
                e.setCancelled(true);
            }
            return;
        }
        if (p.getGameMode() == GameMode.CREATIVE && p.hasPermission(AurexaHunger.PERM)) return;

        // coffres d'arène : jamais cassables ; lobby / fin de partie : rien n'est cassable
        if (arenas.isChest(b)) {
            e.setCancelled(true);
        } else {
            Game g = arenas.gameOf(p);
            if (g != null && g.state() != GameState.RUNNING) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Game g = arenas.gameOf(p);
        if (g != null && g.state() != GameState.RUNNING
                && !(p.getGameMode() == GameMode.CREATIVE && p.hasPermission(AurexaHunger.PERM))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (arenas.isChest(b) || signs.isSign(b)) it.remove();
        }
    }
}
