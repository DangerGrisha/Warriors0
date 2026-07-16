package org.money.money.kits.bluerose;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import org.money.money.session.KitSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ability 2 — Rosebind / Управляемая Роза.
 *
 * <p><b>ПКМ</b> — поставить розу: она вылетает по взгляду и по дуге садится на блок. На месте
 * вырастает та же защитная зона, что и у Blue Rose Ward («один в один»), но она НЕ пропадает.
 * Роза одна на игрока (новая ломает старую), гейт 30с, нельзя ставить при врагах в радиусе 10.
 *
 * <p><b>ЛКМ</b> (зажать/клик) — вести стоящую розу по земле к взгляду: вверх — подъём на +1 блок,
 * вниз — падение с высоты на землю под собой; иначе держится поверхности.
 *
 * <p><b>Shift+ПКМ</b> — меню режимов: «Стоять» (ручное ведение) или «Поиск» — роза детектит врага
 * в 2× своего радиуса, преследует его как монстр и периодически кусает, высасывая ему жизнь
 * (лечит владельца), пока цель не умрёт. Предмет-краситель всегда остаётся в руке.
 */
public final class RosebindListener implements Listener {

    private static final double STEP = 0.3; // длина под-шага трассировки полёта (анти-туннелинг)

    private final BlueRoseGuardianManager m;
    private final Set<UUID> inFlight = new HashSet<>(); // у кого роза сейчас в полёте

