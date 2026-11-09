package com.example.skyconstruction.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для базового класса ChunkAccess:
 * 1. Прямой доступ к полю sections через @Shadow @Mutable, устраняющий модификатор final
 *    и предотвращающий JIT-кеширование старого массива.
 * 2. Перехватывает getSections() для возврата актуального массива секций.
 * 3. Перехватывает getSection(int index) для ВСЕХ типов чанков (LevelChunk, ProtoChunk и др.),
 *    гарантированно динамически расширяя массив sections при запросе секций выше Y=319.
 * 4. Безопасно перехватывает getNoiseBiome(III), предотвращая ArrayIndexOutOfBoundsException.
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {

    @Shadow
    @Final
    protected LevelHeightAccessor levelHeightAccessor;

    @Shadow
    @Mutable
    protected LevelChunkSection[] sections;

    /**
     * Защита конструктора ChunkAccess:
     * Если переданный массив секций отличается по длине от массива levelHeightAccessor.getSectionsCount()
     * (например, при загрузке чанка с диска или из пакета), актуализируем массив sections,
     * предотвращая потерю блоков и предупреждение 'Could not set level chunk sections'.
     */
    @Inject(
        method = "<init>",
        at = @At("RETURN"),
        require = 1
    )
    private void skyForge$onInit(
        net.minecraft.world.level.ChunkPos pos,
        net.minecraft.world.level.chunk.UpgradeData upgradeData,
        LevelHeightAccessor levelHeightAccessor,
        Registry<Biome> biomeRegistry,
        long inhabitedTime,
        LevelChunkSection[] passedSections,
        net.minecraft.world.level.levelgen.blending.BlendingData blendingData,
        CallbackInfo ci
    ) {
        if (passedSections != null && (this.sections == null || this.sections.length != passedSections.length)) {
            this.sections = new LevelChunkSection[passedSections.length];
            System.arraycopy(passedSections, 0, this.sections, 0, passedSections.length);
            for (int i = 0; i < this.sections.length; i++) {
                if (this.sections[i] == null) {
                    this.sections[i] = skyForge$createSection(biomeRegistry);
                }
            }
        }
    }

    @Unique
    private Registry<Biome> skyForge$getBiomeRegistry(ChunkAccess chunk) {
        if (this.levelHeightAccessor instanceof Level level) {
            return level.registryAccess().registryOrThrow(Registries.BIOME);
        } else if (this.levelHeightAccessor instanceof LevelReader levelReader) {
            return levelReader.registryAccess().registryOrThrow(Registries.BIOME);
        } else if (chunk instanceof LevelChunk levelChunk && levelChunk.getLevel() != null) {
            return levelChunk.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        }
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                return server.registryAccess().registryOrThrow(Registries.BIOME);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level != null) {
                    return mc.level.registryAccess().registryOrThrow(Registries.BIOME);
                }
                if (mc.getConnection() != null) {
                    return mc.getConnection().registryAccess().registryOrThrow(Registries.BIOME);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Unique
    private LevelChunkSection skyForge$createSection(Registry<Biome> biomeRegistry) {
        if (biomeRegistry != null) {
            return new LevelChunkSection(biomeRegistry);
        }
        if (this.sections != null) {
            for (LevelChunkSection s : this.sections) {
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
     * Перехват getSections(): возвращает актуальный массив секций.
     */
    @Inject(method = "getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;", at = @At("HEAD"), cancellable = true, require = 1)
    private void skyForge$getSections(CallbackInfoReturnable<LevelChunkSection[]> cir) {
        if (this.sections != null) {
            cir.setReturnValue(this.sections);
        }
    }

    /**
     * Безопасный перехват getHighestFilledSectionIndex():
     * Предотвращает NullPointerException при вызове hasOnlyAir() на null-секциях,
     * которые могут возникать при десериализации или динамическом расширении массива секций.
     */
    @Inject(method = "getHighestFilledSectionIndex", at = @At("HEAD"), cancellable = true, require = 1)
    private void skyForge$getHighestFilledSectionIndex(CallbackInfoReturnable<Integer> cir) {
        LevelChunkSection[] current = this.sections;
        if (current != null) {
            for (int i = current.length - 1; i >= 0; i--) {
                LevelChunkSection s = current[i];
                if (s != null && !s.hasOnlyAir()) {
                    cir.setReturnValue(i);
                    return;
                }
            }
        }
        cir.setReturnValue(-1);
    }

    /**
     * Возвращает фактическое количество секций в массиве, предотвращая ArrayIndexOutOfBoundsException
     * в циклах ThreadedLevelLightEngine, когда getHeight() увеличен, а массив sections еще не расширен.
     */
    public int getSectionsCount() {
        return this.sections != null ? this.sections.length : 24;
    }

    /**
     * Перехват getSection(int index): динамически расширяет массив sections для всех типов чанков
     * при обращении к секциям выше ванильного диапазона (например, при /setblock на высоте 990).
     */
    @Inject(method = "getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;", at = @At("HEAD"), cancellable = true, require = 1)
    private void skyForge$getSection(int index, CallbackInfoReturnable<LevelChunkSection> cir) {
        if (index < 0) {
            cir.setReturnValue(null);
            return;
        }

        // Если индекс в пределах текущего массива и секция уже существует — даем ванильному коду вернуть её
        if (this.sections != null && index < this.sections.length && this.sections[index] != null) {
            return;
        }

        // Иначе потокобезопасно расширяем массив
        synchronized (this) {
            if (this.sections != null && index < this.sections.length && this.sections[index] != null) {
                cir.setReturnValue(this.sections[index]);
                return;
            }

            Registry<Biome> biomeRegistry = skyForge$getBiomeRegistry((ChunkAccess) (Object) this);

            if (this.sections == null) {
                int initialLen = Math.max(24, index + 1);
                this.sections = new LevelChunkSection[initialLen];
                for (int i = 0; i < initialLen; i++) {
                    this.sections[i] = skyForge$createSection(biomeRegistry);
                }
            }

            if (index >= this.sections.length) {
                LevelChunkSection[] expanded = new LevelChunkSection[index + 1];
                System.arraycopy(this.sections, 0, expanded, 0, this.sections.length);
                for (int i = this.sections.length; i < expanded.length; i++) {
                    expanded[i] = skyForge$createSection(biomeRegistry);
                }
                this.sections = expanded; // Прямая замена поля через @Shadow @Mutable
            }

            if (this.sections[index] == null) {
                this.sections[index] = skyForge$createSection(biomeRegistry);
            }

            cir.setReturnValue(this.sections[index]);
        }
    }

    @Inject(method = "getNoiseBiome(III)Lnet/minecraft/core/Holder;", at = @At("HEAD"), cancellable = true, require = 1)
    private void skyForge$getNoiseBiome(int x, int y, int z, CallbackInfoReturnable<Holder<Biome>> cir) {
        ChunkAccess self = (ChunkAccess) (Object) this;
        int i = QuartPos.fromBlock(self.getMinBuildHeight());
        int k = i + QuartPos.fromBlock(self.getHeight()) - 1;
        int l = Mth.clamp(y, i, k);
        int j = self.getSectionIndex(QuartPos.toBlock(l));

        if (this.sections == null || j < 0 || j >= this.sections.length || this.sections[j] == null) {
            Registry<Biome> biomeRegistry = skyForge$getBiomeRegistry(self);
            if (biomeRegistry != null) {
                cir.setReturnValue(biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
            } else if (this.sections != null && this.sections.length > 0 && this.sections[0] != null) {
                cir.setReturnValue(this.sections[0].getNoiseBiome(x & 3, 0, z & 3));
            }
        }
    }
}
