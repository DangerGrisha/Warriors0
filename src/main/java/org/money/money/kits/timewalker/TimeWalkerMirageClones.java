package org.money.money.kits.timewalker;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Citizens-backed afterimage clones for TimeWalker's Chrono Mirage ultimate.
 *
 * <p><b>This class hard-references the Citizens API.</b> It is therefore instantiated
 * ONLY when the Citizens plugin is installed (guarded in {@link TimeWalkerUltListener}),
 * so the JVM never tries to load it — and the Citizens classes — when Citizens is absent.
 *
 * <p>Clones are temporary player-NPCs in a private in-memory registry (never persisted to
 * disk). For each active mirage they appear at random spots inside the circle, run to a
 * couple of random points, then despawn — cycling for the whole duration. Everything is
 * destroyed + cancelled in {@link #stop(UUID)} / {@link #shutdown()}.
 */
final class TimeWalkerMirageClones {

    private final Plugin plugin;
    private NPCRegistry registry;
    // true only when `registry` is a PRIVATE registry we created and exclusively own.
    // We must never deregisterAll() a shared/default registry (it would wipe foreign NPCs).
    private final boolean ownsRegistry;

    private final Map<UUID, List<NPC>> npcsByCaster = new HashMap<>();
    private final Map<UUID, List<BukkitTask>> tasksByCaster = new HashMap<>();

    TimeWalkerMirageClones(Plugin plugin) {
        this.plugin = plugin;

        NPCRegistry reg = null;
        boolean owns = false;
        try {
            reg = CitizensAPI.createNamedNPCRegistry("timewalker_mirage", new MemoryNPCDataStore());
            owns = true;
        } catch (Throwable t) {
            // Name collision (e.g. after /reload) or API issue -> retry with a unique private
            // registry so clones stay in-memory and we still exclusively own it.
            try {
                reg = CitizensAPI.createNamedNPCRegistry("timewalker_mirage_" + System.nanoTime(), new MemoryNPCDataStore());
                owns = true;
            } catch (Throwable t2) {
                // Last resort: the shared default registry. We do NOT own it, so shutdown()
                // will never deregisterAll() it — clones are removed individually via destroy().
                reg = CitizensAPI.getNPCRegistry();
                owns = false;
            }
        }
        this.registry = reg;
        this.ownsRegistry = owns;
    }

    /**
     * Spawn clones around the FIXED anchor that appear, run around inside the circle and
     * despawn, cycling for {@code durationTicks}. Clones wear the caster's skin.
     */
    void start(Player caster, Location anchor, double radius, int count, long durationTicks, String skin) {
        final UUID id = caster.getUniqueId();
        final World world = anchor.getWorld();
        if (world == null) return;

        // Fixed skin (e.g. "seirah515") — fetched by name from Mojang and cached by Citizens.
        final String skinName = skin;

        // Same blade as the caster (Perfect Sever is a netherite sword) — held in the clone's hand.
        final ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);

        // Driver: every 0.5s top the live clones back up to `count`, scheduling each fresh
        // clone to despawn after a staggered lifetime -> a constant appear/run/vanish churn.
        BukkitTask driver = new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                if (elapsed >= durationTicks) {
                    cancel();
                    return;
                }
                List<NPC> list = npcsByCaster.computeIfAbsent(id, k -> new ArrayList<>());
                list.removeIf(n -> n == null || !n.isSpawned());

                int safety = 0;
                while (list.size() < count && safety++ < count) {
                    NPC npc = spawnOne(anchor, radius, skinName, sword);
                    if (npc == null) break;
                    list.add(npc);
                    scheduleRoam(id, npc, anchor, radius);

                    long life = 25L + ThreadLocalRandom.current().nextLong(0, 45L); // ~1.25–3.5s
                    BukkitTask kill = Bukkit.getScheduler().runTaskLater(plugin, () -> despawn(id, npc), life);
                    addTask(id, kill);
                }
                elapsed += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);
        addTask(id, driver);
    }

    private NPC spawnOne(Location anchor, double radius, String skin, ItemStack sword) {
        try {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            double ang = rnd.nextDouble(0, Math.PI * 2);
            double rr = radius * rnd.nextDouble(0.35, 1.0);
            Location loc = anchor.clone().add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr);
            loc.setY(anchor.getY());

            NPC npc = registry.createNPC(EntityType.PLAYER, "");
            npc.setProtected(true);
            try {
                npc.getOrAddTrait(SkinTrait.class).setSkinName(skin);
            } catch (Throwable ignored) {}
            // Give the clone the same blade in hand.
            try {
                npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, sword);
            } catch (Throwable ignored) {}
            npc.spawn(loc);
            return npc;
        } catch (Throwable t) {
            return null;
        }
    }

    private void scheduleRoam(UUID id, NPC npc, Location anchor, double radius) {
        // Fixed straight-run axis chosen once per clone — it just sprints back and forth along it
        // (no zig-zag wandering), so collectively the clones streak straight across the circle.
        double a = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        final double ax = Math.cos(a);
        final double az = Math.sin(a);
        BukkitTask roam = new BukkitRunnable() {
            int steps = 0;

            @Override
            public void run() {
                if (npc == null || !npc.isSpawned() || steps >= 12) {
                    cancel();
                    return;
                }
                try {
                    double sign = (steps % 2 == 0) ? 1.0 : -1.0; // one end of the line, then the other
                    Location t = anchor.clone().add(ax * radius * sign, 0, az * radius * sign);
                    t.setY(anchor.getY());
                    npc.getNavigator().getLocalParameters().speedModifier(2.2f); // back to the original slower pace
                    npc.getNavigator().setTarget(t);
                } catch (Throwable ignored) {}
                steps++;
            }
        }.runTaskTimer(plugin, 5L, 18L); // ~0.9s per straight leg
        addTask(id, roam);
    }

    private void despawn(UUID id, NPC npc) {
        try {
            if (npc != null) {
                Location at = (npc.isSpawned() && npc.getEntity() != null)
                        ? npc.getEntity().getLocation() : null;
                npc.destroy();
                if (at != null && at.getWorld() != null) {
                    at.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, at.add(0, 1, 0), 12, 0.2, 0.4, 0.2, 0.03);
                }
            }
        } catch (Throwable ignored) {}
        List<NPC> list = npcsByCaster.get(id);
        if (list != null) list.remove(npc);
    }

    /** Despawn all clones + cancel all tasks for a caster. Idempotent. */
    void stop(UUID id) {
        List<BukkitTask> tasks = tasksByCaster.remove(id);
        if (tasks != null) {
            for (BukkitTask t : tasks) {
                try { if (t != null) t.cancel(); } catch (Throwable ignored) {}
            }
        }
        List<NPC> list = npcsByCaster.remove(id);
        if (list != null) {
            for (NPC n : list) {
                try { if (n != null) n.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Destroy everything (plugin disable). */
    void shutdown() {
        for (UUID id : new ArrayList<>(npcsByCaster.keySet())) stop(id);
        npcsByCaster.clear();
        tasksByCaster.clear();
        // Only ever wipe a private registry we own — never the shared default (would destroy
        // server-wide / other plugins' NPCs). On the default-registry fallback, the per-NPC
        // destroy() in stop() above already removed our clones.
        try {
            if (registry != null && ownsRegistry) registry.deregisterAll();
        } catch (Throwable ignored) {}
    }

    private void addTask(UUID id, BukkitTask t) {
        tasksByCaster.computeIfAbsent(id, k -> new ArrayList<>()).add(t);
    }
}
