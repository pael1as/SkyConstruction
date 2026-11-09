package com.example.skyconstruction.mixin;

import com.example.skyconstruction.SkyConstruction;
import com.example.skyconstruction.client.ClientSkyConstructionData;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для RenderSectionManager из Sodium 0.6.9+.
 *
 * Архитектура (почему пропадали чанки на Y >= 320 и как это исправлено):
 * Ванильный OcclusionCuller.init() при камере внутри "мира" (а границы мира расширены нашим
 * LevelHeightAccessorMixin до Y=1000) вызывает initWithinWorld(), который берёт корневую секцию
 * sectionByPosition.get(camera section) и при null просто делает return — BFS не стартует и
 * весь ближний рельеф исчезает. Кроме того, BFS ходит только по смежным (adjacent) секциям,
 * поэтому дыры в вертикальной колонке секций обрывают обход.
 *
 * Решение — не обходить OcclusionCuller, а гарантировать ему полноценный граф:
 * 1. Каждая колонка загруженного чанка содержит ВСЕ секции от minY до maxY (пустые — с
 *    BuiltSectionInfo.EMPTY, как в ванильном Sodium). Culling и BFS работают штатно, FPS сохраняется.
 * 2. Если границы высоты расширились после загрузки чанков (SyncPayload приходит позже чанков),
 *    колонки достраиваются лениво, с бюджетом на кадр, начиная с ближайших к камере.
 * 3. Корневая секция камеры гарантированно создаётся до запуска BFS.
 * 4. Никакого принудительного collector.visit() и никакой итерации по sectionByPosition в кадре.
 */
