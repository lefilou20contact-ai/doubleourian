package fr.doubleourien;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DoubleOuRienPlugin extends JavaPlugin implements Listener, TabExecutor {

    private ValueManager values;
    private DoubleOuRienGUI gui;
    private NamespacedKey itemKey;

    public static Component text(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "values.yml").exists()) saveResource("values.yml", false);

        itemKey = new NamespacedKey(this, "doubleourien_item");
        values = new ValueManager(this);
        gui = new DoubleOuRienGUI(this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(gui, this);

        PluginCommand cmd = getCommand("dor");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Recette : 4 lingots d'or + 4 diamants + 1 enclume (sans forme)
        ShapelessRecipe recipe = new ShapelessRecipe(new NamespacedKey(this, "machine"), createMachineItem());
        recipe.addIngredient(4, Material.GOLD_INGOT);
        recipe.addIngredient(4, Material.DIAMOND);
        recipe.addIngredient(Material.ANVIL);
        Bukkit.addRecipe(recipe);

        // Pré-calcule la liste des cibles une fois le serveur démarré
        getServer().getScheduler().runTask(this, () -> values.allTargets());
    }

    @Override
    public void onDisable() {
        if (gui != null) gui.deliverAllPending();
    }

    public ValueManager values() {
        return values;
    }

    public ItemStack createMachineItem() {
        ItemStack item = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text("&6&lDouble ou Rien"));
        meta.lore(List.of(text("&7Clic droit pour tenter ta chance."), text("&7Quitte ou double !")));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        if (!it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        gui.open(e.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            if (s instanceof Player p) gui.open(p);
            else s.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!admin(s)) return true;
                reloadConfig();
                values.reload();
                s.sendMessage(text("&aConfiguration et prix rechargés."));
            }
            case "value" -> {
                if (a.length < 2) { s.sendMessage(text("&cUsage : /dor value <ITEM>")); return true; }
                Material m = Material.matchMaterial(a[1]);
                if (m == null) { s.sendMessage(text("&cObjet inconnu.")); return true; }
                double v = values.getValue(m);
                s.sendMessage(text(v > 0 ? "&7Valeur de &f" + m + " &7: &e" + DoubleOuRienGUI.fmt(v)
                        : "&c" + m + " n'a pas de valeur (interdit)."));
            }
            case "setvalue" -> {
                if (!admin(s)) return true;
                if (a.length < 3) { s.sendMessage(text("&cUsage : /dor setvalue <ITEM> <valeur>")); return true; }
                Material m = Material.matchMaterial(a[1]);
                if (m == null) { s.sendMessage(text("&cObjet inconnu.")); return true; }
                double v;
                try { v = Double.parseDouble(a[2].replace(',', '.')); }
                catch (NumberFormatException ex) { s.sendMessage(text("&cNombre invalide.")); return true; }
                values.setValue(m, v);
                s.sendMessage(text("&aValeur de &f" + m + " &adéfinie à &e" + DoubleOuRienGUI.fmt(v)));
            }
            case "give" -> {
                if (!admin(s)) return true;
                if (s instanceof Player p) p.getInventory().addItem(createMachineItem());
            }
            default -> s.sendMessage(text("&cUsage : /dor [reload|value|setvalue|give]"));
        }
        return true;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("doubleourien.admin")) return true;
        s.sendMessage(text("&cTu n'as pas la permission."));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : List.of("reload", "value", "setvalue", "give"))
                if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("value") || a[0].equalsIgnoreCase("setvalue"))) {
            String p = a[1].toUpperCase(Locale.ROOT);
            for (Material m : Material.values())
                if (!m.name().startsWith("LEGACY_") && m.isItem() && m.name().startsWith(p)) out.add(m.name());
        }
        return out;
    }
}
