package gg.chaldea.client.reset.packet.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.HandshakeMessages;

/**
 * Server → Client (ID 96).
 * Carries the SHA-256 hash of the server's registry state so the client can
 * decide whether its local cache is still valid.
 *
 * Extends C2SAcknowledge (which is public) rather than LoginIndexedMessage
 * (which is package-private) – same pattern used by S2CReset in this project.
 */
public class S2CHashChallenge extends HandshakeMessages.C2SAcknowledge {

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

    private int loginIndex;

    public int getLoginIndex()        { return loginIndex; }
    public void setLoginIndex(int idx) { this.loginIndex = idx; }
}
