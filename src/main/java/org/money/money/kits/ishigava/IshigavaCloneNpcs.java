package org.money.money.kits.ishigava;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Citizens-backed formation clones for Ishigava's Mirror Clones ability.
 *
 * <p><b>Hard-references the Citizens API</b> — instantiated ONLY when Citizens is installed
 * (guarded in {@link IshigavaCloneListener}), so the JVM never loads it (or Citizens) otherwise.
 *
 * <p>Each owner gets {@code count} player-NPC clones wearing the owner's skin, referenced by a
 * stable int index; the listener decides which slot each index occupies and where it goes. Clones
 * are damageable (≈3 hearts), non-collidable and live in a private in-memory registry (never
 * persisted). Everything is destroyed in {@link #despawnAll}/{@link #shutdown}.
 */
final class IshigavaCloneNpcs {

    private NPCRegistry registry;
    private final boolean ownsRegistry;

    // owner -> (index -> NPC)
    private final Map<UUID, Map<Integer, NPC>> byOwner = new HashMap<>();

    IshigavaCloneNpcs(Plugin plugin) {
        NPCRegistry reg = null;
        boolean owns = false;
        try {
            reg = CitizensAPI.createNamedNPCRegistry("ishigava_clones", new MemoryNPCDataStore());
            owns = true;
        } catch (Throwable t) {
            try {
                reg = CitizensAPI.createNamedNPCRegistry("ishigava_clones_" + System.nanoTime(), new MemoryNPCDataStore());
                owns = true;
            } catch (Throwable t2) {
                reg = CitizensAPI.getNPCRegistry();
                owns = false;
            }
        }
        this.registry = reg;
        this.ownsRegistry = owns;
    }

    /** Spawn {@code count} clones for the owner at the given initial locations, skinned like them. */
    void spawn(Player owner, int count, Location[] initialLocs, double health, ItemStack heldItem, String skin) {
        despawnAll(owner);

        String nick = owner.getName();
        Map<Integer, NPC> map = new HashMap<>();
        for (int i = 0; i < count; i++) {
            Location loc = (initialLocs != null && i < initialLocs.length && initialLocs[i] != null)
                    ? initialLocs[i] : owner.getLocation();
            NPC npc = spawnOne(loc, nick, skin, health, heldItem);
            if (npc != null) map.put(i, npc);
        }
        byOwner.put(owner.getUniqueId(), map);
    }

    private NPC spawnOne(Location loc, String nick, String skin, double health, ItemStack heldItem) {
        try {
            // Nametag = owner's nick (clones share the name); skin = the fixed configured skin.
            NPC npc = registry.createNPC(EntityType.PLAYER, nick);
            npc.setProtected(false); // damageable
            try {
                npc.getOrAddTrait(SkinTrait.class).setSkinName(skin);
            } catch (Throwable ignored) {}
            try {
                if (heldItem != null) npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, heldItem);
            } catch (Throwable ignored) {}

            npc.spawn(loc);

            if (npc.getEntity() instanceof LivingEntity le) {
                try { le.setHealth(Math.min(health, le.getHealth())); } catch (Throwable ignored) {}
                if (le instanceof Player pe) {
                    try { pe.setCollidable(false); } catch (Throwable ignored) {}
                }
            }

            // Put the clone on the owner's team (same colour + no teammate friendly-fire). The clone's
            // scoreboard entry equals the owner's name, so this just re-affirms the owner's own entry —
            // never removed on despawn, so the real player is never dropped from the team.
            try {
                org.bukkit.scoreboard.Team team =
                        org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(nick);
                if (team != null && npc.getEntity() != null) team.addEntry(npc.getEntity().getName());
            } catch (Throwable ignored) {}

            return npc;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Move a clone into formation: teleport to {@code loc} facing (yaw,pitch); update held item if non-null. */
    void setTransform(Player owner, int index, Location loc, float yaw, float pitch, ItemStack heldItem) {
        NPC npc = npc(owner, index);
        if (npc == null || !npc.isSpawned()) return;
        try {
            Location at = loc.clone();
            at.setYaw(yaw);
            at.setPitch(pitch);
            npc.teleport(at, PlayerTeleportEvent.TeleportCause.PLUGIN);
            if (heldItem != null) {
                try { npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, heldItem); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    Location getLocation(Player owner, int index) {
        NPC npc = npc(owner, index);
        if (npc == null || !npc.isSpawned() || npc.getEntity() == null) return null;
        return npc.getEntity().getLocation();
    }

    void teleport(Player owner, int index, Location loc) {
        NPC npc = npc(owner, index);
        if (npc == null || !npc.isSpawned()) return;
        try { npc.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN); } catch (Throwable ignored) {}
    }

    boolean isAlive(Player owner, int index) {
        NPC npc = npc(owner, index);
        if (npc == null || !npc.isSpawned() || npc.getEntity() == null) return false;
        if (npc.getEntity() instanceof LivingEntity le) return !le.isDead() && le.getHealth() > 0;
        return npc.isSpawned();
    }

    void despawn(Player owner, int index) {
        Map<Integer, NPC> map = byOwner.get(owner.getUniqueId());
        if (map == null) return;
        NPC npc = map.remove(index);
        if (npc != null) try { npc.destroy(); } catch (Throwable ignored) {}
    }

    void despawnAll(Player owner) {
        Map<Integer, NPC> map = byOwner.remove(owner.getUniqueId());
        if (map == null) return;
        for (NPC npc : map.values()) {
            if (npc != null) try { npc.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** Destroy everything (plugin disable). */
    void shutdown() {
        for (UUID id : new ArrayList<>(byOwner.keySet())) {
            Map<Integer, NPC> map = byOwner.remove(id);
            if (map != null) {
                for (NPC npc : map.values()) {
                    if (npc != null) try { npc.destroy(); } catch (Throwable ignored) {}
                }
            }
        }
        byOwner.clear();
        try { if (registry != null && ownsRegistry) registry.deregisterAll(); } catch (Throwable ignored) {}
    }

    private NPC npc(Player owner, int index) {
        Map<Integer, NPC> map = byOwner.get(owner.getUniqueId());
        return map == null ? null : map.get(index);
    }
}
