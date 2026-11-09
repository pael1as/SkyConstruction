package com.example.skyconstruction.mixin;

import com.example.skyconstruction.client.ClientSkyConstructionData;
import com.example.skyconstruction.data.SkyConstructionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для LevelChunk:
 * 1. Возвращает актуальный массив секций в getSections() и синхронизирует базовое поле sections.
 * 2. Перехватывает getBlockState(BlockPos) для возврата реального BlockState выше Y=320 (коллизия и физика).
 * 3. Перехватывает getFluidState(int, int, int) для корректной физики жидкостей.
 * 4. Переопределяет границы высоты на обеих сторонах на основе SkyConstructionData / ClientSkyConstructionData.
 * 5. Безопасно считывает секции пакета в replaceWithPacketData, предотвращая Netty buffer underflow.
 * 6. При создании LevelChunk из ProtoChunk восстанавливает расширенные секции, прочитанные из NBT.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin extends ChunkAccess {

    @Shadow
    @Final
    protected Level level;

    @Unique
    private LevelChunkSection[] skyForge$paddedSections;

    public LevelChunkMixin() {
        super(null, null, null, null, 0L, null, null);
    }

    @Unique
    private LevelChunkSection skyForge$createEmptySection(Registry<Biome> biomeRegistry, LevelChunkSection[] existing) {
        if (biomeRegistry != null) {
            return new LevelChunkSection(biomeRegistry);
        }
        if (existing != null) {
            for (LevelChunkSection s : existing) {
                if (s != null && s.getBiomes() != null) {
                    return new LevelChunkSection(
                        new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                        s.getBiomes().recreate()
                    );
                }
            }
        }
        return null;
    }

    /**
     * При создании LevelChunk из ProtoChunk (после чтения чанка с диска) переносим расширенные
     * секции из ProtoChunk в создаваемый LevelChunk, так как ванильный конструктор ChunkAccess
     * отбрасывает массивы длины != 24.
     */
    @Inject(
        method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
        at = @At("RETURN"),
        require = 1
    )
    private void skyForge$onConstructFromProtoChunk(ServerLevel level, ProtoChunk protoChunk, LevelChunk.PostLoadProcessor postLoad, CallbackInfo ci) {
        LevelChunkSection[] protoSections = ((ChunkAccessAccessor) protoChunk).skyForge$getSections();
        if (protoSections != null && protoSections.length > 24) {
            ((ChunkAccessAccessor) this).skyForge$setSections(protoSections);
        }
        this.getSections();
    }

    /**
     * Возвращает актуальный массив секций, включая расширенные.
     * На клиенте и сервере безопасно дополняет длину массива при наличии расширенных зон,
     * заполняя пустые слоты валидными пустыми секциями (hasOnlyAir = true),
     * исключая NullPointerException и ArrayIndexOutOfBoundsException в Sodium и свиперах коллизий Lithium.
     * Синхронизирует поле sections в ChunkAccess.
     */
    @Override
    public LevelChunkSection[] getSections() {
        LevelChunkSection[] currentSections = ((ChunkAccessAccessor) this).skyForge$getSections();
        int maxGlobalY = 320;
        if (this.level != null) {
            if (this.level.isClientSide()) {
                maxGlobalY = ClientSkyConstructionData.getGlobalMaxY();
            } else if (this.level instanceof ServerLevel serverLevel) {
                SkyConstructionData data = SkyConstructionData.get(serverLevel);
                if (data != null) {
                    maxGlobalY = data.getGlobalMaxY();
                }
            }
        }
        if (maxGlobalY > 320 && this.level != null) {
            int targetLen = this.level.getSectionsCount();
            if (targetLen > 0) {
                if (this.skyForge$paddedSections == null || this.skyForge$paddedSections.length != targetLen) {
                    this.skyForge$paddedSections = new LevelChunkSection[targetLen];
                    Registry<Biome> biomeRegistry = null;
                    try {
                        biomeRegistry = this.level.registryAccess().registryOrThrow(Registries.BIOME);
                    } catch (Throwable ignored) {
                    }
                    for (int i = 0; i < targetLen; i++) {
                        this.skyForge$paddedSections[i] = skyForge$createEmptySection(biomeRegistry, currentSections);
                    }
                }

                if (currentSections != null && currentSections != this.skyForge$paddedSections) {
                    for (int i = 0; i < Math.min(currentSections.length, targetLen); i++) {
                        if (currentSections[i] != null) {
                            this.skyForge$paddedSections[i] = currentSections[i];
                        }
                    }
                }
                ((ChunkAccessAccessor) this).skyForge$setSections(this.skyForge$paddedSections);
                return this.skyForge$paddedSections;
            }
        }
        return currentSections != null ? currentSections : super.getSections();
    }

    /**
     * Безопасное чтение секций из буфера пакета чанка (replaceWithPacketData).
     * Предотвращает IndexOutOfBoundsException (buffer underflow), если массив sections
     * длиннее количества секций, переданных сервером.
     */
    @Redirect(
        method = "replaceWithPacketData",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;read(Lnet/minecraft/network/FriendlyByteBuf;)V"),
        require = 1
    )
    private void skyForge$safeReadSection(LevelChunkSection section, FriendlyByteBuf buf) {
        if (buf.isReadable()) {
            section.read(buf);
        }
    }

    /**
     * Перехват getBlockState(BlockPos):
     * Возвращает реальный BlockState для секций выше Y=320 (вплоть до Y=1000+).
     * Ванильный метод LevelChunk.getBlockState проверяет:
     *   "if (l >= 0 && l < this.sections.length)"
     * и при стандартной длине массива (24 секции) для Y=990 (индекс l=65) условие дает false,
     * возвращая Blocks.AIR.defaultBlockState(). Воздух не имеет коллизии (Shapes.empty()),
     * из-за чего блоки становятся "фантомными" и игрок проваливается сквозь них.
     * Данный перехват гарантирует получение реального BlockState из актуального массива секций.
     */
    @Inject(
        method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void skyForge$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (this.level != null && this.level.isDebug()) {
            return;
        }
        int y = pos.getY();
        int sectionIndex = this.getSectionIndex(y);
        LevelChunkSection[] sections = this.getSections();
        if (sections != null && sectionIndex >= 0 && sectionIndex < sections.length) {
            LevelChunkSection section = sections[sectionIndex];
            if (section != null) {
                if (!section.hasOnlyAir()) {
                    cir.setReturnValue(section.getBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15));
                } else {
                    cir.setReturnValue(Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /**
     * Перехват getFluidState(int, int, int):
     * Обеспечивает корректную физику жидкостей на расширенной высоте (выше Y=320).
     */
    @Inject(
        method = "getFluidState(III)Lnet/minecraft/world/level/material/FluidState;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void skyForge$getFluidState(int x, int y, int z, CallbackInfoReturnable<FluidState> cir) {
        int sectionIndex = this.getSectionIndex(y);
        LevelChunkSection[] sections = this.getSections();
        if (sections != null && sectionIndex >= 0 && sectionIndex < sections.length) {
            LevelChunkSection section = sections[sectionIndex];
            if (section != null) {
                if (!section.hasOnlyAir()) {
                    cir.setReturnValue(section.getFluidState(x & 15, y & 15, z & 15));
                } else {
                    cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
                }
            }
        }
    }

    /**
     * Количество секций должно строго соответствовать текущей длине массива sections,
     * предотвращая ArrayIndexOutOfBoundsException в ThreadedLevelLightEngine и свиперах коллизий.
     */
    @Override
    public int getSectionsCount() {
        LevelChunkSection[] currentSections = this.getSections();
        return currentSections != null ? currentSections.length : super.getSectionsCount();
    }

    @Override
    public int getMaxBuildHeight() {
        if (this.level != null) {
            if (this.level.isClientSide()) {
                int maxGlobalY = ClientSkyConstructionData.getGlobalMaxY();
                int minGlobalY = ClientSkyConstructionData.getGlobalMinY();
                if (maxGlobalY > 320 || minGlobalY < -64) {
                    int alignedHeight = ((maxGlobalY - minGlobalY + 15) / 16) * 16;
                    return minGlobalY + alignedHeight;
                }
            } else if (this.level instanceof ServerLevel serverLevel) {
                SkyConstructionData data = SkyConstructionData.get(serverLevel);
                if (data != null) {
                    int minY = data.getGlobalMinY();
                    int maxY = data.getGlobalMaxY();
                    int alignedHeight = ((maxY - minY + 15) / 16) * 16;
                    return minY + alignedHeight;
                }
            }
        }
        return super.getMaxBuildHeight();
    }

    @Override
    public int getMinBuildHeight() {
        if (this.level != null) {
            if (this.level.isClientSide()) {
                int minGlobalY = ClientSkyConstructionData.getGlobalMinY();
                int maxGlobalY = ClientSkyConstructionData.getGlobalMaxY();
                if (maxGlobalY > 320 || minGlobalY < -64) {
                    return minGlobalY;
                }
            } else if (this.level instanceof ServerLevel serverLevel) {
                SkyConstructionData data = SkyConstructionData.get(serverLevel);
                if (data != null) {
                    return data.getGlobalMinY();
                }
            }
        }
        return super.getMinBuildHeight();
    }

    @Override
    public int getHeight() {
        if (this.level != null) {
            if (this.level.isClientSide()) {
                int maxGlobalY = ClientSkyConstructionData.getGlobalMaxY();
                int minGlobalY = ClientSkyConstructionData.getGlobalMinY();
                if (maxGlobalY > 320 || minGlobalY < -64) {
                    return ((maxGlobalY - minGlobalY + 15) / 16) * 16;
                }
            } else if (this.level instanceof ServerLevel serverLevel) {
                SkyConstructionData data = SkyConstructionData.get(serverLevel);
                if (data != null) {
                    int minY = data.getGlobalMinY();
                    int maxY = data.getGlobalMaxY();
                    return ((maxY - minY + 15) / 16) * 16;
                }
            }
        }
        return super.getHeight();
    }
}
