package com.example.skyconstruction.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Дефензивный миксин для защиты от ArrayIndexOutOfBoundsException в Lithium (ChunkAwareBlockCollisionSweeper).
 * Перехватывает обращения к chunk.getSections() внутри sweeper'а коллизий и гарантирует,
 * что возвращаемый массив секций покрывает всю необходимую высоту мира, предотвращая падение
 * при проверке коллизий игрока или сущностей выше Y=320.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper")
public abstract class LithiumCollisionSweeperMixin {

    @Redirect(
        method = "nextSection",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"
        )
    )
    private LevelChunkSection[] skyForge$safeguardNextSectionSections(ChunkAccess chunk) {
        return skyForge$ensurePaddedSections(chunk);
    }

    @Redirect(
        method = "hasChunkSectionOversizedBlocks",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"
        )
    )
    private static LevelChunkSection[] skyForge$safeguardOversizedBlocksSections(ChunkAccess chunk) {
        return skyForge$ensurePaddedSections(chunk);
    }

    private static LevelChunkSection[] skyForge$ensurePaddedSections(ChunkAccess chunk) {
        if (chunk == null) {
            return new LevelChunkSection[24];
        }
        LevelChunkSection[] sections = chunk.getSections();
        int requiredLen = Math.max(chunk.getSectionsCount(), chunk.getHeight() / 16);
        if (chunk instanceof LevelChunk levelChunk && levelChunk.getLevel() != null) {
            Level level = levelChunk.getLevel();
            requiredLen = Math.max(requiredLen, level.getSectionsCount());
        }
        if (sections == null) {
            return new LevelChunkSection[Math.max(24, requiredLen)];
        }
        if (sections.length < requiredLen) {
            LevelChunkSection[] padded = new LevelChunkSection[requiredLen];
            System.arraycopy(sections, 0, padded, 0, sections.length);
            return padded;
        }
        return sections;
    }
}
