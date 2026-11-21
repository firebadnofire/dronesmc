# DronesMC testing

## Current Forge 2859 distributable

Requested target: Minecraft 1.12.2 with Forge **14.23.5.2859**, Java 8. Both the build dependency and runtime minimum dependency now use 2859. The isolated client launcher uses cached Forge 2859 and sets master volume to 0%.

- Artifact: `dist/dronesmc-0.1.1-beta.jar` (compiled and reobfuscated).
- SHA-256: `bc7a1e31b340ad5b0ad96aeb18f3b2116f5981b51959ee260a4c0bf446c3aa71`.
- Build: **PASS**, including `reobfJar`; see `testing/evidence/forge-2859/gradle-build.log`.
- Automated regression tests: **PASS**, 14 tests, zero failures/errors/skips. Matching JUnit XML is in `testing/evidence/forge-2859`.
- Functional runtime tests on this exact Forge 2859 artifact: **NOT TESTED**. The revised death landing, visible player body, input prediction, R return toggle, Ctrl+R drone selection/chat announcement, and crafting recipe still require gameplay verification.
- Supplied 29-mod client compatibility on this exact artifact: **NOT TESTED**. Startup, actual world entry, and at least 10 consecutive real-time seconds without fatal errors remain required.
- Supplied dedicated-server bundle compatibility on this exact artifact: **NOT TESTED**. Earlier server observations below concern earlier artifacts on Forge 2860.

The user confirmed multiple drone deployment, anvil naming/nameplates, and named one-punch recovery on an earlier review build. Those observations do not establish a pass for this exact artifact. Multiplayer synchronization remains pending user testing.

### Player body lighting correction

The user reported a dark/glitched local player skin from the drone camera. Inspection of the Forge 2859 renderer confirmed that the direct renderEntity call bypassed renderEntityStatic's per-entity lightmap brightness and white tint setup. The late world render now enables standard entity lighting, uses renderEntityStatic, and restores the view entity and lightmap state afterward. The Forge 2859 reobfuscated build and all 12 existing regression tests passed after this change. Visual confirmation in game is **NOT TESTED**; these JUnit tests do not exercise OpenGL. Please verify the local skin and armor in daylight and shade while moving the drone, then exit with R and check the normal view.

### Pilot disconnect landing

Logout now captures the last anchored player position, clears pilot control, and queues autonomous landing. The normal player-relative safe landing is attempted first; if unavailable, a bounded search within 16 horizontal blocks of the drone looks for safe support below/near it. Existing support, hazard, collision, loaded-chunk, and route checks apply to both searches. If no safe loaded route exists, the drone hovers rather than landing on hazards. Pending/active landings are saved with their destination reference and replanned after reload; they do not retain ownership or pilot identity. Unloaded chunks are not forced to tick, so landing resumes when loaded. This does not retroactively change old hovering drones with no saved pending request; pilot one and use Ctrl+E to land it.

Build/reobfuscation and 14 automated tests: **PASS**. Two new checks verify pending landing persistence without a pilot and suppression of stale requests for landed drones. Actual multiplayer disconnect/reconnect and hazardous-terrain landing: **NOT TESTED**. Manual check: pilot over land, disconnect while another player keeps the area loaded, observe landing, then repeat with the last player leaving and returning. Repeat near water/lava to verify safe support selection or safe hovering when no route exists. R should not remotely reactivate a landed drone.

The following report preserves historical evidence. Its Forge 2860 version, artifact hashes, test counts, and runtime results do not describe the current Forge 2859 distributable. References to a final artifact in the historical report mean the artifact at that time.

---
# Historical Forge 2860 testing

Test date: 2026-11-20, America/New_York. Results distinguish minimal-Forge functional testing from supplied-modpack compatibility testing. PASS means an executed check; NOT TESTED includes pending manual verification.

## Environment and artifacts

