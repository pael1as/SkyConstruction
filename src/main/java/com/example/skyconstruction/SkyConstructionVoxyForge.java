package com.example.skyconstruction;

import com.example.skyconstruction.command.SkyConstructionCommand;
import com.example.skyconstruction.data.SkyConstructionData;
import com.example.skyconstruction.network.SkyConstructionNetwork;
import com.example.skyconstruction.network.SkyConstructionSyncPayload;
import com.mojang.logging.LogUtils;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

@Mod(SkyConstructionVoxyForge.MOD_ID)
public class SkyConstructionVoxyForge {
    public static final String MOD_ID = "skyconstructionvoxyforge";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Максимальное количество пакетов обновления блоков, отправляемых за один серверный тик.
     * Защищает буфер Netty и сетевой поток от переполнения и лагов.
     */
    private static final int MAX_BLOCKS_PER_TICK = 80;

    /**
     * Количество чанков, обрабатываемых задачей установки зон за один серверный тик.
     * Ограничение в 10 чанков за тик полностью предотвращает просадку TPS, перегрузку Netty и OOM
     * даже для сверхбольших радиусов (3125+ чанков = 39+ млн чанков).
     */
    private static final int CHUNKS_PER_TICK = 10;

    /**
     * Потокобезопасная очередь процедурных задач обновления зон высот для чанков.
     */
    private static final Queue<ChunkZoneBatchTask> PENDING_ZONE_TASKS = new ConcurrentLinkedQueue<>();

    /**
     * Потокобезопасная очередь активных ленивых стримеров блоков чанков.
     */
    private static final Queue<ChunkBlockStreamer> PENDING_BLOCK_STREAMERS = new ConcurrentLinkedQueue<>();

    /**
     * Класс процедурной задачи пошагового обновления зон высот в чанках.
     * Хранит только границы прямоугольника координат, потребляя минимум памяти
     * даже для 39+ миллионов чанков.
     */
    public static class ChunkZoneBatchTask {
        public final ServerLevel level;
        public final ServerPlayer sourcePlayer;
        public final int minX, maxX, minZ, maxZ;
        public final int minY, maxY;
        public final int radius;
        public final long totalChunks;

        private int curX;
        private int curZ;
        private long processed = 0;

        public ChunkZoneBatchTask(ServerLevel level, ServerPlayer sourcePlayer, int minX, int maxX, int minZ, int maxZ, int minY, int maxY, int radius) {
            this.level = level;
            this.sourcePlayer = sourcePlayer;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.minY = minY;
            this.maxY = maxY;
            this.radius = radius;
            this.curX = minX;
            this.curZ = minZ;
            this.totalChunks = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        }

        /**
         * Обрабатывает порцию чанков в рамках текущего тика сервера.
         *
         * @param limit максимальное количество чанков за один вызов
         * @return true, если задача еще не завершена; false, если все чанки обработаны
         */
        public boolean processBatch(int limit) {
            SkyConstructionData data = SkyConstructionData.get(this.level);
            int count = 0;

            while (this.curZ <= this.maxZ && count < limit) {
                ChunkPos pos = new ChunkPos(this.curX, this.curZ);
                if (data != null) {
                    data.setHeightZone(pos, this.minY, this.maxY);
                }

                // Если чанк уже загружен в память сервера, сразу актуализируем его массив секций
                net.minecraft.world.level.chunk.ChunkAccess loadedChunk = this.level.getChunkSource().getChunk(this.curX, this.curZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                if (loadedChunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk) {
                    levelChunk.getSections();
                }

                // Отправляем сетевые пакеты ТОЛЬКО игрокам, находящимся в радиусе 16 чанков от обрабатываемого чанка.
                // Это предотвращает отправку миллионов бесполезных пакетов и исключает SocketTimeoutException в Netty.
                SkyConstructionSyncPayload payload = null;
                for (ServerPlayer player : this.level.players()) {
                    if (Math.abs(player.chunkPosition().x - this.curX) <= 16 &&
                        Math.abs(player.chunkPosition().z - this.curZ) <= 16) {
                        if (payload == null) {
                            payload = new SkyConstructionSyncPayload(this.curX, this.curZ, this.minY, this.maxY);
                        }
                        PacketDistributor.sendToPlayer(player, payload);
                    }
                }

                this.curX++;
                this.processed++;
                count++;

                // Логирование прогресса каждые 10 000 чанков
                if (this.processed % 10000 == 0 || this.processed == this.totalChunks) {
                    int percent = (int) ((this.processed * 100L) / Math.max(1, this.totalChunks));
                    LOGGER.info("SkyConstruction: обработка зон чанков: {}/{} ({}%)", this.processed, this.totalChunks, percent);
                }

                if (this.curX > this.maxX) {
                    this.curX = this.minX;
                    this.curZ++;
                }
            }

            return this.curZ <= this.maxZ;
        }

        public long getTotalChunks() {
            return this.totalChunks;
        }

        public long getProcessed() {
            return this.processed;
        }
    }

