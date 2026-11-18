package com.twofrogs.frog;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.level.Level;

/** Sound cues for the special frogs. */
public final class FrogSounds {

    private FrogSounds() {
    }

    /** A loud, deep croak and a snapping sound. Volumes above 1 carry much farther (about 48 blocks). */
    public static void escapeCue(Frog frog) {
        Level level = frog.level();
        if (level.isClientSide) {
            return;
        }
        level.playSound(null, frog.getX(), frog.getY(), frog.getZ(),
                SoundEvents.FROG_HURT, SoundSource.NEUTRAL, 3.0F, 0.5F);
        level.playSound(null, frog.getX(), frog.getY(), frog.getZ(),
                SoundEvents.LEASH_KNOT_BREAK, SoundSource.NEUTRAL, 2.0F, 1.0F);
    }
}