package com.example.skyconstruction.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.world.level.ChunkPos;

/**
 * Клиентский кэш зон высот SkyForge.
 * Получает данные от сервера через сетевой пакет SkyConstructionSyncPayload
 * и используется клиентскими миксинами для правильного рендеринга и коллизий блоков.
 *
 * ОПТИМИЗАЦИЯ: Добавлены статические поля globalMinY и globalMaxY.
 * Аналогично серверному SkyConstructionData — позволяют миксинам на клиенте
 * проверять isOutsideBuildHeight(int y) за O(1) без перебора всей карты.
 * Это критически важно, потому что клиент вызывает isOutsideBuildHeight
 * при каждом рендеринге чанка — тысячи вызовов за кадр.
 */
public class ClientSkyConstructionData {

    /**
     * Запись диапазона высоты чанка на клиенте.
     */
    public record HeightZone(int minY, int maxY) {}

    // Потокобезопасная статическая карта зон для клиентского мира
    private static final Map<ChunkPos, HeightZone> ZONES = new HashMap<>();

    // ===== ОПТИМИЗАЦИЯ: Глобальный кэш абсолютных границ на клиенте =====
    // volatile — гарантирует видимость изменений между потоками рендеринга и сетевым.
    // Клиент: Render Thread читает эти поля, а Netty Thread (через enqueueWork на Main Thread)
    // обновляет их при получении пакетов от сервера. volatile исключает кэширование
    // устаревших значений в CPU-кэше потока рендеринга.
    private static volatile int globalMinY = -64;
    private static volatile int globalMaxY = 320;

    /**
     * Сохраняет зону высоты для чанка в клиентском кэше.
     *
     * ОПТИМИЗАЦИЯ: При каждом вызове обновляем глобальные границы за O(1).
     * Math.min/Math.max — инлайнятся JIT-компилятором, нулевой оверхед.
     */
    public static void setZone(ChunkPos pos, int min, int max) {
        synchronized (ZONES) {
            ZONES.put(pos, new HeightZone(min, max));
        }

        // Обновляем глобальные границы вне synchronized блока —
        // volatile записи атомарны для int, а нам не нужна строгая консистентность
        // с картой, достаточно монотонного расширения диапазона.
        if (min < globalMinY) {
            globalMinY = min;
        }
        if (max > globalMaxY) {
            globalMaxY = max;
        }
    }

    /**
     * Получить зону высоты для чанка из клиентского кэша.
     */
    @Nullable
    public static HeightZone getZone(ChunkPos pos) {
        synchronized (ZONES) {
            return ZONES.get(pos);
        }
    }

    /**
     * Получить неизменяемую копию всех сохраненных зон.
     */
    public static Map<ChunkPos, HeightZone> getAllZones() {
        synchronized (ZONES) {
            return Collections.unmodifiableMap(new HashMap<>(ZONES));
        }
    }

    // ===== Геттеры глобальных границ (O(1) доступ для миксинов) =====

    /**
     * Абсолютный минимум Y среди всех синхронизированных зон на клиенте.
     * Volatile read — ~1 наносекунда vs. HashMap iteration ~10+ микросекунд на 1000 зон.
     */
    public static int getGlobalMinY() {
        if (globalMinY >= -64) {
            skyForge$checkSingleplayer();
        }
        return globalMinY;
    }

    /**
     * Абсолютный максимум Y среди всех синхронизированных зон на клиенте.
     */
    public static int getGlobalMaxY() {
        if (globalMaxY <= 320) {
            skyForge$checkSingleplayer();
        }
        return globalMaxY;
    }

    private static void skyForge$checkSingleplayer() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                for (net.minecraft.server.level.ServerLevel sl : mc.getSingleplayerServer().getAllLevels()) {
                    com.example.skyconstruction.data.SkyConstructionData data = com.example.skyconstruction.data.SkyConstructionData.get(sl);
                    if (data != null) {
                        if (data.getGlobalMaxY() > globalMaxY) {
                            globalMaxY = data.getGlobalMaxY();
                        }
                        if (data.getGlobalMinY() < globalMinY) {
                            globalMinY = data.getGlobalMinY();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Проверяет, синхронизированы ли зоны на клиенте.
     */
    public static boolean hasZones() {
        synchronized (ZONES) {
            return !ZONES.isEmpty();
        }
    }

    /**
     * Очистить кэш (при выходе из мира или смене сервера).
     * Сбрасываем глобальные границы к ванильным значениям — критически важно,
     * чтобы при повторном входе в мир без SkyForge-зон не было артефактов.
     */
    public static void clear() {
        synchronized (ZONES) {
            ZONES.clear();
        }
        // Сброс к ванильным значениям
        globalMinY = -64;
        globalMaxY = 320;
    }
}