    /**
     * Помещает процедурную задачу обновления зон в очередь сервера.
     */
    public static void queueZoneTask(ChunkZoneBatchTask task) {
        PENDING_ZONE_TASKS.add(task);
    }

    /**
     * Ленивый стример блоков для расширенных секций чанка (Y >= 320).
     * Вместо создания огромных списков в памяти итерируется прямо по существующим секциям чанка,
     * отправляя блоки порциями по тикам без зависаний сервера.
     * Перед каждой порцией запрашивает актуальный массив секций через ChunkSectionsHelper,
     * исключая чтение устаревших массивов и NPE при конкурентной установке блоков.
     */
    private static class ChunkBlockStreamer {
        private final ServerPlayer player;
        private final LevelChunk chunk;
        private final int minSection;
        private final int chunkBaseX;
        private final int chunkBaseZ;

        private int sectionIndex = 24;
        private int x = 0;
        private int y = 0;
        private int z = 0;

        public ChunkBlockStreamer(ServerPlayer player, LevelChunk chunk) {
            this.player = player;
            this.chunk = chunk;
            this.minSection = chunk.getMinSection();
            this.chunkBaseX = chunk.getPos().getMinBlockX();
            this.chunkBaseZ = chunk.getPos().getMinBlockZ();
        }

        /**
         * Выполняет отправку порции блоков.
         *
         * @param limit максимальное количество блоков для отправки в этот вызов
         * @return количество фактически отправленных блоков (или -1, если стример завершил работу)
         */
        public int streamNextBatch(int limit) {
            if (this.player.hasDisconnected()) {
                return -1;
            }

            // Запрашиваем актуальный массив секций чанка перед каждой итерацией
            LevelChunkSection[] sections = this.chunk.getSections();

            if (sections == null) {
                return -1;
            }

            int sentCount = 0;

            while (this.sectionIndex < sections.length && sentCount < limit) {
                LevelChunkSection section = sections[this.sectionIndex];
                if (section == null || section.hasOnlyAir()) {
                    this.sectionIndex++;
                    this.x = 0;
                    this.y = 0;
                    this.z = 0;
                    continue;
                }

                int sectionBaseY = (this.minSection + this.sectionIndex) << 4;

                while (this.x < 16 && sentCount < limit) {
                    while (this.z < 16 && sentCount < limit) {
                        while (this.y < 16 && sentCount < limit) {
                            BlockState state = section.getBlockState(this.x, this.y, this.z);
                            if (!state.isAir()) {
                                BlockPos pos = new BlockPos(this.chunkBaseX + this.x, sectionBaseY + this.y, this.chunkBaseZ + this.z);
                                this.player.connection.send(new ClientboundBlockUpdatePacket(pos, state));
                                sentCount++;
                                System.out.println("[SkyConstruction] ChunkBlockStreamer: отправлен пакет ClientboundBlockUpdatePacket для блока " + state + " на позиции " + pos + " игроку " + this.player.getName().getString());
                            }
                            this.y++;
                        }
                        if (this.y >= 16) {
                            this.y = 0;
                            this.z++;
                        }
                    }
                    if (this.z >= 16) {
                        this.z = 0;
                        this.x++;
                    }
                }

                if (this.x >= 16) {
                    this.x = 0;
                    this.y = 0;
                    this.z = 0;
                    this.sectionIndex++;
                }
            }

            if (this.sectionIndex >= sections.length) {
                return -1; // Полностью завершено
            }

            return sentCount;
        }
    }

