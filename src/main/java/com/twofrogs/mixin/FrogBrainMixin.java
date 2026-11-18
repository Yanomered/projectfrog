package com.twofrogs.mixin;

import com.twofrogs.frog.EscapeControl;
import net.minecraft.world.entity.animal.frog.Frog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips the vanilla frog brain while one of our special frogs is free and under our control. */
@Mixin(Frog.class)
public abstract class FrogBrainMixin {

    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void twofrogs$skipBrainWhileControlled(CallbackInfo ci) {
        Frog self = (Frog) (Object) this;
        if (EscapeControl.isControlled(self.getUUID())) {
            ci.cancel();
        }
    }
}