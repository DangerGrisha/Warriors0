package org.money.money.match;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.money.money.Main;
import org.money.money.gui.ClassSelectionGUI;

import java.util.*;

public class ClassSelectionService {

    private static final Set<String> activeWorlds = new HashSet<>();
    private static final Map<UUID, String> chosen = new HashMap<>();
    private static final Random rnd = new Random();

    // IMPORTANT: truth = scoreboard tags, this is just a cache
    public static boolean isActive(String worldName) {
        return activeWorlds.contains(worldName);
    }

    public static Optional<String> getChosen(UUID uuid) {
        return Optional.ofNullable(chosen.get(uuid));
    }

    public static void clearPlayer(UUID uuid) {
        chosen.remove(uuid);
    }

    public static void start(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        activeWorlds.add(worldName);
        Main.getInstance().getLogger().info("[ClassSelect] start world=" + worldName + " players=" + w.getPlayers().size());

        for (Player p : w.getPlayers()) {
            // hard clean before selection
            wipeInventory(p);

            // prevent grabbing items during selection
            p.setCanPickupItems(false);

            // clear previous class tags (important!)
            stripAllClassTags(p);

            // allow re-giving kit later
            p.removeScoreboardTag("KIT_GIVEN");

            ClassSelectionGUI.open(p);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                activeWorlds.remove(worldName);

                World ww = Bukkit.getWorld(worldName);
                if (ww == null) return;

                for (Player p : ww.getPlayers()) {
                    // pick class (chosen map or random)
                    String c = chosen.get(p.getUniqueId());
                    if (c == null) {
                        c = ClassSelectionGUI.ORDER[rnd.nextInt(ClassSelectionGUI.ORDER.length)];
                        chosen.put(p.getUniqueId(), c);
                    }

                    // write to scoreboard tags (SOURCE OF TRUTH)
                    stripAllClassTags(p);
                    p.addScoreboardTag(c);

                    // close GUI if open
                    try {
                        if (p.getOpenInventory() != null
                                && ClassSelectionGUI.GUI_TITLE.equals(p.getOpenInventory().getTitle())) {
                            p.closeInventory();
                        }
                    } catch (Throwable ignored) {}

                    p.setCanPickupItems(true);
                    p.sendMessage("§aClass selected: §f" + c);
                }

                // NEXT STEP
                MatchOrchestrator.afterClassSelection(worldName);
            }
        }.runTaskLater(Main.getInstance(), 20L * 20);
    }

    public static void choose(Player p, String classId) {
        chosen.put(p.getUniqueId(), classId);

        // optional: instant tag update, so if they crash during selection we still have tag
        stripAllClassTags(p);
        p.addScoreboardTag(classId);
        p.removeScoreboardTag("KIT_GIVEN");
    }

    /* ================= helpers ================= */

    private static void wipeInventory(Player p) {
        try {
            p.getInventory().clear();
            p.getInventory().setHelmet(null);
            p.getInventory().setChestplate(null);
            p.getInventory().setLeggings(null);
            p.getInventory().setBoots(null);
            p.getInventory().setItemInOffHand(null);
            p.updateInventory();
        } catch (Throwable ignored) {}
    }

    private static void stripAllClassTags(Player p) {
        // must match your class list exactly
        Set<String> copy = new HashSet<>(p.getScoreboardTags());
        for (String t : copy) {
            for (String cls : ClassSelectionGUI.ORDER) {
                if (t.equalsIgnoreCase(cls)) {
                    p.removeScoreboardTag(t);
                    break;
                }
            }
            // optional alias
            if (t.equalsIgnoreCase("LadyNagan")) p.removeScoreboardTag(t);
        }
    }
}