- Windows 11 amd64; Minecraft 1.12.2; Forge 14.23.5.2860.
- Temurin JDK 8u472 (`1.8.0_472`, 64-bit). The build explicitly selects Java 8; Java 21 on PATH is not used.
- Gradle 4.9 wrapper; ForgeGradle 3.0.197; MCP stable 39 mappings.
- Isolated runtime instances, large caches, and temporary files: `F:\dronesmc-testing`. The repository remains on C:, which had approximately 2 GB free at setup.
- Client assets and verified libraries are read from the existing Prism Launcher cache. A separate local test identity is used; account credentials are not read. Local test clients have master volume set to **0%**.
- Dedicated servers bind only to `127.0.0.1`, retain `online-mode=true`, and use disposable worlds. The user explicitly accepted the Minecraft EULA for these local tests.
- Distributable: `build/libs/dronesmc-0.1.0-beta.jar`.
- SHA-256: `bc0a337dfd3626ec73418ae2669524522f4c03d8c99e66f1bb4b80754d0e8bbf`.

The final artifact is also staged at `F:\dronesmc-testing\build\libs\dronesmc-0.1.0-beta.jar`. The active manual camera-review client initially loaded the preceding camera revision, SHA-256 `62910a51efdf62175aa98d9ae75df165cb83d14f83237ecb9a418e9c342afcf8`. The final artifact additionally prioritizes exit/landing packets over same-tick movement packets and updates the resource description. Runtime evidence is attributed to its tested revision below.

## Build and automated regression tests

| Check | Result | Evidence |
| --- | --- | --- |
| Actual Gradle build, including reobfuscation | PASS | `testing/evidence/gradle-build.log`: `reobfJar`, `BUILD SUCCESSFUL in 23s` |
| JUnit regression checks | PASS | 9 tests, 0 failures/errors; XML in `testing/evidence` |
| Metadata and resources in distributable | PASS | `mcmod.info`, mod ID `dronesmc`, entity/item classes, recipe, language, and model resources present |
| Java 8 bytecode | PASS | Class header `CA FE BA BE 00 00 00 34` (major version 52) |
| Removed ownership ledger absent from JAR | PASS | JAR inventory contains no `DroneLedger` class |

Regression checks cover diagonal/vertical normalization, yaw-relative movement, left/right strafing at eight headings, zero input, invalid control payloads, Creative-scale speed defaults, non-snapping position interpolation, short-path yaw interpolation across ±180°, and saving a custom name without permanent ownership.

Two attempted plain-JUnit checks exposed missing test fixtures: Forge networking requires an active mod loader and full entity NBT loading requires a world. They were diagnosed as fixture limitations; no production workaround was introduced. Anvil-to-recovery and full save/reload behavior require runtime verification.

Reproduce with Java 8:

```powershell
.\scripts\Build.ps1 -CacheDirectory F:\dronesmc-testing\gradle -BuildDirectory F:\dronesmc-testing\build
```

## Minimal Forge functional testing

The client contains only Forge and DronesMC, with the actual reobfuscated JAR in `mods`. No full modpack is required for gameplay checks.

| Check | Result | Execution details |
| --- | --- | --- |
| Client startup and actual world entry | PASS, camera-review revision | Agent observed main menu, entered the disposable Superflat world, and observed initialized terrain/player. DronesMC appears in the client/server handshake. |
| Drone obtainable and deployable | PASS, camera-review revision | `/give @p dronesmc:drone` succeeded; right-click spawned a physical bat-model entity. |
| Visible default label | PASS, camera-review revision | Agent observed “Drone” above the entity. |
| Corrected A/D controls | NOT TESTED in runtime | Automated heading tests pass; manual feedback pending. |
| Smooth camera rotation and translation | NOT TESTED for subjective smoothness | Frame-based camera handling and position interpolation implemented; manual continuous-flight feedback pending. |
| Normal and Ctrl flight speeds | NOT TESTED in runtime | Defaults are 0.55/1.1 blocks per tick; automated scale check passes. Minimal test config explicitly updated from old defaults. |
| Hover, collision/sliding, stationary vulnerable body | NOT TESTED independently after revision | User reported the earlier drone generally behaved normally; this is not counted as individual executed regression checks. |
| R exit and Ctrl+E real return/landing | NOT TESTED after revision | Manual feedback pending. |
| Multiple deployed drones | NOT TESTED in runtime | Deployment reservation code and persistent ownership ledger removed. |
| Anvil name, deployed custom label, named recovery item | NOT TESTED in runtime | Name transfer and recovery preservation implemented; manual feedback pending. |
| One-hit destruction and duplicate-drop protection | NOT TESTED independently after revision | Server implementation guards repeated damage; no standalone runtime pass claimed. |
| Two-player takeover and synchronization | NOT TESTED | No two-client authenticated test executed. |
| Save/reload, disconnect, death, chunk-boundary edge cases | NOT TESTED in runtime | No pass inferred from source inspection. |
| Final-JAR dedicated server startup and loaded-world operation | PASS | Fresh minimal server initialized at 20:01:28; gametime queries returned 343 at 20:01:45 and 784 at 20:02:07. At least 39 seconds of post-readiness operation. |

