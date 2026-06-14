package com.victorfaurschou.fasterboats.mixin;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(AbstractBoat.class)
public class BoatSpeedMixin {
    @ModifyConstant(method = "controlBoat", constant = @Constant(floatValue = 0.04F))
    private float boostForwardAcceleration(float original) {
        return 0.06F;
    }

    @ModifyConstant(method = "controlBoat", constant = @Constant(floatValue = 0.005F, ordinal = 1))
    private float boostReverseAcceleration(float original) {
        return 0.0065F;
    }
}
