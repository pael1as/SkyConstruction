package com.example.skyconstruction.network;

import com.example.skyconstruction.SkyConstructionVoxyForge;
import com.example.skyconstruction.client.ClientSkyConstructionData;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Регистрация сетевых пакетов SkyForge с использованием PayloadRegistrar в NeoForge 1.21.1.
 */
public class SkyConstructionNetwork {

    /**
     * Регистрация пакетов при вызове RegisterPayloadHandlersEvent на шине событий мода.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1.0.0");

        // Регистрируем client-bound пакет фазы PLAY
        registrar.playToClient(
                SkyConstructionSyncPayload.TYPE,
                SkyConstructionSyncPayload.STREAM_CODEC,
                SkyConstructionNetwork::handleSync
        );
    }

    /**
     * Обработчик пакета синхронизации на стороне клиента.
     */
    private static void handleSync(SkyConstructionSyncPayload payload, IPayloadContext context) {
        SkyConstructionVoxyForge.LOGGER.debug("[SkyConstruction] Получен пакет SkyConstructionSyncPayload: Chunk[{}, {}], Y=[{}..{}]", payload.chunkX(), payload.chunkZ(), payload.minY(), payload.maxY());

        context.enqueueWork(() -> {
            int oldMaxY = ClientSkyConstructionData.getGlobalMaxY();
            int oldMinY = ClientSkyConstructionData.getGlobalMinY();

            ClientSkyConstructionData.setZone(
                    new ChunkPos(payload.chunkX(), payload.chunkZ()),
                    payload.minY(),
                    payload.maxY()
            );

            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.level != null && mc.levelRenderer != null) {
                    if ((payload.maxY() > oldMaxY || payload.minY() < oldMinY) && !ModList.get().isLoaded("sodium")) {
                        mc.levelRenderer.allChanged();
                    }
                }
            });
        });
    }
}
