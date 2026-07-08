package com.victorfaurschou.fasterboats.mixin;

import com.victorfaurschou.fasterboats.FasterBoatsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Boat.class)
public class BoatSpeedMixin {
    @Unique private static final double MIN_MOVING_SPEED = 0.05;
    @Unique private static final double COLLISION_DROP_RATIO = 0.7;
    @Unique private static final float TURN_THRESHOLD_DEG = 2.0F;
    @Unique private static final float BASE_FORWARD_ACCEL = 0.052F;
    @Unique private static final float BOOST_FORWARD_ACCEL_BONUS = 0.0312F;
    // SimpleParticleType has no per-spawn scale; ParticleEngine.createParticle returns the actual
    // Particle instance so we can call scale() on it ourselves, unlike Level.addParticle.
    @Unique private static final float TRAIL_SCALE_MIN = 1.5F;
    @Unique private static final float TRAIL_SCALE_MAX = 2.0F;
    @Unique private static final int TRAIL_PARTICLES_PER_TICK_MAX = 6;

    @Unique private float prevYaw = Float.NaN;
    @Unique private double prevHSpeed = 0.0;
    @Unique private int straightLineTicks = 0;
    @Unique private int boostThresholdTicks = -1;
    @Unique private boolean isBoosted = false;
    @Unique private float boostProgress = 0.0F;

    @Inject(method = "tick", at = @At("HEAD"))
    private void trackStraightLine(CallbackInfo ci) {
        Boat self = (Boat) (Object) this;

        // The server zeroes getDeltaMovement() every tick for a boat it doesn't locally control,
        // so real per-tick velocity only exists on the client driving it.
        if (!self.level().isClientSide()) {
            return;
        }

        Vec3 vel = self.getDeltaMovement();
        double hSpeed = vel.horizontalDistance();
        float yaw = self.getYRot();
        float yawDelta = Float.isNaN(prevYaw) ? 0.0F : Math.abs(Mth.wrapDegrees(yaw - prevYaw));
        double previousHSpeed = prevHSpeed;
        double dropThreshold = previousHSpeed * COLLISION_DROP_RATIO;

        if (!FasterBoatsConfig.enabled) {
            resetBoost(self);
        } else {
            if (boostThresholdTicks < 0) {
                boostThresholdTicks = rollBoostThresholdTicks(self);
            }

            if (previousHSpeed > MIN_MOVING_SPEED && hSpeed < dropThreshold) {
                resetBoost(self);
            }

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
                    boostProgress = Math.min(1.0F, boostProgress + 0.005F);
                    spawnTrail(self, vel, hSpeed);
                }
            } else {
                resetBoost(self);
            }
        }

        prevHSpeed = hSpeed;
        prevYaw = yaw;
    }

    @Unique
    private void resetBoost(Boat self) {
        straightLineTicks = 0;
        isBoosted = false;
        boostProgress = 0.0F;
        boostThresholdTicks = rollBoostThresholdTicks(self);
    }

    @Unique
    private static int rollBoostThresholdTicks(Boat self) {
        return 120 + self.getRandom().nextInt(41);
    }

    @Unique
    private void spawnTrail(Boat self, Vec3 vel, double hSpeed) {
        var random = self.getRandom();
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
        if (!FasterBoatsConfig.enabled) {
            return original;
        }
        return BASE_FORWARD_ACCEL + boostProgress * BOOST_FORWARD_ACCEL_BONUS;
    }

    @ModifyConstant(method = "controlBoat", constant = @Constant(floatValue = 0.005F, ordinal = 1))
    private float boostReverseAcceleration(float original) {
        if (!FasterBoatsConfig.enabled) {
            return original;
        }
        return 0.0065F;
    }
}
