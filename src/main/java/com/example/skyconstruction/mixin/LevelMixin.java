package com.example.skyconstruction.mixin;

import com.example.skyconstruction.client.ClientSkyConstructionData;
import com.example.skyconstruction.data.SkyConstructionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin implements LevelHeightAccessor {

    /**
     * Перехватываем глобальную проверку границ мира по горизонтали и вертикали.
     * В ванили isInWorldBounds проверяет X/Z и диапазон высот.
     * Мы заменяем вертикальную проверку на границы SkyForge,
     * сохраняя стандартные лимиты по X и Z (-30000000 .. 30000000).
     */
    @Inject(method = "isInWorldBounds(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void skyForge$isInWorldBounds(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        int minY, maxY;

        if (self.isClientSide()) {
            if (ClientSkyConstructionData.hasZones() || ClientSkyConstructionData.getGlobalMaxY() > 320 || ClientSkyConstructionData.getGlobalMinY() < -64) {
                minY = ClientSkyConstructionData.getGlobalMinY();
                maxY = ClientSkyConstructionData.getGlobalMaxY();
            } else {
                minY = self.dimensionType().minY();
                maxY = minY + self.dimensionType().height();
            }
        } else if (self instanceof ServerLevel serverLevel) {
            SkyConstructionData data = SkyConstructionData.get(serverLevel);
            if (data != null) {
                minY = data.getGlobalMinY();
                maxY = data.getGlobalMaxY();
            } else {
                return; // ванильная логика
            }
        } else {
            return;
        }

        boolean inBounds = pos.getY() >= minY && pos.getY() < maxY
                && pos.getX() >= -30000000 && pos.getZ() >= -30000000
                && pos.getX() < 30000000 && pos.getZ() < 30000000;
        cir.setReturnValue(inBounds);
    }

    /**
     * Прямое переопределение default-методов isOutsideBuildHeight из LevelHeightAccessor для класса Level.
     * Работает на обеих сторонах (сервер + клиент).
     */
    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        return this.isOutsideBuildHeight(pos.getY());
    }

    @Override
    public boolean isOutsideBuildHeight(int y) {
        Level self = (Level) (Object) this;
        int minY, maxY;

        if (self.isClientSide()) {
            if (ClientSkyConstructionData.hasZones() || ClientSkyConstructionData.getGlobalMaxY() > 320 || ClientSkyConstructionData.getGlobalMinY() < -64) {
                minY = ClientSkyConstructionData.getGlobalMinY();
                int alignedHeight = ((ClientSkyConstructionData.getGlobalMaxY() - minY + 15) / 16) * 16;
                maxY = minY + alignedHeight;
            } else {
                minY = self.dimensionType().minY();
                maxY = minY + self.dimensionType().height();
            }
        } else if (self instanceof ServerLevel serverLevel) {
            SkyConstructionData data = SkyConstructionData.get(serverLevel);
            if (data != null) {
                minY = data.getGlobalMinY();
                int alignedHeight = ((data.getGlobalMaxY() - minY + 15) / 16) * 16;
                maxY = minY + alignedHeight;
            } else {
                minY = self.dimensionType().minY();
                maxY = minY + self.dimensionType().height();
            }
        } else {
            minY = self.dimensionType().minY();
            maxY = minY + self.dimensionType().height();
        }

        return y < minY || y >= maxY;
    }

    /**
     * Переопределяем getMaxBuildHeight на обеих сторонах (сервер + клиент).
     * На клиенте возвращает выровненную высоту при наличии расширенных зон ClientSkyConstructionData,
     * что критически важно для физических свиперов коллизий Lithium (ChunkAwareBlockCollisionSweeper),
     * которые иначе обрезают (clamp) коллизию игрока на уровне ванильного Y=320.
     */
    @Override
    public int getMaxBuildHeight() {
        Level self = (Level) (Object) this;
        if (self.isClientSide()) {
            int maxGlobalY = ClientSkyConstructionData.getGlobalMaxY();
            int minGlobalY = ClientSkyConstructionData.getGlobalMinY();
            if (maxGlobalY > 320 || minGlobalY < -64) {
                int alignedHeight = ((maxGlobalY - minGlobalY + 15) / 16) * 16;
                return minGlobalY + alignedHeight;
            }
        } else if (self instanceof ServerLevel serverLevel) {
            SkyConstructionData data = SkyConstructionData.get(serverLevel);
            if (data != null) {
                int minY = data.getGlobalMinY();
                int maxY = data.getGlobalMaxY();
                int alignedHeight = ((maxY - minY + 15) / 16) * 16;
                return minY + alignedHeight;
            }
        }
        return self.dimensionType().minY() + self.dimensionType().height();
    }

    @Override
    public int getMinBuildHeight() {
        Level self = (Level) (Object) this;
        if (self.isClientSide()) {
            int minGlobalY = ClientSkyConstructionData.getGlobalMinY();
            int maxGlobalY = ClientSkyConstructionData.getGlobalMaxY();
            if (maxGlobalY > 320 || minGlobalY < -64) {
                return minGlobalY;
            }
        } else if (self instanceof ServerLevel serverLevel) {
            SkyConstructionData data = SkyConstructionData.get(serverLevel);
            if (data != null) {
                return data.getGlobalMinY();
            }
        }
        return self.dimensionType().minY();
    }

    @Override
    public int getHeight() {
        Level self = (Level) (Object) this;
        if (self.isClientSide()) {
            int maxGlobalY = ClientSkyConstructionData.getGlobalMaxY();
            int minGlobalY = ClientSkyConstructionData.getGlobalMinY();
            if (maxGlobalY > 320 || minGlobalY < -64) {
                return ((maxGlobalY - minGlobalY + 15) / 16) * 16;
            }
        } else if (self instanceof ServerLevel serverLevel) {
            SkyConstructionData data = SkyConstructionData.get(serverLevel);
            if (data != null) {
                int minY = data.getGlobalMinY();
                int maxY = data.getGlobalMaxY();
                return ((maxY - minY + 15) / 16) * 16;
            }
        }
        return self.dimensionType().height();
    }

    @Override
    public int getSectionsCount() {
        return this.getHeight() / 16;
    }

    @Override
    public int getMaxSection() {
        return SectionPos.blockToSectionCoord(this.getMaxBuildHeight() - 1) + 1;
    }

    @Override
    public int getMinSection() {
        return SectionPos.blockToSectionCoord(this.getMinBuildHeight());
    }

    @Override
    public int getSectionIndex(int y) {
        return this.getSectionIndexFromSectionY(SectionPos.blockToSectionCoord(y));
    }

    @Override
    public int getSectionIndexFromSectionY(int sectionY) {
        return sectionY - this.getMinSection();
    }

    @Override
    public int getSectionYFromSectionIndex(int sectionIndex) {
        return sectionIndex + this.getMinSection();
    }
}
