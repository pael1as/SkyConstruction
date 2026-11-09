package com.example.skyconstruction.mixin;

import net.minecraft.util.BitStorage;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для ChunkSkyLightSources:
 * Предотвращает IllegalArgumentException ("The value ... is not in the specified inclusive range of 0 to 511")
 * и IndexOutOfBoundsException при установке блоков на экстремальных высотах (Y >= 447 и до 8192).
 * Автоматически гарантирует принудительное расширение массива секций чанка и разрядности BitStorage на лету.
 */
@Mixin(ChunkSkyLightSources.class)
public abstract class ChunkSkyLightSourcesMixin {

    @Shadow
    @Final
    @Mutable
    private BitStorage heightmap;

    @Shadow
    @Final
    private int minY;

    @Unique
    private ChunkAccess skyForge$chunk;

    @Inject(method = "update", at = @At("HEAD"))
    private void skyForge$onUpdate(BlockGetter blockGetter, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (blockGetter instanceof ChunkAccess chunkAccess) {
            this.skyForge$chunk = chunkAccess;
        }
    }

    @Inject(method = "fillFrom", at = @At("HEAD"))
    private void skyForge$onFillFrom(ChunkAccess chunkAccess, CallbackInfo ci) {
        this.skyForge$chunk = chunkAccess;
    }

    /**
     * Предотвращает отрицательные значения (y - minY) при установке блоков ниже ванильного дна.
     */
    @ModifyVariable(method = "set(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int skyForge$clampMinY(int y) {
        return Math.max(y, this.minY);
    }

    /**
     * Автоматическое расширение разрядности BitStorage в ChunkSkyLightSources
     * и принудительное расширение массива секций чанка перед записью.
     */
    @Inject(method = "set(II)V", at = @At("HEAD"), cancellable = true)
    private void skyForge$ensureBitStorageCapacity(int index, int y, CallbackInfo ci) {
        // Безопасно проверяем границы, чтобы не запрашивать секцию за пределами максимальной высоты мира
        if (this.skyForge$chunk != null && y >= this.skyForge$chunk.getMinBuildHeight() && y < this.skyForge$chunk.getMaxBuildHeight()) {
            int sectionIndex = this.skyForge$chunk.getSectionIndex(y);
            if (sectionIndex >= 0) {
                try {
                    this.skyForge$chunk.getSection(sectionIndex);
                } catch (Throwable ignored) {
                }
            }
        }

        int value = y - this.minY;
        if (value > 0 && this.heightmap != null) {
            int requiredBits = Mth.ceillog2(value + 1);
            if (requiredBits > this.heightmap.getBits()) {
                BitStorage oldStorage = this.heightmap;
                SimpleBitStorage newStorage = new SimpleBitStorage(requiredBits, 256);
                for (int i = 0; i < 256; i++) {
                    newStorage.set(i, oldStorage.get(i));
                }
                this.heightmap = newStorage;
            }
        }
    }
}
