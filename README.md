# Terrain Diffusion+

A fork of [Terrain Diffusion MC](https://github.com/xandergos/terrain-diffusion-mc) ([Modrinth](https://modrinth.com/mod/terrain-diffusion)) by xandergos — a Minecraft multiplatform mod integrating [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion), whose research was accepted to SIGGRAPH 2026.

## What this fork changes

Forked from [xandergos/terrain-diffusion-mc](https://github.com/xandergos/terrain-diffusion-mc) at v2.3.0 (the `1.21.1` branch). Everything in this section is on top of that release.

### More vanilla biomes

|                                       | before   | now      |
|---------------------------------------|----------|----------|
| Vanilla overworld biomes generated    | 20 of 53 | 53 of 53 |
| Overworld structures able to generate | 24       | 29 of 29 |

Every vanilla overworld biome generates, and with them every vanilla overworld structure. Most are derived from climate signals the classifier already computed, so no model changes were needed:

- **Oceans** — `deep_ocean`, `deep_cold_ocean`, `deep_frozen_ocean`, `lukewarm_ocean`, `deep_lukewarm_ocean`
- **Forests** — `dark_forest`, `birch_forest`, `old_growth_birch_forest`, `old_growth_pine_taiga`, `old_growth_spruce_taiga`
- **Jungle and wetland** — `sparse_jungle`, `bamboo_jungle`, `mangrove_swamp`
- **Dry and high** — `wooded_badlands`, `eroded_badlands`, `savanna_plateau`, `windswept_savanna`, `windswept_forest`, `windswept_gravelly_hills`, `jagged_peaks`, `ice_spikes`
- **Accents** — `cherry_grove`, `flower_forest` and `sunflower_plains`, carved out of meadow, forest and plains in patches a couple of hundred blocks across. Sunflowers keep to real sunflower country: continental-interior plains with hot summers and cold winters, so coastal plains never grow them.
- **Mushroom islands** — `mushroom_fields` covers small islands with nothing but open sea for a couple of thousand blocks on every side, mycelium running to the waterline. Only around one in eight qualifying islands gets it, so finding one still means something.
- **Caves** — `dripstone_caves`, `lush_caves`, `deep_dark`, placed by depth below the local surface and biased by the biome above, so lush caves favour wet regions and dripstone dry ones

`badlands` and `meadow` generate as well, and `river` / `frozen_river` come with the river system below.

### Shorelines

Coasts are read from the terrain the way real ones work: where the land meets the ocean on a gentle gradient, waves can deposit sediment, so a `beach` forms — wide on flat coasts, a thin ribbon on steeper ones, `snowy_beach` where the country behind it is snowy. Steep coastal faces become rocky `stony_shore` headlands instead, and genuine sea cliffs stay cliffs. Swamp and mangrove coasts keep their muddy edges, as they should. Ocean floors got matching attention — sand under warm and temperate water, gravel under cold and deep.

Mangroves themselves are held to real ecology rather than vanilla's rules: they are intertidal, so `mangrove_swamp` only grows where hot, soaked land sits nearly flat within a couple of metres of the sea and close to the coast. The same country further inland is rainforest, so it generates as jungle instead.

### Climate realism

Where vanilla convention and real-world logic disagree, this fork picks the real world:

- **Sand needs genuine aridity.** Warm land that is merely too dry for trees keeps its grass cover — hot shrub-steppe generates as savanna and temperate steppe as plains, with true desert reserved for actually rain-starved country. Deserts shrink to their cores and fade out through grassland the way they should.
- **Green river corridors.** Rivers water their margins, so a channel meandering through desert, savanna or badlands carries a ribbon of greenery — grass and acacias through sand, gallery forest through savanna — sized to the river and only where the bank sits within a few blocks of the water. A river that has cut itself a deep canyon leaves its stone walls bare.
- **Marshy deltas.** A big slack river reaching the sea drops its sediment: the flats around the mouth silt over into swamp — mangrove where it is hot — rather than staying sandy beach. Steep or small mouths keep their sand and waterfalls.
- **Lake margins.** Where warm, wet lowland meets a lake at nearly its own level, the shore turns to swamp fringe.
- **Latitude.** A temperature wave runs north-south: warmest at the equator, coldest at the poles, matching Earth's roughly 50-degree swing in annual means. Spawn sits at 45° north by default, so travelling south grows warmer and north colder, with near-certain frozen ice caps at the poles and tropics along the equator. The bands repeat, so crossing a pole eventually warms again. Distance, spawn latitude, and strength are per-world settings; strength 0 turns it off.

### Cave systems

Cave generation now uses vanilla's Caves & Cliffs noise on top of the diffusion terrain: **cheese caverns, spaghetti tunnels, noodle caves, cave entrances and pillars**, alongside the carver tunnels and ravines that were already there. Aquifers are enabled to go with it, so caves below sea level are dry rather than flooded, with underground water and lava pockets where vanilla would put them.

Underground they run everywhere, but the surface only breaks where real landscapes break: small cave mouths open in genuinely craggy ground and rocky biomes, while the big entrance shafts need the right country — glacier and snowfield crevasse terrain, humid forested karst hills, or broken canyon rock — and a rare landscape-scale mask on top, so a gaping mouth is an event rather than a texture. A flat green field keeps its skin: tunnels, caverns and ravines all stay a dozen or more blocks down there instead of scarring the surface.

### Rivers and lakes

Rivers are traced from a real drainage analysis of the terrain itself: rainfall accumulates downhill across the heightmap, and only systems that gather a genuinely large catchment become rivers. Each one is then followed back up its main stem, so sources sit high in wet, cold, or rugged country and the river spends its whole run growing — springs a single block wide, trunks up to 50+ blocks, over courses that can span many thousands of blocks.

- **Follows the terrain** — rivers run down real valleys, collect tributaries at true confluences, and end in the ocean or a lake. Steep reaches cut narrow and deep with rapids and waterfalls; slack reaches spread wide and shallow and meander.
- **Real water at any altitude** — channels hold actual water at their own elevation, not just sea level, with flowing steps and falling sheets at every drop. Ocean mouths shoal over a bar and fan into the shelf like a delta.
- **Lakes** — basins large enough to matter fill with standing water; rivers flow in, cross, and continue out the outlet. Shorelines get natural climb-out spots.
- **Beds and banks by current** — fast water runs over stone, cobblestone and gravel with rocky banks; slow water over sand, dirt and clay with grassy edges. Frozen rivers freeze from the banks inward and keep an open flowing centre, with no ice near rapids — until deep subarctic cold, below about −20 °C, closes them bank to bank: solid ice surface, frozen falls, no open water at all.
- **Plays well with the rest of generation** — cave carvers and ravines stop short of channels instead of cutting dry gashes through them, and villages build over rivers at water level rather than sinking houses into them.
- Wetted channels are real `river` / `frozen_river` biome, so vanilla features, spawns, and biome-keyed mods apply.

Rivers are configured per world at creation — see [Per-world settings](#per-world-settings) — including turning them off entirely. The `/td-explore` detail map has a Rivers layer that renders exactly what the generator will place.

### Structures

`ancient_city`, `ocean_monument` and `woodland_mansion` now have biomes to generate in, which brings with them sculk, the warden, echo shards, Swift Sneak, sponge, prismarine, elder guardians and dark oak. Beaches unlock `buried_treasure` and `shipwreck_beached` on top of that.

The three custom biomes — `forest_sparse`, `taiga_sparse` and `snowy_taiga_sparse` — now carry the same biome tags as their vanilla counterparts, so structures generate in them and modded content keyed off `#minecraft:is_*` or `#c:is_*` applies to them too.

### Mod compatibility

Every biome added here is a vanilla biome key rather than a new namespaced one, so mods that edit vanilla biomes and structure mods keyed to vanilla biome tags pick them up with no extra work.

### Not done yet

`cherry_grove`, `flower_forest`, `sunflower_plains` and `mushroom_fields` are still missing.

### GPU stability

Some GPU setups could hit a driver resource limit during long generation sessions, after which the integrated server would stop and the world could not be rejoined until Minecraft was restarted. The underlying resource leak in the ONNX session handling has been fixed. If you were setting `inference.offload_models=false` to work around it, that is no longer necessary.

## Which version should I use?

Each build of this fork produces three runtime variants (grab them from the Actions workflow artifacts, or [build from source](#building-from-source)):

**The CPU build is slow unless you are on MacOS.**

| Build                     | Supports                    | Setup required                          |
|---------------------------| --------------------------- | --------------------------------------- |
| **Windows** (recommended) | Windows with any modern GPU | None                                    |
| **CUDA**                  | NVIDIA GPUs                 | [CUDA + cuDNN install](CUDA_INSTALL.md) |
| **CPU**                   | Everything else             | None                                    |

> **Mac users:** the CPU build automatically uses CoreML for hardware acceleration on Apple Silicon. No extra setup is needed.

Use the `-cuda` build only if you are on Linux, or have an NVIDIA GPU and prefer CUDA (may improve performance).

## Requirements

- Windows with a GPU OR Linux with an NVIDIA GPU is strongly recommended. CPU inference works but is very slow.
- VRAM (GPU RAM) needed: 1.5GB
- RAM needed: 2.5GB (May need to increase Minecraft's RAM allocation)

One of the following:
- Minecraft with [Fabric](https://fabricmc.net/) and the [Fabric API Mod](https://modrinth.com/mod/fabric-api) installed
- Minecraft with [NeoForge](https://neoforged.net/) installed

## Usage

**If using CUDA build:** First see [CUDA_INSTALL.md](CUDA_INSTALL.md).

1. Place the mod jar for your loader and variant in your Minecraft `mods/` folder. Make sure the Minecraft version matches.
2. Launch Minecraft, at least once online to download the models (~2.5GB).
3. Create a world, and select the **Terrain Diffusion** world type. Click **Customize** to set the `World Scale` and river options (see [Per-world settings](#per-world-settings) below).
4. The mod will search for a land spawn point near the world origin automatically. If the area around (0, 0) is entirely ocean, it may take a moment to find land. Use `/td-explore` (see below) to scout the world further.

## Exploring the World

The mod includes a built-in terrain explorer web UI. Run the `/td-explore` command in-game; it will print a clickable link (e.g. `http://localhost:19801`) that opens an interactive map in your browser. Click the map on the left to open a "detailed view". Click the detailed view to get coordinates in the bottom left. You can also filter for certain climates.

Use the explorer to scout continents, mountains, islands, and other interesting terrain before venturing out in Minecraft.

## Configuration

Edit `config/terrain-diffusion-mc.properties` (created automatically on first launch):

```
# Terrain Diffusion MC configuration

# Inference device: "cpu", "gpu", or "auto" (try GPU first then fall back to CPU).
# "gpu" uses DirectML on the -windows build, or CUDA on the -cuda build.
# GPU builds default to "gpu" so startup fails loudly if no GPU is detected.
# CPU build defaults to "auto": uses CoreML on macOS, otherwise CPU.
inference.device=gpu

# Offload inactive models from VRAM between pipeline stages.
# Keeps peak VRAM to ~1.5-2 GB. Set to false if you have ~2.5+ GB free: each model
# swap costs seconds of session rebuilding, so keeping everything resident speeds
# generation up noticeably. Falls back to offloading if VRAM runs out.
inference.offload_models=true

# Speculatively generate the terrain around the player while inference is idle, so
# walking into new land finds it already computed. Uses the GPU in the background;
# set to false if that competes with your frame rate.
inference.prefetch=true

# Validate SHA-256 for pre-existing files in .minecraft/terrain-diffusion-models.
# Set to false if you want to provide custom models/config files without hash checks.
validate_model=true

# Port for the local terrain explorer web UI (/td-explore).
explorer.port=19801

# Spawn search: coarse-pixel region sizes for finding a land spawn near (0, 0).
# Starts at initial_size x initial_size and expands by 8 each step up to max_size x max_size.
# Each coarse pixel covers a large area (hundreds of blocks), so 16–128 is typically sufficient.
spawn_search.initial_size=16
spawn_search.max_size=128
```

### Per-world settings

For Terrain Diffusion worlds, click **Customize** in world creation. Everything here is saved with the world.

**World Scale** (integer `1..6`):

- how many real-world meters each block represents (`scale=1` => `30m/block`, `scale=2` => `15m/block`, etc.)
- world max height for newly created worlds (assumes tallest point is 10000 real-world meters), applied per scale at creation so tall ranges are never clipped
- 2 is recommended for a good balance of scale and playability. Use 1 for smaller, more compressed worlds.
- Lower values put more stress on the GPU (Terrain Diffusion runs more often), while higher values put more stress on the CPU (larger world height). Most modern GPUs will be bottlenecked by the CPU around scale 2 or 3.

**Rivers**: `Off` disables the system entirely; `Fast` analyses drainage in small regions (short, frequent generation pauses, smaller biggest-rivers); `Detailed` uses large regions (rarer but longer pauses, room for major rivers).

Below the mode sit ten river parameters — rarity, smallest stream, maximum width and depth, width growth, bank height, lake size and depth, bank wobble, and bed relief. Every field has a hover tooltip explaining in plain terms what raising or lowering it does; the defaults are the tuned values.

Two of them pull against each other. **Rarity** decides how many separate river systems exist; **smallest stream** decides how far up its valleys each one is followed, and is much the stronger of the two — set it low and tributaries fill the map whatever rarity says. For fewer, larger, mountain-born rivers, raise smallest stream rather than lowering it.

**Caves**: two cover depths control how far below the surface caves must stay in gentle country — one for small tunnels and ravines, one for big caverns and their wide mouths. Craggy, rocky, and karst-like country still opens as described above. Set a field to `0` to switch that gate off and let those breaks appear anywhere, the way vanilla generates.

**Climate**: pole distance (blocks from equator to pole, default 30,000 — the north pole is a 15,000-block trek from a default spawn, about 45 minutes of sprinting), start latitude (0 equator to 90 north pole, default 45), and band strength (°C at the extremes, default 25; `0` disables latitude banding). Worlds created before this feature keep banding off.

## Common Issues

**A dynamic link library (DLL) initialization routine failed**

This can happen for some older Java versions. Please update to the most recent version of Java 21 or higher. The [latest Microsoft OpenJDK 21](https://learn.microsoft.com/en-us/java/openjdk/download) version is known to work.

**LoadLibrary failed with error 126** *(CUDA build only)*

This is typically due to an improper CUDA or cuDNN installation. See [CUDA_INSTALL.md](CUDA_INSTALL.md) for troubleshooting steps.

**java.lang.IllegalStateException: Failed to load terrain-diffusion models**

This typically indicates an "out of memory" error (the logs should show this as well).
Terrain Diffusion's models take up about 2.5GB of RAM, so make sure to allocate enough RAM to account for this.

**If your issue is still not resolved, please [raise it here](https://github.com/xandergos/terrain-diffusion-mc/issues/new).**

## Building from Source

An internet connection is required during the build to fetch the pinned model manifest metadata from Hugging Face.

A manually triggered GitHub Actions workflow (**Actions → Build → Run workflow**) builds every loader and variant and uploads them as artifacts, stamped with the build datetime as their version.

The `-windows` build requires `libs/onnxruntime-dml.jar`, which is provided as part of the repo. See [Building onnxruntime with DirectML](#building-onnxruntime-with-directml) to build from source.

### Build task layout

Use `Windows` in commands when you want the DirectML build. The old `Dml` task names still work as aliases, but the readable names are preferred.

| What you want | Command |
|---------------|---------|
| Fabric + NeoForge, Windows/DirectML | `./gradlew buildWindows` |
| Fabric + NeoForge, CUDA | `./gradlew buildCuda` |
| Fabric + NeoForge, CPU/CoreML | `./gradlew buildCpu` |
| Every loader and every variant | `./gradlew buildRelease` |
| Every loader/variant, copied into `build/release/` | `./gradlew collectReleaseJars` |
| Fabric only, Windows/DirectML | `./gradlew buildFabricWindows` |
| Fabric only, CUDA | `./gradlew buildFabricCuda` |
| Fabric only, CPU/CoreML | `./gradlew buildFabricCpu` |
| Every Fabric variant | `./gradlew buildFabricAll` |
| NeoForge only, Windows/DirectML | `./gradlew buildNeoForgeWindows` |
| NeoForge only, CUDA | `./gradlew buildNeoForgeCuda` |
| NeoForge only, CPU/CoreML | `./gradlew buildNeoForgeCpu` |
| Every NeoForge variant | `./gradlew buildNeoForgeAll` |

Equivalent direct Gradle property calls still work:

```
./gradlew build -PuseDml=true
./gradlew build -PuseCuda=true
./gradlew build -PuseCpu=true
./gradlew :fabric:build -PuseDml=true
./gradlew :neoforge:build -PuseDml=true
```

Compatibility aliases kept for existing scripts:

```
./gradlew buildDml
./gradlew buildFabricDml
./gradlew buildNeoForgeDml
./gradlew buildAll
```

Final jars are written under each loader module:

```
fabric/build/libs/
neoforge/build/libs/
```

For release packaging, use `./gradlew collectReleaseJars`. It copies the distributable jars into:

```
build/release/fabric/
build/release/neoforge/
```

### Building onnxruntime with DirectML

**Requirements**

- [Windows 10 SDK (10.0.17134.0)](https://developer.microsoft.com/en-us/windows/downloads/sdk-archive/index-legacy) — for Windows 10 version 1803 or newer
- Visual Studio 2017 toolchain — install *Desktop development with C++* from the VS Installer
- Visual Studio 2022 toolchain — same as above
- Python 3.10+: [https://python.org/](https://python.org/)
- CMake 3.28 or higher

Keep both VS toolchains up to date. Full details at the [ONNX Runtime build docs](https://onnxruntime.ai/docs/build/inferencing.html) and the [DirectML EP requirements](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#build).

**Steps**

Run all commands from the **Developer Command Prompt for VS 2022**.

```
git clone --recursive https://github.com/Microsoft/onnxruntime.git
cd onnxruntime
.\build.bat --config RelWithDebInfo --build_shared_lib --parallel --compile_no_warning_as_error --skip_submodule_sync --use_dml --build_java --build
```

The built jar appears in `java/build/`. Rename it to `onnxruntime-dml.jar` and place it in `libs/` in this repository.

## Note For Mod Developers

While modifying the AI terrain itself is quite complex, the integration with Minecraft biomes is simple. The model outputs elevation + 4 climate variables, and this is converted to Minecraft biomes with hand-written rules in [BiomeClassifier](common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/BiomeClassifier.java). The river and lake system builds on the same outputs: a pure-Java drainage analysis under [pipeline/river](common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/river) with no Minecraft types in it, so it can be driven from a test harness without launching the game.

The terrain diversity still outpaces the biome diversity, and closing that gap remains the most approachable way to contribute.
