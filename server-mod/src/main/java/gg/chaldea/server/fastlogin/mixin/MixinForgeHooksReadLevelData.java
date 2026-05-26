package gg.chaldea.server.fastlogin.mixin;

import gg.chaldea.server.fastlogin.CanonicalIdManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mutate the in-memory level.dat root NBT BEFORE
 * {@link ForgeHooks#readAdditionalLevelSaveData(CompoundTag, LevelStorageSource.LevelDirectory)}
 * processes it, so the existing Forge load path picks up the canonical
 * registry IDs.
 *
 * Net effect: Forge sees a level.dat whose fml/Registries section contains
 * the canonical mapping; its standard "remap on load" logic (used when mods
 * are added or IDs shift) re-assigns runtime IDs to match. No second pass,
 * no API hacks — we just sneak the data in before Forge looks at it.
 */
@Mixin(value = ForgeHooks.class, remap = false)
public abstract class MixinForgeHooksReadLevelData {

    @Inject(method = "readAdditionalLevelSaveData", at = @At("HEAD"))
    private static void crp$mergeCanonicalRegistries(
            CompoundTag rootTag,
            LevelStorageSource.LevelDirectory levelDirectory,
            CallbackInfo ci) {
        CanonicalIdManager.patchRootTag(rootTag, levelDirectory);
    }
}
