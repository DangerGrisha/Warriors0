# New Kits & Abilities (TimeWalker, Blastborn, Ishigava Mirror Clones)

This document covers the kits and abilities added/changed in the June 2026 work, including the
Citizens NPC integration. It is the source of truth for these abilities' numbers. High-level
index: `PROJECT_DOCUMENTATION.md`.

All values below are the in-code **defaults**; every kit reads them from `config.yml` (see §5).
The deployed server's `config.yml` currently has no overrides for these sections, so the defaults
apply on restart.

---

## 0. Citizens integration (soft-depend)

Two abilities spawn real **Citizens NPC clones** (TimeWalker's ult, Ishigava's Mirror Clones).

- `build.gradle`: `compileOnly "net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT" { transitive = false }`
  from `https://maven.citizensnpcs.co/repo`. `transitive=false` is required — Citizens' `libby-bukkit`
  runtime dep isn't in the configured repos and isn't needed for compile.
- `plugin.yml`: `softdepend: [Citizens]`.
- **Isolation pattern:** all Citizens API usage lives in dedicated classes
  (`TimeWalkerMirageClones`, `IshigavaCloneNpcs`) that import `net.citizensnpcs.*`. The owning
  listeners do **not** import Citizens and only instantiate the helper behind a
  `Bukkit.getPluginManager().getPlugin("Citizens") != null` guard, so on a server without Citizens
  the class is never loaded (no `NoClassDefFoundError`). TimeWalker's ult falls back to
  particle-only clones; Ishigava's Mirror Clones denies activation with a message.
- Clones live in a **private in-memory `NPCRegistry`** (never persisted to disk). The registry is
  only `deregisterAll()`-ed when we own it (`ownsRegistry` flag) — never the shared default
  registry, so other plugins' NPCs are never wiped.

---

## 1. TimeWalker

Time-themed bruiser. `/kitgive TimeWalker <run|slash|momentum|ult>`. Config root: `timewalker.*`.
Interop scoreboard tags: `TimeWalkerFutureRun`, `TimeWalkerMomentum`, `TimeWalkerMirage`,
`TimeWalkerSeverIFrame`.

### Future Run (`run`) — `CLOCK` "Future Run"
- RMB → ~1s of **Speed level 60** (near-teleport dash; you control the movement, so no wall-clip),
  then the speed wears off. After **8s** total you are teleported back to the activation point
  ("rewind") with safe-teleport guards (cross-world / death / lobby / spectator / stuck-in-block
  all abort the return).
- Adds the external-game tag **`cantpickupflag`** on activation, removed on rewind and every other
  end path (death/quit/lobby/reset) — gate flag pickup on it.
- Cooldown **45s**. Config: `timewalker.run.{cooldown-seconds=45, dash-ticks=20, total-ticks=160, dash-speed-level=60}`.

### Perfect Sever (`slash`) — `NETHERITE_SWORD` "Perfect Sever"
- RMB → a travelling slash wave (range **6**), forward dash (launch 0.6, wall-safe), **0.4s full
  i-frames** (the `TimeWalkerSeverIFrame` tag cancels all damage — not a Resistance buff).
- Damage scales with your horizontal speed: `clamp(5 + speed*13.5, 5, 20)`; +1.5 inside your own
  Mirage. Knockback + per-cast single-hit set. Enhanced FX when Future Run / Momentum / Mirage is active.
- Cooldown **8s**. Config: `timewalker.slash.{cooldown-seconds=8, iframe-ticks=8, forward-launch=0.6, range=6.0, base-damage=5.0, speed-multiplier=13.5, min-damage=5.0, max-damage=20.0}`.

### Momentum Drive (`momentum`) — `SUGAR` "Momentum Drive" (toggle)
- F/RMB toggles. Running straight ramps **Speed I → VIII** (`max-level=8`, `0.9s`/level); sharp
  turn (>60°) or stopping resets it.
- FX: black "evil" **footprints** on the ground + galloping **horse** hoofbeats (cadence/pitch rise
  with level); satisfying **milestone** cue at level 5 and 8.
- **Fall mechanic:** while Momentum is on, fall damage is **halved** and the impact is converted to
  momentum (harder fall → bigger speed spike), with an impact cue.
- Powers Perfect Sever (its Speed buff = faster movement = bigger slash). Config:
  `timewalker.momentum.{max-level=8, seconds-per-level=0.9, turn-reset-degrees=60, min-move-speed=0.04, fall-damage-multiplier=0.5, fall-momentum-ticks-per-damage=4.0}`.

