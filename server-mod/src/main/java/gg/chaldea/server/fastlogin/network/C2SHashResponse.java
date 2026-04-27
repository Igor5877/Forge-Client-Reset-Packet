package gg.chaldea.server.fastlogin.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.HandshakeMessages;

/**
 * Client → Server.
 * The client's answer to S2CHashChallenge.
 *
 * hasCache = true  → client has a valid cached registry for this hash → server skips S2CRegistry
 * hasCache = false → client needs a full sync
 */
public class C2SHashResponse extends HandshakeMessages.LoginIndexedMessage {

    private final boolean hasCache;

    public C2SHashResponse(boolean hasCache) {
        this.hasCache = hasCache;
    }

    public boolean hasCache() {
        return hasCache;
    }

    public static C2SHashResponse decode(FriendlyByteBuf buf) {
        return new C2SHashResponse(buf.readBoolean());
    }

    public static void encode(C2SHashResponse msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.hasCache);
    }

    // ----- HandshakeMessages boilerplate -----

    private int loginIndex;

    @Override
    public int getLoginIndex() { return loginIndex; }

    @Override
    public void setLoginIndex(int idx) { this.loginIndex = idx; }
}
