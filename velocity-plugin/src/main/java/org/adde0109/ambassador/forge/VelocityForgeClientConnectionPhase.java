package org.adde0109.ambassador.forge;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.ModInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ClientConnectionPhase;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionMessages;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.LoginPluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import org.adde0109.ambassador.Ambassador;
import org.adde0109.ambassador.forge.packet.Context;
import org.adde0109.ambassador.forge.packet.GenericForgeLoginWrapperPacket;
import org.adde0109.ambassador.forge.packet.IForgeLoginWrapperPacket;
import org.adde0109.ambassador.forge.packet.ModListReplyPacket;
import org.adde0109.ambassador.velocity.client.FML2CRPMResetCompleteDecoder;
import org.adde0109.ambassador.velocity.client.OutboundSuccessHolder;
import org.adde0109.ambassador.velocity.client.ClientPacketQueue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public enum VelocityForgeClientConnectionPhase implements ClientConnectionPhase {

  NOT_STARTED {
    @Override
    VelocityForgeClientConnectionPhase nextPhase() {
      return IN_PROGRESS;
    }

    @Override
    public void complete(ConnectedPlayer player) {
      //When no handshake has taken place.
      //Test if the client supports CRP.
      ClientResetType.CRP.doReset(player);
    }
    },
  IN_PROGRESS {
  },
  WAITING_RESET() {
    @Override
    void onTransitionToNewPhase(ConnectedPlayer player) {
      //We unregister so no plugin sees this client while the client is being reset.
      ((VelocityServer) Ambassador.getInstance().server).unregisterConnection(player);
      player.getConnection().getChannel().pipeline().addAfter(Connections.MINECRAFT_ENCODER,
              ForgeConstants.LOGIN_PACKET_QUEUE, new ClientPacketQueue(StateRegistry.PLAY));
      if (player.getConnection().getChannel().pipeline().get(ForgeConstants.PLUGIN_PACKET_QUEUE) == null)
        player.getConnection().getChannel().pipeline().addAfter(Connections.MINECRAFT_ENCODER,
                ForgeConstants.PLUGIN_PACKET_QUEUE, new ClientPacketQueue(StateRegistry.LOGIN));
    }

    @Override
    public boolean handle(ConnectedPlayer player, IForgeLoginWrapperPacket msg, VelocityServerConnection server) {
      if (msg.getContext().getResponseID() == 98) {
        //Reset complete
        player.getConnection().getChannel().pipeline().remove(ForgeConstants.RESET_LISTENER);
        player.setPhase(NOT_STARTED);

        player.getConnection().getChannel().pipeline().remove(ForgeConstants.LOGIN_PACKET_QUEUE);

        if (!(server.getConnection().getType() instanceof ForgeFMLConnectionType)) {
          // -> vanilla
          complete(player, ((Context.ClientContext) msg.getContext()).success() ? ClientResetType.CRP : null);
        }

        if (player.getConnectionInFlight() != null) {
          player.getConnectionInFlight().getConnection().getChannel().config().setAutoRead(true);
        }

        return true;
      } else {
        return false;
      }
    }
  },
  COMPLETE {

    private ClientResetType resetType = ClientResetType.UNKNOWN;
    @Override
    void onTransitionToNewPhase(ConnectedPlayer player) {
      //Send Login Success to client
      MinecraftConnection connection = player.getConnection();
      ((OutboundSuccessHolder) connection.getChannel().pipeline().get(ForgeConstants.SERVER_SUCCESS_LISTENER))
              .sendPacket();
      connection.setState(StateRegistry.PLAY);
      //Plugins may now send packets to client
      player.getConnection().getChannel().pipeline().remove(ForgeConstants.PLUGIN_PACKET_QUEUE);
      ((VelocityServer) Ambassador.getInstance().server).registerConnection(player);
    }

    @Override
    public void resetConnectionPhase(ConnectedPlayer player) {
      getResetType().doReset(player);
    }

    @Override
    public boolean consideredComplete() {
      return true;
    }

    @Override
    public void complete(ConnectedPlayer player) {
      if (Ambassador.getInstance().config.isDebugMode()) {
        player.sendMessage(Component.text("Not resetting"));
      }
    }

    @Override
    void setResetType(ConnectedPlayer player, ClientResetType resetType) {
      // Sticky CRP: once a player has been classified CRP-capable, refuse to
      // downgrade to NONE/UNKNOWN. Without this, a later modInfo overwrite
      // (e.g. PlayerChannelRegisterEvent with a short channel list) would
      // cause getResetType() to return NONE on the next switch, sending the
      // player through kick-reset and breaking the soft-clearLevel path.
      if (this.resetType == ClientResetType.CRP && resetType != ClientResetType.CRP) {
        Ambassador.getInstance().logger.info(
            "[crp-detect] player={} refusing downgrade CRP→{}",
            player.getUsername(), resetType);
        return;
      }
      this.resetType = resetType;
      if (Ambassador.getInstance().config.isDebugMode()) {
        player.sendMessage(Component.text("Reset type: " + this.resetType.toString()));
      }
    }

    @Override
    public ClientResetType getResetType() {
      return resetType;
    }
  };

  //TODO: Make a new class that's linked to each player with these fields instead of having them in this phase class
  public ForgeHandshake forgeHandshake = new ForgeHandshake();

  public boolean handle(ConnectedPlayer player, IForgeLoginWrapperPacket<Context.ClientContext> msg, VelocityServerConnection server) {

    if (server == null) {
      // Client-side handshake packet arrived after the in-flight backend
      // connection was already cleared (e.g. the backend killed the
      // connection mid-handshake - PlayerSync kick, crash, etc). Ambassador's
      // CRP switch doesn't go through Velocity's standard connect() future
      // chain, so nothing else will notify the client of the failure - if we
      // just drop the packet the client is left frozen forever. Disconnect
      // it with the same message Velocity itself uses for this failure mode
      // so the client can reconnect instead of hanging.
      Ambassador.getInstance().logger.warn(
          "[crp] {} sent {} with no connection in flight, disconnecting",
          player.getUsername(), msg.getClass().getSimpleName());
      player.disconnect(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR);
      return true;
    }

    if (msg.getContext().getChannelName().equals("zeta:main")) {
      forgeHandshake.zetaFlagsPacket = (GenericForgeLoginWrapperPacket<Context.ClientContext>) msg;
    }

    if (msg instanceof ModListReplyPacket replyPacket) {
      ModInfo modInfo = new ModInfo("FML2", replyPacket.getMods().stream().map(
              (v) -> new ModInfo.Mod(v,"1")).toList());
      player.setModInfo(modInfo);
      forgeHandshake.setModListReplyPacket(replyPacket);
      if (!(server.getConnection().getType() instanceof ForgeFMLConnectionType)) {
        complete(player);
        player.getConnectionInFlight().getConnection().getChannel().config().setAutoRead(true);
        return true;
      }
      replyPacket.getChannels().put(MinecraftChannelIdentifier.from("ambassador:commands"),"1");
    }

    player.getConnectionInFlight().getConnection().write(msg);

    player.setPhase(nextPhase());
    nextPhase().forgeHandshake = this.forgeHandshake;

    return true;
  }
  public void complete(ConnectedPlayer player) {
    complete(player, null);
  }

  public void complete(ConnectedPlayer player, ClientResetType resetType) {
    //Change phase to COMPLETE
    player.setPhase(COMPLETE);
    COMPLETE.onTransitionToNewPhase(player);
    COMPLETE.forgeHandshake = forgeHandshake;
    if (resetType != null) {
      COMPLETE.setResetType(player, resetType);
    }

    if (Ambassador.getInstance().config.isDebugMode()) {
      player.sendMessage(Component.text("Forge handshake complete"));
    }
  }

  void onTransitionToNewPhase(ConnectedPlayer player) {

  }

  VelocityForgeClientConnectionPhase nextPhase() {
    return this;
  }

  @Override
  public boolean consideredComplete() {
    return false;
  }

  public ClientResetType getResetType() {
    return COMPLETE.getResetType();
  }

  private ClientResetType getResetType(ConnectedPlayer player) {
    if (Ambassador.getInstance().config.isDebugMode()) {
      player.sendMessage(Component.text("Scanning modlist for client reset mods"));
    }
    if (player.getModInfo().isPresent()) {
      // Log every channel/mod ID at INFO level so we can see what the
      // proxy actually receives for a given player. Helps diagnose
      // "Phase 1 not activating" issues.
      String allMods = player.getModInfo().get().getMods().stream()
              .map(m -> m.getId()).reduce((a, b) -> a + "," + b).orElse("(none)");
      Ambassador.getInstance().logger.info("[crp-detect] player={} channels/mods=[{}]",
              player.getUsername(), allMods);
      // Match exactly "clientresetpacket" (mod ID style, 1.4.x) OR any
      // channel ID whose namespace is "clientresetpacket" (1.5.x style,
      // e.g. "clientresetpacket:main").
      if (player.getModInfo().get().getMods().stream().anyMatch(
              (mod -> mod.getId().equals("clientresetpacket")
                   || mod.getId().startsWith("clientresetpacket:")))) {
        Ambassador.getInstance().logger.info("[crp-detect] player={} → CRP", player.getUsername());
        return ClientResetType.CRP;
      } else if (Ambassador.getInstance().config.getServerSwitchCancellationTime() >= 0 &&
              player.getModInfo().get().getMods().stream().anyMatch((mod -> mod.getId().equals("serverredirect")
                      || mod.getId().equals("srvredirect:red")))
              && player.getVirtualHost().isPresent()) {
        return ClientResetType.SR;
      }
    } else {
      Ambassador.getInstance().logger.info("[crp-detect] player={} modInfo absent", player.getUsername());
    }
    return ClientResetType.NONE;
  }

  void setResetType(ConnectedPlayer player, ClientResetType resetType) {
    COMPLETE.setResetType(player, resetType);
  }
  public void updateResetType(ConnectedPlayer player) {
    COMPLETE.setResetType(player, getResetType(player));
  }

  public enum ClientResetType {
    UNKNOWN,
    NONE,
    CRP {
      @Override
      void doReset(ConnectedPlayer player) {
        MinecraftConnection connection = player.getConnection();

        //There is no going back even if the handshake fails. No reason to still be connected.
        if (player.getConnectedServer() != null) {
          player.getConnectedServer().disconnect();
          player.setConnectedServer(null);
        }
        //Don't handle anything from the server until the reset has completed.
        if (player.getConnectionInFlight() != null) {
          player.getConnectionInFlight().getConnection().getChannel().config().setAutoRead(false);
        }

        if (connection.getState() == StateRegistry.PLAY || connection.getState() == StateRegistry.CONFIG) {
          connection.write(new PluginMessagePacket("fml:handshake", Unpooled.wrappedBuffer(ForgeHandshakeUtils.generatePluginResetPacket())));
          connection.setState(StateRegistry.LOGIN);
        } else {
          connection.write(new LoginPluginMessagePacket(98,"fml:loginwrapper", Unpooled.wrappedBuffer(ForgeHandshakeUtils.generateResetPacket())));
        }

        //Prepare to receive reset ACK
        connection.getChannel().pipeline().addBefore(Connections.MINECRAFT_DECODER,
                ForgeConstants.RESET_LISTENER, new FML2CRPMResetCompleteDecoder());

        //Transition
        player.setPhase(WAITING_RESET);
        WAITING_RESET.onTransitionToNewPhase(player);
      }
    },
    SR {
      @Override
      void doReset(ConnectedPlayer player) {
        ByteBuf buf = Unpooled.buffer();
        ProtocolUtils.writeVarInt(buf, 0);
        buf.writeBytes((player.getVirtualHost().get().getHostName() + ":"
                + player.getVirtualHost().get().getPort()).getBytes(StandardCharsets.UTF_8));
        player.getConnection().write(new PluginMessagePacket("srvredirect:red", buf));

        Ambassador.getInstance().reconnectSwitchPlayer(player);
      }
    };

    void doReset(ConnectedPlayer player) {
    }
  }
}
