package com.example.skyconstruction.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor-интерфейс для прямого доступа к полю sections в ChunkAccess.
 * Аннотация @Mutable указывает Mixin снять модификатор final с поля sections в байткоде,
 * что позволяет безопасно присваивать новый расширенный массив секций.
 */
@Mixin(ChunkAccess.class)
public interface ChunkAccessAccessor {

    @Accessor("sections")
    LevelChunkSection[] skyForge$getSections();

    @Accessor("sections")
    @Mutable
    void skyForge$setSections(LevelChunkSection[] sections);
}
