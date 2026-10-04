package com.example.dutyradio;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DutyRadioPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** وضعیت هر بازیکن (روی ترد اصلی ساخته میشه، روی ترد صوتی فقط خونده میشه) */
    public static final class State {
        public final int tx;               // فرکانس ارسال (0 = ارسال نمیکنه)
        public final Set<Integer> rx;      // فرکانس‌هایی که گوش میده
        public final UUID world;
        public final double x, y, z;

        State(int tx, Set<Integer> rx, UUID world, double x, double y, double z) {
            this.tx = tx;
            this.rx = rx;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Set<UUID> micOn = ConcurrentHashMap.newKeySet();

    private NamespacedKey freqKey;
    private boolean toggleMode;
    private double nearbySkip;
    private boolean transmitWhispers;
    private Material radioMaterial;
    private int customModelData;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        freqKey = new NamespacedKey(this, "radio_freq");

        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Simple Voice Chat پیدا نشد! پلاگین غیرفعال شد.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        service.registerPlugin(new RadioVoicePlugin(this));

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("dutyradio");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::updateStates, 20L, 4L);
        getLogger().info("DutyRadio فعال شد.");
    }

    private void loadSettings() {
        reloadConfig();
        toggleMode = "toggle".equalsIgnoreCase(getConfig().getString("mode", "hold"));
        nearbySkip = getConfig().getDouble("nearby-skip-distance", 10);
        transmitWhispers = getConfig().getBoolean("transmit-whispers", false);
        radioMaterial = Material.matchMaterial(getConfig().getString("item.material", "IRON_NUGGET"));
        if (radioMaterial == null) radioMaterial = Material.IRON_NUGGET;
        customModelData = getConfig().getInt("item.custom-model-data", 0);
    }

    // ---------- API برای کلاس صوتی ----------
    public Map<UUID, State> getStates() { return states; }
    public double getNearbySkip() { return nearbySkip; }
    public boolean isTransmitWhispers() { return transmitWhispers; }

    // ---------- کش وضعیت ----------
    private void updateStates() {
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            updateState(p);
            online.add(p.getUniqueId());
        }
        states.keySet().retainAll(online);
    }

    private void updateState(Player p) {
        UUID id = p.getUniqueId();
        if (!p.hasPermission("dutyradio.use")) {
            states.remove(id);
            return;
        }

        int main = freqOf(p.getInventory().getItemInMainHand());
        int first = 0;
        Set<Integer> rx = new HashSet<>();
        for (ItemStack it : p.getInventory().getContents()) {
            int f = freqOf(it);
            if (f > 0) {
                rx.add(f);
                if (first == 0) first = f;
            }
        }

        int tx = 0;
        if (toggleMode) {
            if (micOn.contains(id)) tx = main > 0 ? main : first;
        } else {
            tx = main;
        }

        Location l = p.getLocation();
        states.put(id, new State(tx, rx, l.getWorld().getUID(), l.getX(), l.getY(), l.getZ()));
    }

    // ---------- آیتم رادیو ----------
    private int freqOf(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return 0;
        Integer f = item.getItemMeta().getPersistentDataContainer().get(freqKey, PersistentDataType.INTEGER);
        return f == null ? 0 : f;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages." + key, key));
    }

    private void applyFreq(ItemMeta meta, int freq) {
        meta.getPersistentDataContainer().set(freqKey, PersistentDataType.INTEGER, freq);
        String name = getConfig().getString("frequencies." + freq, "&fرادیو (فرکانس " + freq + ")");
        meta.setDisplayName(color(name));
        List<String> lore = new ArrayList<>();
        lore.add(msg("lore-freq").replace("%freq%", String.valueOf(freq)));
        meta.setLore(lore);
        if (customModelData > 0) meta.setCustomModelData(customModelData);
    }

    private ItemStack createRadio(int freq) {
        ItemStack item = new ItemStack(radioMaterial);
        ItemMeta meta = item.getItemMeta();
        applyFreq(meta, freq);
        item.setItemMeta(meta);
        return item;
    }

    private void actionBar(Player p, String text) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
    }

    // ---------- راست‌کلیک (حالت toggle) ----------
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!toggleMode) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        int freq = freqOf(e.getItem());
        if (freq <= 0) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("dutyradio.use")) return;
        e.setCancelled(true);

        UUID id = p.getUniqueId();
        if (micOn.remove(id)) {
            actionBar(p, msg("mic-off"));
        } else {
            micOn.add(id);
            actionBar(p, msg("mic-on").replace("%freq%", String.valueOf(freq)));
        }
        updateState(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        micOn.remove(e.getPlayer().getUniqueId());
        states.remove(e.getPlayer().getUniqueId());
    }

    // ---------- دستورات ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("dutyradio.admin")) {
            sender.sendMessage(msg("no-perm"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(msg("usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give": {
                if (args.length < 3) { sender.sendMessage(msg("usage")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(color("&cبازیکن آنلاین نیست")); return true; }
                int freq = parseInt(args[2]);
                if (freq <= 0) { sender.sendMessage(msg("usage")); return true; }
                int amount = args.length >= 4 ? Math.max(1, Math.min(64, parseInt(args[3]))) : 1;
                ItemStack radio = createRadio(freq);
                radio.setAmount(amount);
                target.getInventory().addItem(radio).values()
                        .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                sender.sendMessage(msg("given").replace("%freq%", String.valueOf(freq)).replace("%player%", target.getName()));
                return true;
            }
            case "set": {
                if (!(sender instanceof Player)) { sender.sendMessage("Only players."); return true; }
                if (args.length < 2) { sender.sendMessage(msg("usage")); return true; }
                Player p = (Player) sender;
                int freq = parseInt(args[1]);
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (freq <= 0 || freqOf(hand) <= 0) { sender.sendMessage(msg("need-radio")); return true; }
                ItemMeta meta = hand.getItemMeta();
                applyFreq(meta, freq);
                hand.setItemMeta(meta);
                updateState(p);
                sender.sendMessage(msg("set").replace("%freq%", String.valueOf(freq)));
                return true;
            }
            case "reload": {
                loadSettings();
                sender.sendMessage(msg("reloaded"));
                return true;
            }
            default:
                sender.sendMessage(msg("usage"));
                return true;
        }
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return -1; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("dutyradio.admin")) return Collections.emptyList();
        if (args.length == 1) return Arrays.asList("give", "set", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        return Collections.emptyList();
    }
}