    public RosebindListener(BlueRoseGuardianManager m) { this.m = m; }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!m.isRosebind(p.getInventory().getItemInMainHand())) return;

        Action a = e.getAction();
        boolean left  = (a == Action.LEFT_CLICK_AIR  || a == Action.LEFT_CLICK_BLOCK);
        boolean right = (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK);
        if (!left && !right) return;
        e.setCancelled(true);
        if (!KitSession.isInGame(p)) return;

        if (left) { steer(p); return; }
        if (p.isSneaking()) { openModeMenu(p); return; }   // Shift+ПКМ — меню режимов
        place(p);
    }

    /* ===================== ЛКМ — вести розу ===================== */

    private void steer(Player p) {
        if (m.activeRosebind(p.getUniqueId()) == null) return;          // нечего вести
        if (m.rosebindHunt(p.getUniqueId())) return;                    // в режиме «Поиск» роза едет сама
        m.driveRosebind(p.getUniqueId(), m.numInt("rosebind", "driveWindowTicks", 8));
    }

    /* ===================== Shift+ПКМ — меню режимов ===================== */

    private void openModeMenu(Player p) {
        boolean hunt = m.rosebindHunt(p.getUniqueId());
        RosebindMenuHolder holder = new RosebindMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 9,
                Component.text("Rosebind: режим", NamedTextColor.DARK_AQUA));
        holder.inv = inv;
        inv.setItem(3, modeIcon(Material.BLUE_ORCHID, "Стоять", NamedTextColor.AQUA, !hunt,
                "Роза стоит на месте,", "ведётся вручную (ЛКМ)."));
        inv.setItem(5, modeIcon(Material.WITHER_ROSE, "Поиск", NamedTextColor.RED, hunt,
                "Гонится за врагом в 2× радиуса", "и пьёт его жизнь, пока не убьёт."));
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.2f);
    }

    private ItemStack modeIcon(Material mat, String name, NamedTextColor color, boolean selected, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text((selected ? "» " : "") + name + (selected ? " «" : ""), color)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lines.add(Component.text(selected ? "✔ Выбрано" : "Нажми, чтобы выбрать",
                        selected ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        im.lore(lines);
        it.setItemMeta(im);
        return it;
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RosebindMenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        boolean hunt;
        if (slot == 3) hunt = false;
        else if (slot == 5) hunt = true;
        else return;
        m.setRosebindHunt(p.getUniqueId(), hunt);
        RoseZone z = m.activeRosebind(p.getUniqueId());
        if (z != null) z.huntTarget = null;                             // сброс залоченной цели при смене режима
        p.closeInventory();
        p.sendActionBar(Component.text("Режим розы: " + (hunt ? "Поиск" : "Стоять"),
                hunt ? NamedTextColor.RED : NamedTextColor.AQUA));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, hunt ? 0.8f : 1.4f);
    }

    /** Маркер-владелец инвентаря меню режимов (чтобы ловить клики только в нём). */
    public static final class RosebindMenuHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    /* ===================== ПКМ — поставить розу ===================== */

    private void place(Player p) {
        final UUID ownerId = p.getUniqueId();
        if (inFlight.contains(ownerId)) return;                         // одна роза в полёте
        if (m.isOnCooldown(p, "rosebind", 600, "Rosebind")) return;     // тихий гейт 30с

        final double speed = m.num("rosebind", "travelSpeed", 0.65);
        final double gravity = m.num("rosebind", "gravity", 0.035);
        final int maxTicks = m.numInt("rosebind", "maxTravelTicks", 80);
        final int groundDepth = m.numInt("rosebind", "groundSearchDepth", 5);
        final double radius = m.num("rosebind", "radius", 5.5);
        final double enemyRadius = m.num("rosebind", "placeBlockedEnemyRadius", 10.0);

        final Location spawn = p.getEyeLocation().clone();
        final ArmorStand visual = m.spawnRoseAnchor(spawn, "blue_rose_guardian_rosebind");
        if (visual == null) return;
        inFlight.add(ownerId);
        BlueRoseVisualUtil.soundBind(spawn);

        new BukkitRunnable() {
            Location pos = spawn.clone();
            final Vector vel = spawn.getDirection().normalize().multiply(speed);
            int ticks = 0;

            @Override public void run() {
                Player owner = Bukkit.getPlayer(ownerId);
                if (owner == null || !owner.isOnline() || !KitSession.isInGame(owner)) { abort(); return; }

                vel.setY(vel.getY() - gravity);
                double dist = vel.length();
                Vector unit = dist > 1e-6 ? vel.clone().multiply(1.0 / dist) : new Vector(0, -1, 0);

                double moved = 0;
                while (moved < dist) {
                    double s = Math.min(STEP, dist - moved);
                    Location probe = pos.clone().add(unit.clone().multiply(s));
                    if (probe.getWorld() == null) { abort(); return; }
                    if (probe.getBlock().getType().isSolid()) {     // влетели в блок — садимся в этой колонке
                        land(owner);
                        cancel();
                        return;
                    }
                    pos = probe;
                    moved += s;
                }

                visual.teleport(pos);
                BlueRoseVisualUtil.roseBloom(pos);

                if (++ticks >= maxTicks) {                          // время вышло — пробуем сесть под собой
                    land(owner);
                    cancel();
                }
            }

            private void abort() {
                inFlight.remove(ownerId);
                try { visual.remove(); } catch (Throwable ignored) {}
                cancel();
            }

            private void land(Player owner) {
                inFlight.remove(ownerId);
                Location rest = resolveRest(pos, groundDepth);
                if (rest == null) { fizzle(owner, "Розе негде встать — нет блока"); return; }
                if (!m.enemiesIn(owner, rest, enemyRadius).isEmpty()) {
                    fizzle(owner, "Рядом враги — розу не поставить");
                    return;
                }
                m.enforceSingleRosebind(ownerId);
                visual.teleport(rest);
                spawnZone(owner, rest, radius, visual);
                m.markUsed(owner, "rosebind");                      // запускаем 30с гейт только при удаче
                BlueRoseVisualUtil.soundPlace(rest);
                BlueRoseVisualUtil.roseBloom(rest);
                owner.sendActionBar(Component.text("Rosebind: ЛКМ ведёт, Shift+ПКМ — режим", NamedTextColor.AQUA));
            }

            private void fizzle(Player owner, String reason) {
                try { visual.remove(); } catch (Throwable ignored) {}
                owner.sendActionBar(Component.text(reason, NamedTextColor.RED));
                owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 0.7f);
            }
        }.runTaskTimer(m.plugin(), 0L, 1L);
    }

    /** Найти поверхность под точкой удара: первый твёрдый блок с воздухом сверху (в пределах depth). */
    private Location resolveRest(Location impact, int depth) {
        World w = impact.getWorld();
        if (w == null) return null;
        int x = impact.getBlockX(), z = impact.getBlockZ(), startY = impact.getBlockY();
        for (int y = startY; y >= startY - depth; y--) {
            if (w.getBlockAt(x, y, z).getType().isSolid()
                    && !w.getBlockAt(x, y + 1, z).getType().isSolid()) {
                return new Location(w, x + 0.5, y + 1.0, z + 0.5);
            }
        }
        return null;
    }

    /* ===================== Зона + поведение ===================== */

    /** Бесконечная зона-роза «один в один» как Ward, с ведением/погоней. Снимается reset'ом/новой розой. */
    private void spawnZone(Player owner, Location center, double radius, ArmorStand anchor) {
        final UUID ownerId = owner.getUniqueId();
        final int healInterval = Math.max(5, m.numInt("rosebind", "allyHealIntervalTicks", 20));

        final RoseZone zone = new RoseZone(RoseZone.Kind.ROSEBIND, ownerId);
        zone.center = center.clone();
        zone.radius = radius;
        zone.setAnchor(anchor);

        BukkitTask task = new BukkitRunnable() {
            int elapsed = 0;
            @Override public void run() {
                Player o = Bukkit.getPlayer(ownerId);
                if (o == null || !o.isOnline() || !KitSession.isInGame(o)) {
                    if (zone.center != null) BlueRoseVisualUtil.roseBurst(zone.center, 0.6f);
                    m.endZone(zone);
                    return;
                }

                if (m.rosebindHunt(ownerId)) huntStep(o, zone, elapsed);            // «Поиск» — едет сама
                else if (m.isDrivingRosebind(ownerId)) stepRose(o, zone);           // «Стоять» — ведём ЛКМ

                Location c = zone.center;
                if (c == null) { m.endZone(zone); return; }

                if (elapsed % 4 == 0) {
                    BlueRoseVisualUtil.groundRing(c, zone.radius, 24);
                    BlueRoseVisualUtil.roseBloom(c);
                }
                if (elapsed % 10 == 0) BlueRoseVisualUtil.frostDust(c, zone.radius, 6);
                if (elapsed % healInterval == 0) m.applyWardTickEffects(o, c, zone.radius, 1.0, "rosebind");
                elapsed++;
            }
        }.runTaskTimer(m.plugin(), 0L, 1L);

        zone.setTask(task);
        m.registerZone(zone);
    }

    /** «Стоять»: шаг розы к горизонтальному взгляду. Вверх — подъём +1; вниз — падение с высоты. */
    private void stepRose(Player owner, RoseZone zone) {
        Location c = zone.center;
        if (c == null || c.getWorld() == null) return;
        Vector look = owner.getEyeLocation().getDirection();
        Vector horiz = new Vector(look.getX(), 0, look.getZ());
        if (horiz.lengthSquared() < 1e-6) return;                       // строго вверх/вниз — не едем
        horiz.normalize().multiply(m.num("rosebind", "steerStepPerTick", 0.18));

        float pitch = owner.getLocation().getPitch();
        boolean up = pitch <= m.num("rosebind", "climbLookUpPitch", -30.0);
        boolean down = pitch >= m.num("rosebind", "fallDownPitch", 30.0);
        int drop = down ? m.numInt("rosebind", "maxFallDown", 32) : m.numInt("rosebind", "maxStepDown", 3);
        doStep(c.getWorld(), zone, horiz, up, drop);
    }

    /** «Поиск»: захват и преследование врага + укусы с вампиризмом, пока цель жива. */
    private void huntStep(Player owner, RoseZone zone, int elapsed) {
        Location c = zone.center;
        if (c == null || c.getWorld() == null) return;
        double detect = zone.radius * m.num("rosebind", "huntDetectMultiplier", 2.0);

        Player target = lockedTarget(owner, zone);
        if (target == null) {
            target = nearestEnemy(owner, c, detect);                    // захват в пределах 2× радиуса
            if (target != null) zone.huntTarget = target.getUniqueId();
        }
        if (target == null) return;                                     // никого — стоим

        Vector horiz = target.getLocation().toVector().subtract(c.toVector());
        horiz.setY(0);
        if (horiz.lengthSquared() > 1e-6) {                             // едем к цели (climb+fall разрешены)
            horiz.normalize().multiply(m.num("rosebind", "huntStepPerTick", 0.22));
            doStep(c.getWorld(), zone, horiz, true, m.numInt("rosebind", "maxFallDown", 32));
        }

        Location rc = zone.center;                                      // обновился после doStep
        double br = m.num("rosebind", "biteRange", 2.0);
        if (rc != null && rc.getWorld() == target.getWorld()
                && rc.distanceSquared(target.getLocation()) <= br * br) {
            int interval = Math.max(1, m.numInt("rosebind", "biteIntervalTicks", 10));
            if (elapsed % interval == 0) {
                m.safeDamage(target, m.num("rosebind", "biteDamage", 2.0), owner);
                m.healAlly(owner, m.num("rosebind", "biteLifesteal", 1.0));      // высасываем жизнь владельцу
                BlueRoseVisualUtil.iceSpike(target.getLocation());
                BlueRoseVisualUtil.soundBind(rc);
            }
        }
    }

    /** Залоченная цель преследования, если она ещё валидна (иначе сбрасываем). */
    private Player lockedTarget(Player owner, RoseZone zone) {
        if (zone.huntTarget == null) return null;
        Player t = Bukkit.getPlayer(zone.huntTarget);
        if (t != null && t.isOnline() && !t.isDead() && KitSession.isInGame(t)
                && t.getWorld() == zone.center.getWorld() && m.isEnemy(owner, t)) {
            return t;
        }
        zone.huntTarget = null;
        return null;
    }

    private Player nearestEnemy(Player owner, Location at, double radius) {
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (Player en : m.enemiesIn(owner, at, radius)) {
            double d = en.getLocation().distanceSquared(at);
            if (d < bestSq) { bestSq = d; best = en; }
        }
        return best;
    }

    /** Один шаг розы в заданном горизонтальном направлении, держась поверхности. */
    private void doStep(World w, RoseZone zone, Vector horizDir, boolean allowClimb, int maxDrop) {
        Location c = zone.center;
        double nx = c.getX() + horizDir.getX();
        double nz = c.getZ() + horizDir.getZ();
        int bx = (int) Math.floor(nx), bz = (int) Math.floor(nz);
        int feetY = (int) Math.floor(c.getY());                         // воздушная ячейка розы; опора = feetY-1
        Integer standY = resolveStandY(w, bx, bz, feetY, allowClimb, maxDrop);
        if (standY == null) return;                                     // стена/обрыв — стоим этот шаг
        zone.relocate(new Location(w, nx, standY, nz));
    }

    /**
     * Высота, на которой роза встанет в колонке (bx,bz): вровень / спуск до {@code maxDrop} /
     * подъём на +1 (если {@code allowClimb}). null — нельзя.
     */
    private Integer resolveStandY(World w, int bx, int bz, int feetY, boolean allowClimb, int maxDrop) {
        if (w.getBlockAt(bx, feetY, bz).getType().isSolid()) {          // впереди стена на уровне ног
            if (allowClimb && !w.getBlockAt(bx, feetY + 1, bz).getType().isSolid()) return feetY + 1;
            return null;
        }
        if (w.getBlockAt(bx, feetY - 1, bz).getType().isSolid()) return feetY;   // вровень
        for (int y = feetY - 2; y >= feetY - 1 - maxDrop; y--) {        // спуск/падение к земле
            if (w.getBlockAt(bx, y, bz).getType().isSolid()
                    && !w.getBlockAt(bx, y + 1, bz).getType().isSolid()) {
                return y + 1;
            }
        }
        return null;                                                    // нет опоры в пределах maxDrop
    }
}
