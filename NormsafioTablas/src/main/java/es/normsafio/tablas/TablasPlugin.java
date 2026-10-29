package es.normsafio.tablas;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import java.util.function.Consumer;

public class TablasPlugin extends JavaPlugin implements Listener, TabExecutor {

    static final String TAG = "ns3d_tabla";
    static final float W = 8f;          // ancho del panel en bloques
    static final float ROW_H = 1.1f;    // alto de cada fila

    enum Tipo {
        KILLS("TOP KILLS", Material.RED_CONCRETE, NamedTextColor.RED),
        MUERTES("TOP MUERTES", Material.PURPLE_CONCRETE, NamedTextColor.LIGHT_PURPLE),
        RADIANITAS("TOP RADIANITAS", Material.LIME_CONCRETE, NamedTextColor.GREEN);
        final String titulo; final Material acento; final NamedTextColor color;
        Tipo(String t, Material m, NamedTextColor c) { titulo = t; acento = m; color = c; }
    }

    static final class Stat { String name = "?"; int kills, deaths; }
    static final class Entry { UUID id; String name; int value; }

    static final class Board {
        String id; Tipo tipo; Location loc;
        final List<UUID> uuids = new ArrayList<>();
        TextDisplay[] name, val, rank; ItemDisplay[] head;
        String lastSig = "";
        boolean valid() {
            for (UUID u : uuids) { Entity e = Bukkit.getEntity(u); if (e == null || !e.isValid()) return false; }
            return !uuids.isEmpty() && name != null;
        }
    }

    private final Map<String, Board> boards = new LinkedHashMap<>();
    private final Map<UUID, Stat> cache = new HashMap<>();
    private File boardsFile;
    private int rows;
    private Statistic killStat;
    private float headScale, headYaw, textScale;

    // reflexion hacia el plugin Normsafio (economia de Radianitas)
    private Method ecoGetter, balanceMethod;

    @Override public void onEnable() {
        saveDefaultConfig();
        boardsFile = new File(getDataFolder(), "boards.yml");
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand c = getCommand("tablas");
        if (c != null) { c.setExecutor(this); c.setTabCompleter(this); }
        readConfig();
        loadBoards();
        // primera construccion un poco despues para que los mundos esten listos
        Bukkit.getScheduler().runTaskLater(this, () -> { seedCache(); rebuildAll(); }, 40L);
        long period = Math.max(2, getConfig().getInt("update-seconds", 10)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 100L, period);
    }

    private void readConfig() {
        reloadConfig();
        rows = Math.max(1, Math.min(10, getConfig().getInt("rows", 5)));
        try { killStat = Statistic.valueOf(getConfig().getString("kills-statistic", "PLAYER_KILLS").toUpperCase()); }
        catch (Exception e) { killStat = Statistic.PLAYER_KILLS; }
        headScale = (float) getConfig().getDouble("head-scale", 1.0);
        headYaw = (float) getConfig().getDouble("head-yaw", 0);
        textScale = (float) getConfig().getDouble("text-scale", 1.6);
    }

    // ───────────── datos ─────────────
    private static int find(String json, String key) {
        Matcher m = Pattern.compile("\"minecraft:" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // Lee las estadisticas guardadas de todos los jugadores (online y offline) desde world/stats/<uuid>.json
    private void seedCache() {
        File dir = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "stats");
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (!op.hasPlayedBefore() && !op.isOnline()) continue;
            Stat s = cache.computeIfAbsent(op.getUniqueId(), k -> new Stat());
            if (op.getName() != null) s.name = op.getName();
            File f = new File(dir, op.getUniqueId() + ".json");
            if (!f.isFile()) continue;
            try {
                String json = Files.readString(f.toPath());
                s.kills = find(json, killStat.name().toLowerCase());
                s.deaths = find(json, "deaths");
            } catch (IOException ignored) { }
        }
    }

