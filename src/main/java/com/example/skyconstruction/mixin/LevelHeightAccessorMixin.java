package com.example.skyconstruction.mixin;

import com.example.skyconstruction.client.ClientSkyConstructionData;
import com.example.skyconstruction.data.SkyConstructionData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelHeightAccessor.class)
public interface LevelHeightAccessorMixin {

    @Inject(method = "isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void skyForge$isOutsideBuildHeightPos(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Level level) {
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();

            if (level.isClientSide()) {
                if (ClientSkyConstructionData.getGlobalMaxY() > 320 || ClientSkyConstructionData.getGlobalMinY() < -64) {
                    minY = ClientSkyConstructionData.getGlobalMinY();
                    int alignedHeight = ((ClientSkyConstructionData.getGlobalMaxY() - minY + 15) / 16) * 16;
                    maxY = minY + alignedHeight;
                }
            } else if (level instanceof ServerLevel serverLevel) {
                SkyConstructionData data = SkyConstructionData.get(serverLevel);
                if (data != null) {
                    minY = data.getGlobalMinY();
                    int alignedHeight = ((data.getGlobalMaxY() - minY + 15) / 16) * 16;
                    maxY = minY + alignedHeight;
                }
            }

            int y = pos.getY();
            cir.setReturnValue(y < minY || y >= maxY);
        }
    }

    @Inject(method = "isOutsideBuildHeight(I)Z", at = @At("HEAD"), cancellable = true)
    private void skyForge$isOutsideBuildHeightInt(int y, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Level level) {
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();

            if (level.isClientSide()) {
                if (ClientSkyConstructionData.getGlobalMaxY() > 320 || ClientSkyConstructionData.getGlobalMinY() < -64) {
                    minY = ClientSkyConstructionData.getGlobalMinY();
                    int alignedHeight = ((ClientSkyConstructionData.getGlobalMaxY() - minY + 15) / 16) * 16;
                    maxY = minY + alignedHeight;
                }
            } else if (level instanceof ServerLevel serverLevel) {
                SkyConstructionData data = SkyConstructionData.get(serverLevel);
                if (data != null) {
                    minY = data.getGlobalMinY();
                    int alignedHeight = ((data.getGlobalMaxY() - minY + 15) / 16) * 16;
                    maxY = minY + alignedHeight;
                }
            }

            cir.setReturnValue(y < minY || y >= maxY);
        }
    }

    @Inject(method = "getMaxBuildHeight()I", at = @At("HEAD"), cancellable = true)
    private void skyForge$getMaxBuildHeight(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this instanceof Level level) {
            if (level.isClientSide()) {
                int maxGlobalY = ClientSkyConstructionData.getGlobalMaxY();
                int minGlobalY = ClientSkyConstructionData.getGlobalMinY();
                if (maxGlobalY > 320 || minGlobalY < -64) {
                    int alignedHeight = ((maxGlobalY - minGlobalY + 15) / 16) * 16;
                    cir.setReturnValue(minGlobalY + alignedHeight);
                }
            } else if (level instanceof ServerLevel serverLevel) {
                SkyConstructionData data = SkyConstructionData.get(serverLevel);
                if (data != null) {
                    int minY = data.getGlobalMinY();
                    int maxY = data.getGlobalMaxY();
                    int alignedHeight = ((maxY - minY + 15) / 16) * 16;
                    cir.setReturnValue(minY + alignedHeight);
                }
            }
        }
    }

    @Inject(method = "getSectionsCount()I", at = @At("HEAD"), cancellable = true)
    private void skyForge$getSectionsCount(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this instanceof Level level) {
            if (level.isClientSide()) {
                int maxGlobalY = ClientSkyConstructionData.getGlobalMaxY();
                int minGlobalY = ClientSkyConstructionData.getGlobalMinY();
                if (maxGlobalY > 320 || minGlobalY < -64) {
                    int alignedHeight = ((maxGlobalY - minGlobalY + 15) / 16) * 16;
                    cir.setReturnValue(alignedHeight / 16);
                }
            } else if (level instanceof ServerLevel serverLevel) {
                SkyConstructionData data = SkyConstructionData.get(serverLevel);
                if (data != null) {
                    int minY = data.getGlobalMinY();
                    int maxY = data.getGlobalMaxY();
                    int alignedHeight = ((maxY - minY + 15) / 16) * 16;
                    cir.setReturnValue(alignedHeight / 16);
                }
            }
        }
    }
}
