package fr.aurexa.hunger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/** Données d'une arène nommée : région, lobby, spawns, joueurs min/max (sauvegardées dans arenas.yml). */
public final class Arena {

    /** Position sérialisable (le monde peut ne pas être encore chargé au démarrage). */
    public static final class Loc {
        private final String world;
        private final double x, y, z;
        private final float yaw, pitch;

        public Loc(Location l) {
            this(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }

        public Loc(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }

        public Location toBukkit() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }

        public String ser() { return world + ";" + x + ";" + y + ";" + z + ";" + yaw + ";" + pitch; }

        public static Loc des(String s) {
            try {
                String[] p = s.split(";");
                return new Loc(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Float.parseFloat(p[4]), Float.parseFloat(p[5]));
            } catch (Exception e) {
                return null;
            }
        }

        @Override public String toString() {
            return (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z);
        }
    }

    private final String name;
    private String world;
    private boolean hasRegion;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private Loc lobby;
    private final List<Loc> spawns = new ArrayList<Loc>();
    private int minPlayers, maxPlayers;

    public Arena(String name, int minPlayers, int maxPlayers) {
        this.name = name;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
    }

    public String name() { return name; }
    public String world() { return world; }
    public boolean hasRegion() { return hasRegion; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }
    public int minPlayers() { return minPlayers; }
    public int maxPlayers() { return maxPlayers; }
    public boolean hasLobby() { return lobby != null; }
    public Loc lobby() { return lobby; }
    public List<Loc> spawns() { return spawns; }

    public void setPlayers(int min, int max) { this.minPlayers = min; this.maxPlayers = max; }
    public void setLobby(Loc l) { this.lobby = l; }
    public void addSpawn(Loc l) { spawns.add(l); }
    public void clearSpawns() { spawns.clear(); }

    public void setRegion(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.world = world;
        minX = Math.min(x1, x2); maxX = Math.max(x1, x2);
        minY = Math.min(y1, y2); maxY = Math.max(y1, y2);
        minZ = Math.min(z1, z2); maxZ = Math.max(z1, z2);
        hasRegion = true;
    }

    public Location lobbyLocation() { return lobby == null ? null : lobby.toBukkit(); }

    public List<Location> spawnLocations() {
        List<Location> l = new ArrayList<Location>();
        for (Loc s : spawns) {
            Location b = s.toBukkit();
            if (b != null) l.add(b);
        }
        return l;
    }

    public void save(ConfigurationSection s) {
        s.set("min-players", minPlayers);
        s.set("max-players", maxPlayers);
        if (hasRegion) {
            s.set("region.world", world);
            s.set("region.min", minX + "," + minY + "," + minZ);
            s.set("region.max", maxX + "," + maxY + "," + maxZ);
        }
        if (lobby != null) s.set("lobby", lobby.ser());
        List<String> sp = new ArrayList<String>();
        for (Loc l : spawns) sp.add(l.ser());
        s.set("spawns", sp);
    }

    public static Arena load(String name, ConfigurationSection s, int defMin, int defMax) {
        Arena a = new Arena(name, s.getInt("min-players", defMin), s.getInt("max-players", defMax));
        if (s.isSet("region.world")) {
            try {
                String[] mn = s.getString("region.min").split(",");
                String[] mx = s.getString("region.max").split(",");
                a.setRegion(s.getString("region.world"),
                        Integer.parseInt(mn[0].trim()), Integer.parseInt(mn[1].trim()), Integer.parseInt(mn[2].trim()),
                        Integer.parseInt(mx[0].trim()), Integer.parseInt(mx[1].trim()), Integer.parseInt(mx[2].trim()));
            } catch (Exception ignored) { }
        }
        if (s.isSet("lobby")) a.lobby = Loc.des(s.getString("lobby"));
        for (String str : s.getStringList("spawns")) {
            Loc l = Loc.des(str);
            if (l != null) a.spawns.add(l);
        }
        return a;
    }
}
