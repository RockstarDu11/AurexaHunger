package fr.aurexa.hunger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Arène = région sélectionnée en jeu (data.yml). Les zones numérotées (1..grid²) découpent
 * cette région en grille. Tous les calculs sont O(1).
 */
public final class ZoneManager {

    private final String worldName;
    private final boolean hasBounds, enforceHeight;
    private final int minX, minY, minZ, maxX, maxY, maxZ, grid;
    private final double zw, zd;
    private final Set<Integer> passive = new HashSet<Integer>();

    public ZoneManager(FileConfiguration c, Arena a) {
        worldName = a.world();
        hasBounds = a.hasRegion() && worldName != null;
        minX = a.minX(); minY = a.minY(); minZ = a.minZ();
        maxX = a.maxX(); maxY = a.maxY(); maxZ = a.maxZ();
        grid = Math.max(1, c.getInt("arena.grid", 4));
        enforceHeight = c.getBoolean("arena.enforce-height", false);
        zw = (maxX - minX + 1.0) / grid;
        zd = (maxZ - minZ + 1.0) / grid;
        passive.addAll(c.getIntegerList("zones.passive"));
    }

    public boolean valid() { return hasBounds && Bukkit.getWorld(worldName) != null; }
    public World world() { return worldName == null ? null : Bukkit.getWorld(worldName); }
    public int count() { return grid * grid; }
    public int width() { return maxX - minX + 1; }
    public int depth() { return maxZ - minZ + 1; }
    public double centerX() { return (minX + maxX + 1) / 2.0; }
    public double centerZ() { return (minZ + maxZ + 1) / 2.0; }
    public boolean isPassive(int zone) { return passive.contains(zone); }

    /** Dans l'arène (monde + X/Z, et hauteur si enforce-height). */
    public boolean contains(Location l) {
        if (!containsXZ(l)) return false;
        return !enforceHeight || (l.getBlockY() >= minY && l.getBlockY() <= maxY);
    }

    public boolean containsXZ(Location l) {
        if (!hasBounds || l.getWorld() == null || !l.getWorld().getName().equals(worldName)) return false;
        int x = l.getBlockX(), z = l.getBlockZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public int zoneAt(Location l) {
        int col = clamp((int) Math.floor((l.getX() - minX) / zw));
        int row = clamp((int) Math.floor((l.getZ() - minZ) / zd));
        return row * grid + col + 1;
    }

    /** Point aléatoire dans une zone (null si chunk non chargé, pour éviter tout lag). */
    public Location randomIn(int zone, World w) {
        int idx = zone - 1;
        int row = idx / grid, col = idx % grid;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double x = minX + col * zw + r.nextDouble() * zw;
        double z = minZ + row * zd + r.nextDouble() * zd;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        return new Location(w, x, w.getHighestBlockYAt(bx, bz) + 1, z);
    }

    /** Bloc (x, z) aléatoire dans une zone, avec une petite marge par rapport aux bords. */
    public int[] randomBlock(int zone) {
        int idx = zone - 1;
        int row = idx / grid, col = idx % grid;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double m = Math.min(3, Math.min(zw, zd) / 4);
        double x = minX + col * zw + m + r.nextDouble() * (zw - 2 * m);
        double z = minZ + row * zd + m + r.nextDouble() * (zd - 2 * m);
        return new int[]{(int) Math.floor(x), (int) Math.floor(z)};
    }

    /** Bloc (x, z) aléatoire autour du centre de l'arène (cornucopia). */
    public int[] randomAroundCenter(int radius) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double a = r.nextDouble() * 2 * Math.PI;
        double d = Math.sqrt(r.nextDouble()) * radius;
        return new int[]{(int) Math.floor(centerX() + Math.cos(a) * d), (int) Math.floor(centerZ() + Math.sin(a) * d)};
    }

    private int clamp(int v) { return Math.max(0, Math.min(grid - 1, v)); }
}
