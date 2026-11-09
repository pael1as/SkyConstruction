package com.example.skyconstruction.command;

import com.example.skyconstruction.SkyConstructionVoxyForge;
import com.example.skyconstruction.data.SkyConstructionData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Класс серверной команды /skyforge <radius_in_chunks> <min_y> <max_y>.
 * Позволяет операторам сервера (OP ур. 2+) задавать границы высоты для чанков в радиусе от игрока.
 * Обработка выполняется процедурно через очередь по тикам без зависания сервера и без лимита на радиус.
 */
public class SkyConstructionCommand {

    /**
     * Регистрирует команду в диспетчере команд Minecraft Brigadier.
     * Ограничение радиуса полностью снято (радиус >= 0).
     *
     * @param dispatcher диспетчер команд сервера
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("skyforge")
                .requires(source -> source.hasPermission(2)) // Требуются права оператора (уровень 2 и выше)
                .then(Commands.argument("radius_in_chunks", IntegerArgumentType.integer(0)) // Радиус чанков >= 0 без искусственного потолка
                    .then(Commands.argument("min_y", IntegerArgumentType.integer()) // min_y (например -64)
                        .then(Commands.argument("max_y", IntegerArgumentType.integer()) // max_y (например 5000)
                            .executes(SkyConstructionCommand::execute)
                        )
                    )
                )
        );
    }

    /**
     * Обработка выполнения команды /skyforge <radius_in_chunks> <min_y> <max_y>.
     */
    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        int radius = IntegerArgumentType.getInteger(context, "radius_in_chunks");
        int minY = IntegerArgumentType.getInteger(context, "min_y");
        int maxY = IntegerArgumentType.getInteger(context, "max_y");

        // Проверка корректности переданного диапазона высот
        if (minY > maxY) {
            source.sendFailure(Component.literal("Ошибка: min_y (" + minY + ") не может быть больше max_y (" + maxY + ")!")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Ограничение максимальной высоты до 1000 блоков
        if (maxY > 1000) {
            source.sendFailure(Component.literal("Максимальная высота ограничена 1000 блоками")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Ограничение минимальной высоты до -64 блоков
        if (minY < -64) {
            source.sendFailure(Component.literal("Минимальная высота ограничена -64 блоками")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Моментально обновляем глобальные границы уровня, чтобы строить и выполнять setblock можно было сразу
        SkyConstructionData data = SkyConstructionData.get(level);
        if (data != null) {
            data.updateGlobalBounds(minY, maxY);
        }

        // Синхронно расширяем секции всех уже загруженных чанков вокруг игроков ДО старта тиков и батча,
        // чтобы тики игрока (коллизии, физика, позы) гарантированно выполнялись на расширенных чанках
        int vDist = Math.max(level.getServer().getPlayerList().getViewDistance(), level.getServer().getPlayerList().getSimulationDistance()) + 2;
        for (ServerPlayer player : level.players()) {
            ChunkPos pChunk = player.chunkPosition();
            for (int cx = pChunk.x - vDist; cx <= pChunk.x + vDist; cx++) {
                for (int cz = pChunk.z - vDist; cz <= pChunk.z + vDist; cz++) {
                    ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk instanceof LevelChunk levelChunk) {
                        levelChunk.getSections(); // Вызов getSections() гарантированно инициализирует и расширяет секции
                    }
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            player,
                            new com.example.skyconstruction.network.SkyConstructionSyncPayload(cx, cz, minY, maxY)
                    );
                }
            }
        }

        // Получаем мировую позицию источника команды (игрока или точки исполнения консоли)
        BlockPos centerBlockPos = BlockPos.containing(source.getPosition());
        ChunkPos centerChunk = new ChunkPos(centerBlockPos);

        // Границы квадрата чанков
        int minChunkX = centerChunk.x - radius;
        int maxChunkX = centerChunk.x + radius;
        int minChunkZ = centerChunk.z - radius;
        int maxChunkZ = centerChunk.z + radius;

        // Создаем процедурную задачу на пошаговое обновление чанков
        SkyConstructionVoxyForge.ChunkZoneBatchTask task = new SkyConstructionVoxyForge.ChunkZoneBatchTask(
                level,
                source.getPlayer(),
                minChunkX,
                maxChunkX,
                minChunkZ,
                maxChunkZ,
                minY,
                maxY,
                radius
        );

        // Помещаем задачу в очередь тиков сервера
        SkyConstructionVoxyForge.queueZoneTask(task);

        // Отправляем игроку подтверждение запуска пошаговой обработки
        source.sendSuccess(() -> Component.literal(
                String.format("Запущена процедурная обработка %d чанков (радиус: %d, Y=[%d, %d]). Обновление выполняется порциями по тикам без лагов сервера.",
                        task.getTotalChunks(), radius, minY, maxY)
        ).withStyle(ChatFormatting.GREEN), true);

        return 1;
    }
}
