package com.example.skyconstruction.mixin;

import com.example.skyconstruction.SkyConstructionVoxyForge;
import com.example.skyconstruction.client.ClientSkyConstructionData;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для VoxyRenderSystem мода Voxy (LOD Renderer).
 * 
 * Назначение:
 * 1. Расширение сетки высот Voxy (RenderDistanceTracker) за пределы ванильных Y=-64..320.
 *    По умолчанию Voxy вычисляет maxSec как (ClientLevel.getMaxSection() - 1) >> 5 = 0,
 *    что охватывает секции только до Y=511. Блоки на высоте Y=1000 (секция 62) полностью
 *    игнорируются и не попадают в дерево верхнего уровня (TopLevelNode).
 *    Миксин расширяет maxSec, гарантируя генерацию и рендеринг LOD для высот до 1000+ блоков.
 * 2. Защита основного рендер-цикла renderOpaque и setupViewport от падений при выходе за границы.
 */
@Pseudo
@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class VoxyRenderSystemMixin {

    @Shadow
    private RenderDistanceTracker renderDistanceTracker;

    /**
     * Расширяет верхнюю границу секций (maxSec, аргумент index 2) в конструкторе RenderDistanceTracker.
     * Voxy использует сетку LOD уровня 4, где каждая нода верхнего уровня охватывает 32 секции (512 блоков).
     * Значение maxSec = 0 охватывает секции 0..31 (Y=0..511).
     * Значение maxSec = 1 охватывает секции 32..63 (Y=512..1023).
     * Значение maxSec = 3 охватывает секции до 127 (Y до 2047).
     */
    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;<init>(IIILjava/util/function/LongConsumer;Ljava/util/function/LongConsumer;)V"
        ),
        index = 2,
        require = 1
    )
    private int skyConstruction$expandVoxyMaxSection(int maxSec) {
        try {
            int globalMaxY = ClientSkyConstructionData.getGlobalMaxY();
            // Гарантируем охват как минимум высоты 1000 (секция 62 -> maxSec >= 1)
            int targetMaxY = Math.max(1000, globalMaxY);
            int calculatedMaxSec = (targetMaxY - 1) >> 9;
            int expandedMaxSec = Math.max(maxSec, Math.max(1, calculatedMaxSec));

            SkyConstructionVoxyForge.LOGGER.info(
                "[SkyConstruction] Voxy RenderDistanceTracker maxSec успешно расширен: {} -> {} (Целевая высота Y={})",
                maxSec, expandedMaxSec, targetMaxY
            );
            return expandedMaxSec;
        } catch (Throwable t) {
            SkyConstructionVoxyForge.LOGGER.error("[SkyConstruction] Ошибка при расширении maxSec для Voxy RenderDistanceTracker", t);
            return Math.max(maxSec, 1);
        }
    }

    /**
     * Расширяет нижнюю границу секций (minSec, аргумент index 1) в конструкторе RenderDistanceTracker
     * при наличии зон с отрицательными высотами ниже -64.
     */
    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;<init>(IIILjava/util/function/LongConsumer;Ljava/util/function/LongConsumer;)V"
        ),
        index = 1,
        require = 1
    )
    private int skyConstruction$expandVoxyMinSection(int minSec) {
        try {
            int globalMinY = ClientSkyConstructionData.getGlobalMinY();
            int targetMinY = Math.min(-64, globalMinY);
            int calculatedMinSec = targetMinY >> 9;
            return Math.min(minSec, calculatedMinSec);
        } catch (Throwable t) {
            SkyConstructionVoxyForge.LOGGER.error("[SkyConstruction] Ошибка при расширении minSec для Voxy RenderDistanceTracker", t);
            return minSec;
        }
    }

    /**
     * Защитная обертка вокруг renderOpaque: перехватывает возможные сбои OpenGL или конвейера Voxy
     * при отрисовке экстремальных высот, предотвращая краш клиента.
     */
    @Inject(
        method = "renderOpaque",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void skyConstruction$safeRenderOpaque(Viewport<?> viewport, CallbackInfo ci) {
        try {
            if (viewport == null || viewport.width <= 0 || viewport.height <= 0) {
                ci.cancel();
            }
        } catch (Throwable t) {
            SkyConstructionVoxyForge.LOGGER.error("[SkyConstruction] Исключение в VoxyRenderSystem.renderOpaque, пропуск кадра", t);
            ci.cancel();
        }
    }

    /**
     * Защитная проверка при инициализации Viewport в Voxy.
     */
    @Inject(
        method = "setupViewport",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void skyConstruction$safeSetupViewport(ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ, CallbackInfoReturnable<Viewport<?>> cir) {
        try {
            if (matrices == null || matrices.projection() == null || matrices.modelView() == null) {
                cir.setReturnValue(null);
            }
        } catch (Throwable t) {
            SkyConstructionVoxyForge.LOGGER.error("[SkyConstruction] Исключение в VoxyRenderSystem.setupViewport", t);
            cir.setReturnValue(null);
        }
    }
}
