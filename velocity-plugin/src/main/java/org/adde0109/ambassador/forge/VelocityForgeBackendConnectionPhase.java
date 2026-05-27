package org.adde0109.ambassador.forge;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.BackendConnectionPhase;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import net.kyori.adventure.text.Component;
import org.adde0109.ambassador.Ambassador;
import org.adde0109.ambassador.forge.packet.*;
import org.adde0109.ambassador.forge.pipeline.CommandDecoderErrorCatcher;

import io.netty.buffer.Unpooled;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public enum VelocityForgeBackendConnectionPhase implements BackendConnectionPhase {
  NOT_STARTED {
    @Override
    VelocityForgeBackendConnectionPhase nextPhase() {
      return IN_PROGRESS;
    }

    @Override
    public boolean consideredComplete() {
      //Safe if the server hasn't initiated the handshake yet.
      return true;
    }
  },
  IN_PROGRESS {
    @Override
    public void onLoginSuccess(VelocityServerConnection serverCon, ConnectedPlayer player) {
      serverCon.setConnectionPhase(VelocityForgeBackendConnectionPhase.COMPLETE);

      long[] t = SWITCH_TIMING.remove(player.getUniqueId());
      if (t != null) {
        Ambassador.getInstance().logger.info(
            "[crp-timing] player={} server={} EVENT=login_success TOTAL_HANDSHAKE_MS={}",
            player.getUsername(), serverCon.getServerInfo().getName(),
            System.currentTimeMillis() - t[0]);
      }

      serverCon.getConnection().getChannel().pipeline().addBefore(Connections.MINECRAFT_DECODER,
              ForgeConstants.COMMAND_ERROR_CATCHER,
              new CommandDecoderErrorCatcher(serverCon.getConnection().getProtocolVersion(),player));
    }

    @Override
    void onTransitionToNewPhase(VelocityServerConnection connection) {
      MinecraftConnection mc = connection.getConnection();
      if (mc != null) {
        //This looks ugly. But unless the player didn't have a FML marker, we're fine.
        mc.setType(connection.getPlayer().getConnection().getType());
      }
    }
  },

  COMPLETE {
    @Override
    public boolean consideredComplete() {
      return true;
    }
  };

  public ForgeHandshake handshake = new ForgeHandshake();
  CountDownLatch remainingRegistries;

  // Diagnostic counters (Phase 3 investigation)
  private static final ConcurrentHashMap<java.util.UUID, long[]> SWITCH_TIMING =
      new ConcurrentHashMap<>();

  /**
   * Per-backend registry fingerprint cache.
   *
   * Key:   server name (from RegisteredServer.getServerInfo().getName())
   * Value: defensive copy of ForgeHandshake.getRegistries() — Map<registryName, Adler32>
   *
   * Populated after a successful isCompatible() check so we know the backend's
   * registry fingerprint is consistent with a known-good client handshake.
   *
   * Used to detect "same modset" transitions: if the fingerprints of
   * old backend and new backend are equal, we send fastlogin:same_modset to
   * the client BEFORE the CRP reset packet, enabling Phase 2 chunk-buffer reuse.
   *
   * Concurrency: ConcurrentHashMap for thread-safe reads/writes; values are
   * immutable HashMap snapshots taken at store time.
   */
  private static final ConcurrentHashMap<String, Map<String, Long>> BACKEND_REGISTRY_CACHE =
      new ConcurrentHashMap<>();

  VelocityForgeBackendConnectionPhase() {
  }

  public void handle(VelocityServerConnection server, ConnectedPlayer player, IForgeLoginWrapperPacket<Context> message) {
    VelocityForgeBackendConnectionPhase newPhase = getNewPhase(server,message);

    server.setConnectionPhase(newPhase);

    //Forge -> Forge

    VelocityForgeClientConnectionPhase clientPhase = (VelocityForgeClientConnectionPhase) player.getPhase();


    if (!player.isActive()) {
      return;
    }

    if (!clientPhase.consideredComplete()) {
      //Initial Forge
      if (message instanceof ModListPacket modListPacket) {
        clientPhase.forgeHandshake = new ForgeHandshake();
        // Stamp start of fresh handshake for this player
        long[] t = new long[]{System.currentTimeMillis(), 0L};
        SWITCH_TIMING.put(player.getUniqueId(), t);
        Ambassador.getInstance().logger.info(
            "[crp-timing] player={} server={} EVENT=modlist expecting={} (handshake start)",
            player.getUsername(), server.getServerInfo().getName(),
            modListPacket.getRegistries().size());
      }
      if (message instanceof RegistryPacket registryPacket) {
        clientPhase.forgeHandshake.addRegistry(registryPacket);
        long[] t = SWITCH_TIMING.get(player.getUniqueId());
        if (t != null) {
          long now = System.currentTimeMillis();
          long sinceStart = now - t[0];
          long sinceLast = t[1] == 0L ? 0 : now - t[1];
          t[1] = now;
          Ambassador.getInstance().logger.info(
              "[crp-timing] player={} server={} EVENT=registry name={} since_start_ms={} since_last_ms={}",
              player.getUsername(), server.getServerInfo().getName(),
              registryPacket.getRegistryName(), sinceStart, sinceLast);
        }
      }
      if (message instanceof ConfigDataPacket) {
        long[] t = SWITCH_TIMING.get(player.getUniqueId());
        if (t != null) {
          Ambassador.getInstance().logger.info(
              "[crp-timing] player={} server={} EVENT=configdata since_start_ms={}",
              player.getUsername(), server.getServerInfo().getName(),
              System.currentTimeMillis() - t[0]);
        }
      }
      player.getConnection().write(message);
    } else {
      //Reset client if not ready to receive new handshake
      if (clientPhase.getResetType() == VelocityForgeClientConnectionPhase.ClientResetType.CRP) {
        // --- sameModset detection ---
        // Before sending the CRP reset packet, check if the old and new backends
        // have identical registry fingerprints (cached from previous handshakes).
        // If yes, send fastlogin:same_modset to the client so it can reuse GPU
        // chunk buffers (Phase 2) instead of releasing and reallocating all VBOs.
        // This message must arrive BEFORE the reset packet (Netty write-order guarantee).
        if (player.getConnectedServer() != null) {
          String oldServer = player.getConnectedServer().getServerInfo().getName();
          String newServer = server.getServerInfo().getName();
          Map<String, Long> oldRegs = BACKEND_REGISTRY_CACHE.get(oldServer);
          Map<String, Long> newRegs = BACKEND_REGISTRY_CACHE.get(newServer);
          if (oldRegs != null && newRegs != null && oldRegs.equals(newRegs)) {
            player.getConnection().write(new PluginMessagePacket(
                "fastlogin:same_modset", Unpooled.wrappedBuffer(new byte[]{1})));
            Ambassador.getInstance().logger.info(
                "[sameModset] player={} {} → {} — registry fingerprints match, sent same_modset",
                player.getUsername(), oldServer, newServer);
          } else {
            Ambassador.getInstance().logger.info(
                "[sameModset] player={} {} → {} — no cache or mismatch (old={} new={}), skip",
                player.getUsername(), oldServer, newServer,
                oldRegs != null ? "cached" : "absent",
                newRegs != null ? "cached" : "absent");
          }
        }
        clientPhase.resetConnectionPhase(player);
        player.getConnection().write(message);
        return;
      }
      if (clientPhase.getResetType() == VelocityForgeClientConnectionPhase.ClientResetType.SR) {
        clientPhase.resetConnectionPhase(player);
        player.getConnection().write(message);
        return;
      }

      if (clientPhase.forgeHandshake.getModListReplyPacket() == null) {
        //We have nothing to respond with during this handshake. Unable to proceed.
        if (Ambassador.getInstance().config.isEnableKickReset()) {
          //Kick-reset
          Ambassador.getInstance().reconnectSwitchPlayer(player);
        } else {
          Ambassador.getInstance().logger.error("Unable for {} to switch servers. Vanilla({}) -> Forge({}) switch " +
                          "without client side mod or kick-reset enabled is not yet supported!",
                  player.getGameProfile().getName(), player.getConnectedServer().getServerInfo().getName(),
                  server.getServerInfo().getName());
          server.disconnect();
        }
        return;
      }

      if (message instanceof ModListPacket modListPacket) {
        remainingRegistries = new CountDownLatch(modListPacket.getRegistries().size());

        if (Ambassador.getInstance().config.isDebugMode())
          player.sendMessage(Component.text("Expecting " + modListPacket.getRegistries().size() +
                  " packets from server " + server.getServer().getServerInfo().getName()));

        long time = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
          try {
            remainingRegistries.await();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }).thenAcceptAsync((v) -> {

          if(Ambassador.getInstance().config.isDebugMode()) {
            player.sendMessage(Component.text("Handshake took: " + (System.currentTimeMillis()-time) + " ms"));
            player.sendMessage(Component.text("Avg packet time" +
                    (System.currentTimeMillis()-time)/modListPacket.getRegistries().size() + " ms"));
          }

          if (Ambassador.getInstance().config.isBypassRegistryCheck() ||
                  clientPhase.forgeHandshake.isCompatible(handshake)) {
            // Cache this backend's registry fingerprint for future sameModset checks.
            // The map is an immutable snapshot so future modifications to `handshake`
            // (from the next player's handshake — enum singletons share the field)
            // don't corrupt the cache.
            BACKEND_REGISTRY_CACHE.put(
                server.getServerInfo().getName(),
                new HashMap<>(handshake.getRegistries()));
            Ambassador.getInstance().logger.info(
                "[sameModset] Cached registry fingerprint for server '{}' ({} registries)",
                server.getServerInfo().getName(), handshake.getRegistries().size());
            server.ensureConnected().write(clientPhase.forgeHandshake.getModListReplyPacket());
          } else if (Ambassador.getInstance().config.isEnableKickReset()) {
            //Kick-reset
            Ambassador.getInstance().reconnectSwitchPlayer(player);
          } else {
            Ambassador.getInstance().logger.error("Unable to switch due to the registries of " +
                    server.getServer().getServerInfo().getName() + " being different from the registries of " +
                    player.getConnectedServer().getServer().getServerInfo().getName());
            server.disconnect();
          }
        }, server.ensureConnected().eventLoop());
      } else if (message instanceof RegistryPacket registryPacket) {
        server.getConnection().write(new ACKPacket(Context.fromContext(message.getContext(), true)));
        handshake.addRegistry(registryPacket);
        remainingRegistries.countDown();
      } else if (message instanceof ConfigDataPacket) {
        server.getConnection().write(new ACKPacket(Context.fromContext(message.getContext(), true)));
      } else if (message instanceof GenericForgeLoginWrapperPacket<Context> packet
              && ForgeHandshakeUtils.ThirdPartyRegistryUtils.isThirdPartyPacket(packet)) {
          server.getConnection().write(
                  ForgeHandshakeUtils.ThirdPartyRegistryUtils.getThirdPartyChannel(packet).
                          generateResponsePacket(
                                  Context.ClientContext.fromContext(packet.getContext(), true),
                                  clientPhase.forgeHandshake));
      }
    }
    //Forge server
    //To avoid unnecessary resets, we wait until we get the handshake even if we know that we should
    //reset because that the previous server was Forge.
  }

  public void onLoginSuccess(VelocityServerConnection serverCon, ConnectedPlayer player) {
  }

  void onTransitionToNewPhase(VelocityServerConnection connection) {
  }

  VelocityForgeBackendConnectionPhase nextPhase() {
    return this;
  }

  private VelocityForgeBackendConnectionPhase getNewPhase(VelocityServerConnection serverConnection,
                                                       IForgeLoginWrapperPacket<Context> packet) {
    VelocityForgeBackendConnectionPhase phaseToTransitionTo = nextPhase();
    if (phaseToTransitionTo != this) {
      phaseToTransitionTo.onTransitionToNewPhase(serverConnection);
    }
    return phaseToTransitionTo;
  }

  @Override
  public boolean handle(VelocityServerConnection server, ConnectedPlayer player, PluginMessagePacket message) {
    if (message.getChannel().equals("ambassador:commands")) {
      AvailableCommandsPacket packet = new AvailableCommandsPacket();
      packet.decode(message.content(), ProtocolUtils.Direction.CLIENTBOUND,server.getConnection().getProtocolVersion());
      server.getConnection().getActiveSessionHandler().handle(packet);
      return true;
    }
    return false;
  }

  public boolean consideredComplete() {
    return false;
  }



}
