package com.victorfaurschou.fasterboats.mixin;

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

import java.util.Random;

@Mixin(AbstractBoat.class)
public class BoatSpeedMixin {
    @Unique private static final Random RANDOM = new Random();

    @Unique private float prevYaw = Float.NaN;
    @Unique private double prevHSpeed = 0.0;
    @Unique private int straightLineTicks = 0;
    @Unique private int boostThresholdTicks = -1;
    @Unique private boolean isBoosted = false;
    @Unique private float boostProgress = 0.0F;

    @Inject(method = "tick", at = @At("HEAD"))
    private void trackStraightLine(CallbackInfo ci) {
        if (boostThresholdTicks < 0) {
            boostThresholdTicks = 120 + RANDOM.nextInt(41); // 6–8 s at 20 ticks/s
        }

        AbstractBoat self = (AbstractBoat) (Object) this;
        Vec3 vel = self.getDeltaMovement();
        double hSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        // Sudden speed drop (>30% in one tick) means a collision — normal drag is ~10%/tick
        if (prevHSpeed > 0.05 && hSpeed < prevHSpeed * 0.7) {
            straightLineTicks = 0;
            isBoosted = false;
            boostProgress = 0.0F;
            boostThresholdTicks = 120 + RANDOM.nextInt(41);
        }
        prevHSpeed = hSpeed;

        if (hSpeed < 0.05 || Float.isNaN(prevYaw)) {
            if (hSpeed < 0.05) {
                straightLineTicks = 0;
                isBoosted = false;
                boostProgress = 0.0F;
                boostThresholdTicks = 120 + RANDOM.nextInt(41);
            }
            prevYaw = self.getYRot();
            return;
        }

        float yawDelta = Math.abs(Mth.wrapDegrees(self.getYRot() - prevYaw));
        if (yawDelta < 2.0F) {
            straightLineTicks++;
            if (!isBoosted && straightLineTicks >= boostThresholdTicks) {
                isBoosted = true;
            }
        } else {
            straightLineTicks = 0;
            isBoosted = false;
            boostProgress = 0.0F;
            boostThresholdTicks = 120 + RANDOM.nextInt(41);
        }

        if (isBoosted) {
            boostProgress = Math.min(1.0F, boostProgress + 0.005F); // ~10 s ramp to full boost
        }

        prevYaw = self.getYRot();
    }

    @ModifyConstant(method = "controlBoat", constant = @Constant(floatValue = 0.04F))
    private float boostForwardAcceleration(float original) {
        return 0.052F + boostProgress * 0.0312F;
    }

    @ModifyConstant(method = "controlBoat", constant = @Constant(floatValue = 0.005F, ordinal = 1))
    private float boostReverseAcceleration(float original) {
        return 0.0065F;
    }
}
