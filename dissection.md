# DronesMC repository dissection

This document is an orientation guide for contributors. It describes the code that is in this repository today, how to build and test it, and the boundaries to preserve when changing it.

## What this project is

DronesMC is a Forge mod for **Minecraft 1.12.2**. It adds one craftable item, `dronesmc:drone`, which deploys a physical, bat-model drone entity. A player can pilot an unoccupied hovering drone from a remote camera, then exit or have it autonomously seek a nearby safe place to land.

The project is intentionally small and has no external mod dependencies beyond Forge. Its current release metadata is `0.1.1-beta`; clients and dedicated servers must use that same version. The supported Forge baseline is `14.23.5.2859` or later within the declared Forge dependency range, and the build is compiled for Java 8.

The repository contains both production code and recent testing evidence. It is not a multi-module build, a Bukkit/Paper plugin, or a client-only mod.

## Repository map

| Location | Purpose |
| --- | --- |
| `build.gradle` | Single-module ForgeGradle build, Forge version/mappings, Java 8 target, and JUnit dependency. |
| `gradle/`, `gradlew*` | Bundled Gradle 4.9 wrapper. Use this wrapper rather than a system Gradle installation. |
| `src/main/java/dev/dronesmc/` | Mod initialization, entity behavior, networking, landing logic, client camera/input, and configuration. |
| `src/main/resources/` | Forge metadata plus the item model, language entries, recipe, and resource-pack metadata. |
| `src/test/java/dev/dronesmc/` | Fast JUnit regression tests for packet encoding/validation, pure movement math, NBT behavior, and client interpolation. |
| `scripts/Build.ps1` | Java-8-enforcing build entry point; can relocate Gradle cache and output directories. |
| `.forgejo/workflows/main.yml`, `scripts/ci/` | Linux CI build, exact-artifact dedicated-server smoke testing, signing and draft-first Forgejo/GitHub release publication. See README for configuration and TESTING for executed evidence. |
| `scripts/Start-TestClient.ps1` | Windows-only launcher for an isolated local Forge test profile backed by an existing Prism Launcher cache. |
| `testing/evidence/` | Captured build logs, JUnit XML, server logs, and mod inventories. These are evidence, not a test harness. |
| `TESTING.md` | Current test status, exact artifact hash, historical evidence, and manual checks still outstanding. |
| `dist/` | Staged compiled distributable; do not mistake it for source or development output. |

Generated directories such as `build/`, `.gradle/`, `run/`, `run-server/`, and `logs/` are local working state. They are not the source of truth and should not be committed unless a task explicitly calls for a captured artifact or evidence update.

## Runtime architecture

### Initialization and registries

`DronesMC.java` is the entry point (`@Mod`). During Forge pre-initialization it:

1. Loads server-side configuration through `DroneConfig`.
2. Registers the control packet as client-to-server and the snapshot packet as server-to-client.
3. Registers `DroneEntity` with mod entity ID `0` and a 256-block tracking range.
4. Delegates side-specific setup to `CommonProxy` or `ClientProxy`.

The static `DRONE` item is registered through the Forge item-registry event. Keep client-only references out of this common entry point: dedicated servers must be able to load it without client rendering or input classes.

### Domain model and lifecycle

`DroneItem` creates a `DroneEntity` on the server when the player uses the item on a valid adjacent block. It validates edit permission, loaded terrain, liquid/collision/entity overlap, copies an anvil name to the entity, and consumes the item outside Creative mode.

`DroneEntity` is the core model. It is a non-living, no-gravity entity with a 0.5 by 0.5 bounding box and these synchronized states:

| State | Meaning |
| --- | --- |
| `IDLE` | Hovering and available to be piloted. |
| `PILOTED` | Controlled by one current player. |
| `RETURNING` | Following a planned safe route toward a landing approach point. |
| `DESCENDING` | Moving from that approach point down to the selected landing position. |
| `LANDED` | Resting after a successful autonomous landing. |

There is no owner field or deployment quota. A drone tracks only its current pilot/operator while it is in use; any eligible player can pilot an idle drone. Positive accepted damage posts `DroneDamageEvent` first, then drops exactly one recovery item and removes the entity. Custom names round-trip between item and entity.

Piloting anchors the player at their original position. Server tick handling keeps their body stationary and validates that the drone remains in loaded, in-bounds terrain that is within the player’s practical view-distance area. Death, logout, world unload, and relevant chunk unload queue a safe landing rather than retaining a player session.

### Server authority, packets, and prediction

The network channel has two messages:

| Packet | Direction | Responsibility |
| --- | --- | --- |
| `ControlPacket` | Client → server | Input axes, view angles, sprint flag, action, sequence number, session ID, and target entity ID. |
| `DroneSnapshot` | Server → controlling client | Authoritative position, acknowledged control sequence, session/pilot IDs, and configured flight speeds. |

