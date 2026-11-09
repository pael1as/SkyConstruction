package com.example.skyconstruction.mixin;

import com.example.skyconstruction.data.SkyConstructionData;
import com.example.skyconstruction.network.SkyConstructionSyncPayload;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Миксин для ChunkHolder.
 * Решает проблему ArrayIndexOutOfBoundsException при установке (/setblock) и разрушении блоков выше Y=319.
 * Поле changedBlocksPerSection инициализируется фиксированным размером (24 секции).
 * При изменении блоков на высоте 320+ (индекс секции >= 24) мы динамически расширяем массив.
 * Также синхронизируем зону высот с игроками, отслеживающими чанк, предотвращая фантомные блоки.
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {

    @Shadow
    @Final
    private LevelHeightAccessor levelHeightAccessor;

    @Shadow
    @Mutable
    @Final
    private ShortSet[] changedBlocksPerSection;

    @Shadow
    @Final
    private ChunkHolder.PlayerProvider playerProvider;

    @Shadow
    public abstract LevelChunk getTickingChunk();

    /**
     * Перехват оповещения об изменении блока в чанке.
     * Динамически расширяет changedBlocksPerSection, если индекс секции превышает текущую длину массива,
     * исключая падение по ArrayIndexOutOfBoundsException при Y >= 320.
     */
    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void skyForge$onBlockChanged(BlockPos pos, CallbackInfo ci) {
        int i = this.levelHeightAccessor.getSectionIndex(pos.getY());
        if (i >= 0 && (this.changedBlocksPerSection == null || i >= this.changedBlocksPerSection.length)) {
            int newLength = Math.max(i + 1, (this.changedBlocksPerSection != null ? this.changedBlocksPerSection.length * 2 : 24));
            ShortSet[] newArray = new ShortSet[newLength];
            if (this.changedBlocksPerSection != null) {
                System.arraycopy(this.changedBlocksPerSection, 0, newArray, 0, this.changedBlocksPerSection.length);
            }
            this.changedBlocksPerSection = newArray;
        }

        // Если блок выше ванильного лимита или вне текущей зоны, немедленно синхронизируем высоту с клиентами чанка
        if (pos.getY() >= 320 || pos.getY() < -64) {
            LevelChunk chunk = this.getTickingChunk();
            if (chunk != null && chunk.getLevel() instanceof ServerLevel serverLevel) {
                // Если сервер находится в процессе остановки, пропускаем синхронизацию
                if (serverLevel.getServer() != null && (!serverLevel.getServer().isRunning() || serverLevel.getServer().isStopped())) {
                    return;
                }
                try {
                    SkyConstructionData data = SkyConstructionData.get(serverLevel);
                    if (data != null) {
                        // Вычисляем ChunkPos напрямую из координат BlockPos
                        ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);

                        SkyConstructionData.HeightZone zone = data.getHeightZone(chunkPos);
                        int effMin = zone != null ? zone.minY() : data.getGlobalMinY();
                        int effMax = zone != null ? zone.maxY() : data.getGlobalMaxY();

                        // Отправляем пакет синхронизации ТОЛЬКО если блок находится в пределах установленной зоны
                        if (pos.getY() >= effMin && pos.getY() < effMax) {
                            List<ServerPlayer> players = this.playerProvider.getPlayers(chunkPos, false);
                            if (!players.isEmpty()) {
                                SkyConstructionSyncPayload syncPayload = new SkyConstructionSyncPayload(chunkPos.x, chunkPos.z, effMin, effMax);
                                for (ServerPlayer player : players) {
                                    PacketDistributor.sendToPlayer(player, syncPayload);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
