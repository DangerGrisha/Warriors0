# LastWarriors0 - Project Documentation

## 1. Overview
`LastWarriors0` is a Paper Minecraft plugin (API `1.21`) that implements multiple hero/class kits with custom ability items.

Main characteristics:
- Ability-driven PvP classes:
  - Native: Ganyu, HuTao, DIO, Uraraka, Naruto, BurgerMaster, AirWalker, HaoHao, Opera, SaberLight, SaberDark.
  - Migrated from `Last_Warriors`: Fukuko, LadyNagan, Saske, Ishigava.
  - New (June 2026): TimeWalker, Blastborn, and Ishigava "Mirror Clones" — see `docs/NEW_KITS.md`.
- Abilities are triggered by right/left-clicking tagged items (PDC keys) or matching display names.
- Team-aware damage/targeting in many skills (scoreboard teams).
- Shared elemental reaction system (`Pyro`, `Cryo`, `Hydro`, `Electro`).

### Related documentation
Per-character ability detail (item, activation, exact damage/cooldown/radius numbers) lives in:
- `docs/NATIVE_CHARACTERS.md` — native characters (incl. HaoHao, Opera, SaberDark).
- `docs/MIGRATED_CHARACTERS.md` — the 4 characters migrated from `Last_Warriors`.
- `docs/SABER_PLAYER_GUIDE.md`, `docs/SABER_TECHNICAL_REFERENCE.md` — Saber deep-dive.
- `docs/NEW_KITS.md` — TimeWalker, Blastborn, Ishigava Mirror Clones + the Citizens NPC integration (June 2026).

This file (`PROJECT_DOCUMENTATION.md`) is the high-level index; the two `*_CHARACTERS.md` files are the source of truth for ability numbers.

## 2. Tech Stack
- Java 21
- Gradle (wrapper `./gradlew`)
- Paper API `1.21.8-R0.1-SNAPSHOT`
- Adventure API
- ProtocolLib `5.3.0` (`compileOnly`) — declared for future use; currently unused at compile time, installed on the test server.
- Citizens `2.0.42-SNAPSHOT` (`compileOnly`, `transitive=false`, repo `maven.citizensnpcs.co`) — soft-depend; powers the TimeWalker ult and Ishigava Mirror Clones NPC clones. See `docs/NEW_KITS.md` §0.

Build file: `build.gradle`

### Build & deploy
- `./gradlew build` — compiles, builds the jar, and copies it to the test server's plugins folder
  (`/Users/admin/Desktop/Lw0Coding/LastWar0/plugins`) via the `copyToServer` task.
- `./gradlew clean` — also removes `warriors0-*.jar` from that plugins folder.
- The copy is a single-file copy (not a Gradle `Copy` task) to avoid scanning the server's unreadable runtime files.

## 3. Plugin Entry and Wiring
- Plugin entry: `org.money.money.Main`
- Descriptor: `src/main/resources/plugin.yml`
- `Main` creates and registers all listeners, then registers `/kitgive` executor + tab completer.

Key files:
- `src/main/java/org/money/money/Main.java`
- `src/main/resources/plugin.yml`
- `src/main/java/org/money/money/kits/ganyu/give/KitGiveCommand.java`

## 4. Commands and Permissions

### `/kitgive`
Gives class items to self or target.

Permission:
- `kits.give` (default: op)

General pattern:
- `/kitgive <Hero> <subitem> [player]`

Supported heroes/subitems in code:
- `Ganyu`: `bow`, `bud`, `ult`
- `HuTao`: `homa`, `pyro`, `ult`
- `Dio`: `hand`, `timestop`, `vampire`
- `Uraraka`: `glove`, `gravity`, `healpost`, `levmark`
- `Naruto`: `rasengan`, `disappear`, `clones`
- `BurgerMaster`: `grill`, `garden`, `hungry`, `sword`
- `AirWalker`: `wind`, `windult`, `windsword`, `windinvis` (`invis`)
- `HaoHao`: `swordshield` (`shield`/`sword`), `mask`
- `Opera`: `transformation`, `aura`
- `SaberLight`: `excalibur`, `ult`, `soultrades`, `souladd`
- `SaberD` (SaberDark): `excaliburd`, `ultd`, `soultrades`, `souladd`
- `Fukuko` *(migrated)*: `pistol`, `mortira`, `bombzone`
- `LadyNagan` *(migrated)*: `sniper`, `ult`, `fly`, `trap`, `explosion`
- `Saske` *(migrated)*: `katana`, `shuriken`, `body`, `chidori`, `attraction`
- `Ishigava` *(migrated)*: `shield`, `bridge`, `wall`, `aura`, `kunai`, `clones`
- `TimeWalker`: `run`, `slash`, `momentum`, `ult` — see `docs/NEW_KITS.md`
- `Blastborn`: `gloves`, `grenade`, `ult`, `machinegun` — see `docs/NEW_KITS.md`

