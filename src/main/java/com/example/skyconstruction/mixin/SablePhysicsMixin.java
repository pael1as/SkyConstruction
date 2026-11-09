package com.example.skyconstruction.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для физического акселератора Sable (LevelAccelerator).
 *
 * Назначение:
 * 1. Устраняет проблему пролета физических контрапшенов (Create Aeronautics) сквозь блоки выше Y=319.
 *    Оригинальный LevelAccelerator кеширует maxBuildHeight в final-поле при создании (часто 320)
 *    и в методе getBlockState(...) жестко возвращает Blocks.AIR при y >= maxBuildHeight.
 *    Из-за этого физический движок Rapier3D строит абсолютно пустые воксельные коллайдеры для мира на высоте Y >= 320.
 * 2. Перенаправляет вызовы getBlockState(chunk, pos) и getBlockState(pos) на проверенный метод
 *    LevelChunk.getBlockState(pos), в котором наш LevelChunkMixin уже гарантирует получение реальных
 *    блоков из расширенного массива секций.
 * 3. Динамически синхронизирует границы высоты (minBuildHeight, maxBuildHeight, minSection) с актуальными
 *    данными уровня через @Shadow @Mutable без использования Unsafe.
 * 4. Защищает getFluidState и isOutsideBuildHeight от вылетов и некорректных ванильных отсечений.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.util.LevelAccelerator", remap = false)
public abstract class SablePhysicsMixin {

    @Shadow(remap = false)
    @Final
    private Level level;

    @Shadow(remap = false)
    @Final
    @Mutable
    private int minBuildHeight;

    @Shadow(remap = false)
    @Final
    @Mutable
    private int maxBuildHeight;

    @Shadow(remap = false)
    @Final
    @Mutable
    private int minSection;

    @Shadow(remap = false)
    public abstract LevelChunk getChunk(BlockPos pos);

    /**
     * При создании акселератора актуализируем границы высоты.
     */
    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;)V", at = @At("RETURN"), remap = false, require = 1)
    private void skyForge$onInit(Level level, CallbackInfo ci) {
        if (level != null) {
            this.minBuildHeight = level.getMinBuildHeight();
            this.maxBuildHeight = level.getMaxBuildHeight();
            this.minSection = level.getMinSection();
        }
    }

    /**
     * Потокобезопасная синхронизация границ высоты.
     * Если высота мира была расширена динамически (например, при загрузке SkyConstructionData),
     * актуализируем внутренние поля акселератора.
     */
    @Unique
    private void skyForge$syncBounds() {
        if (this.level != null) {
            int currentMin = this.level.getMinBuildHeight();
            int currentMax = this.level.getMaxBuildHeight();
            if (this.maxBuildHeight != currentMax || this.minBuildHeight != currentMin) {
                this.minBuildHeight = currentMin;
                this.maxBuildHeight = currentMax;
                this.minSection = this.level.getMinSection();
            }
        }
    }

    /**
     * Перехват getBlockState(LevelChunk, BlockPos):
     * Вместо оригинального обрезания по закешированному maxBuildHeight (320), перенаправляет
     * запрос на LevelChunk.getBlockState(pos), гарантируя получение реального BlockState
     * на высоте до 1000+ блоков.
     */
    @Inject(
        method = "getBlockState(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 1
    )
    private void skyForge$getBlockStateWithChunk(LevelChunk chunk, BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (chunk == null || pos == null) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
            return;
        }

        skyForge$syncBounds();

        int y = pos.getY();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();

        if (y < minY || y >= maxY) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
            return;
        }

        // Вызываем LevelChunk.getBlockState, который перехвачен нашим LevelChunkMixin
        // и корректно извлекает блоки из расширенных секций чанка
        cir.setReturnValue(chunk.getBlockState(pos));
    }

    /**
     * Перехват getBlockState(BlockPos):
     * Безопасное получение блока по координатам с гарантией расширенных границ.
     */
    @Inject(
        method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 1
    )
    private void skyForge$getBlockStatePos(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (pos == null) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
            return;
        }

        skyForge$syncBounds();

        LevelChunk chunk = this.getChunk(pos);
        if (chunk == null) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
            return;
        }

        int y = pos.getY();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();

        if (y < minY || y >= maxY) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
            return;
        }

        cir.setReturnValue(chunk.getBlockState(pos));
    }

    /**
     * Перехват isOutsideBuildHeight(Vec3i):
     * Возвращает false для координат в пределах расширенного мира (до Y=1000+),
     * предотвращая ошибочное отсечение коллизий в Sable.
     */
    @Inject(
        method = "isOutsideBuildHeight(Lnet/minecraft/core/Vec3i;)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 1
    )
    private void skyForge$isOutsideBuildHeight(Vec3i pos, CallbackInfoReturnable<Boolean> cir) {
        if (pos == null) {
            cir.setReturnValue(true);
            return;
        }

        skyForge$syncBounds();

        int y = pos.getY();
        int minY = this.level != null ? this.level.getMinBuildHeight() : this.minBuildHeight;
        int maxY = this.level != null ? this.level.getMaxBuildHeight() : this.maxBuildHeight;

        cir.setReturnValue(y < minY || y >= maxY);
    }

    /**
     * Перехват getMinBuildHeight():
     * Возвращает актуальную нижнюю границу мира.
     */
    @Inject(
        method = "getMinBuildHeight()I",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 1
    )
    private void skyForge$getMinBuildHeight(CallbackInfoReturnable<Integer> cir) {
        if (this.level != null) {
            cir.setReturnValue(this.level.getMinBuildHeight());
        }
    }

    /**
     * Перехват getFluidState(BlockPos):
     * Защищает от NPE и возвращает корректную жидкость на расширенной высоте.
     */
    @Inject(
        method = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 1
    )
    private void skyForge$getFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (pos == null) {
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
            return;
        }

        LevelChunk chunk = this.getChunk(pos);
        if (chunk == null) {
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
            return;
        }

        cir.setReturnValue(chunk.getFluidState(pos));
    }
}
