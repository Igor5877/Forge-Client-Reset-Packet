package gg.chaldea.server.fastlogin;

import io.netty.channel.Channel;

/**
 * ThreadLocal carrier identifying the Netty channel whose handshake the current
 * server-main-thread invocation is processing.  Mixin classes can't define
 * non-private static fields, so this lives in its own utility class.
 *
 * Set by MixinHandshakeHandler at the HEAD of handleClientModListOnServer,
 * read by MixinGameData inside gatherLoginPayloads().
 */
public final class ChannelContext {

    public static final ThreadLocal<Channel> CURRENT_CHANNEL = new ThreadLocal<>();

    private ChannelContext() {}
}
