# LastWarriors0 - Project Documentation

## 1. Overview
`LastWarriors0` is a Paper Minecraft plugin (API `1.21`) that implements multiple hero/class kits with custom ability items.

Main characteristics:
- Ability-driven PvP classes (Ganyu, HuTao, DIO, Uraraka, Naruto, BurgerMaster, AirWalker, SaberLight).
- Abilities are mostly triggered by right-clicking tagged items (PDC keys).
- Team-aware damage/targeting in many skills (scoreboard teams).
- Shared elemental reaction system (`Pyro`, `Cryo`, `Hydro`, `Electro`).

## 2. Tech Stack
- Java 21
- Gradle
- Paper API `1.21.8-R0.1-SNAPSHOT`
- Adventure API

Build file: `build.gradle`

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
- `AirWalker`: `wind`, `windult`, `windsword`, `windinvis`
- `SaberLight`: `excalibur`, `ult`, `soultrades`

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
  - Right-click charge workflow with temporary charged core item.
  - Scaled damage/power with caps.
  - Cooldown return: ~90s.
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
  - Multiple burger types with cook timers (~60s) and output slots.
- `garden`:
  - Placeable garden platform utility.
  - Return delay ~150s.
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

## 7. Source Structure

Top-level packages:
- `org.money.money` - bootstrap (`Main`)
- `org.money.money.combat` - shared combat systems
- `org.money.money.kits.*` - per-class ability listeners

Important class map:
- `kits/ganyu/listeners/*`
- `kits/hutao/*`
- `kits/dio/*`
- `kits/uraraka/*`
- `kits/naruto/*`
- `kits/burgerMaster/*`
- `kits/airwalker/*`
- `kits/saberL/*`

## 8. Notes / Current Gaps
- `plugin.yml` command description still mentions only a subset of heroes.
- Tab completion in `KitGiveCommand` is incomplete for some newer subcommands (for example AirWalker sub-list in `args.length == 2` is not fully populated, SaberLight completion only exposes `excalibur`).
- Some comments and numeric comments in source are inconsistent with exact HP/hearts wording; the runtime constants are the source of truth.