## 5. Elemental Reactions
Implemented in `ElementalReactions`:

- `Pyro` hit on `Cryo` aura: x2.0
- `Cryo` hit on `Pyro` aura: x1.5
- `Hydro` hit on `Pyro` aura: x2.0
- `Pyro` hit on `Hydro` aura: x1.5
- `Pyro/Cryo/Hydro` hit on `Electro` aura: x1.5
- `Electro` hit on `Pyro/Cryo/Hydro` aura: x1.3

System behavior:
- Applies/removes scoreboard tags (`Pyro`, `Cryo`, `Hydro`, `Electro`).
- Can consume aura on reaction or re-apply/refresh aura if no reaction.

## 6. Kit Documentation

> **New kits (TimeWalker, Blastborn) and Ishigava's Mirror Clones are documented in full in
> `docs/NEW_KITS.md`** (items, abilities, exact cooldowns/damage, config keys, Citizens integration).

### Ganyu
- `Amos' Bow` (`bow`):
  - Charged shot mechanic (~3s hold), icy arrow tag.
  - On hit: Cryo AoE, freeze and slowness, direct-hit bonus freeze.
- `Frostbud` (`bud`):
  - Places cryo bud stand with pulse effect.
  - Creates ring-based AoE control and temporary linked `View Changer` item.
  - Cooldown return: ~80s.
- `Everfrost Core` (`ult`):
  - Casts long-lived sphere/zone ultimate.
  - Periodic falling ice hitters with AoE Cryo damage/control.
  - Cooldown: ~150s.

### HuTao
- `Staff of Homa` (`homa`):
  - Invisibility-form style swap flow (weapon/removal item/totem handling).
- `Pyro Status` (`pyro`):
  - Right-click empowers for ~10s.
  - Adds Pyro interactions and fire-based bonus behavior.
  - Cooldown return: ~60s.
- `BOOM` (`ult`):
  - Delayed blast (fuse ~2s), AoE explosion effect.
  - Cooldown return: ~120s.

### DIO
- `hand`:
  - Stand-style hand combat behavior with dash/anchor/punch loops.
  - Team checks for enemy targeting.
  - Plain melee hit with the hand sword is capped to `3.0` (was vanilla diamond-sword `7`).
- `TIME_STOP`:
  - Windup ~3.9s, freeze duration ~5s.
  - Blindness/darkness/flicker effects on targets.
  - Cooldown return: ~2 min.
- `Vampire`:
  - Windup ~3s, form duration ~40s.
  - Lifesteal + temporary max HP increase.
  - Cooldown return: ~3 min.

### Uraraka
- `Hammer` (`glove`):
  - Crit-based short stun/slowness when close range conditions are met.
- `gravity`:
  - Windup ~3s, then ~10s armed window.
  - Applies levitation to targets on hit interactions.
  - Cancel item `CancelGravity` ends state.
  - Cooldown return: ~2 min.
- `Heal Post`:
  - Placeable post (red glazed terracotta).
  - Heals teammates in radius (~10) periodically (every ~5s).
- `Levitation Mark`:
  - Applies mark/curse workflow with levitation bursts.
  - Cooldown return: ~2 min.

### Naruto
- `Rasengan`:
  - Right-click charge workflow with temporary charged core item (core TTL ~15s).
  - Damage scales with fall height: `10.0 + 1.2 × blocks`, capped at `70`.
  - Cooldown return: ~25s.
