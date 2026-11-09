package com.example.skyconstruction.mixin;

import com.example.skyconstruction.SkyConstruction;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для ChunkSerializer.
 * Обеспечивает корректное сохранение и загрузку расширенных секций чанка (выше Y=320),
 * предотвращая порчу чанков, зависания при загрузке мира и IndexOutOfBoundsException.
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {

    @Shadow
    @Final
    private static Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC;

    @Shadow
    private static Codec<PalettedContainerRO<Holder<Biome>>> makeBiomeCodec(Registry<Biome> registry) {
        throw new AssertionError();
    }

    /**
     * Перехват сохранения чанка в NBT.
     * Ванильный цикл сохраняет только стандартные 24 секции.
     * Мы сериализуем все расширенные непустые секции (индексы >= 24) в тег "SkyForgeSections".
     */
    @Inject(method = "write", at = @At("RETURN"))
    private static void skyForge$onWrite(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = cir.getReturnValue();
        if (tag == null) {
            return;
        }

        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length <= 24) {
            return;
        }

        ListTag extraSections = new ListTag();
        Registry<Biome> registry = null;
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = null;

        int minSection = chunk.getMinSection();
        int limit = sections.length;
        for (int j = 24; j < limit; j++) {
            LevelChunkSection section = sections[j];
            if (section != null && !section.hasOnlyAir()) {
                if (registry == null) {
                    registry = level.registryAccess().registryOrThrow(Registries.BIOME);
                    biomeCodec = makeBiomeCodec(registry);
                }
                int sectionY = minSection + j;
                SkyConstruction.LOGGER.debug("[SkyConstruction] Сохранение дополнительной секции {} (Y={}) для чанка {}", j, sectionY, chunk.getPos());
                CompoundTag sectionTag = new CompoundTag();
                sectionTag.putInt("Y", sectionY);
                sectionTag.put("block_states", BLOCK_STATE_CODEC.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow());
                sectionTag.put("biomes", biomeCodec.encodeStart(NbtOps.INSTANCE, section.getBiomes()).getOrThrow());
                extraSections.add(sectionTag);
            }
        }

        if (!extraSections.isEmpty()) {
            tag.put("SkyForgeSections", extraSections);
        }
    }

    /**
     * Перехват загрузки чанка из NBT.
     * Восстанавливает расширенные секции из "SkyForgeSections" и подменяет массив sections
     * в чанке до необходимого размера, предотвращая ArrayIndexOutOfBoundsException.
     */
    @Inject(method = "read", at = @At("RETURN"))
    private static void skyForge$onRead(
            ServerLevel level,
            PoiManager poiManager,
            RegionStorageInfo storageInfo,
            ChunkPos pos,
            CompoundTag tag,
            CallbackInfoReturnable<ProtoChunk> cir
    ) {
        ProtoChunk protoChunk = cir.getReturnValue();
        if (protoChunk == null || !tag.contains("SkyForgeSections", Tag.TAG_LIST)) {
            return;
        }

        ListTag extraSections = tag.getList("SkyForgeSections", Tag.TAG_COMPOUND);
        if (extraSections.isEmpty()) {
            return;
        }

        ChunkAccess chunk = protoChunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : protoChunk;
        LevelChunkSection[] currentSections = chunk.getSections();
        int minSection = chunk.getMinSection();

        int maxSectionY = minSection + currentSections.length - 1;
        for (int i = 0; i < extraSections.size(); i++) {
            CompoundTag sectionTag = extraSections.getCompound(i);
            int y = sectionTag.getInt("Y");
            if (y > maxSectionY) {
                maxSectionY = y;
            }
        }

        int neededLength = maxSectionY - minSection + 1;
        if (neededLength > currentSections.length) {
            LevelChunkSection[] newSections = new LevelChunkSection[neededLength];
            System.arraycopy(currentSections, 0, newSections, 0, currentSections.length);

            Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = makeBiomeCodec(registry);

            for (int i = 0; i < extraSections.size(); i++) {
                CompoundTag sectionTag = extraSections.getCompound(i);
                int y = sectionTag.getInt("Y");
                int index = y - minSection;
                if (index >= 0 && index < newSections.length) {
                    PalettedContainer<BlockState> states;
                    if (sectionTag.contains("block_states", Tag.TAG_COMPOUND)) {
                        states = BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, sectionTag.getCompound("block_states"))
                                .getOrThrow(ChunkSerializer.ChunkReadException::new);
                    } else {
                        states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
                    }

                    PalettedContainerRO<Holder<Biome>> biomes;
                    if (sectionTag.contains("biomes", Tag.TAG_COMPOUND)) {
                        biomes = biomeCodec.parse(NbtOps.INSTANCE, sectionTag.getCompound("biomes"))
                                .getOrThrow(ChunkSerializer.ChunkReadException::new);
                    } else {
                        biomes = new PalettedContainer<>(registry.asHolderIdMap(), registry.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
                    }

                    newSections[index] = new LevelChunkSection(states, biomes);
                }
            }

            for (int i = currentSections.length; i < newSections.length; i++) {
                if (newSections[i] == null) {
                    newSections[i] = new LevelChunkSection(registry);
                }
            }

            ((ChunkAccessAccessor) chunk).skyForge$setSections(newSections);
            if (protoChunk != null && protoChunk != chunk) {
                ((ChunkAccessAccessor) protoChunk).skyForge$setSections(newSections);
            }
            chunk.setUnsaved(false);
            if (protoChunk != null) {
                protoChunk.setUnsaved(false);
            }
        }
    }
}
