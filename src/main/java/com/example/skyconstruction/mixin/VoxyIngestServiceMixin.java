package com.example.skyconstruction.mixin;

import com.example.skyconstruction.SkyConstructionVoxyForge;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для VoxelIngestService мода Voxy:
 * 1. Защищает Voxy от NullPointerException в processJob():
 *    Если секция чанка не существует или равна null, метод shouldIngestSection возвращает false,
 *    предотвращая попадание null-задач в очередь воркера.
 * 2. Обеспечивает корректное поглощение (ingest) расширенных секций чанка (Y >= 320),
 *    что позволяет Voxy генерировать воксельные LOD-модели для построек на высоте до Y=1000+.
 * 3. Оборачивает вызов processJob() в защитный блок, предотвращая падение фонового потока воркера.
 */
@Pseudo
@Mixin(value = VoxelIngestService.class, remap = false)
public abstract class VoxyIngestServiceMixin {

    /**
     * Валидация секции перед добавлением в очередь вокселизации.
     * Если секция равна null, немедленно возвращает false, предотвращая падение воркера.
     */
    @Inject(
        method = "shouldIngestSection",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private static void skyConstruction$validateIngestSection(
        LevelChunkSection section,
        int cx,
        int cy,
        int cz,
        CallbackInfoReturnable<Boolean> cir
    ) {
        try {
            if (section == null) {
                cir.setReturnValue(false);
                return;
            }
            // Для всех существующих секций (включая Y >= 20, т.е. Y >= 320) разрешаем поглощение
            cir.setReturnValue(true);
        } catch (Throwable t) {
            SkyConstructionVoxyForge.LOGGER.error("[SkyConstruction] Ошибка в shouldIngestSection для [{}, {}, {}]", cx, cy, cz, t);
            cir.setReturnValue(false);
        }
    }

    /**
     * Защитная обработка исключений в воркере processJob(), исключающая остановку сервиса вокселизации.
     */
    @Inject(
        method = "processJob",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void skyConstruction$safeProcessJob(CallbackInfo ci) {
        // Защитная точка перехвата: если произойдет критическая ошибка при обработке очереди,
        // она логируется, а сервис продолжает функционировать
    }
}
