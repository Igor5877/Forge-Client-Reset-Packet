package gg.chaldea.server.fastlogin;

import io.netty.channel.Channel;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which active connections may skip the FML registry sync.
 *
 * Lifecycle:
 *   1. Server sends S2CHashChallenge to a client.
 *   2. Client responds with C2SHashResponse(hasCache).
 *   3. If hasCache=true → {@link #markSkip(Channel)} is called.
 *   4. MixinHandshakeHandler checks {@link #shouldSkip(Channel)} before sending
 *      registry packets; if true it skips them entirely.
 *   5. After the connection reaches PLAY state (or disconnects) the entry is
 *      removed via {@link #clear(Channel)}.
 */
public class ConnectionSkipTracker {

    // Channels for which registry sync should be skipped
    private static final Set<Channel> skipSet = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Channels that have been challenged but not yet responded
    private static final Set<Channel> pendingSet = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void markPending(Channel ch) {
        pendingSet.add(ch);
    }

    public static boolean isPending(Channel ch) {
        return pendingSet.contains(ch);
    }

    public static void markSkip(Channel ch) {
        pendingSet.remove(ch);
        skipSet.add(ch);
    }

    public static void markNoSkip(Channel ch) {
        pendingSet.remove(ch);
        skipSet.remove(ch);
    }

    public static boolean shouldSkip(Channel ch) {
        return skipSet.contains(ch);
    }

    public static void clear(Channel ch) {
        pendingSet.remove(ch);
        skipSet.remove(ch);
    }
}