`ControlPacket.Handler` validates primitive values before scheduling work on the server world thread. `DroneEntity.control` then confirms the sending player is the active pilot, validates the session, rejects stale sequence values, bounds its queue, and applies at most one accepted movement step per server tick. The client never sends a position.

`ClientProxy` samples local input, moves the local visual drone predictively, queues no more than 32 predicted controls, and reconciles each server snapshot by discarding acknowledged commands and replaying the remaining ones. This makes control responsive without making the client authoritative. Do not bypass packet/session/sequence validation to simplify a feature: those checks are the principal movement-abuse boundary.

Actions `0`, `1`, and `2` are movement, exit, and return-and-land. Actions `3` and `4` support selecting/switching among available drones. The client binds `R` for exiting/toggling, `Ctrl+R` for cycling loaded eligible drones, and `Ctrl+E` for return-and-land.

### Camera and rendering

`ClientProxy` is isolated behind the `@SidedProxy` boundary. It registers:

- the item model (`dronesmc:drone#inventory`);
- a renderer that uses vanilla’s bat texture/model but never spawns a bat;
- the `R` key binding and Forge input/render events;
- camera switching, suppression of normal player movement/mouse/hand interactions while piloting, and snapshot reconciliation.

While piloting, the render view entity becomes the drone. The proxy explicitly renders the stationary local player body during world rendering, with normal lightmap state restored afterward. Remote/uncontrolled drones interpolate server position/rotation updates; the locally controlled drone uses prediction instead.

### Landing and loaded-world safety

`LandingPlanner` is deliberately conservative. It searches six-neighbor air positions toward a safe landing surface and never requests chunk generation or loading. A valid landing needs a full solid top face, clear drone space, and no nearby liquid, fire, cactus, or magma. The planner first searches around the player anchor using the configured radius; if that fails, it performs a bounded fallback near the drone.

The route search is bounded by a maximum node count and spatial limits, and movement is collision checked. If a route cannot be found or becomes unsafe, the drone remains hovering and informs the last operator when available. Pending landings persist through NBT save/reload and resume only once their world/chunks are available.

This behavior is a safety guarantee, not a promise that every terrain layout can be landed in. Preserve the no-force-load rule when modifying pathfinding or recovery behavior.

### Configuration and content

`DroneConfig` creates/updates Forge’s `config/dronesmc.cfg`. All values are server-side:

| Key | Default | Allowed range |
| --- | ---: | ---: |
| `flight.normalSpeed` | 0.55 blocks/tick | 0.05–1.0 |
| `flight.sprintSpeed` | 1.1 blocks/tick | 0.05–2.0 |
| `landing.landingRadius` | 3 blocks | 1–16 |

Changes require restart. Existing config values are intentionally preserved, so older installations retain their former speeds until changed manually.

The resource files define the recipe, item model, English labels, `mcmod.info`, and `pack.mcmeta`. The current visual intentionally reuses vanilla’s compass icon and bat model/texture; adding custom art would require replacing the relevant asset/model references rather than changing entity behavior.

## Build environment

Use a **Java 8 JDK**, not merely a JRE. ForgeGradle 3.0.197 and the Gradle 4.9 wrapper are legacy tooling and expect that runtime. `scripts/Build.ps1` fails early unless its selected Java reports `1.8.*`, preventing accidental builds with a newer JDK found on `PATH`.

From PowerShell:

```powershell
.\scripts\Build.ps1 -JavaHome 'C:\path\to\jdk8'
```

The script runs `gradlew.bat build`, which compiles, runs JUnit, produces the normal JAR, and finalizes it through `reobfJar`. The distributable is normally:

```text
build/libs/dronesmc-0.1.1-beta.jar
```

For constrained system drives, both large Gradle cache state and build output can be relocated without changing repository configuration:

```powershell
.\scripts\Build.ps1 -JavaHome 'C:\path\to\jdk8' `
  -CacheDirectory 'F:\dronesmc-cache' `
  -BuildDirectory 'F:\dronesmc-build'
```

The build directory override is passed through `DRONESMC_BUILD_DIR`; the cache override is passed through `GRADLE_USER_HOME`. The script saves and restores the caller’s environment variables, so repeated runs are safe.

Distribute the reobfuscated JAR only. Do not distribute development/mapped classes or assume a successful `compileJava` is equivalent to a packaged mod build.

## Tests and validation

### Automated checks

The JUnit suite currently covers:

- `FlightMath`: normalization, yaw-relative forward/strafe movement, hovering, defaults, and malformed controls.
- `ControlPacket`: byte encoding/decoding plus sequence/action validation.
- `DroneEntity`: client interpolation and NBT persistence invariants that do not need a full world.