### Chrono Mirage (`ult`) — `ENDER_EYE` "Chrono Mirage"
- RMB → a **fixed domain** (radius **36**) for ~6s. Enemies inside take **4 dmg every 0.5s**.
- **Citizens clones** (`clone-count=6`, skin **`seirah515`**) holding netherite swords sprint
  straight back-and-forth across the circle (+ particle afterimages as fallback). All effects are
  light-blue (cyan dust + soul-fire "blue torch"). Item returns after the cooldown (in-game only).
- Cooldown **60s** (1 min). Config: `timewalker.ult.{radius=36.0, duration-seconds=6, damage-per-tick=4.0, tick-period=10, clone-count=6, cooldown-seconds=60, clone-skin=seirah515, self-speed-amplifier=1, self-resistance-amplifier=0}`.

---

## 2. Blastborn (Bakugo)

Explosion demolitions class. `/kitgive Blastborn <gloves|grenade|ult|machinegun>`. Config root:
`classes.blastborn.*`. Class tag: `Blastborn` (added by `/kitgive`). Shared helper `ExplosionUtil`
(manual fake explosions — never `world.createExplosion`; block breaking drops **no items**).

### Self-Destruction resource (the player's XP bar)
- 0–100, shown on the **vanilla XP bar** (only ever touched for tagged Blastborn players). +10 per
  glove blast, decays **2/sec**. At 100 → a 1s OVERLOAD warning (blinking bar + `OVERLOAD!` +
  ticking + smoke) → detonation:
  - **Self-destructs Bakugo himself** (lethal). A **Totem of Undying** in hand pops (vanilla) and he
    survives — but then the blast lands **softer on everyone else** (×0.5). Without a totem he dies.
  - Others take **28 dmg** (×0.5 if a totem soaked it) + knockback + no-drop block break.
- `selfKnockbackMultiplier=2.25` — every Blastborn blast flings **himself** ~2.25× harder than
  others (mobility). Config: `classes.blastborn.selfDestruction.{max=100, decayIntervalTicks=10,
  decayAmount=1, gainPerGloveExplosion=10, overloadDelayTicks=20, overloadExplosionRadius=5.5,
  overloadBlockBreakRadius=4.0, overloadDamage=28.0, overloadKnockback=2.0, overloadKillsSelf=true,
  overloadTotemDamageMultiplier=0.5}` and `classes.blastborn.selfKnockbackMultiplier=2.25`.

### Blast Gloves (`gloves`) — `LEATHER_HORSE_ARMOR` (F toggles mode)
- **Wall Blast** (mode A): RMB at a wall (≤4 blocks) → ricochet — knocks **everyone incl. self**
  away (self ×2.25 for mobility), no damage, no block break. CD ~0.4s.
- **Air Burst** (mode B): RMB → blast 3 blocks ahead (needs a clear air path) — damages enemies
  **and the caster** (self ×0.75), launches self for flight. CD ~0.4s.
- Config: `classes.blastborn.gloves.{switchWithF=true, wallBlast.*, airBurst.*}` (both cooldownTicks=8).

### Impact Grenade (`grenade`) — `FIRE_CHARGE`
- RMB → ~1s pin-pull (sound + slowness) → thrown snowball that detonates on impact: breaks blocks,
  **12 dmg**, knockback. Self-cleans (no lobby trail/detonation). CD **30s**. Config:
  `classes.blastborn.grenade.{cooldownTicks=600, primeTicks=20, projectileSpeed=1.9, explosionRadius=4.0, blockBreakRadius=3.0, damage=12.0, knockback=1.5, friendlyFire=false, selfDestructionGain=0}`.

### Sweat Machine Gun (`machinegun`) — `BLAZE_POWDER`
- RMB → a burst of **20** grenade "bullets" that fly **straight like fireballs** (gravity off) with
  a little spread; each explodes for **6 dmg** (~half a grenade) and a weaker blast. Bullets expire
  after ~2s; never detonate in the lobby. CD **50s**. Config:
  `classes.blastborn.machineGun.{count=20, fireIntervalTicks=2, projectileSpeed=2.2, spread=0.12, explosionRadius=2.5, damage=6.0, knockback=0.9, cooldownTicks=1000, maxLifeTicks=40, breakBlocks=false, selfDestructionGain=0, allowDuringUltimate=true}`.