Minimal dedicated-server evidence (`testing/evidence/minimal-server.log`):

```text
[20:01:27] [Server thread/INFO] [dronesmc]: DronesMC initialized; physical drone entity and control channel registered
[20:01:28] ... Done (0.892s)! For help, type "help" or "?"
[20:01:45] ... Time is 343
[20:02:07] ... Time is 784
```

Dedicated-server success verifies startup without loading client-only implementation classes. It does not establish client rendering or multiplayer gameplay correctness.

## Supplied modpack compatibility testing

### Archive preservation and inventory

| Bundle | Contents | SHA-256 |
| --- | --- | --- |
| `C:\Users\william\Downloads\mods.tzs` | 29 client mod JARs at archive root | `b96857d2400521606494ecfff9e982c2e992911bfbaa44a04d589c4ef80cc9d7` |
| `C:\Users\william\Downloads\server-mods.tzs` | 21 server mod JARs at archive root | `11e38a5bb8893e0a65db9a5b385c1d1f8cbbcba37886940008a905370fdefa5b` |

The client archive has Zstandard magic `28 B5 2F FD`; Windows `tar.exe` successfully listed and extracted its TAR contents despite the `.tzs` extension. Archives were preserved; testing uses separate extracted copies. Neither bundle supplies a launcher profile, Forge version, Java runtime, worlds, or preexisting configuration. Forge 14.23.5.2860 and Java 8 were selected explicitly, and isolated instances generated their own default configurations.

The client bundle contains OptiFine G5, Baritone/ForgeEssentials Mixin tweakers, BetterFps, ConfigAnytime, CarbonConfig, OpenComputers, and Xaero coremod transformations. The server bundle includes MixinBooter, VintageFix, ForgeEssentials, DropsOverhaul, and LoraxCustomTrees. No supplied mod was disabled, removed, or replaced to obtain a pass. Per-JAR inventories/hashes are in `testing/evidence/client-mods.json` and `server-mods.json`.

### Client compatibility — 29 supplied mods plus DronesMC

**NOT TESTED to final acceptance with the final JAR.** An earlier supplied-pack baseline reached the main menu, and an earlier DronesMC build was installed and launched. These preliminary observations do not establish the final-JAR world-entry and consecutive 10-second acceptance interval.

Required remaining evidence: the final reobfuscated artifact installed with all 29 original JARs; successful client startup; confirmed initialized player/world; at least 10 consecutive real-time seconds running after that confirmation; no fatal errors or crash during the interval. Full-pack gameplay checks are not required.

### Dedicated-server compatibility — 21 supplied mods plus DronesMC

**PASS for startup and post-readiness operation with the final JAR.** The fresh `server-pack-revised` instance uses the supplied server bundle intact, plus the final artifact. It loaded its world at 20:01:52 and responded to `list` at 20:02:08, at least 16 seconds afterward. No fatal shutdown or DronesMC exception occurred in this interval. See `testing/evidence/server-modpack.log`.

Observed nonfatal messages include unsigned coremod/missing annotation warnings, Mixin/ForgeEssentials classloader restrictions, missing optional WorldEdit integration, duplicate ForgeEssentials command aliases, metadata warnings in supplied mods, and disabled VintageFix mixin messages. These are reported rather than silently suppressed; startup success does not imply those integrations function correctly.

