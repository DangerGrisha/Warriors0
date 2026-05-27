# Saber Technical Reference

Internal maintainer/dev reference for Saber systems.

## 1. Main Files

- Light sword/guard: `src/main/java/org/money/money/kits/saberL/SaberLightExcaliburListener.java`
- Light ult: `src/main/java/org/money/money/kits/saberL/SaberLightUltimateListener.java`
- Dark sword/guard: `src/main/java/org/money/money/kits/saberD/SaberDarkExcaliburListener.java`
- Dark ult: `src/main/java/org/money/money/kits/saberD/SaberDarkUltimateListener.java`
- SoulTrades GUI: `src/main/java/org/money/money/kits/saberL/SaberLightSoulTradesListener.java`
- Command wiring: `src/main/java/org/money/money/kits/ganyu/give/KitGiveCommand.java`
- Bootstrap wiring: `src/main/java/org/money/money/Main.java`

## 2. Class/Tag Gates

- Light class gate: `LightSaber`
- Dark class gate: `DarkSaber`
- SoulTrades gate accepts both tags.

## 3. Item IDs / Names

### Light
- Excalibur item PDC:
  - `saberlight_excalibur`
  - `saberlight_excalibur_souls`
- Ult item display name: `Soul Release`

### Dark
- Excalibur item PDC:
  - `saberd_excalibur`
  - `saberd_excalibur_souls`
- Ult item display name: `Soul ReleaseD`

## 4. Excalibur Tunables (Light + Dark)

From both sword listeners:
- `BASE_DAMAGE = 7.0`
- `BASE_ATTACK_SPEED = 1.6`
- `DAMAGE_PER_SOUL = 0.5`
- `HEAVY_PER_SOUL = 0.1`
- `MIN_ATTACK_SPEED = 0.4`
- `BLOCK_PERCENT = 0.80`
- `GUARD_BREAK_THRESHOLD = 12.0`
- `GUARD_BREAK_TICKS = 20*5`
- `GUARD_REGEN_PERIOD_TICKS = 20*2`
- `GUARD_REGEN_AMOUNT = 1.0`
- `LAST_HIT_WINDOW_MS = 4000`

Guard UI is actionbar-based and only shown for correct class tag.

## 5. Ultimate Tunables (Light + Dark)

From both ult listeners:
- `SOUL_COST = 4`
- `ULT_COOLDOWN_MS = 25_000`
- `CAST_LOCK_TICKS = 350`
- Timeline:
  - `PHASE_BEAM_START_TICKS = 160`
  - `PHASE_BEAM_MAX_TICKS = 190`
  - `PHASE_DISPERSAL_START_TICKS = 290`
- Beam:
  - `BEAM_MAX_RANGE = 111`
  - `BEAM_BACK_DISTANCE = 14.0`
  - `BEAM_STEP_PER_TICK = 0.85`
  - `BEAM_CORE_RADIUS = 3`
  - `BEAM_WAVE_RADIUS = 3.2`
  - `BEAM_HIT_RADIUS = 3.0`
- Phase 4 shaping:
  - `PHASE4_BLAST_SIZE = 1.4`
  - `PILLAR_HEIGHT_MIN = 24.0`
  - `PILLAR_HEIGHT_MAX = 46.0`
  - `PILLAR_WIDTH_BASE = 1.4`
  - `PILLAR_WIDTH_PULSE = 0.35`
  - `BEAM_SAFE_START_DISTANCE = 6.0`

## 6. Aim/Lock Flow (Current)

Current behavior:
1. Charge phase allows free look/movement.
2. On release start, ult snapshots current eye yaw/pitch/direction/target.
3. During firing phase only, movement is locked (`firingPhase` set).

Implementation notes:
- `activeCasts` stores cast state.
- `firingPhase` controls whether `onMoveFreeze` blocks movement.
- `captureCurrentCastState(...)` is called right before `runExcaliburBeam(...)`.

## 7. Damage Model (Current)

### Beam path / landmine damage
- Done in `damageEntitiesInSlashBeam(...)`.
- Applies per-contact damage with `setNoDamageTicks(0)` before hit.
- Knockback intentionally reduced so victims stay in danger area longer.

### Final explosion
- Final blast remains visual/destruction + knockback.
- Lethal finisher damage was removed by design.

## 8. SoulTrades Integration

SoulTrades reads/writes souls via hook in `Main.java`.

Hook behavior:
- Finds first matching sword in inventory:
  - Light Excalibur first, then Dark Excalibur.
- `getSouls` and `setSouls` route to the matching listener API.

`/kitgive ... souladd` also supports both sword types via shared helper logic.

## 9. Known Risks / Cleanup TODO

- ULT files still contain verbose debug messages (`[ULT-DBG]`) that can spam chat.
- `FINAL_ONESHOT_RADIUS` constant still exists but is currently unused in final damage flow.
- Some comments/labels still reference older naming (`SaberLight/SaberD`) while runtime gates are now `LightSaber/DarkSaber`.
- Optional improvement: split shared ult logic into a common base class to avoid light/dark drift.

