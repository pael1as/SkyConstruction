package com.example.skyconstruction.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Плагин конфигурации Mixin для SkyForge.
 * Обеспечивает корректный приоритет применения миксинов и совместимость с Sinytra Connector.
 */
public class SkyConstructionMixinPlugin implements IMixinConfigPlugin {

    /**
     * Возвращает повышенный приоритет (2000) для применения миксинов.
     * Необходим для корректной интеграции со сторонними загрузчиками (например, Sinytra Connector),
     * чтобы изменения границ высоты применялись поверх базовых трансформаций.
     */
    public int getPriority() {
        return 2000;
    }

    @Override
    public void onLoad(String mixinPackage) {
        // Устанавливаем системную локаль US, чтобы сторонние генераторы конфигов (например, FlyHigher)
        // не генерировали числа в JSON с запятыми вместо точек на системах с русской локалью
        try {
            Locale.setDefault(Locale.US);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String getRefMapperConfig() {
        // В NeoForge 1.21.1 используются официальные маппинги Mojang, refmap не требуется
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("Lithium")) {
            try {
                if (net.neoforged.fml.ModList.get() != null) {
                    return net.neoforged.fml.ModList.get().isLoaded("lithium");
                }
                if (net.neoforged.fml.loading.FMLLoader.getLoadingModList() != null) {
                    return net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById("lithium") != null;
                }
            } catch (Throwable t) {
                try {
                    Class.forName("net.caffeinemc.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper", false, getClass().getClassLoader());
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }
            return false;
        }

        // Миксины Sodium помечены @Pseudo, поэтому Mixin безопасно применит их при наличии Sodium
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // Дополнительная фильтрация целей не требуется
    }

    @Override
    public List<String> getMixins() {
        // Дополнительные динамические миксины отсутствуют, список берется из skyconstruction.mixins.json
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Хук перед модификацией байткода целевого класса
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Хук после модификации байткода целевого класса
    }
}
