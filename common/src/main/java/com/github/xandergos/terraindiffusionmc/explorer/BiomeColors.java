package com.github.xandergos.terraindiffusionmc.explorer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Display colours and names for the classifier's biome ids.
 *
 * <p>Chosen so a map reads at a glance rather than to match any in-game palette: cold
 * country pale, dry country sandy, forest green deepening with canopy, water blue
 * darkening with depth. Neighbouring biomes that a player would tell apart on the ground
 * are kept apart here too, which is the whole point of looking at one of these maps.
 */
public final class BiomeColors {

    private static final Map<Integer, String> NAMES = new LinkedHashMap<>();
    private static final Map<Integer, Integer> RGB = new LinkedHashMap<>();

    private static void put(int id, String name, int rgb) {
        NAMES.put(id, name);
        RGB.put(id, rgb);
    }

    static {
        put(1,  "plains",                   0x8DB360);
        put(2,  "sunflower_plains",         0xC8C33C);
        put(3,  "snowy_plains",             0xE4EAF0);
        put(4,  "ice_spikes",               0xB4DCE4);
        put(5,  "desert",                   0xE0C271);
        put(6,  "swamp",                    0x4C6340);
        put(7,  "mangrove_swamp",           0x2E5A4C);
        put(8,  "forest",                   0x3F7A34);
        put(9,  "flower_forest",            0x8FAE4E);
        put(10, "birch_forest",             0x6E9A55);
        put(11, "dark_forest",              0x27461F);
        put(12, "old_growth_birch_forest",  0x5E8C48);
        put(13, "old_growth_pine_taiga",    0x3A5B45);
        put(14, "old_growth_spruce_taiga",  0x2F4E3C);
        // Kept clear of blue: a blue-green conifer is truthful but reads as water on a
        // map whose rivers are the thing you are usually looking for.
        put(15, "taiga",                    0x3E7A45);
        put(16, "snowy_taiga",              0xA9C6C0);
        put(17, "savanna",                  0xBFB755);
        put(18, "savanna_plateau",          0xCFC878);
        put(19, "windswept_hills",          0x7C8A72);
        put(20, "windswept_gravelly_hills", 0x8E958C);
        put(21, "windswept_forest",         0x5C7A55);
        put(22, "windswept_savanna",        0xAFA765);
        put(23, "jungle",                   0x2E8B22);
        put(24, "sparse_jungle",            0x54A03C);
        put(25, "bamboo_jungle",            0x7BB13A);
        put(26, "badlands",                 0xC26A2B);
        put(27, "eroded_badlands",          0xD98A45);
        put(28, "wooded_badlands",          0xA07440);
        put(29, "meadow",                   0x7FC45B);
        put(30, "cherry_grove",             0xE8A8C8);
        put(31, "grove",                    0x9FBFB0);
        put(32, "snowy_slopes",             0xDCE8EE);
        put(33, "frozen_peaks",             0xC2DDEA);
        put(34, "jagged_peaks",             0xF2F6F8);
        put(35, "stony_peaks",              0x9A9A96);
        put(36, "river",                    0x3A6FD0);
        put(37, "frozen_river",             0x93BEDC);
        put(38, "beach",                    0xE8DCA0);
        put(39, "snowy_beach",              0xE6EDE4);
        put(40, "stony_shore",              0x8C8C86);
        put(41, "warm_ocean",               0x2B7FC4);
        put(42, "lukewarm_ocean",           0x2670B4);
        put(43, "deep_lukewarm_ocean",      0x1B558C);
        put(44, "ocean",                    0x21609E);
        put(45, "deep_ocean",               0x163F6E);
        put(46, "cold_ocean",               0x27578C);
        put(47, "deep_cold_ocean",          0x18395E);
        put(48, "frozen_ocean",             0x7FA8C4);
        put(49, "deep_frozen_ocean",        0x4E7B9C);
        put(50, "mushroom_fields",          0xB07FB0);
        put(108, "forest_sparse",           0x63924A);
        put(115, "taiga_sparse",            0x5A806A);
        put(116, "snowy_taiga_sparse",      0xBFD4CE);
    }

    private BiomeColors() {
    }

    /** Packed 0xRRGGBB for a classifier id; magenta marks an id with no entry. */
    public static int rgb(int id) {
        Integer v = RGB.get(id);
        return v == null ? 0xFF00FF : v;
    }

    /** Readable name for a classifier id, or null when the id is unknown. */
    public static String name(int id) {
        return NAMES.get(id);
    }

    /** Every id to its name, for the explorer's hover readout. */
    public static Map<Integer, String> names() {
        return NAMES;
    }
}
