package com.github.xandergos.terraindiffusionmc.world;

/**
 * How river channels are placed, chosen per world at creation.
 *
 * <p>Both on settings analyse drainage at native resolution and follow real valleys; they
 * differ only in how big an area is analysed at once. Total work over a given area is much
 * the same either way, so this is about how the cost is delivered: {@link #FAST} pauses
 * generation briefly but more often, {@link #DETAILED} pauses longer but rarely and keeps
 * more of a catchment inside one analysis, which joins rivers up better across boundaries.
 */
public enum RiverMode {
    /** No rivers, and none of the work that finds them. */
    OFF("off"),
    /** Small analysis regions: short, frequent pauses, and smaller biggest rivers. */
    FAST("fast"),
    /** Large analysis regions: rarer, longer pauses, and room for major rivers. */
    DETAILED("detailed");

    public static final RiverMode DEFAULT = DETAILED;

    private final String id;

    RiverMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Resolves a saved id, falling back to the default rather than failing world load. */
    public static RiverMode byId(String id) {
        for (RiverMode mode : values()) {
            if (mode.id.equalsIgnoreCase(id)) return mode;
        }
        return DEFAULT;
    }

    /** The next mode in the cycle, for a click-through button. */
    public RiverMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
