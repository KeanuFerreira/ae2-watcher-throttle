# AE2 Watcher Throttle

A small server-side Forge 1.20.1 mod for Applied Energistics 2 15.4.x.

## The problem

While a network has any **storage watcher**, AE2 rebuilds that network's complete item list at the end of
**every tick** (`StorageService.onServerEndTick` → `updateCachedStacks`). Watchers are:
- AE2: Level Emitter, Storage Monitor, Conversion Monitor
- ExtendedAE: Threshold Level Emitter
- CrazyAE2 Addons: Multi Level Emitter, Tag Level Emitter
- ME Requester

On a large network this means asking every storage cell for its full contents 20 times a second. On the
Project Infinity server it was about 8% of tick time (~4 ms per tick).

## What this mod changes

The rebuild for watchers runs every **N ticks** (default 10, i.e. twice a second) instead of every tick.
- **Skipped ticks:** the cache is only marked stale, exactly as AE2 does for networks without watchers. Terminals, crafting calculations and anything else that reads the cached inventory still get fresh amounts on demand.
- **Spread out:** each network starts at a different offset, so large networks don't all rebuild on the same tick.

Effect on watchers: Level Emitters, monitors and ME Requesters react up to N ticks later. ME Requester
already keeps track of items it inserted itself until its next update, so it doesn't re-request crafts
because of the delay.

## Install

Server only: put `ae2watcherthrottle-1.0.0.jar` in the server's `mods/`. Clients don't need it.

`config/ae2watcherthrottle-common.toml`:

```toml
# 1 = AE2's default behaviour (every tick)
watcherUpdateInterval = 10
```

To undo, set it to 1 or remove the jar.

## Tests

`testharness/run_tests.py` runs a real Forge 1.20.1-47.4.20 server with AE2 15.4.10. The test network is a drive with a 1k cell
and a Level Emitter watching diamonds. A test-only mixin counts every list rebuild.

```
PASS interval 1: emitter is on the drive's network  [dump nodes=4 cell0=1 BasicStorageCell[item_storage_cell_1k] status=EMPTY keys=0 diamonds=0 cached=0 active=true online=true]
interval 1: rebuilds/tick with watcher 0.995, without 0.000; emitter on [2.0, 2.0, 2.0, 2.0, 2.0] off [1.0, 1.0, 1.0, 1.0, 1.0] ticks; fresh before=0 after=5
PASS interval 1: AE2 behaviour, one rebuild per tick  [0.995]
PASS interval 1: emitter reacts within 1-2 ticks  [[2.0, 2.0, 2.0, 2.0, 2.0] [1.0, 1.0, 1.0, 1.0, 1.0]]
PASS interval 10: emitter is on the drive's network  [dump nodes=4 cell0=1 BasicStorageCell[item_storage_cell_1k] status=EMPTY keys=0 diamonds=0 cached=0 active=true online=true]
interval 10: rebuilds/tick with watcher 0.100, without 0.000; emitter on [9.0, 10.0, 10.0, 10.0, 10.0] off [10.0, 10.0, 10.0, 10.0, 10.0] ticks; fresh before=0 after=5
PASS interval 10: one rebuild per 10 ticks  [0.100]
PASS interval 10: emitter reacts within 10 ticks  [[9.0, 10.0, 10.0, 10.0, 10.0] [10.0, 10.0, 10.0, 10.0, 10.0]]
PASS interval 10: cached inventory fresh on demand  [fresh before=0 after=5]
PASS no watchers: no per-tick rebuilds (both)  [0.000 0.000]
8 checks, 0 failed
```

- **Cache check:** "fresh" inserts 5 diamonds and reads the cached inventory 2 ticks later, inside a 10-tick window.
- **Full pack:** the same checks on the full Project Infinity server mod set (351 mods, including NeoECO and Molecular Manipulator, which also patch AE2 storage) gave 0.100 rebuilds per tick, the emitter reacting in 9–10 ticks, fresh reads, and no mixin errors.

## Building

Copy the jars listed in `libs/README.md` into `libs/`, then:

```bash
./gradlew :forge:build
```

The jar is written to `forge/build/libs/`. Building needs JDK 17.