- `Disappearance Technique`:
  - Teleport/reposition style ability.
  - Cooldown return: ~60s.
- `clones`:
  - Spawns multiple zombie clones (default count 15).
  - Clone interactions can apply slowness.
  - Cooldown return: ~90s.

### BurgerMaster
- `grill`:
  - Place/open grill station and cooking GUI.
  - Multiple burger types with cook timers (~30s) and output slots.
  - Item returns ~60s after placing (plus on grill destruction).
- `garden`:
  - Placeable garden platform utility.
  - Return delay ~60s.
- `Hungry master` (`hungry`):
  - Activation window to consume burgers, then “beast” buff package.
  - Includes hunger stage then strong timed effects.
- `sword`:
  - Iron sword with Knockback II.

### AirWalker
- `Wind`:
  - Core anchor + gust/knock/utility behavior.
  - Internal anti-spam cooldown and ult-aware ranges.
- `WindUlt`:
  - Ultimate state tag (`WindUlt`) for ~45s.
  - Applies slow falling management while active.
- `Wind Sword`:
  - Projectile/slash-style attack path.
  - Cooldown ~7s, team-aware damage checks.
- `Invis`:
  - Invisibility for ~30s.
  - Ability item returns after ~3 min.

### SaberLight
- `Excalibur`:
  - Shield-based weapon storing `souls` in PDC.
  - Souls scale damage and affect attack speed.
  - Guard/block system with break + regen behavior.
- `Soul Release` (`ult`):
  - Crossbow item for ultimate cast.
  - Costs souls (default 4), has optional cooldown (~25s).
  - Large cinematic multi-phase attack logic.
- `SoulTrades`:
  - Right-click GUI shop for soul-based purchases:
    - Totem, temporary jump boost, permanent speed/jump upgrades, etc.

### SaberDark
- Mechanically identical to SaberLight; black/red "bloody" theme.
- Items: `ExcaliburD` (`excaliburd`), `Soul ReleaseD` (`ultd`).
- Gated by class tag `DarkSaber`; shares `SoulTrades`/`souladd` with Light.

### HaoHao
- `King's Sword` (`swordshield`):
  - Right-click: dash (×1.35) then cone cleave (`6.0` dmg, push `0.8`); dash cooldown ~7s.
  - Shift-right-click toggles to a shield "Hand" mode; absorbing a hit lets you reflect the damage and self-heal 50% (HandG window ~1s, then HandY lockout ~10s).
  - Passive 25% chance to apply Glowing on hit.
- `Mask` (`mask`):
  - Toggle yellow↔green (`MaskGreen` tag); while green, enemy damage between players is reflected back after ~1s.
- Team perks (passive, `HaoHaoPerkListener`, tag `HaoHao`):
  - Within 4s of the king hitting a target: enemy totem-pop → allies get Strength II (10s); king kill → allies Strength III (10s); king death → allies Strength II (30s).

### Opera
- `transformation`:
  - Toggle: spawns a bound mount horse; owner gets Invisibility/Weakness/Jump III/Speed IV until toggled off. Teammates can ride; damage to horse is redirected to owner.
- `Opera Aura` (`aura`):
  - Right-click GUI with modes (Attack→Strength, Formation→Resistance, March→Speed) applied to teammates in radius 10 every 0.5s.
- Passive (tag `Opera`): Speed I to all `Opera`-tagged players every ~2s.

## 6b. Migrated Kit Documentation (Last_Warriors → Warriors0)

Full per-ability numbers in `docs/MIGRATED_CHARACTERS.md`. Summary:

### Fukuko
- `pistol`: left-click crossbow bullet, dmg `1.0`, cooldown 5s (per-player).
- `mortira`: placeable `GOLD_BLOCK` turret; fires every 5s at enemies in radius 30, blast `10.0` (R4).
- `bombzone` (ult): `REDSTONE_BLOCK` zone (R10) raining fireballs on enemies; lasts 20s.

