package com.example.skyconstruction.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * Защитный миксин для LayerLightSectionStorage.
 * Предотвращает вылет игры с NullPointerException при обращении к DataLayer на больших высотах (например, Y=2000+),
 * где ванильный движок света не инициализирует световые секции на клиенте.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LayerLightSectionStorageMixin {

    @Shadow
    @Final
    protected LightLayer layer;

    @Shadow
    @Nullable
    protected abstract DataLayer getDataLayer(long sectionPos, boolean cached);

    /**
     * Перехват чтения уровня освещения блока.
     * Если DataLayer для секции отсутствует (datalayer == null), возвращает:
     * - 15 (полный свет) для небесного освещения (LightLayer.SKY), так как на открытой высоте свет максимален.
     * - 0 (темнота) для блочного освещения (LightLayer.BLOCK).
     */
    @Inject(method = "getStoredLevel", at = @At("HEAD"), cancellable = true)
    private void skyForge$onGetStoredLevel(long posIndex, CallbackInfoReturnable<Integer> cir) {
        long sectionPos = SectionPos.blockToSection(posIndex);
        DataLayer datalayer = this.getDataLayer(sectionPos, true);
        if (datalayer == null) {
            cir.setReturnValue(this.layer == LightLayer.SKY ? 15 : 0);
        }
    }

    /**
     * Перехват записи уровня освещения блока.
     * Если DataLayer для секции отсутствует, отменяем запись во избежание NPE.
     */
    @Inject(method = "setStoredLevel", at = @At("HEAD"), cancellable = true)
    private void skyForge$onSetStoredLevel(long posIndex, int level, CallbackInfo ci) {
        long sectionPos = SectionPos.blockToSection(posIndex);
        DataLayer datalayer = this.getDataLayer(sectionPos, true);
        if (datalayer == null) {
            ci.cancel();
        }
    }
}
