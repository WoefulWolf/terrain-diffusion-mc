package com.github.xandergos.terraindiffusionmc.pipeline.river;

import java.util.ArrayList;
import java.util.List;

/**
 * Places the coarse drainage network in block coordinates and clips it to tiles.
 *
 * <p>A reach spans one coarse cell, {@code 32 * latentCompression * scale} blocks, so this
 * fixes only where a river enters and leaves a tile; {@link ChannelRefiner} chooses the
 * route between. Two neighbours clipping the same segment land on the same crossing, so
 * they agree without exchanging anything.
 *
 * <p>Pure geometry; no Minecraft or tensor types.
 */
public final class RiverSkeleton {

    private RiverSkeleton() {
    }

    /** A river reach in block coordinates, running from upstream to downstream. */
    public static final class Segment {
        public final double x0, z0;
        public final double x1, z1;
        public final float discharge;
        public final int order;

        public Segment(double x0, double z0, double x1, double z1, float discharge, int order) {
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
            this.discharge = discharge;
            this.order = order;
        }
    }

    /**
     * Converts coarse reaches into block-space segments through cell centres.
     *
     * @param reaches             from {@link RiverNetwork#extract}
     * @param windowWidth         width in cells of the coarse window the reaches index into
     * @param ci0                 window's first row in coarse space (the Z axis)
     * @param cj0                 window's first column in coarse space (the X axis)
     * @param unitsPerCoarseCell {@code 32 * latentCompression * scale}
     */
    public static List<Segment> toWorld(List<RiverNetwork.Reach> reaches, int windowWidth,
                                        int ci0, int cj0, int unitsPerCoarseCell) {
        List<Segment> out = new ArrayList<>(reaches.size());
        double half = unitsPerCoarseCell / 2.0;

        for (RiverNetwork.Reach reach : reaches) {
            if (reach.to < 0) continue;
            int fromRow = reach.from / windowWidth;
            int fromCol = reach.from - fromRow * windowWidth;
            int toRow = reach.to / windowWidth;
            int toCol = reach.to - toRow * windowWidth;

            double x0 = (cj0 + fromCol) * (double) unitsPerCoarseCell + half;
            double z0 = (ci0 + fromRow) * (double) unitsPerCoarseCell + half;
            double x1 = (cj0 + toCol) * (double) unitsPerCoarseCell + half;
            double z1 = (ci0 + toRow) * (double) unitsPerCoarseCell + half;
            out.add(new Segment(x0, z0, x1, z1, reach.discharge, reach.order));
        }
        return out;
    }

    /**
     * Clips a segment to the square tile at {@code (tileX, tileZ)} of the given size.
     *
     * @return the part inside the tile, or null if it misses entirely
     */
    public static Segment clipToTile(Segment s, int tileX, int tileZ, int size) {
        return clipToTile(s, tileX, tileZ, size, size);
    }

    /** As {@link #clipToTile(Segment, int, int, int)}, for windows that are not square. */
    public static Segment clipToTile(Segment s, int tileX, int tileZ, int w, int h) {
        double dx = s.x1 - s.x0;
        double dz = s.z1 - s.z0;
        double xMax = tileX + w, zMax = tileZ + h;

        double[] p = {-dx, dx, -dz, dz};
        double[] q = {s.x0 - tileX, xMax - s.x0, s.z0 - tileZ, zMax - s.z0};

        double enter = 0.0, exit = 1.0;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0) {
                // Parallel to this edge: either wholly inside its slab or wholly outside.
                if (q[i] < 0.0) return null;
                continue;
            }
            double t = q[i] / p[i];
            if (p[i] < 0.0) {
                if (t > exit) return null;
                if (t > enter) enter = t;
            } else {
                if (t < enter) return null;
                if (t < exit) exit = t;
            }
        }

        return new Segment(
                s.x0 + enter * dx, s.z0 + enter * dz,
                s.x0 + exit * dx, s.z0 + exit * dz,
                s.discharge, s.order);
    }

    /** Every segment with a part inside the tile, already clipped to it. */
    public static List<Segment> inTile(List<Segment> segments, int tileX, int tileZ, int size) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : segments) {
            Segment clipped = clipToTile(s, tileX, tileZ, size);
            if (clipped != null) out.add(clipped);
        }
        return out;
    }
}
