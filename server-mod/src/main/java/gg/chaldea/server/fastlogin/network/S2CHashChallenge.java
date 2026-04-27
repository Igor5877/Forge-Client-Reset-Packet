package gg.chaldea.server.fastlogin.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.HandshakeMessages;

/**
 * Server → Client.
 * Sent by the server early in the FML handshake (right after S2CModList).
 * Contains the SHA-256 hash of the server's registry state.
 *
 * The client checks its local cache:
 *   - hash match  → responds with C2SHashResponse(hasCache=true)  → server skips S2CRegistry
 *   - no match    → responds with C2SHashResponse(hasCache=false) → server does full sync
 */
public class S2CHashChallenge extends HandshakeMessages.LoginIndexedMessage {

    private final String registryHash;

    public S2CHashChallenge(String registryHash) {
        this.registryHash = registryHash;
    }

    public String getRegistryHash() {
        return registryHash;
    }

    public static S2CHashChallenge decode(FriendlyByteBuf buf) {
        return new S2CHashChallenge(buf.readUtf(64));
    }

    public static void encode(S2CHashChallenge msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.registryHash, 64);
    }

    // ----- HandshakeMessages boilerplate -----

    private int loginIndex;

    @Override
    public int getLoginIndex() { return loginIndex; }

    @Override
    public void setLoginIndex(int idx) { this.loginIndex = idx; }
}
