# Terrain Diffusion Mod [[Modrinth]](https://modrinth.com/mod/terrain-diffusion)

#### UPDATE: The research behind this mod has been accepted to SIGGRAPH 2026, the world's premier graphics conference! That means the research was officially peer reviewed and recognized as a significant contribution to the field. Enjoy the mod!

This is a Minecraft multiplateform mod integrating [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion).

## What this fork changes

Forked from [xandergos/terrain-diffusion-mc](https://github.com/xandergos/terrain-diffusion-mc) at v2.3.0 (the `1.21.1` branch). Everything in this section is on top of that release.

### More vanilla biomes

|                                       | before   | now      |
|---------------------------------------|----------|----------|
| Vanilla overworld biomes generated    | 20 of 53 | 44 of 53 |
| Overworld structures able to generate | 24       | 27       |

The 24 new biomes are derived from climate signals the classifier already computed, so no model changes were needed:

- **Oceans** — `deep_ocean`, `deep_cold_ocean`, `deep_frozen_ocean`, `lukewarm_ocean`, `deep_lukewarm_ocean`
- **Forests** — `dark_forest`, `birch_forest`, `old_growth_birch_forest`, `old_growth_pine_taiga`, `old_growth_spruce_taiga`
- **Jungle and wetland** — `sparse_jungle`, `bamboo_jungle`, `mangrove_swamp`
- **Dry and high** — `wooded_badlands`, `eroded_badlands`, `savanna_plateau`, `windswept_savanna`, `windswept_forest`, `windswept_gravelly_hills`, `jagged_peaks`, `ice_spikes`
- **Caves** — `dripstone_caves`, `lush_caves`, `deep_dark`, placed by depth below the local surface and biased by the biome above, so lush caves favour wet regions and dripstone dry ones

`badlands` and `meadow` now generate as well.

### Cave systems

Cave generation now uses vanilla's Caves & Cliffs noise on top of the diffusion terrain: **cheese caverns, spaghetti tunnels, noodle caves, cave entrances and pillars**, alongside the carver tunnels and ravines that were already there. Aquifers are enabled to go with it, so caves below sea level are dry rather than flooded, with underground water and lava pockets where vanilla would put them.

### Structures

`ancient_city`, `ocean_monument` and `woodland_mansion` now have biomes to generate in, which brings with them sculk, the warden, echo shards, Swift Sneak, sponge, prismarine, elder guardians and dark oak. `buried_treasure` and `shipwreck_beached` are still waiting on beaches.

The three custom biomes — `forest_sparse`, `taiga_sparse` and `snowy_taiga_sparse` — now carry the same biome tags as their vanilla counterparts, so structures generate in them and modded content keyed off `#minecraft:is_*` or `#c:is_*` applies to them too.

### Mod compatibility

Every biome added here is a vanilla biome key rather than a new namespaced one, so mods that edit vanilla biomes and structure mods keyed to vanilla biome tags pick them up with no extra work.

### Not done yet

Rivers, beaches and the shoreline biomes need a spatial pass over each terrain tile rather than per-pixel climate rules, so they are still missing, along with `cherry_grove`, `flower_forest`, `sunflower_plains` and `mushroom_fields`.

### GPU stability

Some GPU setups could hit a driver resource limit during long generation sessions, after which the integrated server would stop and the world could not be rejoined until Minecraft was restarted. The underlying resource leak in the ONNX session handling has been fixed. If you were setting `inference.offload_models=false` to work around it, that is no longer necessary.

## Which version should I use?

Three builds are available on the [Releases](https://github.com/xandergos/terrain-diffusion-mc/releases) page:

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

1. Download the mod jar from [Releases](https://github.com/xandergos/terrain-diffusion-mc/releases) for your Minecraft version and place it in your Minecraft `mods/` folder. Make sure the Minecraft version matches.
2. Launch Minecraft, at least once online to download the models (~2.5GB).
3. Create a world, and select the **Terrain Diffusion** world type. Click **Customize** to set the `World Scale` (see [Per-world settings](#per-world-settings) below).
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
# Keeps peak VRAM to ~1.5-2 GB. Set to false if you have ~2.5+ GB free for slightly
# faster generation.
inference.offload_models=true

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

For Terrain Diffusion worlds, click **Customize** in world creation and set:

- `World Scale` (integer `1..6`)

This value is saved with the world save and affects:

- how many real-world meters each block represents (`scale=1` => `30m/block`, `scale=2` => `15m/block`, etc.)
- world max height for newly created worlds (assumes tallest point is 10000 real-world meters)
- 2 is recommended for a good balance of scale and playability. Use 1 for smaller, more compressed worlds.
- Lower values put more stress on the GPU (Terrain Diffusion runs more often), while higher values put more stress on the CPU (larger world height). Most modern GPUs will be bottlenecked by the CPU around scale 2 or 3.

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

While modifying the AI terrain itself is quite complex, the integration with Minecraft biomes is extremely simple. The model outputs elevation + 4 climate variables, and this is converted to Minecraft biomes with hand-written rules. This is the most immediate way to improve the quality of the terrain and is relatively easy, but takes time to get realistic. The entire biome classifier is [only 250 lines](https://github.com/xandergos/terrain-diffusion-mc/blob/master/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/BiomeClassifier.java).

The terrain diversity far outpaces the biome diversity and there's a real opportunity to close that gap. I'm hoping someone goes crazy with it.