@Pseudo
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumRenderSectionManagerMixin {

    /** Сколько колонок чанков достраивать за один кадр. */
    @Unique
    private static final int SKYFORGE_COLUMN_BUDGET_PER_FRAME = 24;

    @Shadow(remap = false)
    @Final
    private ClientLevel level;

    @Shadow(remap = false)
    @Final
    private RenderRegionManager regions;

    @Shadow(remap = false)
    @Final
    private Long2ReferenceMap<RenderSection> sectionByPosition;

    @Shadow(remap = false)
    private boolean needsGraphUpdate;

    @Shadow(remap = false)
    protected abstract boolean updateSectionInfo(RenderSection section, BuiltSectionInfo info);

    @Shadow(remap = false)
    protected abstract void connectNeighborNodes(RenderSection section);

    @Shadow(remap = false)
    public abstract void onSectionAdded(int x, int y, int z);

    /** Колонки (ChunkPos.asLong), которые ещё нужно достроить до полной высоты. */
    @Unique
    private final LongArrayList skyForge$pendingColumns = new LongArrayList();

    /** Сигнатура границ высоты, для которой сформирован список pendingColumns. */
    @Unique
    private long skyForge$columnSignature = Long.MIN_VALUE;

    @Unique
    private boolean skyForge$errorLogged;

    @Unique
    private void skyForge$logOnce(Throwable t) {
        if (!this.skyForge$errorLogged) {
            this.skyForge$errorLogged = true;
            SkyConstruction.LOGGER.warn("[SkyConstruction] Ошибка при управлении секциями Sodium (повторы подавлены)", t);
        }
    }

    /**
     * Безопасное создание RenderSection в Sodium для расширенных зон.
     * Секции вне допустимых границ не создаются; регион обязан существовать — подмена региона
     * на y=0 удалена, так как она портила бы слоты чужих секций в RenderRegion.
     */
    @Inject(method = "onSectionAdded", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void skyForge$onSectionAdded(int x, int y, int z, CallbackInfo ci) {
        try {
            long key = SectionPos.asLong(x, y, z);
            if (this.sectionByPosition.containsKey(key)) {
                ci.cancel();
                return;
            }

            int maxAllowedSecY = SectionPos.blockToSectionCoord(ClientSkyConstructionData.getGlobalMaxY() - 1);
            int minAllowedSecY = SectionPos.blockToSectionCoord(ClientSkyConstructionData.getGlobalMinY());
            int vanillaMinSec = this.level.dimensionType().minY() >> 4;
            int vanillaMaxSec = (this.level.dimensionType().minY() + this.level.dimensionType().height()) >> 4;
            maxAllowedSecY = Math.max(maxAllowedSecY, vanillaMaxSec - 1);
            minAllowedSecY = Math.min(minAllowedSecY, vanillaMinSec);

            if (y < minAllowedSecY || y > maxAllowedSecY) {
                ci.cancel();
                return;
            }

            RenderRegion region = this.regions.createForChunk(x, y, z);
            if (region == null) {
                ci.cancel();
                return;
            }

            RenderSection section = new RenderSection(region, x, y, z);
            region.addSection(section);
            this.sectionByPosition.put(key, section);

            LevelChunk chunk = this.level.getChunk(x, z);
            LevelChunkSection s = null;
            if (chunk != null) {
                int idx = this.level.getSectionIndexFromSectionY(y);
                LevelChunkSection[] sections = chunk.getSections();
                if (sections != null && idx >= 0 && idx < sections.length) {
                    s = sections[idx];
                }
            }

            if (s == null || s.hasOnlyAir()) {
                this.updateSectionInfo(section, BuiltSectionInfo.EMPTY);
            } else {
                section.setPendingUpdate(ChunkUpdateType.IMPORTANT_REBUILD);
            }

            this.connectNeighborNodes(section);
            this.needsGraphUpdate = true;
            ci.cancel();
        } catch (Throwable t) {
            this.skyForge$logOnce(t);
            ci.cancel();
        }
    }

    /**
     * Добавление чанка: создаём полную колонку секций (ванильный диапазон + расширенный),
     * включая пустые — это гарантирует связность графа для BFS OcclusionCuller.
     */
    @Inject(method = "onChunkAdded", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void skyForge$onChunkAdded(int chunkX, int chunkZ, CallbackInfo ci) {
        ci.cancel();
        try {
            this.skyForge$completeColumn(chunkX, chunkZ);
        } catch (Throwable t) {
            this.skyForge$logOnce(t);
        }
    }

    /**
     * Создаёт все недостающие секции колонки от нижней до верхней допустимой границы.
     */
    @Unique
    private void skyForge$completeColumn(int chunkX, int chunkZ) {
        int vanillaMin = this.level.dimensionType().minY() >> 4;
        int vanillaMax = (this.level.dimensionType().minY() + this.level.dimensionType().height()) >> 4;
        int minSec = Math.min(vanillaMin, SectionPos.blockToSectionCoord(ClientSkyConstructionData.getGlobalMinY()));
        int maxSec = Math.max(vanillaMax - 1, SectionPos.blockToSectionCoord(ClientSkyConstructionData.getGlobalMaxY() - 1));

        for (int y = minSec; y <= maxSec; ++y) {
            if (!this.sectionByPosition.containsKey(SectionPos.asLong(chunkX, y, chunkZ))) {
                this.onSectionAdded(chunkX, y, chunkZ);
            }
        }
    }

    /**
     * При изменении блока за пределами ванильного диапазона создаёт секцию при необходимости
     * и помечает её на IMPORTANT_REBUILD. Ванильные секции Sodium обновляет штатно.
     */
    @Inject(method = "scheduleRebuild", at = @At("HEAD"), remap = false, require = 1)
    private void skyForge$onScheduleRebuild(int x, int y, int z, boolean important, CallbackInfo ci) {
        if (this.level == null) {
            return;
        }

        int vanillaMinSec = this.level.dimensionType().minY() >> 4;
        int vanillaMaxSec = (this.level.dimensionType().minY() + this.level.dimensionType().height()) >> 4;
        int globalMaxY = ClientSkyConstructionData.getGlobalMaxY();
        int globalMinY = ClientSkyConstructionData.getGlobalMinY();

        if (y >= vanillaMinSec && y < vanillaMaxSec) {
            return;
        }
        if (globalMaxY <= 320 && globalMinY >= -64) {
            return;
        }
        if (y < SectionPos.blockToSectionCoord(globalMinY) || y > SectionPos.blockToSectionCoord(globalMaxY - 1)) {
            return;
        }

        try {
            long key = SectionPos.asLong(x, y, z);
            RenderSection section = this.sectionByPosition.get(key);
            if (section == null) {
                this.onSectionAdded(x, y, z);
                section = this.sectionByPosition.get(key);
            }
            if (section != null && section.getPendingUpdate() == null) {
                section.setPendingUpdate(ChunkUpdateType.IMPORTANT_REBUILD);
                this.needsGraphUpdate = true;
            }
        } catch (Throwable t) {
            this.skyForge$logOnce(t);
        }
    }

    /**
     * Защита createRebuildTask от ArrayIndexOutOfBoundsException в LevelSlice.prepare:
     * для пустых/отсутствующих расширенных секций возвращаем null.
     */
    @Inject(method = "createRebuildTask", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void skyForge$onCreateRebuildTask(
        RenderSection section,
        int frame,
        CallbackInfoReturnable<ChunkBuilderMeshingTask> cir
    ) {
        if (section == null || this.level == null) {
            cir.setReturnValue(null);
            return;
        }

        int secY = section.getChunkY();
        int vanillaMinSec = this.level.dimensionType().minY() >> 4;
        int vanillaMaxSec = (this.level.dimensionType().minY() + this.level.dimensionType().height()) >> 4;

        if (secY >= vanillaMinSec && secY < vanillaMaxSec) {
            return;
        }

        LevelChunk chunk = this.level.getChunk(section.getChunkX(), section.getChunkZ());
        if (chunk == null) {
            cir.setReturnValue(null);
            return;
        }

        int secIdx = this.level.getSectionIndexFromSectionY(secY);
        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || secIdx < 0 || secIdx >= sections.length) {
            cir.setReturnValue(null);
            return;
        }

        LevelChunkSection s = sections[secIdx];
        if (s == null || s.hasOnlyAir()) {
            cir.setReturnValue(null);
        }
    }

    /**
     * Подготовка графа перед обходом OcclusionCuller (вызывается каждый кадр, O(1) в штатном режиме):
     * 1. Гарантирует наличие корневой секции камеры (иначе BFS не стартует).
     * 2. При изменении границ высоты достраивает колонки уже загруженных чанков порциями.
     */
    @Inject(method = "createTerrainRenderList", at = @At("HEAD"), remap = false, require = 1)
    private void skyForge$prepareGraph(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfo ci) {
        if (this.level == null) {
            return;
        }
        int globalMaxY = ClientSkyConstructionData.getGlobalMaxY();
        int globalMinY = ClientSkyConstructionData.getGlobalMinY();
        if (globalMaxY <= 320 && globalMinY >= -64) {
            return;
        }

        try {
            int vanillaMin = this.level.dimensionType().minY() >> 4;
            int vanillaMax = (this.level.dimensionType().minY() + this.level.dimensionType().height()) >> 4;
            int minSec = Math.min(vanillaMin, SectionPos.blockToSectionCoord(globalMinY));
            int maxSec = Math.max(vanillaMax - 1, SectionPos.blockToSectionCoord(globalMaxY - 1));

            BlockPos camPos = camera.getBlockPosition();
            int camX = SectionPos.blockToSectionCoord(camPos.getX());
            int camY = SectionPos.blockToSectionCoord(camPos.getY());
            int camZ = SectionPos.blockToSectionCoord(camPos.getZ());

            // 1. Корневая секция камеры
            if (camY >= minSec && camY <= maxSec
                && !this.sectionByPosition.containsKey(SectionPos.asLong(camX, camY, camZ))
                && this.level.getChunkSource().hasChunk(camX, camZ)) {
                this.onSectionAdded(camX, camY, camZ);
            }

            // 2. Пересборка очереди колонок при смене границ высоты
            long signature = ((long) minSec << 32) ^ (maxSec & 0xFFFFFFFFL);
            if (signature != this.skyForge$columnSignature) {
                this.skyForge$columnSignature = signature;
                this.skyForge$pendingColumns.clear();
                LongIterator it = this.sectionByPosition.keySet().iterator();
                while (it.hasNext()) {
                    long key = it.nextLong();
                    if (SectionPos.y(key) == vanillaMin) {
                        this.skyForge$pendingColumns.add(ChunkPos.asLong(SectionPos.x(key), SectionPos.z(key)));
                    }
                }
                // Сортировка по убыванию расстояния: ближайшие колонки лежат в конце и снимаются первыми
                final int cx = camX;
                final int cz = camZ;
                this.skyForge$pendingColumns.sort((long a, long b) -> Long.compare(
                    skyForge$dist2(b, cx, cz), skyForge$dist2(a, cx, cz)));
            }

            // 3. Достройка колонок с бюджетом на кадр
            int budget = SKYFORGE_COLUMN_BUDGET_PER_FRAME;
            while (budget-- > 0 && !this.skyForge$pendingColumns.isEmpty()) {
                long col = this.skyForge$pendingColumns.removeLong(this.skyForge$pendingColumns.size() - 1);
                int x = ChunkPos.getX(col);
                int z = ChunkPos.getZ(col);
                if (this.level.getChunkSource().hasChunk(x, z)) {
                    this.skyForge$completeColumn(x, z);
                }
            }
        } catch (Throwable t) {
            this.skyForge$logOnce(t);
        }
    }

    @Unique
    private static long skyForge$dist2(long chunkLong, int cx, int cz) {
        long dx = ChunkPos.getX(chunkLong) - cx;
        long dz = ChunkPos.getZ(chunkLong) - cz;
        return dx * dx + dz * dz;
    }

    /**
     * Очистка состояния при смене мира, закрытии рендерера или смене дистанции прорисовки
     * (Sodium пересоздаёт менеджер через destroy()).
     */
    @Inject(method = "destroy", at = @At("HEAD"), remap = false, require = 1)
    private void skyForge$onDestroy(CallbackInfo ci) {
        this.skyForge$pendingColumns.clear();
        this.skyForge$columnSignature = Long.MIN_VALUE;
    }
}
