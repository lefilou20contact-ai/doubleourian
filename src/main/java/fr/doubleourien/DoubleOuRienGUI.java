package fr.doubleourien;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class DoubleOuRienGUI implements Listener {

    // Zone de mise (3x3 à gauche) et zone de cibles (3x3 à droite)
    static final int[] STAKE = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    static final int[] TARGETS = {14, 15, 16, 23, 24, 25, 32, 33, 34};
    static final int MARKER = 13, INFO = 22, PREV = 45, LAUNCH = 49, NEXT = 53;
    static final int SIZE = 54;

    private final DoubleOuRienPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, ItemStack> pending = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    static class Holder implements InventoryHolder {
        Inventory inv;
        int page;
        Material selected;
        boolean spinning;
        List<Material> targets = List.of();
        double stakeValue;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public DoubleOuRienGUI(DoubleOuRienPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- utilitaires ----------

    static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    static String pct(double c) {
        return String.format(Locale.US, "%.1f%%", c * 100);
    }

    private static boolean isStake(int slot) {
        for (int s : STAKE) if (s == slot) return true;
        return false;
    }

    private ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(DoubleOuRienPlugin.text(name));
        if (lore.length > 0) {
            List<net.kyori.adventure.text.Component> l = new ArrayList<>();
            for (String s : lore) l.add(DoubleOuRienPlugin.text(s));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private void giveOrDrop(Player p, ItemStack item) {
        p.getInventory().addItem(item).values()
                .forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
    }

    double chance(double stake, double target) {
        FileConfiguration cfg = plugin.getConfig();
        double c = stake / target * (1.0 - cfg.getDouble("house-edge", 0.10));
        return Math.min(c, cfg.getDouble("max-chance", 0.90));
    }

    // ---------- ouverture ----------

    public void open(Player p) {
        if (!p.hasPermission("doubleourien.use")) {
            p.sendMessage(DoubleOuRienPlugin.text("&cTu n'as pas la permission."));
            return;
        }
        if (pending.containsKey(p.getUniqueId())) {
            p.sendMessage(DoubleOuRienPlugin.text("&cUne roulette est déjà en cours."));
            return;
        }
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, SIZE, DoubleOuRienPlugin.text("&6&lDouble ou Rien"));
        h.inv = inv;
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) inv.setItem(i, filler);
        for (int s : STAKE) inv.setItem(s, null);
        inv.setItem(MARKER, named(Material.HOPPER, "&e▼ Résultat ▼"));
        p.openInventory(inv);
        refresh(h);
    }

    // ---------- rafraîchissement ----------

    void refresh(Holder h) {
        Inventory inv = h.inv;
        ValueManager vm = plugin.values();
        FileConfiguration cfg = plugin.getConfig();
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");

        double total = 0;
        boolean valid = true, empty = true;
        String problem = null;
        boolean allowMeta = cfg.getBoolean("allow-item-meta", false);

        for (int s : STAKE) {
            ItemStack it = inv.getItem(s);
            if (it == null || it.getType().isAir()) continue;
            empty = false;
            double v = vm.getValue(it.getType());
            if (v <= 0) { valid = false; problem = "&c" + it.getType() + " n'a pas de valeur"; continue; }
            if (!allowMeta && it.hasItemMeta()) { valid = false; problem = "&cObjet modifié refusé"; continue; }
            if (it.getItemMeta() instanceof Damageable d && d.hasDamage()) { valid = false; problem = "&cObjet abîmé refusé"; continue; }
            total += v * it.getAmount();
        }
        h.stakeValue = (valid && !empty) ? total : 0;

        double minStake = cfg.getDouble("min-stake-value", 4.0);
        if (h.stakeValue > 0 && h.stakeValue < minStake) {
            problem = "&cMise trop faible (min " + fmt(minStake) + ")";
            h.stakeValue = 0;
        }

        // Liste des cibles possibles
        if (h.stakeValue > 0) {
            double maxRatio = cfg.getDouble("max-ratio", 20.0);
            double minChance = cfg.getDouble("min-chance", 0.03);
            List<Material> l = new ArrayList<>();
            for (Material m : vm.allTargets()) {
                double tv = vm.getValue(m);
                if (tv <= h.stakeValue) continue;
                if (tv > h.stakeValue * maxRatio) break;
                if (chance(h.stakeValue, tv) < minChance) break;
                l.add(m);
            }
            h.targets = l;
        } else {
            h.targets = List.of();
        }
        if (h.selected != null && !h.targets.contains(h.selected)) h.selected = null;
        int maxPage = Math.max(0, (h.targets.size() - 1) / TARGETS.length);
        h.page = Math.max(0, Math.min(h.page, maxPage));

        // Dessin des cibles
        for (int i = 0; i < TARGETS.length; i++) {
            int idx = h.page * TARGETS.length + i;
            if (idx < h.targets.size()) {
                Material m = h.targets.get(idx);
                double tv = vm.getValue(m);
                ItemStack it = new ItemStack(m);
                ItemMeta meta = it.getItemMeta();
                meta.lore(List.of(
                        DoubleOuRienPlugin.text("&7Valeur : &f" + fmt(tv)),
                        DoubleOuRienPlugin.text("&7Chance : &a" + pct(chance(h.stakeValue, tv))),
                        DoubleOuRienPlugin.text(m == h.selected ? "&e▶ Sélectionné" : "&8Clique pour choisir")));
                if (m == h.selected) meta.setEnchantmentGlintOverride(true);
                it.setItemMeta(meta);
                inv.setItem(TARGETS[i], it);
            } else {
                inv.setItem(TARGETS[i], filler);
            }
        }

        // Pagination
        inv.setItem(PREV, h.page > 0 ? named(Material.ARROW, "&e← Page précédente") : filler);
        inv.setItem(NEXT, h.page < maxPage ? named(Material.ARROW, "&ePage suivante →") : filler);

        // Infos + bouton
        List<String> info = new ArrayList<>();
        info.add("&7Valeur de la mise : &f" + (h.stakeValue > 0 ? fmt(h.stakeValue) : "-"));
        if (h.selected != null) {
            double tv = vm.getValue(h.selected);
            info.add("&7Cible : &f" + h.selected + " &7(valeur " + fmt(tv) + ")");
            info.add("&7Chance de gagner : &a" + pct(chance(h.stakeValue, tv)));
        } else if (h.stakeValue > 0) {
            info.add("&7Choisis une cible à droite.");
        } else if (empty) {
            info.add("&7Place des objets à gauche.");
        }
        if (problem != null) info.add(problem);
        inv.setItem(INFO, named(Material.PAPER, "&6Informations", info.toArray(new String[0])));

        inv.setItem(LAUNCH, h.selected != null
                ? named(Material.LIME_STAINED_GLASS_PANE, "&a&lLANCER !", "&7Quitte ou double.")
                : named(Material.RED_STAINED_GLASS_PANE, "&cChoisis une mise et une cible"));
    }

    private void refreshLater(Holder h) {
        Bukkit.getScheduler().runTask(plugin, () -> refresh(h));
    }

    // ---------- événements d'inventaire ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        Player p = (Player) e.getWhoClicked();

        if (h.spinning || e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        int raw = e.getRawSlot();
        if (raw >= SIZE) {                       // inventaire du joueur
            if (e.isShiftClick()) {
                e.setCancelled(true);
                moveToStake(h, e);
            }
            refreshLater(h);
            return;
        }
        if (isStake(raw)) {                      // zone de mise : libre
            refreshLater(h);
            return;
        }

        e.setCancelled(true);                    // tout le reste est verrouillé
        for (int i = 0; i < TARGETS.length; i++) {
            if (raw == TARGETS[i]) {
                int idx = h.page * TARGETS.length + i;
                if (idx < h.targets.size()) {
                    h.selected = h.targets.get(idx);
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                    refresh(h);
                }
                return;
            }
        }
        if (raw == PREV && h.page > 0) { h.page--; refresh(h); }
        else if (raw == NEXT) { h.page++; refresh(h); }
        else if (raw == LAUNCH) launch(p, h);
    }

    private void moveToStake(Holder h, InventoryClickEvent e) {
        ItemStack cur = e.getCurrentItem();
        if (cur == null || cur.getType().isAir()) return;
        Inventory top = h.inv;
        for (int s : STAKE) {
            ItemStack st = top.getItem(s);
            if (st != null && st.isSimilar(cur) && st.getAmount() < st.getMaxStackSize()) {
                int mv = Math.min(cur.getAmount(), st.getMaxStackSize() - st.getAmount());
                st.setAmount(st.getAmount() + mv);
                cur.setAmount(cur.getAmount() - mv);
                if (cur.getAmount() <= 0) break;
            }
        }
        if (cur.getAmount() > 0) {
            for (int s : STAKE) {
                ItemStack st = top.getItem(s);
                if (st == null || st.getType().isAir()) {
                    top.setItem(s, cur.clone());
                    cur.setAmount(0);
                    break;
                }
            }
        }
        e.setCurrentItem(cur.getAmount() <= 0 ? null : cur);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        if (h.spinning) { e.setCancelled(true); return; }
        for (int slot : e.getRawSlots()) {
            if (slot < SIZE && !isStake(slot)) { e.setCancelled(true); return; }
        }
        refreshLater(h);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        if (h.spinning) return; // la mise est déjà consommée
        Player p = (Player) e.getPlayer();
        for (int s : STAKE) {
            ItemStack it = h.inv.getItem(s);
            if (it != null && !it.getType().isAir()) {
                giveOrDrop(p, it);
                h.inv.setItem(s, null);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ItemStack r = pending.remove(e.getPlayer().getUniqueId());
        if (r != null && !r.getType().isAir()) giveOrDrop(e.getPlayer(), r);
    }

    public void deliverAllPending() {
        for (Map.Entry<UUID, ItemStack> en : pending.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null && !en.getValue().getType().isAir()) giveOrDrop(p, en.getValue());
        }
        pending.clear();
    }

    // ---------- lancer + animation ----------

    private void launch(Player p, Holder h) {
        FileConfiguration cfg = plugin.getConfig();
        if (h.selected == null || h.stakeValue <= 0) {
            p.sendMessage(DoubleOuRienPlugin.text("&cChoisis une mise valide et une cible."));
            return;
        }
        long now = System.currentTimeMillis();
        long cd = (long) (cfg.getDouble("cooldown-seconds", 3) * 1000);
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            p.sendMessage(DoubleOuRienPlugin.text("&cPatiente un peu avant de relancer."));
            return;
        }
        cooldowns.put(p.getUniqueId(), now);

        Material target = h.selected;
        double tv = plugin.values().getValue(target);
        double ch = chance(h.stakeValue, tv);
        boolean win = random.nextDouble() < ch;

        h.spinning = true;
        for (int s : STAKE) h.inv.setItem(s, null);
        pending.put(p.getUniqueId(), win ? new ItemStack(target) : new ItemStack(Material.AIR));

        plugin.getLogger().info(p.getName() + " mise " + fmt(h.stakeValue) + " -> " + target
                + " (" + pct(ch) + ") : " + (win ? "GAGNE" : "PERDU"));

        final int frames = 40;
        Material winPane = Material.LIME_STAINED_GLASS_PANE, losePane = Material.RED_STAINED_GLASS_PANE;
        List<Material> strip = new ArrayList<>();
        for (int i = 0; i < frames + 9; i++) strip.add(random.nextDouble() < ch ? winPane : losePane);
        strip.set(frames + 4, win ? winPane : losePane);

        step(p, h, strip, 0, frames, win, target);
    }

    private void step(Player p, Holder h, List<Material> strip, int i, int frames, boolean win, Material target) {
        for (int c = 0; c < 9; c++) {
            Material m = strip.get(i + c);
            h.inv.setItem(c, named(m, m == Material.LIME_STAINED_GLASS_PANE ? "&aGagné" : "&cPerdu"));
        }
        if (p.isOnline()) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f + i * 0.01f);

        if (i >= frames) {
            finish(p, h, win, target);
            return;
        }
        long delay = i < 20 ? 1 : i < 30 ? 2 : i < 36 ? 3 : 5;
        Bukkit.getScheduler().runTaskLater(plugin, () -> step(p, h, strip, i + 1, frames, win, target), delay);
    }

    private void finish(Player p, Holder h, boolean win, Material target) {
        ItemStack reward = pending.remove(p.getUniqueId());
        h.spinning = false;
        h.selected = null;
        h.page = 0;
        if (reward != null && p.isOnline()) {
            if (win) {
                giveOrDrop(p, reward);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                p.sendMessage(DoubleOuRienPlugin.text("&a&lGagné ! &7Tu obtiens &f" + target + "&7."));
            } else {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
                p.sendMessage(DoubleOuRienPlugin.text("&c&lPerdu... &7Ta mise est détruite."));
            }
        } else if (reward != null && win && !p.isOnline()) {
            // sécurité : le joueur est parti, onQuit a déjà dû livrer
        }
        if (p.getOpenInventory().getTopInventory().getHolder() == h) refresh(h);
    }
}
