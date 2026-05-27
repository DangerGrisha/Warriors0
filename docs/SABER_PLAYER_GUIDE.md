# Saber Classes - Player Guide

This is the quick gameplay guide for:
- `LightSaber` (SaberLight)
- `DarkSaber` (SaberD)

## 1. How to Get Items

Use `/kitgive` (admin/op with `kits.give`):

- Light:
  - `/kitgive SaberLight excalibur [player]`
  - `/kitgive SaberLight ult [player]`
  - `/kitgive SaberLight soultrades [player]`
  - `/kitgive SaberLight souladd [amount] [player]`
- Dark:
  - `/kitgive SaberD excaliburd [player]`
  - `/kitgive SaberD ultd [player]`
  - `/kitgive SaberD soultrades [player]`
  - `/kitgive SaberD souladd [amount] [player]`

## 2. Tags (Important)

Classes are controlled by scoreboard tags:
- Light class tag: `LightSaber`
- Dark class tag: `DarkSaber`

If your tag is missing, Saber abilities/guard UI won’t work.

## 3. Excalibur / ExcaliburD (Shield-Sword)

### Core combat
- Base damage: `7.0`
- Extra damage per soul: `+0.5`
- Attack speed gets heavier with souls (`-0.1` per soul, min `0.4`)

### Guard (block) system
- While blocking correctly, shield blocks `80%` and takes `20%` through.
- Guard break threshold: `12.0` blocked damage.
- On guard break:
  - stunned for `5s`,
  - then fully restored.
- Guard regen while not stunned: `-1.0` every `2s`.

### Souls
- Gain souls by:
  - Totem pop by Excalibur hit
  - Kill by Excalibur hit
- Souls buff your weapon.

## 4. SoulTrades (Blue Dye)

`SoulTrades` opens `Soul Contracts` menu (works for both `LightSaber` and `DarkSaber`).

Shop:
- Totem: first cost `3`, then `4` always
- Permanent Speed +1: cost `3` (max III)
- Temporary Jump X for `10s`: cost `1`
- Permanent Jump +1: cost `2` (max III)

## 5. Ultimate - Soul Release / Soul ReleaseD

### Cost and timing
- Soul cost: `4`
- Cooldown: `25s`
- Charge timeline:
  - `8.0s`: beam starts rising
  - `9.5s`: beam reaches max + sky flare
  - `14.5s`: dispersal particles from caster
  - `17.5s`: release/fire phase starts

### Aim and movement behavior
- During charge: you can move camera/crosshair.
- When release starts: aim is snapped from your current look direction.
- During beam firing: movement is locked.

### Damage behavior (current)
- Main kill pressure is from moving landmine beam impacts.
- Final explosion is mostly cinematic + knockback (not the main killer anymore).

## 6. Light vs Dark Visual Theme

- Light: classic bright/gold style.
- Dark: black/red “bloody” style (`ExcaliburD`, `Soul ReleaseD`).