    private void refreshOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Stat s = cache.computeIfAbsent(p.getUniqueId(), k -> new Stat());
            s.name = p.getName();
            s.kills = p.getStatistic(killStat);
            s.deaths = p.getStatistic(Statistic.DEATHS);
        }
    }

    private int radianitas(UUID id) {
        try {
            if (balanceMethod == null) {
                Plugin p = getServer().getPluginManager().getPlugin("Normsafio");
                if (p == null) return 0;
                ecoGetter = p.getClass().getMethod("economy");
                Object eco = ecoGetter.invoke(p);
                balanceMethod = eco.getClass().getMethod("balance", UUID.class);
            }
            Plugin p = getServer().getPluginManager().getPlugin("Normsafio");
            Object eco = ecoGetter.invoke(p);
            return (Integer) balanceMethod.invoke(eco, id);
        } catch (Throwable t) {
            balanceMethod = null;
            return 0;
        }
    }

    private List<Entry> top(Tipo t) {
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<UUID, Stat> me : cache.entrySet()) {
            Entry e = new Entry();
            e.id = me.getKey(); e.name = me.getValue().name;
            e.value = switch (t) {
                case KILLS -> me.getValue().kills;
                case MUERTES -> me.getValue().deaths;
                case RADIANITAS -> radianitas(me.getKey());
            };
            if (e.value > 0) list.add(e);
        }
        list.sort((a, b) -> Integer.compare(b.value, a.value));
        return list.size() > rows ? list.subList(0, rows) : list;
    }

    // ───────────── construccion 3D ─────────────
    private static Transformation tf(float x, float y, float z, float sx, float sy, float sz) {
        return new Transformation(new Vector3f(x, y, z), new AxisAngle4f(), new Vector3f(sx, sy, sz), new AxisAngle4f());
    }

    private <T extends Display> T spawn(Board b, Class<T> cls, Consumer<T> init) {
        T e = b.loc.getWorld().spawn(b.loc, cls, d -> {
            d.addScoreboardTag(TAG);
            d.addScoreboardTag(TAG + "_" + b.id);
            d.setPersistent(true);
            d.setViewRange(4f);
            d.setDisplayWidth(12f);
            d.setDisplayHeight(12f);
            d.setBrightness(new Display.Brightness(15, 15));
            init.accept(d);
        });
        b.uuids.add(e.getUniqueId());
        return e;
    }

    private void block(Board b, Material m, float x, float y, float z, float sx, float sy, float sz) {
        spawn(b, BlockDisplay.class, d -> { d.setBlock(m.createBlockData()); d.setTransformation(tf(x, y, z, sx, sy, sz)); });
    }

    private TextDisplay text(Board b, Component c, float x, float yCenter, float scale) {
        float y = yCenter - 0.125f * scale; // el texto crece hacia arriba desde su origen
        return spawn(b, TextDisplay.class, d -> {
            d.text(c);
            d.setBillboard(Display.Billboard.FIXED);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setLineWidth(2000);
            d.setShadowed(true);
            d.setTransformation(tf(x, y, 0.03f, scale, scale, scale));
        });
    }

    private void removeOld(Board b) {
        for (UUID u : b.uuids) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        b.uuids.clear();
        if (b.loc.getWorld() == null) return;
        b.loc.getChunk().load();
        for (Entity e : b.loc.getWorld().getNearbyEntities(b.loc, 14, 20, 14))
            if (e instanceof Display && e.getScoreboardTags().contains(TAG + "_" + b.id)) e.remove();
    }

    private void build(Board b) {
        if (b.loc.getWorld() == null) return;
        removeOld(b);
        float H = 1.9f + rows * ROW_H;
        Tipo t = b.tipo;
        // marco (acento) + panel oscuro + barra separadora
        block(b, t.acento, -(W + 0.5f) / 2, -0.25f, -0.36f, W + 0.5f, H + 0.5f, 0.30f);
        block(b, Material.BLACK_CONCRETE, -W / 2, 0f, -0.30f, W, H, 0.30f);
        block(b, t.acento, -W / 2 + 0.2f, H - 1.8f, 0.0f, W - 0.4f, 0.07f, 0.02f);
        // patas
        block(b, Material.GRAY_CONCRETE, -W / 2 + 0.6f, -1.2f, -0.30f, 0.4f, 1.0f, 0.3f);
        block(b, Material.GRAY_CONCRETE, W / 2 - 1.0f, -1.2f, -0.30f, 0.4f, 1.0f, 0.3f);
        // titulo
        text(b, Component.text(t.titulo, t.color).decorate(TextDecoration.BOLD), 0f, H - 0.9f, 3.2f);

        b.rank = new TextDisplay[rows]; b.name = new TextDisplay[rows];
        b.val = new TextDisplay[rows]; b.head = new ItemDisplay[rows];
        for (int i = 0; i < rows; i++) {
            float yc = H - 1.8f - ROW_H / 2 - i * ROW_H;
            b.rank[i] = text(b, rankComp(i), -3.5f, yc, textScale);
            final float hs = headScale, hy = headYaw;
            b.head[i] = spawn(b, ItemDisplay.class, d -> {
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setTransformation(new Transformation(new Vector3f(-2.7f, yc, 0.25f), new AxisAngle4f((float) Math.toRadians(hy), 0, 1, 0), new Vector3f(hs, hs, hs), new AxisAngle4f()));
            });
            b.name[i] = text(b, Component.text("-", NamedTextColor.DARK_GRAY), -0.3f, yc, textScale);
            b.val[i] = text(b, Component.text("", t.color), 2.9f, yc, textScale);
        }
        b.lastSig = "";
    }

    private Component rankComp(int i) {
        TextColor c = i == 0 ? NamedTextColor.GOLD : i == 1 ? NamedTextColor.WHITE : i == 2 ? TextColor.color(0xCD7F32) : NamedTextColor.GRAY;
        return Component.text("#" + (i + 1), c).decorate(TextDecoration.BOLD);
    }

    private void render(Board b, List<Entry> top) {
        StringBuilder sig = new StringBuilder();
        for (Entry e : top) sig.append(e.id).append(':').append(e.value).append(';');
        if (sig.toString().equals(b.lastSig)) return;
        b.lastSig = sig.toString();
        for (int i = 0; i < rows; i++) {
            if (i < top.size()) {
                Entry e = top.get(i);
                ItemStack it = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta m = (SkullMeta) it.getItemMeta();
                m.setOwningPlayer(Bukkit.getOfflinePlayer(e.id));
                it.setItemMeta(m);
                b.head[i].setItemStack(it);
                b.name[i].text(Component.text(e.name, NamedTextColor.WHITE));
                b.val[i].text(Component.text(String.format("%,d", e.value), b.tipo.color).decorate(TextDecoration.BOLD));
            } else {
                b.head[i].setItemStack(new ItemStack(Material.AIR));
                b.name[i].text(Component.text("-", NamedTextColor.DARK_GRAY));
                b.val[i].text(Component.empty());
            }
        }
    }

    // ───────────── ciclo ─────────────
    private void rebuildAll() {
        for (Board b : boards.values()) { build(b); }
        tick();
    }

    private void tick() {
        refreshOnline();
        Map<Tipo, List<Entry>> tops = new EnumMap<>(Tipo.class);
        for (Board b : boards.values()) {
            World w = b.loc.getWorld();
            if (w == null || !w.isChunkLoaded(b.loc.getBlockX() >> 4, b.loc.getBlockZ() >> 4)) continue;
            if (!b.valid()) build(b);
            render(b, tops.computeIfAbsent(b.tipo, this::top));
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Stat s = cache.computeIfAbsent(e.getPlayer().getUniqueId(), k -> new Stat());
        s.name = e.getPlayer().getName();
    }

    // ───────────── persistencia ─────────────
    private void loadBoards() {
        boards.clear();
        if (!boardsFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(boardsFile);
        ConfigurationSection sec = y.getConfigurationSection("boards");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) continue;
            Board b = new Board();
            b.id = id;
            try { b.tipo = Tipo.valueOf(s.getString("tipo", "KILLS")); } catch (Exception ex) { continue; }
            b.loc = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), 0f);
            boards.put(id, b);
        }
    }

    private void saveBoards() {
        YamlConfiguration y = new YamlConfiguration();
        for (Board b : boards.values()) {
            String p = "boards." + b.id + ".";
            y.set(p + "tipo", b.tipo.name());
            y.set(p + "world", b.loc.getWorld().getName());
            y.set(p + "x", b.loc.getX()); y.set(p + "y", b.loc.getY()); y.set(p + "z", b.loc.getZ());
            y.set(p + "yaw", b.loc.getYaw());
        }
        try { getDataFolder().mkdirs(); y.save(boardsFile); } catch (IOException e) { getLogger().warning("No se pudo guardar boards.yml: " + e.getMessage()); }
    }

    // ───────────── comandos ─────────────
    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("normsafio.tablas.admin")) { s.sendMessage(Component.text("Sin permiso.", NamedTextColor.RED)); return true; }
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "crear" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Solo jugadores."); return true; }
                if (a.length < 2) { s.sendMessage(Component.text("Uso: /tablas crear <kills|muertes|radianitas> [id]", NamedTextColor.YELLOW)); return true; }
                Tipo t;
                try { t = Tipo.valueOf(a[1].toUpperCase()); } catch (Exception e) { s.sendMessage(Component.text("Tipo invalido: kills, muertes o radianitas.", NamedTextColor.RED)); return true; }
                String id = a.length > 2 ? a[2].toLowerCase() : t.name().toLowerCase() + (boards.size() + 1);
                if (boards.containsKey(id)) { s.sendMessage(Component.text("Ya existe una tabla con id " + id, NamedTextColor.RED)); return true; }
                // 3 bloques delante del jugador, mirando hacia el jugador (snap a 90 grados)
                Vector dir = p.getLocation().getDirection().setY(0);
                if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
                dir.normalize();
                Location l = p.getLocation().clone().add(dir.multiply(3));
                l.setX(l.getBlockX() + 0.5); l.setZ(l.getBlockZ() + 0.5);
                float yaw = Math.round((p.getLocation().getYaw() + 180f) / 90f) * 90f;
                l.setYaw(yaw); l.setPitch(0);
                Board b = new Board(); b.id = id; b.tipo = t; b.loc = l;
                boards.put(id, b);
                saveBoards();
                build(b);
                tick();
                s.sendMessage(Component.text("Tabla '" + id + "' creada.", NamedTextColor.GREEN));
            }
            case "borrar" -> {
                Board b = null;
                if (a.length > 1) b = boards.get(a[1].toLowerCase());
                else if (s instanceof Player p) {
                    double best = 15 * 15;
                    for (Board x : boards.values())
                        if (x.loc.getWorld() == p.getWorld() && x.loc.distanceSquared(p.getLocation()) < best) { best = x.loc.distanceSquared(p.getLocation()); b = x; }
                }
                if (b == null) { s.sendMessage(Component.text("No encuentro esa tabla (usa /tablas borrar <id> o acercate).", NamedTextColor.RED)); return true; }
                removeOld(b);
                boards.remove(b.id);
                saveBoards();
                s.sendMessage(Component.text("Tabla '" + b.id + "' borrada.", NamedTextColor.GREEN));
            }
            case "lista" -> {
                if (boards.isEmpty()) s.sendMessage(Component.text("No hay tablas.", NamedTextColor.GRAY));
                for (Board b : boards.values())
                    s.sendMessage(Component.text(b.id + " - " + b.tipo + " (" + b.loc.getWorld().getName() + " " + b.loc.getBlockX() + " " + b.loc.getBlockY() + " " + b.loc.getBlockZ() + ")", NamedTextColor.YELLOW));
            }
            case "recargar" -> { readConfig(); seedCache(); rebuildAll(); s.sendMessage(Component.text("Tablas recargadas.", NamedTextColor.GREEN)); }
            case "actualizar" -> { for (Board b : boards.values()) b.lastSig = ""; tick(); s.sendMessage(Component.text("Actualizadas.", NamedTextColor.GREEN)); }
            default -> s.sendMessage(Component.text("/tablas crear <kills|muertes|radianitas> [id] | borrar [id] | lista | recargar | actualizar", NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("crear", "borrar", "lista", "recargar", "actualizar"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("crear")) out.addAll(List.of("kills", "muertes", "radianitas"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("borrar")) out.addAll(boards.keySet());
        out.removeIf(x -> !x.startsWith(a[a.length - 1].toLowerCase()));
        return out;
    }
}
