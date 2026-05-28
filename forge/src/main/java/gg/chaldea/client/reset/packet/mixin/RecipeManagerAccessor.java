package gg.chaldea.client.reset.packet.mixin;

import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Direct access to RecipeManager's two private state maps so the recipe cache
 * can swap them in O(1) instead of triggering a 3-4s vanilla rebuild.
 */
@Mixin(RecipeManager.class)
public interface RecipeManagerAccessor {

    @Accessor("recipes")
    Map crp$getRecipes();

    @Accessor("recipes")
    void crp$setRecipes(Map recipes);

    @Accessor("byName")
    Map crp$getByName();

    @Accessor("byName")
    void crp$setByName(Map byName);
}
