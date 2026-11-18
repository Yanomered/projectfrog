package com.twofrogs.frog;

import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.TwoFrogsState.FrogBehavior;
import com.twofrogs.state.TwoFrogsState.FrogRole;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.FrogVariant;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * At full agitation a special frog becomes ENRAGED: it changes color, hunts the nearest player and bites
 * for 4 hearts (after a short, dodgeable wind-up). It calms down when its agitation falls below the exit
 * level (for example after a slimeball), and its color returns to normal.
 *
 * The color change is a vanilla frog variant swap, so it is synced to every client for free. The frog's
 * original variant is remembered in a scoreboard tag so it survives a restart.
 */
public final class RageBehavior {

    private static final String VARIANT_TAG_PREFIX = "twofrogs_variant:";
    private static final Map<UUID, Rage> RAGE = new HashMap<>();

    private static final class Rage {
        boolean enraged = false;
        boolean chasing = false;
        UUID targetId = null;
        int windup = 0;
        int cooldown = 0;
        int chaseTicks = 0;
        int restTicks = 0;
        int hopCooldown = 0;
    }

    private RageBehavior() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(RageBehavior::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RAGE.clear());
    }

    public static boolean isEnraged(UUID frogId) {
        Rage rage = RAGE.get(frogId);
        return rage != null && rage.enraged;
    }

    /** True while an enraged frog is actively hunting a player (it drives its own movement then). */
    public static boolean isChasing(UUID frogId) {
        Rage rage = RAGE.get(frogId);
        return rage != null && rage.enraged && rage.chasing;
    }

    // ---------- Main loop ----------

    private static void onServerTick(MinecraftServer server) {
        TwoFrogsState state = TwoFrogsState.get(server);
        boolean secondTick = server.getTickCount() % 20 == 0;

        for (FrogRole role : FrogRole.values()) {
            Optional<UUID> uuid = state.getFrogUuid(role);
            if (uuid.isEmpty()) {
                continue;
            }
            Frog frog = SpecialFrogManager.findFrog(server, uuid.get());
            if (frog == null) {
                continue;
            }

            double agitation = state.getAgitation(role);
            boolean active = state.getBehavior(role) != FrogBehavior.DORMANT;
            Rage rage = RAGE.computeIfAbsent(uuid.get(), k -> new Rage());

            if (!rage.enraged && active && agitation >= FrogTuning.RAGE_ENTER_AGITATION) {
                becomeEnraged(frog, rage);
            } else if (rage.enraged && (!active || agitation < FrogTuning.RAGE_EXIT_AGITATION)) {
                calmDown(frog, rage);
            } else if (!rage.enraged && secondTick) {
                restoreColor(frog); // e.g. the world was saved while the frog was enraged and then calmed
            }

            if (rage.enraged) {
                tickEnraged(frog, rage);
            }
        }
    }

    // ---------- Becoming enraged and calming down ----------

    private static void becomeEnraged(Frog frog, Rage rage) {
        rage.enraged = true;
        rage.chasing = false;
        rage.windup = 0;
        rage.cooldown = 0;
        rage.chaseTicks = 0;
        rage.restTicks = 0;

        // Remember the original color (once), then swap to a clearly different one.
        String original = originalVariantName(frog);
        if (original == null) {
            original = BuiltInRegistries.FROG_VARIANT.getKey(frog.getVariant()).toString();
            frog.addTag(VARIANT_TAG_PREFIX + original);
        }
        FrogVariant originalVariant = BuiltInRegistries.FROG_VARIANT.get(new ResourceLocation(original));
        frog.setVariant(originalVariant == FrogVariant.COLD ? FrogVariant.WARM : FrogVariant.COLD);

        if (frog.level() instanceof ServerLevel level) {
            level.playSound(null, frog.getX(), frog.getY(), frog.getZ(),
                    SoundEvents.RAVAGER_ROAR, SoundSource.NEUTRAL, 1.5F, 1.6F);
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, frog.getX(), frog.getY() + 0.6, frog.getZ(),
                    10, 0.3, 0.3, 0.3, 0.0);
        }
    }

    private static void calmDown(Frog frog, Rage rage) {
        rage.enraged = false;
        rage.chasing = false;
        rage.windup = 0;
        rage.targetId = null;
        restoreColor(frog);
    }

    private static String originalVariantName(Frog frog) {
        for (String tag : frog.getTags()) {
            if (tag.startsWith(VARIANT_TAG_PREFIX)) {
                return tag.substring(VARIANT_TAG_PREFIX.length());
            }
        }
        return null;
    }

    private static void restoreColor(Frog frog) {
        String original = originalVariantName(frog);
        if (original == null) {
            return;
        }
        frog.setVariant(BuiltInRegistries.FROG_VARIANT.get(new ResourceLocation(original)));
        frog.removeTag(VARIANT_TAG_PREFIX + original);
    }

    // ---------- Hunting and biting ----------

    private static void tickEnraged(Frog frog, Rage rage) {
        ServerLevel level = (ServerLevel) frog.level();
        long tick = level.getGameTime();

        if (rage.cooldown > 0) {
            rage.cooldown--;
        }
        if (rage.hopCooldown > 0) {
            rage.hopCooldown--;
        }
        if (rage.restTicks > 0) {
            rage.restTicks--;
        }

        // Winding up a bite: stand still and glare.
        if (rage.windup > 0) {
            frog.getNavigation().stop();
            frog.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            if (isGrounded(frog)) {
                frog.setDeltaMovement(0.0, frog.getDeltaMovement().y, 0.0);
            }
            rage.windup--;
            if (rage.windup == 0) {
                strike(level, frog, rage);
            }
            return;
        }

        if (tick % 5 == 0) {
            rage.targetId = pickTarget(level, frog);
        }
        ServerPlayer target = rage.targetId == null ? null : playerOf(level, rage.targetId);
        if (target == null) {
            rage.chasing = false;
            rage.chaseTicks = Math.max(0, rage.chaseTicks - 2);
            return;
        }

        double distance = frog.distanceTo(target);

        // Bite anything in reach (even when leashed, in a boat, or out of breath).
        if (distance <= FrogTuning.RAGE_ATTACK_REACH && rage.cooldown <= 0) {
            rage.windup = FrogTuning.RAGE_WINDUP_TICKS;
            rage.chasing = true;
            frog.getNavigation().stop();
            frog.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            level.playSound(null, frog.getX(), frog.getY(), frog.getZ(),
                    SoundEvents.FROG_TONGUE, SoundSource.NEUTRAL, 1.5F, 0.5F);
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, frog.getX(), frog.getY() + 0.6, frog.getZ(),
                    3, 0.2, 0.2, 0.2, 0.0);
            return;
        }

        // Only a free frog can chase, and only while it has breath.
        boolean free = !frog.isLeashed() && !frog.isPassenger();
        if (free && rage.restTicks <= 0 && distance <= FrogTuning.RAGE_CHASE_RANGE) {
            rage.chasing = true;
            rage.chaseTicks++;
            if (rage.chaseTicks >= FrogTuning.RAGE_BURST_TICKS) {
                rage.restTicks = FrogTuning.RAGE_REST_TICKS;
                rage.chaseTicks = 0;
                rage.chasing = false;
                return;
            }
            chaseHop(level, frog, rage, target, distance, tick);
        } else {
            rage.chasing = false;
        }
    }

    private static UUID pickTarget(ServerLevel level, Frog frog) {
        ServerPlayer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double distance = player.distanceTo(frog);
            if (distance > FrogTuning.RAGE_CHASE_RANGE) {
                continue;
            }
            if (distance > FrogTuning.RAGE_SIGHT_FREE_BLOCKS && !frog.hasLineOfSight(player)) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best == null ? null : best.getUUID();
    }

    private static ServerPlayer playerOf(ServerLevel level, UUID id) {
        Player player = level.getPlayerByUUID(id);
        return player instanceof ServerPlayer serverPlayer && serverPlayer.isAlive() ? serverPlayer : null;
    }

    private static void chaseHop(ServerLevel level, Frog frog, Rage rage, ServerPlayer target,
                                 double distance, long tick) {
        // Keep the walking brain pointed at the player so it can path around obstacles.
        if (tick % 20 == 0 || !frog.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
            frog.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(new EntityTracker(target, false), 1.0F, 1));
        }

        if (rage.hopCooldown > 0 || !isGrounded(frog)) {
            return;
        }

        double dx = target.getX() - frog.getX();
        double dz = target.getZ() - frog.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.01) {
            return;
        }
        Vec3 direction = new Vec3(dx / length, 0.0, dz / length);
        double upBoost = target.getY() - frog.getY() > 1.0 ? 0.15 : 0.0;

        Path path = frog.getNavigation().getPath();
        if (path != null && !path.isDone()) {
            Vec3 next = path.getNextEntityPos(frog);
            double px = next.x - frog.getX();
            double pz = next.z - frog.getZ();
            double pathLength = Math.sqrt(px * px + pz * pz);
            if (pathLength > 0.2) {
                direction = new Vec3(px / pathLength, 0.0, pz / pathLength);
            }
            if (next.y - frog.getY() > 0.6) {
                upBoost = 0.2;
            }
        }

        double angle = Math.atan2(direction.z, direction.x) + (frog.getRandom().nextDouble() - 0.5) * 0.24;
        double horizontal = Mth.clamp(distance * 0.14, 0.2, FrogTuning.RAGE_HOP_HORIZONTAL);
        frog.setDeltaMovement(Math.cos(angle) * horizontal, FrogTuning.RAGE_HOP_VERTICAL + upBoost,
                Math.sin(angle) * horizontal);
        rage.hopCooldown = FrogTuning.RAGE_HOP_COOLDOWN_TICKS;
    }

    private static void strike(ServerLevel level, Frog frog, Rage rage) {
        rage.cooldown = FrogTuning.RAGE_COOLDOWN_TICKS;
        ServerPlayer target = rage.targetId == null ? null : playerOf(level, rage.targetId);
        if (target == null) {
            return;
        }
        // The player may have dodged out of reach during the wind-up.
        if (frog.distanceTo(target) > FrogTuning.RAGE_ATTACK_REACH + 1.0) {
            return;
        }
        boolean hurt = target.hurt(level.damageSources().mobAttack(frog), FrogTuning.RAGE_DAMAGE);
        if (hurt) {
            target.knockback(FrogTuning.RAGE_KNOCKBACK, frog.getX() - target.getX(), frog.getZ() - target.getZ());
            target.hurtMarked = true;
        }
    }

    private static boolean isGrounded(Frog frog) {
        BlockPos below = frog.blockPosition().below();
        boolean solidBelow = !frog.level().getBlockState(below)
                .getCollisionShape(frog.level(), below).isEmpty();
        return solidBelow && Math.abs(frog.getDeltaMovement().y) < 0.1;
    }
}