Run it as part of the normal build, or directly with a known Java 8 JDK:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk8'
.\gradlew.bat test
```

These tests do not instantiate a Forge server/client world, render OpenGL, validate recipe behavior, or prove multiplayer behavior. Keep new logic testable at the same boundary where practical; use world-level or rendering validation for changes that cannot be expressed in the existing unit fixtures.

### Isolated manual client testing

`Start-TestClient.ps1` launches a disposable profile using cached Prism Launcher metadata, libraries, assets, and native libraries for the pinned Minecraft/Forge version. It verifies cached library hashes, mutes only that test profile, and does not read account credentials. Point `-Instance` at a dedicated disposable directory, never a personal instance.

```powershell
.\scripts\Start-TestClient.ps1 `
  -Instance 'F:\dronesmc-testing\client' `
  -JavaHome 'C:\path\to\jdk8'
```

By default it installs the built JAR. Use `-Baseline` only with a clean instance when you need to launch without DronesMC, and use `-Server`/`-Port` for a local server connection. The script assumes Prism Launcher already has the matching Minecraft 1.12.2, Forge 14.23.5.2859, and LWJGL metadata/cache; it intentionally does not download or authenticate anything.

### Evidence and current limits

Read `TESTING.md` before calling a change “tested.” It distinguishes the current Forge 2859 artifact from historical Forge 2860 results. At the time of writing, the current artifact has a successful reobfuscated build and 14 passing automated tests, while several important runtime checks remain manual/not tested: exact-artifact client world entry, full supplied-modpack client compatibility, multiplayer synchronization, player-body visual correctness, disconnect/recovery behavior, and hazardous-terrain landing.

When adding or changing a feature, update evidence only for checks actually executed. Record the artifact/version, environment, command or setup, expected behavior, observed outcome, and retained log location. Do not promote an earlier build’s result to a new artifact.

## Contributor workflow

1. Read `README.md` for player-facing behavior and `TESTING.md` for the current validation status.
2. Check `git status` before working. Build products, test instances, and unrelated in-progress changes may already be present; preserve them.
3. Identify the correct boundary: common/server entity logic, client-only presentation/input, packet contract, config/content, or test/evidence.
4. Make the smallest compatible change. Keep authoritative game decisions on the server and client-only classes behind `ClientProxy`.
5. Build with the Java 8 wrapper script. Stop and diagnose dependency, Java-version, or reobfuscation failures before continuing.
6. Add or update focused regression tests where the existing JUnit setup can cover the changed invariant.
7. Perform the appropriate isolated manual check for world, networking, or rendering behavior; record its real status in `TESTING.md` if the task includes validation documentation.

## Change guide

| If you are changing… | Start with… | Also consider… |
| --- | --- | --- |
| Drone motion, collision, state, damage, save data | `DroneEntity.java` | `FlightMath`, `LandingPlanner`, `DroneEvents`, NBT compatibility, server authority, and tests. |
| Client controls, camera, local body rendering, or prediction | `ClientProxy.java` | Packet action/session semantics, rendering state restoration, and dedicated-server classloading. |
| Packet fields or behavior | `ControlPacket.java`, `DroneSnapshot.java`, `DronesMC.java` | Both serializers, registration direction/discriminator, validation, replay/ack logic, and compatibility. |
| Landing/recovery rules | `LandingPlanner.java`, `DroneEntity.java` | Loaded-chunk guarantees, hazards, bounded search costs, persistence, failure behavior, and manual terrain tests. |
| Speeds or configuration | `DroneConfig.java` | Default migration behavior, client snapshot values, README/TESTING documentation, and bounds. |
| Item/recipe/name/model/text | `DroneItem.java` and `src/main/resources/assets/dronesmc/` | Registry names, language keys, player-facing README wording, and in-game resource loading. |
| Protection-mod integration | `DroneDamageEvent.java` | Keep it cancellable and remember drones are not `EntityLiving` instances. |

## Constraints worth preserving

- Java 8 bytecode and Minecraft 1.12.2 / Forge 14.23.5.2859 compatibility are intentional constraints.
- Both client and server need the same compatible DronesMC JAR.
- Server-side simulation remains authoritative; clients may predict but never supply positions.
- No player owns a drone permanently, and no per-player deployment ledger exists.
- Drones must not force-load chunks or generate terrain as part of normal flight or landing.
- Unsafe or unloaded terrain should fail safe by leaving the drone hovering rather than teleporting it, clipping it, or landing it on hazards.
- Dedicated-server startup must never load client rendering, LWJGL input, or `net.minecraft.client` classes.
- Existing user configuration must not be silently overwritten.

## Known gaps and non-goals

The repository now includes CI release automation and a dedicated-server startup smoke harness. It still has no gameplay integration tests or completed runtime multiplayer test suite. The beta has no custom drone textures/model, battery, durability, health pool, AI, chunk loading, or guaranteed recovery route. These are not accidental omissions to “fix” as part of unrelated maintenance.

Future work should be scoped explicitly and should preserve the existing authority and loaded-world safety model unless the change is deliberately redesigning those guarantees.
