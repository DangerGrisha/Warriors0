package org.money.money.kits.ishigava;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.money.money.meta.ClassRegistry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Перенос 1:1 из Last_Warriors (events/ishigava/AuraListener) — ульта «AURA».
 * ПКМ (RED_DYE «AURA»): на 20с вокруг игрока сфера тёмно-циановых частиц (радиус 8),
 * союзники в радиусе становятся невидимыми; отменяется при смерти или падении HP ниже порога.
 * Адаптация API: Particle.REDSTONE → Particle.DUST.
 */
public class IshigavaAuraListener implements Listener {

    private static final String NAME_OF_ISHIGAVA_AURA = "AURA";

    private final Plugin plugin;
    private final Set<Player> activePlayers = new HashSet<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    public IshigavaAuraListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /* ===================== Выдача ===================== */

    public ItemStack makeAura() {
        ItemStack it = new ItemStack(Material.RED_DYE, 1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(NAME_OF_ISHIGAVA_AURA));
        meta.setUnbreakable(true);
        meta.setLore(List.of("something"));
        it.setItemMeta(meta);
        return it;
    }

    /* ===================== Логика ===================== */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isAuraActivator(event, player)) {
            if (activePlayers.contains(player)) return; // already active
            long now = System.currentTimeMillis();
            long until = cooldownUntil.getOrDefault(player.getUniqueId(), 0L);
            if (now < until) {
                long sec = (until - now + 999) / 1000;
                player.sendActionBar(Component.text(sec + " sec"));
                return;
            }
            cooldownUntil.put(player.getUniqueId(), now + ClassRegistry.millis("ishigava", "aura", 120_000L));
            startAuraEffect(player);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (activePlayers.contains(player)) {
            cancelAuraEffect(player, "Ability canceled because you died.");
        }
    }

    private boolean isAuraActivator(PlayerInteractEvent event, Player player) {
        return (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) &&
                player.getInventory().getItemInMainHand().getType() == Material.RED_DYE &&
                player.getInventory().getItemInMainHand().hasItemMeta() &&
                NAME_OF_ISHIGAVA_AURA.equals(player.getInventory().getItemInMainHand().getItemMeta().getDisplayName());
    }

    private void startAuraEffect(Player player) {
        activePlayers.add(player);
        player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);

        // null team -> still show the aura visuals; only the teammate-invisibility part needs a team
        // (so the ability isn't silently dead when testing without teams).
        Team playerTeam = getPlayerTeam(player);

        final double healthLossThreshold = ClassRegistry.num("ishigava", "aura", "healthLossThreshold", 10.0);
        double[] initialHealth = {player.getHealth()};
        double[] thresholdHealth = {Math.max(initialHealth[0] - healthLossThreshold, 0)};

        new BukkitRunnable() {
            final int durationTicks = ClassRegistry.numInt("ishigava", "aura", "durationTicks", 400);
            final double radius = ClassRegistry.num("ishigava", "aura", "radius", 8.0);
            final Random random = new Random();
            final Particle.DustOptions dustOptions = new Particle.DustOptions(Color.fromRGB(0, 100, 100), 2.0F);
            int elapsedTicks = 0;

            @Override
            public void run() {
                if (!activePlayers.contains(player) || player.isDead()) {
                    cancelAuraEffect(player, "Ability canceled because you died.");
                    cancel();
                    return;
                }

                double currentHealth = player.getHealth();

                if (currentHealth > initialHealth[0]) {
                    initialHealth[0] = currentHealth;
                    thresholdHealth[0] = Math.min(currentHealth - healthLossThreshold, 20);
                }

                if (currentHealth <= thresholdHealth[0]) {
                    cancelAuraEffect(player, "Ability canceled because your health dropped below the threshold.");
                    cancel();
                    return;
                }

                if (elapsedTicks >= durationTicks) {
                    activePlayers.remove(player);
                    cancel();
                    return;
                }

                // While Mirror Clones is active the aura emanates from the formation middle (slot 2),
                // not from whichever clone-position the player currently controls.
                Location cloneCenter = IshigavaCloneListener.auraCenter(player);
                Location center = (cloneCenter != null ? cloneCenter : player.getLocation()).clone().add(0, 1, 0);
                Set<Player> affectedPlayers = new HashSet<>();

                if (playerTeam != null) {
                    for (Player nearbyPlayer : center.getWorld().getPlayers()) {
                        if (!nearbyPlayer.equals(player) &&
                                nearbyPlayer.getWorld().equals(center.getWorld()) &&
                                nearbyPlayer.getLocation().distance(center) <= radius &&
                                playerTeam.hasEntry(nearbyPlayer.getName())) {
                            affectedPlayers.add(nearbyPlayer);
                        }
                    }
                }

                for (int i = 0; i < 200; i++) {
                    double offsetX = (random.nextDouble() * 2 - 1) * radius;
                    double offsetY = (random.nextDouble() * 2 - 1) * radius;
                    double offsetZ = (random.nextDouble() * 2 - 1) * radius;

                    if (offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ <= radius * radius) {
                        Location particleLocation = center.clone().add(offsetX, offsetY, offsetZ);
                        player.getWorld().spawnParticle(Particle.DUST, particleLocation, 1, dustOptions);
                    }
                }

                for (Player affectedPlayer : affectedPlayers) {
                    affectedPlayer.addPotionEffect(new PotionEffect(
                            PotionEffectType.INVISIBILITY, 40, 0, false, false));
                }

                elapsedTicks += 20;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void cancelAuraEffect(Player player, String reason) {
        if (activePlayers.contains(player)) {
            activePlayers.remove(player);
            player.sendMessage(reason);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }

    private Team getPlayerTeam(Player player) {
        Scoreboard scoreboard = plugin.getServer().getScoreboardManager().getMainScoreboard();
        for (Team team : scoreboard.getTeams()) {
            if (team.hasEntry(player.getName())) {
                return team;
            }
        }
        return null;
    }
}