## Limitations and completion status

- **Project acceptance remains incomplete** until final-JAR client world-entry and 10-second full-pack evidence are recorded.
- Manual functional results must be recorded separately from modpack startup/world-loading compatibility.
- Local unauthenticated clients emit a Realms authorization warning; this is not a world-loading failure. Online-server authentication remains enabled.
- Forge update checks report a newer available Forge release. These runs use the explicitly pinned baseline and do not claim validation on other Forge versions.
- Landing is bounded and conservative; it can fail safely in complex terrain and does not guarantee a route to every reachable surface.
- Network interpolation smooths authoritative updates but is not client movement prediction; latency can still affect response time. Visual smoothness is not proven by unit tests.
- Existing speed configuration is preserved; update old values explicitly to obtain the revised defaults.
- World-level testing is never inferred from a successful build, an idle process, or the main menu alone.

## CI/CD runner preflight — 2026-11-20

This new evidence concerns the CI-built JAR below, not the older `dist/` copy or historical Forge 2860 artifacts. Existing gameplay and client/modpack limitations above remain unchanged.

Plan: validate Java 8 compilation/reobfuscation and unit tests, transfer the exact distributable between jobs, boot minimal Forge 2859, validate signing and publication safeguards without making a public release, and retain actual registered-runner evidence.

Implementation tested: commit `675c34968a9490357da9888618fb8206ff336f91` on isolated branch `codex/ci-preflight`. The temporary `preflight.yml` is the production workflow with its push trigger moved off `main` and the PR trigger removed; the production release condition still requires a push to `refs/heads/main`. No push to `main`, public release, GitHub source synchronization, runner registration/configuration change, or gameplay-code change was performed. The local worktree/index were preserved. Two diagnostic branches remain available for review: `codex/ci-preflight` and `codex/ci-config-preflight`. Diagnostic workflows belong only to those branch snapshots; the intended local workflow is `.forgejo/workflows/main.yml`.

