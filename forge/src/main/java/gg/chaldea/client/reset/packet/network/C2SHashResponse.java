package gg.chaldea.client.reset.packet.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.HandshakeMessages;

/**
 * Client → Server (ID 97).
 * hasCache = true  → client has a valid cached registry; server may skip S2CRegistry.
 * hasCache = false → client needs a full registry sync.
 *
 * Extends C2SAcknowledge (public) rather than LoginIndexedMessage (package-private).
 */
public class C2SHashResponse extends HandshakeMessages.C2SAcknowledge {

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

    private int loginIndex;

    public int getLoginIndex()        { return loginIndex; }
    public void setLoginIndex(int idx) { this.loginIndex = idx; }
}
