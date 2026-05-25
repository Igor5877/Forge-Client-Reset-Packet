package gg.chaldea.server.fastlogin.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.LoginWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Mixin accessor exposing the package-private
 * {@code LoginWrapper#sendServerToClientLoginPacket} so our
 * {@link MixinHandshakeHandlerBatch} can re-invoke it directly from
 * its drain loop.
 */
@Mixin(value = LoginWrapper.class, remap = false)
public interface LoginWrapperAccessor {

    @Invoker("sendServerToClientLoginPacket")
    void fl$sendServerToClientLoginPacket(ResourceLocation channel, FriendlyByteBuf buffer, int index, Connection manager);
}
