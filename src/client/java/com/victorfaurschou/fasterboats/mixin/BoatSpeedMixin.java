package com.victorfaurschou.fasterboats.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBoat.class)
public class BoatSpeedMixin {
    // Below this speed (blocks/tick) the boat counts as stopped, both for idle detection and as the
    // minimum previous speed the collision check requires before it looks for a sudden drop.
    @Unique private static final double MIN_MOVING_SPEED = 0.05;
    // A one-tick speed drop below this fraction of the previous speed is treated as a collision.
    @Unique private static final double COLLISION_DROP_RATIO = 0.7;
    // Yaw change per tick, in degrees, above which the boat is no longer considered "going straight".
    @Unique private static final float TURN_THRESHOLD_DEG = 2.0F;
    // Forward acceleration: baseline is always +30% over vanilla's 0.04F; boosted adds up to another 60%.
    @Unique private static final float BASE_FORWARD_ACCEL = 0.052F;
    @Unique private static final float BOOST_FORWARD_ACCEL_BONUS = 0.0312F;
    // bubble is a SimpleParticleType with no per-spawn scale field, so Level.addParticle can't size it.
    // ParticleEngine.createParticle (which addParticle just calls and discards the result of) gives us
    // the actual Particle instance back, whose public scale() we can call ourselves. Each particle gets
    // a random scale in [MIN, MAX) so the trail has some size variation.
    @Unique private static final float TRAIL_SCALE_MIN = 1.5F;
    @Unique private static final float TRAIL_SCALE_MAX = 2.0F;
    // Max particles per tick (reached at full boost), each at a random spot along a line across the
    // boat's width. The actual per-tick count ramps from ~0 up to this as boostProgress climbs 0 -> 1.
    @Unique private static final int TRAIL_PARTICLES_PER_TICK_MAX = 6;

    @Unique private float prevYaw = Float.NaN;
    @Unique private double prevHSpeed = 0.0;
    @Unique private int straightLineTicks = 0;
    @Unique private int boostThresholdTicks = -1;
    @Unique private boolean isBoosted = false;
    @Unique private float boostProgress = 0.0F;

    @Inject(method = "tick", at = @At("HEAD"))
    private void trackStraightLine(CallbackInfo ci) {
        AbstractBoat self = (AbstractBoat) (Object) this;

        // Boats use client-authoritative movement (see AbstractBoat#tick): controlBoat() and real
        // per-tick velocity only exist on the client. The server forces getDeltaMovement() to ZERO
        // every tick for a boat it doesn't locally control, so tracking/feedback belongs here only.
        if (!self.level().isClientSide()) {
            return;
        }

        if (boostThresholdTicks < 0) {
            boostThresholdTicks = rollBoostThresholdTicks(self);
        }

        Vec3 vel = self.getDeltaMovement();
        double hSpeed = vel.horizontalDistance();
        float yaw = self.getYRot();
        float yawDelta = Float.isNaN(prevYaw) ? 0.0F : Math.abs(Mth.wrapDegrees(yaw - prevYaw));

        // Sudden speed drop (>30% in one tick) means a collision — normal drag is ~10%/tick.
        double previousHSpeed = prevHSpeed;
        double dropThreshold = previousHSpeed * COLLISION_DROP_RATIO;
        boolean collided = previousHSpeed > MIN_MOVING_SPEED && hSpeed < dropThreshold;
        if (collided) {
            resetBoost(self);
        }
        prevHSpeed = hSpeed;

        if (hSpeed < MIN_MOVING_SPEED || Float.isNaN(prevYaw)) {
            if (hSpeed < MIN_MOVING_SPEED) {
                resetBoost(self);
            }
        } else if (yawDelta < TURN_THRESHOLD_DEG) {
            straightLineTicks++;
            if (!isBoosted && straightLineTicks >= boostThresholdTicks) {
                isBoosted = true;
            }
            if (isBoosted) {
                boostProgress = Math.min(1.0F, boostProgress + 0.005F); // ~10 s ramp to full boost
                spawnTrail(self, vel, hSpeed);
            }
        } else {
            resetBoost(self);
        }

        prevYaw = yaw;
    }

    @Unique
    private void resetBoost(AbstractBoat self) {
        straightLineTicks = 0;
        isBoosted = false;
        boostProgress = 0.0F;
        boostThresholdTicks = rollBoostThresholdTicks(self);
    }

    @Unique
    private static int rollBoostThresholdTicks(AbstractBoat self) {
        return 120 + self.getRandom().nextInt(41); // 6–8 s at 20 ticks/s
    }

    // bubble has an outlined round sprite with actual shape detail, so it still reads as a bubble at
    // large scale instead of turning into a flat smudge the way dolphin's blurrier texture did.
    // Client-only (only meaningful on whichever client is driving).
    @Unique
    private void spawnTrail(AbstractBoat self, Vec3 vel, double hSpeed) {
        var random = self.getRandom();
        // Rate ramps with the boost: ~0 particles/tick at boostProgress 0, up to the max at full boost.
        // The fractional target is realised probabilistically so the average rate ramps smoothly.
        float target = boostProgress * TRAIL_PARTICLES_PER_TICK_MAX;
        int count = (int) target;
        if (random.nextFloat() < target - count) {
            count++;
        }
        if (count == 0) {
            return;
        }

        double dirX = vel.x / hSpeed;
        double dirZ = vel.z / hSpeed;
        // Perpendicular (across the boat's width), in the horizontal plane.
        double perpX = -dirZ;
        double perpZ = dirX;
        double behind = 0.6 + self.getBbWidth() * 0.3;
        double baseX = self.getX() - dirX * behind;
        double baseZ = self.getZ() - dirZ * behind;
        double width = self.getBbWidth();
        var particleEngine = Minecraft.getInstance().particleEngine;

        for (int i = 0; i < count; i++) {
            double offset = (random.nextDouble() - 0.5) * width;
            double x = baseX + perpX * offset;
            double y = self.getY() + 0.1;
            double z = baseZ + perpZ * offset;

            Particle particle = particleEngine.createParticle(ParticleTypes.BUBBLE, x, y, z, 0.0, 0.0, 0.0);
            if (particle != null) {
                particle.scale(TRAIL_SCALE_MIN + random.nextFloat() * (TRAIL_SCALE_MAX - TRAIL_SCALE_MIN));
            }
        }
    }

    @ModifyConstant(method = "controlBoat", constant = @Constant(floatValue = 0.04F))
    private float boostForwardAcceleration(float original) {
        return BASE_FORWARD_ACCEL + boostProgress * BOOST_FORWARD_ACCEL_BONUS;
    }

    @ModifyConstant(method = "controlBoat", constant = @Constant(floatValue = 0.005F, ordinal = 1))
    private float boostReverseAcceleration(float original) {
        return 0.0065F;
    }
}