Validation: [Forgejo run 4](https://pubcode.archuser.org/firebadnofire/dronesmc/actions/runs/4) (API run ID **914**) **PASS**. Build and Minecraft jobs succeeded; the release job was **SKIPPED**, as required for this nonpublishing branch.

| Check | Executed result / evidence |
| --- | --- |
| Existing registered runner | `opensuse-server`, host `192.168.86.54`; runner container `forgejo-runner`, v12.13.2; Docker-in-Docker 29.9.3; label `ubuntu-22.04`. Runner tasks **2934** (build), **2935** (Minecraft); `testing/evidence/ci/runner.log`. |
| Container and actions | Actual jobs used digest-pinned `node:24-bookworm@sha256:9d266751ca3d7fdbe651c878ffd5ad0a42fed49a18c283509267c97f025b7496`; all pinned checkout/setup-java/upload/download actions fetched and executed successfully. |
| Java and Gradle | Temurin **8.0.472+8**, JDK verified inside each job; Gradle wrapper **4.9**; ForgeGradle **3.0.197**, Forge **14.23.5.2859** unchanged. |
| Compilation/reobfuscation | **PASS**, clean Gradle build, including `reobfJar`, `BUILD SUCCESSFUL in 52s`; `testing/evidence/ci/run-4/build-evidence/gradle-build.log`. |
| Existing JUnit tests | **PASS**, 14 tests, zero failures/errors/skips (2 packet, 6 entity, 6 flight math); XML in `testing/evidence/ci/run-4/build-evidence/build/test-results/test/`. |
| CI helper tests | **PASS**, 21 tests on the registered runner, including mocked Forgejo/GitHub drafts and uploads, immutable tag/binary checks, signature variation/failure, dual-target staging order, partial-failure recovery, source-history protections, safe Git credential handling, and simulated runtime timeout/cleanup/loading/exit failures. `testing/evidence/ci/run-4/build.log`. |
| Distributable inspection | **PASS**, expected mod ID, `0.1.1-beta` metadata and compiled annotation, Minecraft/Forge dependency, manifest version, Java 8 bytecode and SRG names. Original CI artifact retained in `testing/evidence/ci/run-4/distributable/`. |
| Cross-job identity | **PASS**, artifact action transferred the initial build output; downloaded and installed hashes matched. Minecraft job never ran Gradle or compiled a replacement. |
| Forge installer | **PASS**, HTTPS download and installation. Recorded installer SHA-256 `dbba1cd1bf34a4b82148bac50a6150f63ffcb2d5dc7d88a0508c27fdcff15b76`; installer/dependency output in `testing/evidence/ci/run-4/minecraft-evidence/installer.log`. |
| Real mod loading | **PASS**, FML's SERVER loaded-mod list contains `dronesmc`; the DronesMC initialization log appears. This is stronger evidence than discovering a filename. |
| World readiness | **PASS**, `Done (0.725s)!` at 02:07:32 UTC (21:07:32 America/New_York on November 20); readiness detected 5.505 seconds after process launch. |
| Post-readiness health | **PASS**, **10.014081 seconds** observed after readiness; process alive and no detected error/fatal logs. |
| Graceful shutdown | **PASS**, `stop`, `Stopping server`, `Saving chunks` and zero process exit; independent `shutdown=true`. Runtime bound to loopback with authentication retained and no published test port. |
| Real cryptographic signing | **PASS with disposable test key**, password-protected RSA key, ASCII detached JAR/manifest signatures verified, strict checksums passed, incorrect password rejected, temporary key material removed. Production signing key was not imported or exercised. |
| Secret names | **PASS**, [configuration-only run 5](https://pubcode.archuser.org/firebadnofire/dronesmc/actions/runs/5), API ID **915**: all three required secret names reported configured. Only boolean presence expressions entered the job environment; secret values were not printed or passed to this diagnostic. Evidence: `testing/evidence/ci/run-5/configuration.log`. |
| YAML, gates and transfer paths | **PASS**, parsed locally and accepted/executed by Forgejo. Build→smoke dependencies and successful-run release skipping verified. Manual dispatch is declared as validation only; an actual manual dispatch was not exercised. |

Produced artifact: **`dronesmc-0.1.1-beta.jar`**.

```text
SHA-256: 1e08c1807adf829f22f0422989852a170566ade28a789991235f07734716d249
```

`testing/evidence/ci/run-4/minecraft-evidence/result.json` independently records `startup`, `mod_loaded`, `observation`, `shutdown` and `passed` as true, together with the matching commit/version/hash and observation duration. Console, FML logs, installer log, build logs, JUnit results, run metadata and artifact inventories were downloaded from the actual Forgejo run. Local `artifact.py verify` passed against the downloaded distributable as a second check. The workflow, artifact/build/smoke/signing helpers are unchanged from that successful runtime preflight.

Final publisher refinement: an existing GitHub repository must have **no branches**, as well as no reported content, before CI treats it as empty. GitHub's reported repository size can be zero for small repositories with history. The new regression check rejects an existing `master` branch even when size is zero. [Run 6](https://pubcode.archuser.org/firebadnofire/dronesmc/actions/runs/6), API ID **916**, **PASS** on the registered runner for commit `44a621d30d01e930edb78ea10f59125125a098b4`: **22 helper tests**, all required secret-name presence checks, no rebuild or publication. Evidence: `testing/evidence/ci/run-6/configuration.log`. Final local workflow/helpers matched this tested revision with line endings normalized. This extra helper-only run preserves the already tested runtime binary instead of recompiling it unnecessarily.

Actual commands executed in runner jobs:

```bash
python3 -m unittest discover -s scripts/ci/tests -v
bash scripts/ci/build.sh
# build.sh normalizes a copy of the existing wrapper and runs:
bash .ci-gradlew --version
env -u DRONESMC_BUILD_DIR bash .ci-gradlew clean build --no-daemon --console=plain
python3 scripts/ci/artifact.py stage ci-dist
python3 scripts/ci/signing_preflight.py
python3 scripts/ci/smoke.py ci-dist smoke-result
```

Inside the smoke helper, `java -jar installer.jar --installServer` and Java 8 `-Xms256M -Xmx1536M -jar forge-1.12.2-14.23.5.2859.jar nogui` ran in a temporary server directory. The actual installer path was an absolute disposable path. The helper sent `stop` over standard input and removed the temporary world after preserving logs.

Diagnosed preflight failures are retained rather than reported as passes: run 1 compiled/tested successfully but the helper rejected Gradle's `> Task :reobfJar` log format; its matcher was fixed. Run 2 built, signed with the disposable key and transferred the artifact, but Forge Maven rejected Python's default user agent with HTTP 403. The helper now identifies itself as `DronesMC-CI/1.0`; TLS/certificate checks were not weakened. Run 3 was superseded/cancelled before completion. Runs 1/2 evidence remains under `testing/evidence/ci/`.

Initial infrastructure discovery accidentally targeted an unrelated Forgejo **service** container on this host while trying to obtain API access. That credential did not authenticate to `pubcode.archuser.org`; the single temporary credential was revoked and its temporary token file removed. The successful preflights executed through the existing Forgejo **runner** container and its registered connection to `pubcode.archuser.org`. No registration tokens were read or exposed and global runner configuration was unchanged.

Remaining untested conditions: production GPG key usability/passphrase, automatic Forgejo release-token write authorization, live `GH_KEY` permissions, GitHub repository creation/fast-forward synchronization, live draft upload/download behavior and real two-platform finalization/recovery. Those paths have mocked coverage but no public release was generated during preflight. Anonymous GitHub inspection returned 404 for `firebadnofire/dronesmc`, which cannot distinguish an absent repository from a private one; authenticated release code must verify the intended destination and fail closed. The administrator must provide contents-write/repository-creation permissions as described in README, ensure Forgejo's automatic token can write tags/releases, and distribute the trusted public signing-key fingerprint. Client rendering, flight controls, multiplayer synchronization, and supplied-modpack compatibility remain outside this minimal dedicated-server smoke result.




## Tag-triggered release follow-up — 2026-11-20

The release policy now supersedes the earlier main-push publication design: pushes to `main` and PRs validate, while only pushed `v*` / `V*` tags publish. Both targets retain the exact pushed tag and beta artifact metadata. The publisher verifies the tag against the tested/checked-out commit and requires that commit to be in `origin/main` history; annotated tags are resolved to their commit. Manual dispatch remains nonpublishing. Important project sources, Gradle wrapper, Windows scripts, CI helpers, documentation, distributable and retained testing evidence are included in `main`; diagnostic-only workflows are excluded.

Local validation: 25 helper tests passed, including lowercase/uppercase tag publication to both mocked platforms and rejection of main pushes, PRs, manual dispatch and unrelated tags by the publisher. Workflow YAML parsed successfully with both tag filters and the tag-only release condition. No version tag was created merely to test publication.

## v0.0.1 publication recovery — 2026-11-20

Original tag workflow run 8 (API ID 918) passed compilation, JUnit, exact-JAR Minecraft smoke testing and production-key signing, and synchronized the source to GitHub. Publication then failed during Forgejo draft verification: its browser release-download URL returned HTTP 404, so GitHub release staging never began. Anonymous release APIs listed no published releases on either platform at diagnosis. The fixed publisher uses authenticated Forgejo attachment UUID downloads and retains draft-first verification. A separate recovery workflow validates/downloads original run artifacts without rebuilding; 32 local publisher/recovery tests pass, including draft-route selection and source-run/ZIP safety checks. A one-time main push trigger is used to start the authorized v0.0.1 repair and will be removed after recovery. Live outcome will be recorded after execution.

Recovery attempts retained as evidence: run 10 stopped because the main checkout had no local release tag; run 12 explicitly fetched it and confirmed the original tag was no longer present remotely. No release assets were replaced by these attempts. The user then explicitly authorized reusing `v0.0.1` for a **new test**. The one-time recovery push trigger was removed, leaving only explicit manual recovery; the authorized fresh tag test will run against the latest fixed main commit, with a newly built/tested binary. The earlier original-artifact recovery was not claimed successful.