### Phoenix Detonator (`ult`) — `NETHER_STAR`
- RMB → 10s "phoenix" mode (Variant A: the player *is* the phoenix), bossbar countdown, ticking.
  Glove blasts still work but build no meter; the grenade is blocked. After 10s: a big blast at your
  current spot (**24 dmg**, knockback **3.5**, radius **8**, no-drop block break) → rewind to the
  original spot (safe-teleport). CD **2 min**. Config:
  `classes.blastborn.ultimate.{cooldownTicks=2400, durationTicks=200, finalExplosionRadius=8.0, blockBreakRadius=5.0, damage=24.0, knockback=3.5, handBlastGainDuringUltimate=0, allowGrenadeDuringUltimate=false, returnToOriginalLocation=true}`.

**TODO hooks left in code:** `BlastbornManager.coolDownOverheat(player, amount)` for ice/water
dousing; a region/protection check inside `ExplosionUtil.breakBlocksSafely` (it currently breaks any
non-bedrock/unbreakable block in radius).

---

## 3. Ishigava — Mirror Clones (new) + ability tuning

### Mirror Clones (`clones`) — `LIGHT_GRAY_DYE` "Mirror Clones" — **requires Citizens**
- RMB → two Citizens clones flank you (slots 1=left, 2=centre, 3=right; you start in slot 2) and
  **mirror your movement, facing and held item 1-to-1**. Clones use the **`_Heugo`** skin but your
  **nick** and are on your **scoreboard team**.
- **F** cycles a target slot (1·2·3, shown on the action bar); **RMB (the item) confirms** the swap
  — you teleport-swap places with that clone (mind-game for enemies). Confirming your own slot is a
  **feint** (no move). A **fallen** clone's slot can't be swapped into.
- Clones have **~3 hearts**; only enemies can hurt them (same team = no friendly fire). **Slot 2 is
  always the formation centre — the Aura ult emanates from there** (`IshigavaCloneListener.auraCenter`).
- Duration **40s**, cooldown **2 min**. Config: `ishigava.clones.{durationSeconds=40,
  cooldownSeconds=120, cloneHealth=6.0, spacing=2.6, skinName=_Heugo}`.

### Tuning to existing Ishigava abilities
- **Bridge** (`bridge`): now **5 charges** (place 5 lapis platforms), then a **45s cooldown** that
  refills all 5. Action bar shows `Bridges: N/5` and the countdown. (Constants in
  `IshigavaWaterBridgesListener`, not config.)
- **Last Wall** (`wall`): **3-min cooldown** (per activator), wall **duration ~1 min** (lowering
  delay 30s → 60s), and **all sounds quieter** (bucket 1.0→0.3, warden dig 0.5→0.25, roar 0.5→0.2).
- **Aura** (`aura`): added a **2-min cooldown**. Also fixed so the **particle sphere always shows**
  even without a scoreboard team (previously it bailed right after the sound if you had no team); the
  teammate-invisibility part still only applies when you're on a team.

---

## 4. `/kitgive` additions

- `TimeWalker`: `run`, `slash`, `momentum`, `ult`
- `Blastborn`: `gloves`, `grenade`, `ult`, `machinegun`
- `Ishigava`: …existing… + `clones`
- `BlueRose` (aka `Guardian`/`brg`): `oath`, `ward`, `rosebind`, `petal`, `heritage`, `garden`

Giving any Blastborn item also tags the player `Blastborn` (so the meter shows). Giving any Blue Rose
Guardian item tags the player `BlueRoseGuardian`. Giving Mirror Clones / TimeWalker ult requires
Citizens on the server for the NPC clones to render.

---

## 5. Blue Rose Guardian (legendary Defender / Support / Zone Controller)

Класс на силе живых голубых морозных роз. Не дамагер: выигрывает пространство, защищает флагоносца,
спасает союзников и ломает push контролем зон. Пакет `org.money.money.kits.bluerose`.

