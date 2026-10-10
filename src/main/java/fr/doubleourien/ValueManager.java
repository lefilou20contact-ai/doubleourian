package fr.doubleourien;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ValueManager {

    private final DoubleOuRienPlugin plugin;
    private final File file;
    private YamlConfiguration yaml;

    private final Map<Material, Double> manual = new EnumMap<>(Material.class);
    private final Map<Material, Double> cache = new EnumMap<>(Material.class);
    private final Set<Material> blacklist = EnumSet.noneOf(Material.class);
    private final List<String> blacklistContains = new ArrayList<>();
    private final Set<Material> inProgress = EnumSet.noneOf(Material.class);
    private List<Material> targets;
    private boolean auto;
    private double craftMult;

    public ValueManager(DoubleOuRienPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "values.yml");
        reload();
    }

    public void reload() {
        yaml = YamlConfiguration.loadConfiguration(file);
        manual.clear();
        cache.clear();
        blacklist.clear();
        blacklistContains.clear();
        targets = null;

        ConfigurationSection sec = yaml.getConfigurationSection("values");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) {
                    plugin.getLogger().warning("values.yml : objet inconnu " + k);
                    continue;
                }
                manual.put(m, sec.getDouble(k));
            }
        }
        FileConfiguration cfg = plugin.getConfig();
        for (String s : cfg.getStringList("blacklist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blacklist.add(m);
        }
        for (String s : cfg.getStringList("blacklist-contains")) blacklistContains.add(s.toUpperCase(Locale.ROOT));
        auto = cfg.getBoolean("auto-values.enabled", true);
        craftMult = cfg.getDouble("auto-values.craft-multiplier", 1.0);
    }

    public void setValue(Material m, double v) {
        yaml.set("values." + m.name(), v);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'écrire values.yml : " + e.getMessage());
        }
        reload();
    }

    /** Valeur de l'objet, ou -1 s'il est inutilisable. */
    public double getValue(Material m) {
        return compute(m);
    }

    /** Tous les objets valorisés, triés par valeur croissante. */
    public List<Material> allTargets() {
        if (targets == null) {
            List<Material> l = new ArrayList<>();
            for (Material m : Material.values()) if (getValue(m) > 0) l.add(m);
            l.sort(Comparator.comparingDouble(this::getValue).thenComparing(Material::name));
            targets = l;
        }
        return targets;
    }

    private boolean isBlacklisted(Material m) {
        if (m.name().startsWith("LEGACY_") || m.isAir() || !m.isItem()) return true;
        if (blacklist.contains(m)) return true;
        String n = m.name();
        for (String s : blacklistContains) if (n.contains(s)) return true;
        return false;
    }

    private double compute(Material m) {
        if (isBlacklisted(m)) return -1;
        Double man = manual.get(m);
        if (man != null) return man > 0 ? man : -1;
        Double c = cache.get(m);
        if (c != null) return c;
        if (!auto || !inProgress.add(m)) return -1;

        double best = -1;
        try {
            String ns = plugin.getName().toLowerCase(Locale.ROOT);
            for (Recipe r : Bukkit.getRecipesFor(new ItemStack(m))) {
                if (r instanceof Keyed k && k.getKey().getNamespace().equals(ns)) continue;

                List<RecipeChoice> choices = new ArrayList<>();
                if (r instanceof ShapedRecipe sr) {
                    for (String row : sr.getShape())
                        for (char ch : row.toCharArray()) {
                            if (ch == ' ') continue;
                            RecipeChoice rc = sr.getChoiceMap().get(ch);
                            if (rc != null) choices.add(rc);
                        }
                } else if (r instanceof ShapelessRecipe sl) {
                    choices.addAll(sl.getChoiceList());
                } else if (r instanceof CookingRecipe<?> cr) {
                    choices.add(cr.getInputChoice());
                } else if (r instanceof SmithingTransformRecipe st) {
                    choices.add(st.getTemplate());
                    choices.add(st.getBase());
                    choices.add(st.getAddition());
                } else continue;

                if (choices.isEmpty()) continue;
                double sum = 0;
                boolean ok = true;
                for (RecipeChoice rc : choices) {
                    double v = choiceValue(rc);
                    if (v <= 0) { ok = false; break; }
                    sum += v;
                }
                if (!ok) continue;
                double v = sum / Math.max(1, r.getResult().getAmount()) * craftMult;
                if (best < 0 || v < best) best = v;
            }
        } finally {
            inProgress.remove(m);
        }
        if (inProgress.isEmpty()) cache.put(m, best);
        return best;
    }

    private double choiceValue(RecipeChoice ch) {
        double best = -1;
        if (ch instanceof RecipeChoice.MaterialChoice mc) {
            for (Material m : mc.getChoices()) {
                double v = compute(m);
                if (v > 0 && (best < 0 || v < best)) best = v;
            }
        } else if (ch instanceof RecipeChoice.ExactChoice ec) {
            for (ItemStack s : ec.getChoices()) {
                double v = compute(s.getType());
                if (v > 0 && (best < 0 || v < best)) best = v;
            }
        }
        return best;
    }
}
