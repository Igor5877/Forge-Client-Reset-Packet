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
 *      - If canonical exists and matches: return the existing canonical bytes.
 *      - If canonical exists but DIFFERS (self-heal): a stale in-memory
 *        canonical can no longer be produced by any live backend once a
 *        modpack update adds/changes registry entries (seen 2026-08-03:
 *        menu/block_entity_type IDs drifted between backends because the
 *        canonical predated a mod update and was silently kept forever,
 *        requiring a full manual stop-all/wipe-nbt/restart-in-order cycle
 *        to fix). Instead of ignoring the new snapshot, ADOPT it as the new
 *        canonical and return it unchanged, so this backend is immediately
 *        confirmed as (the new) master. Previously-canonical backends do
 *        NOT need to be touched by an operator - they self-correct the next
 *        time they restart for any reason (deploy, crash-restart, or a
 *        dynamic island spinning up fresh) by fetching this new canonical.
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
        logger.info("[CanonicalRegistry] {} became master ({} bytes saved as canonical)",
                source, body.length);
      } else if (java.util.Arrays.equals(body, canonicalBytes)) {
        logger.info("[CanonicalRegistry] {} registered ({} bytes); matches canonical from {}",
                source, body.length, canonicalSource);
      } else {
        // Self-heal: a live backend can never retroactively match a canonical
        // that predates its current registry content (e.g. after a modpack
        // update). Adopt this snapshot as the new canonical instead of
        // silently keeping the stale one forever - see class javadoc.
        String previousSource = canonicalSource;
        int previousSize = canonicalBytes.length;
        canonicalBytes = body;
        canonicalSource = source;
        logger.warn("[CanonicalRegistry] {} registered ({} bytes) DIFFERING from canonical "
                + "previously set by {} ({} bytes) — auto-adopting {} as the new canonical "
                + "(self-heal). {} and any other backend still on the old canonical will "
                + "re-sync automatically on their next restart.",
                source, body.length, previousSource, previousSize, source, previousSource);
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
    logger.info("[CanonicalRegistry] Cleared canonical state");
  }
}