### Core — Rose Seed (`RoseSeedService`)
Метки роз. На **враге** — опасная (раскрывается в slow/шипы/бонус-урон), на **союзнике** — защитная
(усиливает лечение от роз, ставится Heritage Bloom'ом). Максимум 1 метка каждого вида на цель;
повторное наложение обновляет/раскрывает. Союзники не получают дебаффов, враги — лечения/щита. Все
семена чистятся при death/quit/спектатор/смене мира/конце игры.

### Passive — Bloodline Bloom
Если рядом (`flagCarrierRange=8`) союзный флагоносец (или сам Guardian с флагом) — розы живут дольше
(`×1.25`), радиус `+0.5`, лечение/щит/emergency `×1.15`, knockback радиус `+0.5`. Детект флага —
best-effort: scoreboard-теги (`flag-carrier-tags`) и/или предмет с `item_model lastwar:banner_*`
(`flag-item-model-prefixes`), настраивается в `config.yml`.

### Abilities
- **Blue Rose Ward** (`ward`, cd 16s): ПКМ по блоку → зона (radius 4, 10s, макс 2). Союзникам heal+
  Resistance+очистка, врагам Slowness/Weakness+Rose Seed. Роза исчезает, если опору сломали.
- **Rosebind** (`rosebind`, cd 11s): ПКМ → линия роз по земле; первый враг — root(1s)+slow+seed+урон;
  на засеянном — раскрытие и +root. Не сквозь стены, не по союзникам. Root = лок горизонтали (камера/
  прыжки живут), не ломает физику.
- **Petal Step** (`petal`, cd 10s): ПКМ → рывок 6.5 (не сквозь стены) + дорожка лепестков сакуры 4s
  (розовая, на уровне ног — зона в один блок высотой): союзникам Speed (+маленький heal при входе),
  врагам slow+seed.
- **Heritage Bloom** (`heritage`, cd 26s): ПКМ по союзнику (или ближайшему перед взглядом; иначе на себя
  слабее) → щит-абсорбция + ally seed. При здоровье < 30% роза раскрывается: heal/щит, отбрасывает+
  замораживает врагов, краткая неуязвимость (одна страховка). Per-target emergency cd.
- **Garden of the First Rose** (`garden`, ult, cd 100s): ПКМ по земле → сад (radius 7.5, 8s): союзникам
  heal+Resistance, флагоносцу Speed, врагам slow+ледяные шипы. **First Rose Salvation**: первый союзник,
  который должен был умереть в саду, остаётся на 1 HP + неуязвимость + отброс/freeze врагов (1 раз).
- **Rose Oath** (`oath`, меч): удар по врагу с активным семенем → раскрытие (slow+шип+бонус-урон),
  внутренний кд на цель. Участие в бою без burst.

### Death-save / урон
`BlueRoseGuardianManager#onDamage` (HIGHEST) делает три вещи: гасит урон в окно неуязвимости;
Heritage emergency при критическом/летальном уроне; Garden First Rose Salvation при летальном. Всё —
через `EntityDamageEvent` (отмена/1 HP), без вмешательства в `PlayerDeathEvent`. VOID спасается только
при `allow-void-save: true`, `/kill` (cause KILL) не спасается.

### Lifecycle
`BlueRoseGuardianManager` — единственный `KitResettable` класса (держит всё «мировое» состояние:
семена, Heritage-розы, активные зоны Ward/Garden/Trail, root/invuln, кулдауны). `resetPlayer` чистит
игрока и как Guardian'а, и как цель. `Main.onDisable` → `blueRose.stop()`. Якоря роз — невидимые
marker-ArmorStand'ы (≤3 на Guardian'а), снимаются вместе с зоной; всё остальное на частицах.

---

## 6. Config map (new sections in `src/main/resources/config.yml`)

- `timewalker.{run,slash,momentum,ult}.*` — TimeWalker (top-level).
- `classes.blastborn.*` — Blastborn (`selfKnockbackMultiplier`, `selfDestruction`, `gloves`,
  `grenade`, `machineGun`, `ultimate`).
- `ishigava.clones.*` — Mirror Clones (the older Ishigava abilities use in-listener constants).
- `classes.blue_rose_guardian.*` — Blue Rose Guardian: **только тогглы** (`death-save-enabled`,
  `allow-void-save`, `self-cast-enabled`, `self-cast-multiplier`) и детект флага
  (`flag-carrier-tags`, `flag-item-model-prefixes`). Весь числовой баланс/кулдауны — в
  `classes.json` (ключ `blue_rose_guardian`), как у всех китов.

---

## 7. Lifecycle / cleanup (all new abilities)

Every stateful listener implements `KitResettable`; `SessionManager` + `KitSession.isInGame`
guards stop abilities, tasks, NPC clones, bossbars and scoreboard tags from leaking into the lobby
(on death/quit/spectator/world-change/`/warriors reset`). `Main.onDisable` calls the per-kit
shutdown hooks (`blastbornManager.stop()`, `blastGun.stop()`, `blastPhoenix.shutdown()`,
`timeWalkerMomentum.stop()`, `timeWalkerUlt.shutdownClones()`, `ishigavaClones.shutdown()`).
