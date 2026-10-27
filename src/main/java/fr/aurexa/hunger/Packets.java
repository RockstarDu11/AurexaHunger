package fr.aurexa.hunger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Titres et action bar en 1.8.8 (NMS par réflexion, aucune dépendance à compiler). */
public final class Packets {

    private static boolean ok;
    private static Method getHandle, sendPacket, serializer;
    private static Field connField;
    private static Constructor<?> titleCtor, timesCtor, chatCtor;
    private static Object TITLE, SUBTITLE;

    static {
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            String nms = "net.minecraft.server." + v + ".";
            getHandle = Class.forName("org.bukkit.craftbukkit." + v + ".entity.CraftPlayer").getMethod("getHandle");
            connField = Class.forName(nms + "EntityPlayer").getField("playerConnection");
            sendPacket = connField.getType().getMethod("sendPacket", Class.forName(nms + "Packet"));
            Class<?> comp = Class.forName(nms + "IChatBaseComponent");
            serializer = Class.forName(nms + "IChatBaseComponent$ChatSerializer").getMethod("a", String.class);
            Class<?> action = Class.forName(nms + "PacketPlayOutTitle$EnumTitleAction");
            TITLE = action.getField("TITLE").get(null);
            SUBTITLE = action.getField("SUBTITLE").get(null);
            Class<?> pt = Class.forName(nms + "PacketPlayOutTitle");
            titleCtor = pt.getConstructor(action, comp);
            timesCtor = pt.getConstructor(int.class, int.class, int.class);
            chatCtor = Class.forName(nms + "PacketPlayOutChat").getConstructor(comp, byte.class);
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
    }

    private Packets() {}

    private static Object json(String text) throws Exception {
        String esc = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return serializer.invoke(null, "{\"text\":\"" + esc + "\"}");
    }

    private static void send(Player p, Object packet) throws Exception {
        sendPacket.invoke(connField.get(getHandle.invoke(p)), packet);
    }

    public static void title(Player p, String title, String subtitle, int in, int stay, int out) {
        if (!ok) { p.sendMessage(title); p.sendMessage(subtitle); return; }
        try {
            send(p, timesCtor.newInstance(in, stay, out));
            send(p, titleCtor.newInstance(TITLE, json(title)));
            send(p, titleCtor.newInstance(SUBTITLE, json(subtitle)));
        } catch (Throwable t) {
            p.sendMessage(title);
        }
    }

    public static void actionBar(Player p, String msg) {
        if (!ok) return;
        try {
            send(p, chatCtor.newInstance(json(msg), (byte) 2));
        } catch (Throwable ignored) { }
    }
}
