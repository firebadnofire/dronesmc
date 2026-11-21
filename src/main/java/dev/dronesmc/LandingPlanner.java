package dev.dronesmc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Bounded six-neighbor air search; never requests chunk generation or loading. */
public final class LandingPlanner {
    private LandingPlanner() { }
    public static final class Result {
        public final List<Vec3d> route;
        public final Vec3d landing;
        Result(List<Vec3d> route, Vec3d landing) { this.route = route; this.landing = landing; }
    }
    public static boolean safeSupport(DroneEntity drone, Vec3d landing) {
        BlockPos support = new BlockPos(landing.x, landing.y - 0.01, landing.z);
        if (!drone.world.isBlockLoaded(support)) return false;
        IBlockState state = drone.world.getBlockState(support);
        // Full, solid top faces only: exclude farmland, fences, slabs and special collision shapes.
        if (!state.isFullCube() || !state.isSideSolid(drone.world, support, EnumFacing.UP)
                || state.getBlock() == Blocks.MAGMA || state.getBlock() == Blocks.CACTUS
                || !drone.clearAt(landing)) return false;
        for (BlockPos p : BlockPos.getAllInBox(support.add(-1, 0, -1), support.add(1, 2, 1))) {
            if (!drone.world.isBlockLoaded(p)) return false;
            IBlockState nearby = drone.world.getBlockState(p);
            if (nearby.getMaterial().isLiquid() || nearby.getBlock() == Blocks.FIRE
                    || nearby.getBlock() == Blocks.CACTUS || nearby.getBlock() == Blocks.MAGMA) return false;
        }
        return true;
    }
    public static Result plan(DroneEntity drone, Vec3d home) {
        if (home == null) return null;
        return plan(drone, home, DroneConfig.landingRadius, 16);
    }
    public static Result planNearest(DroneEntity drone) {
        // Bounded fallback below/near the drone; never loads or generates chunks.
        return plan(drone, drone.getPositionVector(), 16, 255);
    }
    private static Result plan(DroneEntity drone, Vec3d home, int radius, int depth) {
        Map<BlockPos, Vec3d> goals = new HashMap<>();
        BlockPos center = new BlockPos(home);
        int approachY = Math.min(253, Math.max(1, (int) Math.ceil(drone.posY)));
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            double px = center.getX() + x + 0.5, pz = center.getZ() + z + 0.5;
            if ((px - home.x) * (px - home.x) + (pz - home.z) * (pz - home.z) > radius * radius) continue;
            for (int y = Math.min(253, center.getY() + 8); y >= Math.max(1, center.getY() - depth); y--) {
                Vec3d land = new Vec3d(px, y, pz);
                if (!safeSupport(drone, land)) continue;
                int top = Math.max(y + 1, approachY);
                boolean clear = true;
                for (int scan = y; scan <= top; scan++) if (!drone.clearAt(new Vec3d(px, scan, pz))) { clear = false; break; }
                if (clear) goals.put(new BlockPos(px, top, pz), land);
                break;
            }
        }
        if (goals.isEmpty()) return null;
        BlockPos start = new BlockPos(drone.getPositionVector());
        if (!drone.clearAt(point(start))) return null;
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Map<BlockPos, Integer> costs = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingInt(n -> n.score));
        costs.put(start, 0); open.add(new Node(start, heuristic(start, goals.keySet())));
        while (!open.isEmpty() && closed.size() < 8192) {
            BlockPos current = open.remove().pos;
            if (!closed.add(current)) continue;
            if (goals.containsKey(current)) {
                List<Vec3d> route = new ArrayList<>();
                BlockPos cursor = current;
                while (cursor != null) { route.add(point(cursor)); cursor = parents.get(cursor); }
                Collections.reverse(route);
                return new Result(route, goals.get(current));
            }
            for (EnumFacing direction : EnumFacing.values()) {
                BlockPos next = current.offset(direction);
                if (Math.abs(next.getX() - start.getX()) > 96 || Math.abs(next.getZ() - start.getZ()) > 96
                        || Math.abs(next.getY() - start.getY()) > 32 || closed.contains(next)) continue;
                int cost = costs.get(current) + 1;
                if (cost >= costs.getOrDefault(next, Integer.MAX_VALUE) || !drone.clearAt(point(next))) continue;
                AxisAlignedBB swept = drone.boxAt(point(current)).union(drone.boxAt(point(next)));
                if (!drone.world.getCollisionBoxes(drone, swept).isEmpty()) continue;
                costs.put(next, cost); parents.put(next, current);
                open.add(new Node(next, cost + heuristic(next, goals.keySet())));
            }
        }
        return null;
    }
    private static Vec3d point(BlockPos pos) { return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5); }
    private static int heuristic(BlockPos pos, Set<BlockPos> goals) {
        int best = Integer.MAX_VALUE;
        for (BlockPos goal : goals) best = Math.min(best, Math.abs(pos.getX() - goal.getX())
                + Math.abs(pos.getY() - goal.getY()) + Math.abs(pos.getZ() - goal.getZ()));
        return best;
    }
    private static final class Node {
        final BlockPos pos; final int score;
        Node(BlockPos pos, int score) { this.pos = pos; this.score = score; }
    }
}
