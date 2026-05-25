package org.adde0109.ambassador.cache;

import com.google.common.io.BaseEncoding;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3 step 3a — capture-only FML handshake packet cache.
 *
 * Captures the raw bytes of every LoginPluginMessage that flows backend→client
 * during the Forge handshake, keyed by server name. When the backend signals
 * login success, the accumulated bytes are SHA-256 fingerprinted and logged.
 *
 * Next steps (3b/3c/3d):
 *   3b — Custom plugin message channel so client can advertise "I have cache X"
 *   3c — Skip relaying S2CRegistry (FML packet ID 2) when client has cache
 *   3d — Persistent storage of {fingerprint -> packet bytes} so cache survives
 *        restarts; client-side replay into FML handshake state machine.
 *
 * Right now this class only OBSERVES. It does not change behavior.
 */
public class RegistryPacketCache {

  private final Logger logger;
  private final ConcurrentHashMap<String, ServerCapture> inFlight = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> serverFingerprints = new ConcurrentHashMap<>();

  public RegistryPacketCache(Logger logger) {
    this.logger = logger;
  }

  /**
   * Capture one LoginPluginMessage payload for the given backend server.
   * Called on the netty IO thread (per packet); ConcurrentHashMap + per-entry
   * synchronization keeps it thread-safe across concurrent player joins.
   */
  public void capturePacket(String serverName, ByteBuf content) {
    ServerCapture cap = inFlight.computeIfAbsent(serverName, k -> new ServerCapture());
    // Snapshot bytes — the ByteBuf is reused by Netty after this method returns.
    int len = content.readableBytes();
    byte[] copy = new byte[len];
    content.getBytes(content.readerIndex(), copy);
    synchronized (cap) {
      cap.packets.add(copy);
      cap.totalBytes += len;
    }
  }

  /**
   * Called when backend handshake completes (LoginSuccess). Computes the
   * fingerprint over the captured packets and logs it. Resets in-flight buffer
   * so the next player joining the same backend starts fresh.
   */
  public String finalizeAndLog(String serverName) {
    ServerCapture cap = inFlight.remove(serverName);
    if (cap == null || cap.packets.isEmpty()) {
      logger.warn("[ambassador-cache] No captured packets for server {} at login_success", serverName);
      return null;
    }
    String fp;
    synchronized (cap) {
      fp = computeFingerprint(cap.packets);
      serverFingerprints.put(serverName, fp);
      logger.info("[ambassador-cache] server={} packets={} total_bytes={} fingerprint={}",
          serverName, cap.packets.size(), cap.totalBytes, fp);
    }
    return fp;
  }

  /** Returns last-seen fingerprint for a server, or null if unknown. */
  public String getFingerprint(String serverName) {
    return serverFingerprints.get(serverName);
  }

  private static String computeFingerprint(List<byte[]> packets) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (byte[] p : packets) {
        md.update(p);
      }
      return BaseEncoding.base16().lowerCase().encode(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 unavailable", e);
    }
  }

  private static final class ServerCapture {
    final List<byte[]> packets = new ArrayList<>();
    long totalBytes = 0L;
  }
}
