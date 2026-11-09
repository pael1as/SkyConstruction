package com.example.skyconstruction.network;

import com.example.skyconstruction.SkyConstruction;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Сетевой пакет для синхронизации зон высот от сервера к клиенту.
 * Содержит координаты чанка и его диапазон высот [minY, maxY].
 */
public record SkyConstructionSyncPayload(int chunkX, int chunkZ, int minY, int maxY) implements CustomPacketPayload {

    public static final Type<SkyConstructionSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SkyConstruction.MOD_ID, "sync_height_zone")
    );

    public static final StreamCodec<ByteBuf, SkyConstructionSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeInt(payload.chunkX());
                buf.writeInt(payload.chunkZ());
                buf.writeInt(payload.minY());
                buf.writeInt(payload.maxY());
            },
            buf -> new SkyConstructionSyncPayload(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
