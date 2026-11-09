package com.example.skyconstruction.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Защитный миксин для безопасного получения освещения при работе с Sable (Create Aeronautics)
 * и расширенными границами мира.
 * Предотвращает вылеты и возвращает корректные безопасные значения (15 для неба, 0 для блоков)
 * при запросах выше ванильного диапазона (Y > 320) и за пределами допустимых высот.
 */
@Pseudo
@Mixin(value = BlockAndTintGetter.class, priority = 1500)
public interface SableLightEngineMixin {

    @Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
    default void skyForge$safeGetBrightness(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        try {
            if (pos != null && (pos.getY() > 320 || pos.getY() < -64)) {
                cir.setReturnValue(layer == LightLayer.SKY ? 15 : 0);
            }
        } catch (Throwable ignored) {
            cir.setReturnValue(layer == LightLayer.SKY ? 15 : 0);
        }
    }

    @Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
    default void skyForge$safeGetRawBrightness(BlockPos pos, int ambientDarkness, CallbackInfoReturnable<Integer> cir) {
        try {
            if (pos != null && (pos.getY() > 320 || pos.getY() < -64)) {
                cir.setReturnValue(Math.max(0, 15 - ambientDarkness));
            }
        } catch (Throwable ignored) {
            cir.setReturnValue(Math.max(0, 15 - ambientDarkness));
        }
    }
}
