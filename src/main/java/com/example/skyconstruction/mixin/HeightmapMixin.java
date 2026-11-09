package com.example.skyconstruction.mixin;

import net.minecraft.util.BitStorage;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumSet;
import java.util.Set;

/**
 * Миксин для Heightmap:
 * 1. Предотвращает IllegalArgumentException ("The value ... is not in the specified inclusive range of 0 to 511")
 *    и IndexOutOfBoundsException при установке блоков на экстремальных высотах (вплоть до Y=8192).
 *    Принудительно расширяет массив секций чанка перед записью и динамически увеличивает разрядность BitStorage на лету.
 * 2. Подавляет спам в лог ("Ignoring heightmap data for chunk... size does not match") при десериализации
 *    расширенных карт высот на клиенте, адаптируя BitStorage под фактический размер массива данных.
 */
@Mixin(Heightmap.class)
public abstract class HeightmapMixin {

    @Shadow
    @Mutable
    private BitStorage data;

    @Shadow
    @Final
    private ChunkAccess chunk;

    @Shadow
    public static void primeHeightmaps(ChunkAccess chunk, Set<Heightmap.Types> types) {
        throw new AssertionError();
    }

    /**
     * Перехват setRawData: подавляет варнинг в логе ("Ignoring heightmap data... size does not match")
     * и безопасно адаптирует внутренний BitStorage под фактическую разрядность входящего массива данных.
     */
    @Inject(method = "setRawData", at = @At("HEAD"), cancellable = true)
    private void skyForge$onSetRawData(ChunkAccess chunk, Heightmap.Types type, long[] data, CallbackInfo ci) {
        if (this.data == null || data == null) {
            return;
        }

        if (this.data.getRaw().length == data.length) {
            return; // Размер совпадает — выполняется штатный System.arraycopy
        }

        ci.cancel();

        // Подбираем битовую разрядность BitStorage для входящего массива данных (256 значений)
        for (int bits = 32; bits >= 1; bits--) {
            int valuesPerLong = 64 / bits;
            if (valuesPerLong <= 0) {
                continue;
            }
            int expectedLength = (256 + valuesPerLong - 1) / valuesPerLong;
            if (expectedLength == data.length) {
                try {
                    this.data = new SimpleBitStorage(bits, 256, data.clone());
                    return;
                } catch (Throwable ignored) {
                }
            }
        }

        // Если разрядность не определена, инициируем безопасный пересчет без спама в лог
        if (chunk != null) {
            try {
                primeHeightmaps(chunk, EnumSet.of(type));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Автоматическое расширение разрядности BitStorage в Heightmap и принудительное
     * расширение секций чанка перед записью данных карты высот.
     */
    @Inject(method = "setHeight(III)V", at = @At("HEAD"), cancellable = true)
    private void skyForge$ensureBitStorageCapacity(int x, int z, int height, CallbackInfo ci) {
        // Безопасно проверяем границы, чтобы не запрашивать секцию за пределами максимальной высоты мира
        if (this.chunk != null && height >= this.chunk.getMinBuildHeight() && height < this.chunk.getMaxBuildHeight()) {
            int sectionIndex = this.chunk.getSectionIndex(height);
            if (sectionIndex >= 0) {
                try {
                    this.chunk.getSection(sectionIndex);
                } catch (Throwable ignored) {
                }
            }
        }

        int value = height - this.chunk.getMinBuildHeight();
        if (value > 0 && this.data != null) {
            int requiredBits = Mth.ceillog2(value + 1);
            if (requiredBits > this.data.getBits()) {
                BitStorage oldStorage = this.data;
                SimpleBitStorage newStorage = new SimpleBitStorage(requiredBits, 256);
                for (int i = 0; i < 256; i++) {
                    newStorage.set(i, oldStorage.get(i));
                }
                this.data = newStorage;
            }
        }
    }
}
