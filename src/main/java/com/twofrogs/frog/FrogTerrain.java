package com.twofrogs.frog;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shared questions about the world for the frog AI. The caller makes sure the chunk is loaded. */
public final class FrogTerrain {

    private FrogTerrain() {
    }

    public static boolean solid(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Height of the block's collision shape (0 if it has none). Fences and walls are taller than 1. */
    public static double topHeight(ServerLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
    }

    /** True if a frog could hop up onto this block (it is at most one block tall). */
    public static boolean climbable(ServerLevel level, BlockPos pos) {
        return topHeight(level, pos) <= 1.0 + 1.0E-6;
    }

    /** True if the frog is standing on something (works on slabs, steps, carpets, boats...). */
    public static boolean grounded(Frog frog) {
        if (Math.abs(frog.getDeltaMovement().y) > 0.12) {
            return false;
        }
        AABB below = frog.getBoundingBox().move(0.0, -0.08, 0.0);
        return !frog.level().noCollision(frog, below);
    }

    /** The cell an entity is standing in, or will land in if it is in mid-air. */
    public static BlockPos standCell(ServerLevel level, Entity entity) {
        return standCell(level, entity.getX(), entity.getY(), entity.getZ());
    }

    public static BlockPos standCell(ServerLevel level, double x, double y, double z) {
        BlockPos p = BlockPos.containing(x, y + 0.05, z);
        if (!level.hasChunkAt(p)) {
            return p;
        }
        int guard = 0;
        while (solid(level, p) && guard++ < 3) {
            p = p.above(); // standing on a slab or step: the cell above it
        }
        guard = 0;
        while (guard++ < 6 && !solid(level, p.below()) && level.getFluidState(p).isEmpty()) {
            p = p.below(); // in mid-air: where it will come down
        }
        return p;
    }

    /** Looks up to 6 blocks above or below 'base' for a place a frog could stand, or null. */
    public static BlockPos findStandable(ServerLevel level, BlockPos base) {
        if (!level.hasChunkAt(base)) {
            return null;
        }
        for (int offset = 0; offset <= 6; offset++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (offset == 0 && sign == -1) {
                    continue;
                }
                BlockPos p = base.offset(0, sign * offset, 0);
                if (!solid(level, p) && solid(level, p.below())) {
                    return p;
                }
            }
        }
        return null;
    }
}