### LadyNagan
- `sniper`: aim (pumpkin) + shoot bullet dmg `8.0`, one-time homing within R10; 4s between shots.
- `ult` (`Ultra Bullet`): empowered shot dmg `19.0`; cooldown `105s`.
- `fly`: air-blocks, 7 charges / 20s, cooldown 40s.
- `trap` (×3): proximity mine dmg `10.0` (R3, power 4); 15s recharge when out of mines.
- `explosion` (`Self-Destruction`): if green, blast R6 dmg `26.0` on death **or when a totem saves her** (no self-damage); cooldown 60s.

### Saske
- `katana`: cone cleave dmg `5.0` + knockback; cooldown 6s.
- `shuriken` (×3): snowball, +7 dmg on hit; 25s recharge → 3 back.
- `body` (Body Replacement): swap places with target ≤40 blocks after ~3.25s; cooldown 70s.
- `chidori`: dash (×20) dmg `19` over the dash; cooldown 50s.
- `attraction` (ult): pull zone R9, dmg `4.0/s`, lasts 15s; cooldown 120s.

### Ishigava (clones not migrated)
- `shield` (`Quick_Wall`): single flying shield / shift = full wall; blocks projectiles; 20s.
- `bridge`: shift to set distance 5–20, places a 3×3 lapis platform; 20s.
- `wall` (`Last Wall`): toggle; raises a chain of up to 12 beacons; lowers after ~30s.
- `aura` (ult): cyan sphere R8, invisibility to teammates; 20s.
- `kunai`: bow-grapple; pull self to stuck point / pull hit player to you; breaks at 15 blocks.

> Migrated characters keep custom resource-pack sounds (`ladynagan.*`, `saske.*`, plus DIO `my_sounds.*`).
> Without the resource pack those actions are silent (mechanics unaffected).

## 7. Source Structure

Top-level packages:
- `org.money.money` - bootstrap (`Main`)
- `org.money.money.combat` - shared combat systems
- `org.money.money.kits.*` - per-class ability listeners

Important class map:
- `kits/ganyu/listeners/*` (+ `kits/ganyu/give/KitGiveCommand.java`)
- `kits/hutao/*`
- `kits/dio/*`
- `kits/uraraka/*`
- `kits/naruto/*`
- `kits/burgerMaster/*`
- `kits/airwalker/*`
- `kits/haohao/*`
- `kits/opera/*`
- `kits/saberL/*`
- `kits/saberD/*`
- `kits/fukuko/*` *(migrated)*
- `kits/ladynagan/*` *(migrated; incl. shared `LadyCooldownManager`, `LadyDyeUtil`)*
- `kits/saske/*` *(migrated; reuses `LadyCooldownManager`)*
- `kits/ishigava/*` *(migrated; clones intentionally omitted)*

## 8. Notes / Current Gaps
- `plugin.yml` `usage` now lists Ganyu, HuTao, DIO and the 4 migrated heroes (Fukuko/LadyNagan/Saske/Ishigava),
  but several native heroes (AirWalker, HaoHao, Opera, Saber*) are still not enumerated there. The `/kitgive`
  command itself works for all heroes regardless of the `usage` text.
- Tab completion in `KitGiveCommand`: Fukuko/LadyNagan/Saske/Ishigava/SaberLight/SaberD are now populated;
  **AirWalker is still missing** from the `args.length == 2` sub-list (see the `// i dont see airwalker here` comment).
- Migrated characters (and DIO) use **custom resource-pack sounds**; without the pack those actions are silent.
- Migrated characters detect items mostly by **display name** (state machines, e.g. LadyNagan sniper crossbow states);
  native characters and Fukuko use **PDC** keys.
- LadyNagan's sniper/ult state is global per listener instance (designed for one LadyNagan at a time) — same as the original.
- `DioStandFollower` is **disabled** (commented out in `Main.java`); DIO's stand is fully handled by `DioHandListener`.
- `LadyCooldownManager.startCooldown(..., showUI=false)` now schedules cooldown removal on expiry (previously the entry was never cleared → permanent cooldown). This fixed Saske Chidori's "infinite cooldown" / no item return and unstuck Attraction, Body Replacement, and the LadyNagan abilities that share the manager.
- Some numeric comments in source are inconsistent with exact HP/hearts wording (e.g. Wind Sword comment says "3 hearts" but deals `12.0`); runtime constants are the source of truth.