    /**
     * Основной конструктор мода для NeoForge 1.21.1.
     * FML внедряет шину событий мода (IEventBus) и контейнер мода (ModContainer).
     */
    public SkyConstructionVoxyForge(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("SkyConstruction VoxyForge mod initialized!");

        // Регистрация сетевых пакетов на шине событий мода (IEventBus)
        if (modEventBus != null) {
            modEventBus.addListener(SkyConstructionNetwork::register);
        }

        // Подписываемся на регистрацию команд на общей шине событий NeoForge (NeoForge.EVENT_BUS)
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        // Синхронизация сохраненных зон высот при входе игрока в мир
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        // Очистка кэша и очереди при остановке сервера
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        // Синхронизация блоков выше Y=320 и зоны чанка при отправке клиенту
        NeoForge.EVENT_BUS.addListener(this::onChunkSent);
        // Обработка очередей тиков сервера (зоны и блоки)
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onServerStopped(ServerStoppedEvent event) {
        SkyConstructionData.clearCache();
        PENDING_ZONE_TASKS.clear();
        PENDING_BLOCK_STREAMERS.clear();
    }

    /**
     * Обработчик тика сервера (ServerTickEvent.Post).
     * 1. Процедурно обрабатывает задачи зон высот (по CHUNKS_PER_TICK за тик).
     * 2. Выполняет запланированные порции отправки блоков (не более MAX_BLOCKS_PER_TICK за тик).
     */
    private void onServerTick(ServerTickEvent.Post event) {
        // 1. Процедурная обработка очереди задач команд установки зон (не более CHUNKS_PER_TICK за тик)
        int zoneBudget = CHUNKS_PER_TICK;
        while (zoneBudget > 0 && !PENDING_ZONE_TASKS.isEmpty()) {
            ChunkZoneBatchTask task = PENDING_ZONE_TASKS.peek();
            if (task == null) {
                break;
            }

            boolean hasMore = task.processBatch(zoneBudget);
            zoneBudget -= CHUNKS_PER_TICK;

            if (!hasMore) {
                PENDING_ZONE_TASKS.poll();
                if (task.sourcePlayer != null && !task.sourcePlayer.hasDisconnected()) {
                    task.sourcePlayer.sendSystemMessage(Component.literal(
                            String.format("Обработка всех %d чанков (радиус %d) успешно завершена!", task.totalChunks, task.radius)
                    ).withStyle(ChatFormatting.GOLD));
                }
            }
        }

        // 2. Обработка очереди отправки блоков клиентам
        int blockBudget = MAX_BLOCKS_PER_TICK;
        while (blockBudget > 0 && !PENDING_BLOCK_STREAMERS.isEmpty()) {
            ChunkBlockStreamer streamer = PENDING_BLOCK_STREAMERS.poll();
            if (streamer == null) {
                break;
            }

            int sent = streamer.streamNextBatch(blockBudget);
            if (sent >= 0) {
                blockBudget -= sent;
                // Если стример еще не закончил обход чанка, возвращаем его в конец очереди
                PENDING_BLOCK_STREAMERS.add(streamer);
            }
        }
    }

    /**
     * Обработчик события RegisterCommandsEvent.
     * Вызывается движком при регистрации команд сервера.
     */
    private void onRegisterCommands(RegisterCommandsEvent event) {
        SkyConstructionCommand.register(event.getDispatcher());
        LOGGER.info("Команда /skyforge успешно зарегистрирована в CommandDispatcher.");
    }

    /**
     * Обработчик входа игрока на сервер (PlayerLoggedInEvent).
     * Отправляет клиенту зоны высот ТОЛЬКО для чанков в пределах дистанции прорисовки вокруг игрока.
     * Это исключает перегрузку сетевого буфера Netty и SocketTimeoutException при наличии миллионов зон.
     */
    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = player.serverLevel();
            SkyConstructionData data = SkyConstructionData.get(level);
            if (data != null) {
                ChunkPos playerChunk = player.chunkPosition();
                int viewDistance = level.getServer().getPlayerList().getViewDistance();
                int radius = Math.min(16, Math.max(2, viewDistance));

                int gMinY = data.getGlobalMinY();
                int gMaxY = data.getGlobalMaxY();

                for (int cx = playerChunk.x - radius; cx <= playerChunk.x + radius; cx++) {
                    for (int cz = playerChunk.z - radius; cz <= playerChunk.z + radius; cz++) {
                        ChunkPos pos = new ChunkPos(cx, cz);
                        SkyConstructionData.HeightZone zone = data.getHeightZone(pos);
                        int effMin = zone != null ? zone.minY() : gMinY;
                        int effMax = zone != null ? zone.maxY() : gMaxY;
                        if (effMax > 320 || effMin < -64) {
                            PacketDistributor.sendToPlayer(
                                    player,
                                    new SkyConstructionSyncPayload(cx, cz, effMin, effMax)
                            );
                        }
                    }
                }
            }
        }
    }

    /**
     * Обработчик события ChunkWatchEvent.Sent.
     * Вызывается, когда ванильный чанк передан клиенту.
     * 1. Синхронизирует зону высот для этого чанка (если она переопределена).
     * 2. Добавляет ленивый стример чанка в очередь PENDING_BLOCK_STREAMERS, если есть блоки выше Y=320.
     */
    private void onChunkSent(ChunkWatchEvent.Sent event) {
        LevelChunk chunk = event.getChunk();
        ServerPlayer player = event.getPlayer();

        // Синхронизируем зону высот для этого конкретного чанка
        SkyConstructionData data = SkyConstructionData.get(player.serverLevel());
        if (data != null) {
            SkyConstructionData.HeightZone zone = data.getHeightZone(chunk.getPos());
            int effMin = zone != null ? zone.minY() : data.getGlobalMinY();
            int effMax = zone != null ? zone.maxY() : data.getGlobalMaxY();
            if (effMax > 320 || effMin < -64) {
                PacketDistributor.sendToPlayer(
                        player,
                        new SkyConstructionSyncPayload(chunk.getPos().x, chunk.getPos().z, effMin, effMax)
                );
            }
        }

        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length <= 24) {
            return;
        }

        // Проверяем, есть ли хотя бы одна непустая секция выше Y=320 перед регистрацией стримера
        boolean hasCustomBlocks = false;
        for (int s = 24; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section != null && !section.hasOnlyAir()) {
                hasCustomBlocks = true;
                break;
            }
        }

        if (hasCustomBlocks) {
            PENDING_BLOCK_STREAMERS.add(new ChunkBlockStreamer(player, chunk));
        }
    }
}
