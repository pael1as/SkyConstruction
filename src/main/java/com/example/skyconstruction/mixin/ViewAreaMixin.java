package com.example.skyconstruction.mixin;

import com.example.skyconstruction.client.ClientSkyConstructionData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для клиентского класса ViewArea.
 * Отвечает за расширение вертикальной сетки RenderSection[] (sectionGridSizeY)
 * на клиенте, чтобы игра создавала буферы отрисовки и компилировала меши
 * для блоков выше ванильного лимита Y=320 с учетом ClientSkyConstructionData.getGlobalMaxY().
 */
@Mixin(ViewArea.class)
public abstract class ViewAreaMixin {

    @Shadow @Final protected LevelRenderer levelRenderer;
    @Shadow @Final protected Level level;
    @Shadow protected int sectionGridSizeY;
    @Shadow protected int sectionGridSizeX;
    @Shadow protected int sectionGridSizeZ;
    @Shadow public SectionRenderDispatcher.RenderSection[] sections;

    @Shadow public abstract void releaseAllBuffers();
    @Shadow protected abstract void createSections(SectionRenderDispatcher dispatcher);

    @Unique
    private SectionRenderDispatcher skyForge$dispatcher;

    @Unique
    private boolean skyForge$expanding = false;

    /**
     * Кэшированный экземпляр LevelHeightAccessor.
     * Предотвращает миллионы аллокаций в секунду в циклах SectionOcclusionGraph raycast,
     * исключая утечки памяти, фризы GC и JVM-краши.
     */
    @Unique
    private static final LevelHeightAccessor skyForge$cachedAccessor = new LevelHeightAccessor() {
        @Override
        public int getHeight() {
            return getMaxBuildHeight() - getMinBuildHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return ClientSkyConstructionData.getGlobalMinY();
        }

        @Override
        public int getMaxBuildHeight() {
            return ClientSkyConstructionData.getGlobalMaxY();
        }
    };

    /**
     * Сохраняем ссылку на SectionRenderDispatcher при создании ViewArea,
     * чтобы иметь возможность динамически пересоздавать рендер-секции при изменении границ.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void skyForge$onInit(SectionRenderDispatcher dispatcher, Level level, int viewDistance, LevelRenderer levelRenderer, CallbackInfo ci) {
        this.skyForge$dispatcher = dispatcher;
    }

    /**
     * Перехватываем установку дистанции прорисовки, где инициализируется sectionGridSizeY.
     * Вместо жестко фиксированного level.getSectionsCount() (ванильные 24 секции для -64..320)
     * рассчитываем размер сетки с учетом глобального максимума ClientSkyConstructionData.
     * Если размер сетки увеличился, безопасно пересоздаем массив sections соответствующей длины.
     */
    @Inject(method = "setViewDistance", at = @At("TAIL"))
    private void skyForge$onSetViewDistance(int viewDistance, CallbackInfo ci) {
        int maxY = Math.max(this.level.getMaxBuildHeight(), ClientSkyConstructionData.getGlobalMaxY());
        int minY = Math.min(this.level.getMinBuildHeight(), ClientSkyConstructionData.getGlobalMinY());
        int totalHeight = maxY - minY;
        int customSections = Math.min(512, (totalHeight + 15) / 16);

        if (customSections > this.sectionGridSizeY) {
            this.sectionGridSizeY = customSections;
            if (this.skyForge$dispatcher != null) {
                this.releaseAllBuffers();
                this.sections = new SectionRenderDispatcher.RenderSection[this.sectionGridSizeX * this.sectionGridSizeY * this.sectionGridSizeZ];
                this.createSections(this.skyForge$dispatcher);
            }
        }
    }

    /**
     * Перед обновлением позиции камеры проверяем, не расширились ли границы мира.
     * Мгновенно обновляем sectionGridSizeY, исключая повторный вызов allChanged() на каждом кадре.
     * При наличии Sodium вызов allChanged() полностью пропускается, так как Sodium использует RenderSectionManager.
     */
    @Inject(method = "repositionCamera", at = @At("HEAD"))
    private void skyForge$onRepositionCamera(double x, double z, CallbackInfo ci) {
        if (this.skyForge$expanding) {
            return;
        }

        int maxY = Math.max(this.level.getMaxBuildHeight(), ClientSkyConstructionData.getGlobalMaxY());
        int minY = Math.min(this.level.getMinBuildHeight(), ClientSkyConstructionData.getGlobalMinY());
        int totalHeight = maxY - minY;
        int requiredSections = Math.min(512, (totalHeight + 15) / 16);

        if (requiredSections > this.sectionGridSizeY) {
            this.sectionGridSizeY = requiredSections;
            if (ModList.get().isLoaded("sodium")) {
                return;
            }
            this.skyForge$expanding = true;
            Minecraft.getInstance().execute(() -> {
                try {
                    this.levelRenderer.allChanged();
                } finally {
                    this.skyForge$expanding = false;
                }
            });
        }
    }

    /**
     * Возвращаем статический кэшированный LevelHeightAccessor для SectionOcclusionGraph,
     * чтобы алгоритм отсечения невидимых секций (frustum/occlusion culling)
     * учитывал кастомный максимум высоты без создания объектов в куче.
     */
    @Inject(method = "getLevelHeightAccessor", at = @At("HEAD"), cancellable = true)
    private void skyForge$getLevelHeightAccessor(CallbackInfoReturnable<LevelHeightAccessor> cir) {
        cir.setReturnValue(skyForge$cachedAccessor);
    }

    /**
     * Безопасная проверка границ для setDirty.
     * При выходе за границы сетки отменяем обработку через ci.cancel(),
     * предотвращая ArrayIndexOutOfBoundsException в SectionRenderDispatcher.
     */
    @Inject(method = "setDirty", at = @At("HEAD"), cancellable = true)
    private void skyForge$onSetDirty(int chunkX, int sectionY, int chunkZ, boolean reRenderOnMainThread, CallbackInfo ci) {
        int sectionIndexY = sectionY - this.level.getMinSection();
        if (sectionIndexY < 0) {
            ci.cancel();
            return;
        }
        if (sectionIndexY >= this.sectionGridSizeY) {
            int totalHeight = Math.max(this.level.getMaxBuildHeight(), ClientSkyConstructionData.getGlobalMaxY()) - Math.min(this.level.getMinBuildHeight(), ClientSkyConstructionData.getGlobalMinY());
            int requiredSections = Math.min(512, (totalHeight + 15) / 16);
            if (requiredSections > this.sectionGridSizeY) {
                this.sectionGridSizeY = requiredSections;
                if (!ModList.get().isLoaded("sodium") && !this.skyForge$expanding) {
                    this.skyForge$expanding = true;
                    Minecraft.getInstance().execute(() -> {
                        try {
                            this.levelRenderer.allChanged();
                        } finally {
                            this.skyForge$expanding = false;
                        }
                    });
                }
            }
            ci.cancel();
        }
    }
}
