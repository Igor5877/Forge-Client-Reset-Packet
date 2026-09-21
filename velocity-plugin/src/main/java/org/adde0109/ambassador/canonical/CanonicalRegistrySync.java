package org.adde0109.ambassador.canonical;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Tiny HTTP service that lets backend Forge servers exchange their
 * registry-ID snapshots so every backend ends up using the same numerical
 * IDs (canonical reference).
 *
 * Flow on backend startup:
 *   1. Backend POSTs its own ID snapshot to /canonical/register
 *      Headers: X-Server-Name: <backend name>
 *      Body: NBT-compressed CompoundTag of registry → Snapshot.write()
 *   2. Proxy:
 *      - If no canonical stored yet: store this snapshot as canonical, mark
 *        the source backend as master. Return the same bytes.
 *      - If canonical exists and matches: return the existing canonical bytes,
 *        and clear any pending self-heal candidate (this registration just
 *        confirmed the current canonical is still alive and reproducible).
 *      - If canonical exists but DIFFERS (self-heal, quarantined): a stale
 *        in-memory canonical can no longer be produced by any live backend
 *        once a modpack update adds/changes registry entries (seen
 *        2026-08-03: menu/block_entity_type IDs drifted between backends
 *        because the canonical predated a mod update and was silently kept
 *        forever, requiring a full manual stop-all/wipe-nbt/restart-in-order
 *        cycle to fix). Naively adopting ANY differing snapshot immediately
 *        (seen 2026-08-23) causes perpetual flip-flop when two backend
 *        *types* legitimately produce two different-but-both-stable
 *        snapshots (e.g. a lobby vs. an island backend whose own runtime
 *        registry content genuinely differs by a fixed set of entries) -
 *        every restart of either type re-triggers "self-heal" back to its
 *        own shape, and no client ever gets a stable fingerprint to cache
 *        against. Instead, a differing snapshot is held as a *pending
 *        candidate*: it is only promoted to canonical once the SAME bytes
 *        are reported a second time (any backend, any restart) while still
 *        differing from canonical. A genuine one-time drift (every backend
 *        settles on one new shape after an update) confirms itself this way
 *        within two registrations; two backend types oscillating between
 *        their own two stable shapes never repeats the same candidate twice
 *        in a row, so it never promotes and canonical stays put - operators
 *        still resolve that case with the documented full re-sync.
 *   3. Backend compares response to what it sent.
 *      - Identical: this backend IS the master, nothing to do.
 *      - Different: save response to local file. Next restart, MixinForgeHooks
 *        patches level.dat with this canonical, Forge re-maps IDs to match.
 *
 * GET /canonical/get returns the current canonical bytes or 404 if none yet.
 * Used by clients that want to peek without registering.
 */
public class CanonicalRegistrySync {

  private final Logger logger;
  private final int port;
  private HttpServer httpServer;

  // Shared canonical snapshot, lazily populated by the first backend that registers.
  private volatile byte[] canonicalBytes = null;
  private volatile String canonicalSource = null;

  // Self-heal quarantine: a snapshot that differs from canonical must be seen
  // twice (not necessarily from the same backend) before it is promoted. See
  // class javadoc "quarantined" note.
  private byte[] pendingBytes = null;
  private String pendingSource = null;

  public CanonicalRegistrySync(Logger logger, int port) {
    this.logger = logger;
    this.port = port;
  }

  public synchronized void start() throws IOException {
    if (httpServer != null) return;
    httpServer = HttpServer.create(new InetSocketAddress(port), 0);
    httpServer.createContext("/canonical/register", this::handleRegister);
    httpServer.createContext("/canonical/get", this::handleGet);
    httpServer.setExecutor(null); // use a single-threaded default executor
    httpServer.start();
    logger.info("[CanonicalRegistry] Listening on port {} for backend registry sync", port);
  }

  public synchronized void stop() {
    if (httpServer != null) {
      httpServer.stop(0);
      httpServer = null;
    }
  }

  private void handleRegister(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    String source = exchange.getRequestHeaders().getFirst("X-Server-Name");
    if (source == null) source = exchange.getRemoteAddress().toString();

    byte[] body = exchange.getRequestBody().readAllBytes();
    byte[] responseBody;

    synchronized (this) {
      if (canonicalBytes == null) {
        canonicalBytes = body;
        canonicalSource = source;
        pendingBytes = null;
        pendingSource = null;
        logger.info("[CanonicalRegistry] {} became master ({} bytes saved as canonical)",
                source, body.length);
      } else if (java.util.Arrays.equals(body, canonicalBytes)) {
        // A live confirmation of the current canonical - any differing
        // candidate seen since is stale noise, drop it so it doesn't get a
        // free pass on a later unrelated registration.
        if (pendingBytes != null) {
          logger.info("[CanonicalRegistry] {} registered ({} bytes); matches canonical from {} "
                  + "- clearing stale pending candidate from {}",
                  source, body.length, canonicalSource, pendingSource);
          pendingBytes = null;
          pendingSource = null;
        } else {
          logger.info("[CanonicalRegistry] {} registered ({} bytes); matches canonical from {}",
                  source, body.length, canonicalSource);
        }
      } else if (pendingBytes != null && java.util.Arrays.equals(body, pendingBytes)) {
        // Self-heal (confirmed): the same differing snapshot was reported
        // twice in a row - a genuine, reproducible drift rather than one
        // backend type's own permanent shape. Promote it.
        String previousSource = canonicalSource;
        int previousSize = canonicalBytes.length;
        canonicalBytes = body;
        canonicalSource = source;
        pendingBytes = null;
        pendingSource = null;
        logger.warn("[CanonicalRegistry] {} registered ({} bytes) matching the pending candidate "
                + "first seen from {} — CONFIRMED drift, auto-adopting as the new canonical "
                + "(self-heal). Previous canonical was from {} ({} bytes); it and any other "
                + "backend still on it will re-sync automatically on their next restart.",
                source, body.length, pendingSource, previousSource, previousSize);
      } else {
        // First sighting of a differing snapshot (or it differs from both
        // canonical and the previous pending candidate) - quarantine it
        // instead of adopting immediately. See class javadoc.
        String discardedPendingSource = pendingSource;
        pendingBytes = body;
        pendingSource = source;
        logger.warn("[CanonicalRegistry] {} registered ({} bytes) DIFFERING from canonical set by "
                + "{} ({} bytes) — holding as pending self-heal candidate (needs to be seen again "
                + "to promote); canonical unchanged for now.{}",
                source, body.length, canonicalSource, canonicalBytes.length,
                discardedPendingSource != null
                        ? " (replaces stale pending candidate from " + discardedPendingSource + ")"
                        : "");
      }
      responseBody = canonicalBytes;
    }

    exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
    exchange.getResponseHeaders().add("X-Canonical-Source", canonicalSource == null ? "" : canonicalSource);
    exchange.sendResponseHeaders(200, responseBody.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(responseBody);
    }
  }

  private void handleGet(HttpExchange exchange) throws IOException {
    byte[] body;
    String source;
    synchronized (this) {
      body = canonicalBytes;
      source = canonicalSource;
    }
    if (body == null) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
    exchange.getResponseHeaders().add("X-Canonical-Source", source == null ? "" : source);
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
  }

  /** Clears the canonical snapshot. Useful when modset changes and you want
   *  the next backend to register fresh. Exposed for future admin command. */
  public synchronized void reset() {
    canonicalBytes = null;
    canonicalSource = null;
    pendingBytes = null;
    pendingSource = null;
    logger.info("[CanonicalRegistry] Cleared canonical state");
  }
}
