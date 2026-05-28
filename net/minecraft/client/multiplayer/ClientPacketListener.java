package net.minecraft.client.multiplayer;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.advancements.Advancement;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.DebugQueryHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.MapRenderer;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DemoIntroScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.gui.screens.achievement.StatsUpdateListener;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.HorseInventoryScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.particle.ItemPickupParticle;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.debug.BeeDebugRenderer;
import net.minecraft.client.renderer.debug.BrainDebugRenderer;
import net.minecraft.client.renderer.debug.GoalSelectorDebugRenderer;
import net.minecraft.client.renderer.debug.NeighborsUpdateRenderer;
import net.minecraft.client.renderer.debug.WorldGenAttemptRenderer;
import net.minecraft.client.resources.sounds.BeeAggressiveSoundInstance;
import net.minecraft.client.resources.sounds.BeeFlyingSoundInstance;
import net.minecraft.client.resources.sounds.BeeSoundInstance;
import net.minecraft.client.resources.sounds.GuardianAttackSoundInstance;
import net.minecraft.client.resources.sounds.MinecartSoundInstance;
import net.minecraft.client.resources.sounds.SnifferSoundInstance;
import net.minecraft.client.searchtree.SearchRegistry;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Position;
import net.minecraft.core.PositionImpl;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessagesTracker;
import net.minecraft.network.chat.LocalChatSession;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MessageSignatureCache;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.network.chat.SignedMessageLink;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundDeleteChatPacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundHorseScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEndPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEnterPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundResourcePackPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSelectAdvancementsTabPacket;
import net.minecraft.network.protocol.game.ClientboundServerDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.realms.DisconnectedRealmsScreen;
import net.minecraft.realms.RealmsScreen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.util.Crypt;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.SignatureValidator;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class ClientPacketListener implements TickablePacketListener, ClientGamePacketListener {
   private static final Logger f_104883_ = LogUtils.getLogger();
   private static final Component f_104884_ = Component.m_237115_("disconnect.lost");
   private static final Component f_242953_ = Component.m_237115_("multiplayer.unsecureserver.toast.title");
   private static final Component f_242949_ = Component.m_237115_("multiplayer.unsecureserver.toast");
   private static final Component f_244321_ = Component.m_237115_("multiplayer.disconnect.invalid_packet");
   private static final Component f_243809_ = Component.m_237115_("multiplayer.disconnect.chat_validation_failed");
   private static final int f_243949_ = 64;
   private final Connection f_104885_;
   private final List<ClientPacketListener.DeferredPacket> f_268637_ = new ArrayList<>();
   @Nullable
   private final ServerData f_244115_;
   private final GameProfile f_104886_;
   private final Screen f_104887_;
   private final Minecraft f_104888_;
   private ClientLevel f_104889_;
   private ClientLevel.ClientLevelData f_104890_;
   private final Map<UUID, PlayerInfo> f_104892_ = Maps.newHashMap();
   private final Set<PlayerInfo> f_244156_ = new ReferenceOpenHashSet<>();
   private final ClientAdvancements f_104893_;
   private final ClientSuggestionProvider f_104894_;
   private final DebugQueryHandler f_104896_ = new DebugQueryHandler(this);
   private int f_104897_ = 3;
   private int f_194190_ = 3;
   private final RandomSource f_104898_ = RandomSource.m_216337_();
   public CommandDispatcher<SharedSuggestionProvider> f_104899_ = new CommandDispatcher<>();
   private final RecipeManager f_104900_ = new RecipeManager();
   private final UUID f_104901_ = UUID.randomUUID();
   private Set<ResourceKey<Level>> f_104902_;
   private LayeredRegistryAccess<ClientRegistryLayer> f_104903_ = ClientRegistryLayer.m_245874_();
   private FeatureFlagSet f_244039_ = FeatureFlags.f_244332_;
   private final WorldSessionTelemetryManager f_194191_;
   @Nullable
   private LocalChatSession f_252517_;
   private SignedMessageChain.Encoder f_240902_ = SignedMessageChain.Encoder.f_243849_;
   private LastSeenMessagesTracker f_244346_ = new LastSeenMessagesTracker(20);
   private MessageSignatureCache f_244113_ = MessageSignatureCache.m_246587_();

   public ClientPacketListener(Minecraft p_253924_, Screen p_254239_, Connection p_253614_, @Nullable ServerData p_254072_, GameProfile p_254079_, WorldSessionTelemetryManager p_262115_) {
      this.f_104888_ = p_253924_;
      this.f_104887_ = p_254239_;
      this.f_104885_ = p_253614_;
      this.f_244115_ = p_254072_;
      this.f_104886_ = p_254079_;
      this.f_104893_ = new ClientAdvancements(p_253924_, p_262115_);
      this.f_104894_ = new ClientSuggestionProvider(this, p_253924_);
      this.f_194191_ = p_262115_;
   }

   public ClientSuggestionProvider m_105137_() {
      return this.f_104894_;
   }

   public void m_261044_() {
      this.f_104889_ = null;
      this.f_194191_.m_261027_();
   }

   public RecipeManager m_105141_() {
      return this.f_104900_;
   }

   public void m_5998_(ClientboundLoginPacket p_105030_) {
      PacketUtils.m_131363_(p_105030_, this, this.f_104888_);
      this.f_104888_.f_91072_ = new MultiPlayerGameMode(this.f_104888_, this);
      this.f_104903_ = this.f_104903_.m_247705_(ClientRegistryLayer.REMOTE, p_105030_.f_132366_());
      if (!this.f_104885_.m_129531_()) {
         this.f_104903_.m_247579_().m_206193_().forEach((p_205542_) -> {
            p_205542_.f_206234_().m_203635_();
         });
      }

      List<ResourceKey<Level>> list = Lists.newArrayList(p_105030_.f_132365_());
      Collections.shuffle(list);
      this.f_104902_ = Sets.newLinkedHashSet(list);
      ResourceKey<Level> resourcekey = p_105030_.f_132368_();
      Holder<DimensionType> holder = this.f_104903_.m_247579_().m_175515_(Registries.f_256787_).m_246971_(p_105030_.f_132367_());
      this.f_104897_ = p_105030_.f_132370_();
      this.f_194190_ = p_105030_.f_195761_();
      boolean flag = p_105030_.f_132373_();
      boolean flag1 = p_105030_.f_132374_();
      ClientLevel.ClientLevelData clientlevel$clientleveldata = new ClientLevel.ClientLevelData(Difficulty.NORMAL, p_105030_.f_132362_(), flag1);
      this.f_104890_ = clientlevel$clientleveldata;
      this.f_104889_ = new ClientLevel(this, clientlevel$clientleveldata, resourcekey, holder, this.f_104897_, this.f_194190_, this.f_104888_::m_91307_, this.f_104888_.f_91060_, flag, p_105030_.f_132361_());
      this.f_104888_.m_91156_(this.f_104889_);
      if (this.f_104888_.f_91074_ == null) {
         this.f_104888_.f_91074_ = this.f_104888_.f_91072_.m_105246_(this.f_104889_, new StatsCounter(), new ClientRecipeBook());
         this.f_104888_.f_91074_.m_146922_(-180.0F);
         if (this.f_104888_.m_91092_() != null) {
            this.f_104888_.m_91092_().m_120046_(this.f_104888_.f_91074_.m_20148_());
         }
      }

      this.f_104888_.f_91064_.m_113434_();
      this.f_104888_.f_91074_.m_172530_();
      int i = p_105030_.f_132360_();
      this.f_104888_.f_91074_.m_20234_(i);
      this.f_104889_.m_104630_(i, this.f_104888_.f_91074_);
      this.f_104888_.f_91074_.f_108618_ = new KeyboardInput(this.f_104888_.f_91066_);
      this.f_104888_.f_91072_.m_105221_(this.f_104888_.f_91074_);
      this.f_104888_.f_91075_ = this.f_104888_.f_91074_;
      this.f_104888_.m_91152_(new ReceivingLevelScreen());
      this.f_104888_.f_91074_.m_36393_(p_105030_.f_132371_());
      this.f_104888_.f_91074_.m_108711_(p_105030_.f_132372_());
      this.f_104888_.f_91074_.m_219749_(p_105030_.f_238174_());
      this.f_104888_.f_91074_.m_287199_(p_105030_.f_286971_());
      this.f_104888_.f_91072_.m_171805_(p_105030_.f_132363_(), p_105030_.f_132364_());
      this.f_104888_.f_91066_.m_193770_(p_105030_.f_132370_());
      this.f_104888_.f_91066_.m_92172_();
      this.f_104885_.m_129512_(new ServerboundCustomPayloadPacket(ServerboundCustomPayloadPacket.f_133979_, (new FriendlyByteBuf(Unpooled.buffer())).m_130070_(ClientBrandRetriever.getClientModName())));
      this.f_252517_ = null;
      this.f_244346_ = new LastSeenMessagesTracker(20);
      this.f_244113_ = MessageSignatureCache.m_246587_();
      if (this.f_104885_.m_129535_()) {
         this.f_104888_.m_231465_().m_252904_().thenAcceptAsync((p_253341_) -> {
            p_253341_.ifPresent(this::m_260951_);
         }, this.f_104888_);
      }

      this.f_194191_.m_260888_(p_105030_.f_132363_(), p_105030_.f_132362_());
      this.f_104888_.m_278644_().m_278768_(this.f_104888_);
   }

   public void m_6771_(ClientboundAddEntityPacket p_104958_) {
      PacketUtils.m_131363_(p_104958_, this, this.f_104888_);
      EntityType<?> entitytype = p_104958_.m_131508_();
      Entity entity = entitytype.m_20615_(this.f_104889_);
      if (entity != null) {
         entity.m_141965_(p_104958_);
         int i = p_104958_.m_131496_();
         this.f_104889_.m_104627_(i, entity);
         this.m_233663_(entity);
      } else {
         f_104883_.warn("Skipping Entity with id {}", (Object)entitytype);
      }

   }

   private void m_233663_(Entity p_233664_) {
      if (p_233664_ instanceof AbstractMinecart) {
         this.f_104888_.m_91106_().m_120367_(new MinecartSoundInstance((AbstractMinecart)p_233664_));
      } else if (p_233664_ instanceof Bee) {
         boolean flag = ((Bee)p_233664_).m_21660_();
         BeeSoundInstance beesoundinstance;
         if (flag) {
            beesoundinstance = new BeeAggressiveSoundInstance((Bee)p_233664_);
         } else {
            beesoundinstance = new BeeFlyingSoundInstance((Bee)p_233664_);
         }

         this.f_104888_.m_91106_().m_120372_(beesoundinstance);
      }

   }

   public void m_7708_(ClientboundAddExperienceOrbPacket p_104960_) {
      PacketUtils.m_131363_(p_104960_, this, this.f_104888_);
      double d0 = p_104960_.m_131527_();
      double d1 = p_104960_.m_131528_();
      double d2 = p_104960_.m_131529_();
      Entity entity = new ExperienceOrb(this.f_104889_, d0, d1, d2, p_104960_.m_131530_());
      entity.m_217006_(d0, d1, d2);
      entity.m_146922_(0.0F);
      entity.m_146926_(0.0F);
      entity.m_20234_(p_104960_.m_131524_());
      this.f_104889_.m_104627_(p_104960_.m_131524_(), entity);
   }

   public void m_8048_(ClientboundSetEntityMotionPacket p_105092_) {
      PacketUtils.m_131363_(p_105092_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105092_.m_133192_());
      if (entity != null) {
         entity.m_6001_((double)p_105092_.m_133195_() / 8000.0D, (double)p_105092_.m_133196_() / 8000.0D, (double)p_105092_.m_133197_() / 8000.0D);
      }
   }

   public void m_6455_(ClientboundSetEntityDataPacket p_105088_) {
      PacketUtils.m_131363_(p_105088_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105088_.f_133143_());
      if (entity != null) {
         entity.m_20088_().m_135356_(p_105088_.f_133144_());
      }

   }

   public void m_6482_(ClientboundAddPlayerPacket p_104966_) {
      PacketUtils.m_131363_(p_104966_, this, this.f_104888_);
      PlayerInfo playerinfo = this.m_104949_(p_104966_.m_131606_());
      if (playerinfo == null) {
         f_104883_.warn("Server attempted to add player prior to sending player info (Player id {})", (Object)p_104966_.m_131606_());
      } else {
         double d0 = p_104966_.m_131607_();
         double d1 = p_104966_.m_131608_();
         double d2 = p_104966_.m_131609_();
         float f = (float)(p_104966_.m_131610_() * 360) / 256.0F;
         float f1 = (float)(p_104966_.m_131611_() * 360) / 256.0F;
         int i = p_104966_.m_131603_();
         RemotePlayer remoteplayer = new RemotePlayer(this.f_104888_.f_91073_, playerinfo.m_105312_());
         remoteplayer.m_20234_(i);
         remoteplayer.m_217006_(d0, d1, d2);
         remoteplayer.m_19890_(d0, d1, d2, f, f1);
         remoteplayer.m_146867_();
         this.f_104889_.m_104630_(i, remoteplayer);
      }
   }

   public void m_6435_(ClientboundTeleportEntityPacket p_105124_) {
      PacketUtils.m_131363_(p_105124_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105124_.m_133545_());
      if (entity != null) {
         double d0 = p_105124_.m_133548_();
         double d1 = p_105124_.m_133549_();
         double d2 = p_105124_.m_133550_();
         entity.m_217006_(d0, d1, d2);
         if (!entity.m_6109_()) {
            float f = (float)(p_105124_.m_133551_() * 360) / 256.0F;
            float f1 = (float)(p_105124_.m_133552_() * 360) / 256.0F;
            entity.m_6453_(d0, d1, d2, f, f1, 3, true);
            entity.m_6853_(p_105124_.m_133553_());
         }

      }
   }

   public void m_5612_(ClientboundSetCarriedItemPacket p_105078_) {
      PacketUtils.m_131363_(p_105078_, this, this.f_104888_);
      if (Inventory.m_36045_(p_105078_.m_133079_())) {
         this.f_104888_.f_91074_.m_150109_().f_35977_ = p_105078_.m_133079_();
      }

   }

   public void m_7865_(ClientboundMoveEntityPacket p_105036_) {
      PacketUtils.m_131363_(p_105036_, this, this.f_104888_);
      Entity entity = p_105036_.m_132519_(this.f_104889_);
      if (entity != null) {
         if (!entity.m_6109_()) {
            if (p_105036_.m_132534_()) {
               VecDeltaCodec vecdeltacodec = entity.m_217001_();
               Vec3 vec3 = vecdeltacodec.m_238021_((long)p_105036_.m_178997_(), (long)p_105036_.m_178998_(), (long)p_105036_.m_178999_());
               vecdeltacodec.m_238033_(vec3);
               float f = p_105036_.m_132533_() ? (float)(p_105036_.m_132531_() * 360) / 256.0F : entity.m_146908_();
               float f1 = p_105036_.m_132533_() ? (float)(p_105036_.m_132532_() * 360) / 256.0F : entity.m_146909_();
               entity.m_6453_(vec3.m_7096_(), vec3.m_7098_(), vec3.m_7094_(), f, f1, 3, false);
            } else if (p_105036_.m_132533_()) {
               float f2 = (float)(p_105036_.m_132531_() * 360) / 256.0F;
               float f3 = (float)(p_105036_.m_132532_() * 360) / 256.0F;
               entity.m_6453_(entity.m_20185_(), entity.m_20186_(), entity.m_20189_(), f2, f3, 3, false);
            }

            entity.m_6853_(p_105036_.m_132535_());
         }

      }
   }

   public void m_6176_(ClientboundRotateHeadPacket p_105068_) {
      PacketUtils.m_131363_(p_105068_, this, this.f_104888_);
      Entity entity = p_105068_.m_132969_(this.f_104889_);
      if (entity != null) {
         float f = (float)(p_105068_.m_132977_() * 360) / 256.0F;
         entity.m_6541_(f, 3);
      }
   }

   public void m_182047_(ClientboundRemoveEntitiesPacket p_182633_) {
      PacketUtils.m_131363_(p_182633_, this, this.f_104888_);
      p_182633_.m_182730_().forEach((p_205521_) -> {
         this.f_104889_.m_171642_(p_205521_, Entity.RemovalReason.DISCARDED);
      });
   }

   public void m_5682_(ClientboundPlayerPositionPacket p_105056_) {
      PacketUtils.m_131363_(p_105056_, this, this.f_104888_);
      Player player = this.f_104888_.f_91074_;
      Vec3 vec3 = player.m_20184_();
      boolean flag = p_105056_.m_132826_().contains(RelativeMovement.X);
      boolean flag1 = p_105056_.m_132826_().contains(RelativeMovement.Y);
      boolean flag2 = p_105056_.m_132826_().contains(RelativeMovement.Z);
      double d0;
      double d1;
      if (flag) {
         d0 = vec3.m_7096_();
         d1 = player.m_20185_() + p_105056_.m_132818_();
         player.f_19790_ += p_105056_.m_132818_();
         player.f_19854_ += p_105056_.m_132818_();
      } else {
         d0 = 0.0D;
         d1 = p_105056_.m_132818_();
         player.f_19790_ = d1;
         player.f_19854_ = d1;
      }

      double d2;
      double d3;
      if (flag1) {
         d2 = vec3.m_7098_();
         d3 = player.m_20186_() + p_105056_.m_132821_();
         player.f_19791_ += p_105056_.m_132821_();
         player.f_19855_ += p_105056_.m_132821_();
      } else {
         d2 = 0.0D;
         d3 = p_105056_.m_132821_();
         player.f_19791_ = d3;
         player.f_19855_ = d3;
      }

      double d4;
      double d5;
      if (flag2) {
         d4 = vec3.m_7094_();
         d5 = player.m_20189_() + p_105056_.m_132822_();
         player.f_19792_ += p_105056_.m_132822_();
         player.f_19856_ += p_105056_.m_132822_();
      } else {
         d4 = 0.0D;
         d5 = p_105056_.m_132822_();
         player.f_19792_ = d5;
         player.f_19856_ = d5;
      }

      player.m_6034_(d1, d3, d5);
      player.m_20334_(d0, d2, d4);
      float f = p_105056_.m_132823_();
      float f1 = p_105056_.m_132824_();
      if (p_105056_.m_132826_().contains(RelativeMovement.X_ROT)) {
         player.m_146926_(player.m_146909_() + f1);
         player.f_19860_ += f1;
      } else {
         player.m_146926_(f1);
         player.f_19860_ = f1;
      }

      if (p_105056_.m_132826_().contains(RelativeMovement.Y_ROT)) {
         player.m_146922_(player.m_146908_() + f);
         player.f_19859_ += f;
      } else {
         player.m_146922_(f);
         player.f_19859_ = f;
      }

      this.f_104885_.m_129512_(new ServerboundAcceptTeleportationPacket(p_105056_.m_132825_()));
      this.f_104885_.m_129512_(new ServerboundMovePlayerPacket.PosRot(player.m_20185_(), player.m_20186_(), player.m_20189_(), player.m_146908_(), player.m_146909_(), false));
   }

   public void m_5771_(ClientboundSectionBlocksUpdatePacket p_105070_) {
      PacketUtils.m_131363_(p_105070_, this, this.f_104888_);
      p_105070_.m_132992_((p_284633_, p_284634_) -> {
         this.f_104889_.m_233653_(p_284633_, p_284634_, 19);
      });
   }

   public void m_183388_(ClientboundLevelChunkWithLightPacket p_194241_) {
      PacketUtils.m_131363_(p_194241_, this, this.f_104888_);
      int i = p_194241_.m_195717_();
      int j = p_194241_.m_195718_();
      this.m_194198_(i, j, p_194241_.m_195719_());
      ClientboundLightUpdatePacketData clientboundlightupdatepacketdata = p_194241_.m_195720_();
      this.f_104889_.m_194171_(() -> {
         this.m_194248_(i, j, clientboundlightupdatepacketdata);
         LevelChunk levelchunk = this.f_104889_.m_7726_().m_62227_(i, j, false);
         if (levelchunk != null) {
            this.m_194212_(levelchunk, i, j);
         }

      });
   }

   public void m_274374_(ClientboundChunksBiomesPacket p_275437_) {
      PacketUtils.m_131363_(p_275437_, this, this.f_104888_);

      for(ClientboundChunksBiomesPacket.ChunkBiomeData clientboundchunksbiomespacket$chunkbiomedata : p_275437_.f_273816_()) {
         this.f_104889_.m_7726_().m_274444_(clientboundchunksbiomespacket$chunkbiomedata.f_273927_().f_45578_, clientboundchunksbiomespacket$chunkbiomedata.f_273927_().f_45579_, clientboundchunksbiomespacket$chunkbiomedata.m_274543_());
      }

      for(ClientboundChunksBiomesPacket.ChunkBiomeData clientboundchunksbiomespacket$chunkbiomedata1 : p_275437_.f_273816_()) {
         this.f_104889_.m_171649_(new ChunkPos(clientboundchunksbiomespacket$chunkbiomedata1.f_273927_().f_45578_, clientboundchunksbiomespacket$chunkbiomedata1.f_273927_().f_45579_));
      }

      for(ClientboundChunksBiomesPacket.ChunkBiomeData clientboundchunksbiomespacket$chunkbiomedata2 : p_275437_.f_273816_()) {
         for(int i = -1; i <= 1; ++i) {
            for(int j = -1; j <= 1; ++j) {
               for(int k = this.f_104889_.m_151560_(); k < this.f_104889_.m_151561_(); ++k) {
                  this.f_104888_.f_91060_.m_109770_(clientboundchunksbiomespacket$chunkbiomedata2.f_273927_().f_45578_ + i, k, clientboundchunksbiomespacket$chunkbiomedata2.f_273927_().f_45579_ + j);
               }
            }
         }
      }

   }

   private void m_194198_(int p_194199_, int p_194200_, ClientboundLevelChunkPacketData p_194201_) {
      this.f_104889_.m_7726_().m_194116_(p_194199_, p_194200_, p_194201_.m_195656_(), p_194201_.m_195678_(), p_194201_.m_195657_(p_194199_, p_194200_));
   }

   private void m_194212_(LevelChunk p_194213_, int p_194214_, int p_194215_) {
      LevelLightEngine levellightengine = this.f_104889_.m_7726_().m_7827_();
      LevelChunkSection[] alevelchunksection = p_194213_.m_7103_();
      ChunkPos chunkpos = p_194213_.m_7697_();

      for(int i = 0; i < alevelchunksection.length; ++i) {
         LevelChunkSection levelchunksection = alevelchunksection[i];
         int j = this.f_104889_.m_151568_(i);
         levellightengine.m_6191_(SectionPos.m_123196_(chunkpos, j), levelchunksection.m_188008_());
         this.f_104889_.m_104793_(p_194214_, j, p_194215_);
      }

   }

   public void m_5729_(ClientboundForgetLevelChunkPacket p_105014_) {
      PacketUtils.m_131363_(p_105014_, this, this.f_104888_);
      int i = p_105014_.m_132149_();
      int j = p_105014_.m_132152_();
      ClientChunkCache clientchunkcache = this.f_104889_.m_7726_();
      clientchunkcache.m_104455_(i, j);
      this.m_194252_(p_105014_);
   }

   private void m_194252_(ClientboundForgetLevelChunkPacket p_194253_) {
      ChunkPos chunkpos = new ChunkPos(p_194253_.m_132149_(), p_194253_.m_132152_());
      this.f_104889_.m_194171_(() -> {
         LevelLightEngine levellightengine = this.f_104889_.m_5518_();
         levellightengine.m_9335_(chunkpos, false);

         for(int i = levellightengine.m_164447_(); i < levellightengine.m_164448_(); ++i) {
            SectionPos sectionpos = SectionPos.m_123196_(chunkpos, i);
            levellightengine.m_284126_(LightLayer.BLOCK, sectionpos, (DataLayer)null);
            levellightengine.m_284126_(LightLayer.SKY, sectionpos, (DataLayer)null);
         }

         for(int j = this.f_104889_.m_151560_(); j < this.f_104889_.m_151561_(); ++j) {
            levellightengine.m_6191_(SectionPos.m_123196_(chunkpos, j), true);
         }

      });
   }

   public void m_6773_(ClientboundBlockUpdatePacket p_104980_) {
      PacketUtils.m_131363_(p_104980_, this, this.f_104888_);
      this.f_104889_.m_233653_(p_104980_.m_131749_(), p_104980_.m_131746_(), 19);
   }

   public void m_6008_(ClientboundDisconnectPacket p_105008_) {
      this.f_104885_.m_129507_(p_105008_.m_132085_());
   }

   public void m_7026_(Component p_104954_) {
      this.f_104888_.m_91399_();
      this.f_194191_.m_261027_();
      if (this.f_104887_ != null) {
         if (this.f_104887_ instanceof RealmsScreen) {
            this.f_104888_.m_91152_(new DisconnectedRealmsScreen(this.f_104887_, f_104884_, p_104954_));
         } else {
            this.f_104888_.m_91152_(new DisconnectedScreen(this.f_104887_, f_104884_, p_104954_));
         }
      } else {
         this.f_104888_.m_91152_(new DisconnectedScreen(new JoinMultiplayerScreen(new TitleScreen()), f_104884_, p_104954_));
      }

   }

   public void m_104955_(Packet<?> p_104956_) {
      this.f_104885_.m_129512_(p_104956_);
   }

   public void m_8001_(ClientboundTakeItemEntityPacket p_105122_) {
      PacketUtils.m_131363_(p_105122_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105122_.m_133524_());
      LivingEntity livingentity = (LivingEntity)this.f_104889_.m_6815_(p_105122_.m_133527_());
      if (livingentity == null) {
         livingentity = this.f_104888_.f_91074_;
      }

      if (entity != null) {
         if (entity instanceof ExperienceOrb) {
            this.f_104889_.m_7785_(entity.m_20185_(), entity.m_20186_(), entity.m_20189_(), SoundEvents.f_11871_, SoundSource.PLAYERS, 0.1F, (this.f_104898_.m_188501_() - this.f_104898_.m_188501_()) * 0.35F + 0.9F, false);
         } else {
            this.f_104889_.m_7785_(entity.m_20185_(), entity.m_20186_(), entity.m_20189_(), SoundEvents.f_12019_, SoundSource.PLAYERS, 0.2F, (this.f_104898_.m_188501_() - this.f_104898_.m_188501_()) * 1.4F + 2.0F, false);
         }

         this.f_104888_.f_91061_.m_107344_(new ItemPickupParticle(this.f_104888_.m_91290_(), this.f_104888_.m_91269_(), this.f_104889_, entity, livingentity));
         if (entity instanceof ItemEntity) {
            ItemEntity itementity = (ItemEntity)entity;
            ItemStack itemstack = itementity.m_32055_();
            if (!itemstack.m_41619_()) {
               itemstack.m_41774_(p_105122_.m_133528_());
            }

            if (itemstack.m_41619_()) {
               this.f_104889_.m_171642_(p_105122_.m_133524_(), Entity.RemovalReason.DISCARDED);
            }
         } else if (!(entity instanceof ExperienceOrb)) {
            this.f_104889_.m_171642_(p_105122_.m_133524_(), Entity.RemovalReason.DISCARDED);
         }
      }

   }

   public void m_213990_(ClientboundSystemChatPacket p_233708_) {
      PacketUtils.m_131363_(p_233708_, this, this.f_104888_);
      this.f_104888_.m_240442_().m_240494_(p_233708_.f_237849_(), p_233708_.f_240374_());
   }

   public void m_213629_(ClientboundPlayerChatPacket p_233702_) {
      PacketUtils.m_131363_(p_233702_, this, this.f_104888_);
      Optional<SignedMessageBody> optional = p_233702_.f_244090_().m_252762_(this.f_244113_);
      Optional<ChatType.Bound> optional1 = p_233702_.f_240897_().m_242652_(this.f_104903_.m_247579_());
      if (!optional.isEmpty() && !optional1.isEmpty()) {
         UUID uuid = p_233702_.f_243918_();
         PlayerInfo playerinfo = this.m_104949_(uuid);
         if (playerinfo == null) {
            this.f_104885_.m_129507_(f_243809_);
         } else {
            RemoteChatSession remotechatsession = playerinfo.m_247593_();
            SignedMessageLink signedmessagelink;
            if (remotechatsession != null) {
               signedmessagelink = new SignedMessageLink(p_233702_.f_244283_(), uuid, remotechatsession.f_244448_());
            } else {
               signedmessagelink = SignedMessageLink.m_245187_(uuid);
            }

            PlayerChatMessage playerchatmessage = new PlayerChatMessage(signedmessagelink, p_233702_.f_243836_(), optional.get(), p_233702_.f_243686_(), p_233702_.f_243744_());
            if (!playerinfo.m_241043_().m_241126_(playerchatmessage)) {
               this.f_104885_.m_129507_(f_243809_);
            } else {
               this.f_104888_.m_240442_().m_247425_(playerchatmessage, playerinfo.m_105312_(), optional1.get());
               this.f_244113_.m_247208_(playerchatmessage);
            }
         }
      } else {
         this.f_104885_.m_129507_(f_244321_);
      }
   }

   public void m_7039_(ClientboundDisguisedChatPacket p_251920_) {
      PacketUtils.m_131363_(p_251920_, this, this.f_104888_);
      Optional<ChatType.Bound> optional = p_251920_.f_244252_().m_242652_(this.f_104903_.m_247579_());
      if (optional.isEmpty()) {
         this.f_104885_.m_129507_(f_244321_);
      } else {
         this.f_104888_.m_240442_().m_245141_(p_251920_.f_244491_(), optional.get());
      }
   }

   public void m_241037_(ClientboundDeleteChatPacket p_241325_) {
      PacketUtils.m_131363_(p_241325_, this, this.f_104888_);
      Optional<MessageSignature> optional = p_241325_.f_240904_().m_253223_(this.f_244113_);
      if (optional.isEmpty()) {
         this.f_104885_.m_129507_(f_244321_);
      } else {
         this.f_244346_.m_246067_(optional.get());
         if (!this.f_104888_.m_240442_().m_240956_(optional.get())) {
            this.f_104888_.f_91065_.m_93076_().m_240953_(optional.get());
         }

      }
   }

   public void m_7791_(ClientboundAnimatePacket p_104968_) {
      PacketUtils.m_131363_(p_104968_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_104968_.m_131624_());
      if (entity != null) {
         if (p_104968_.m_131627_() == 0) {
            LivingEntity livingentity = (LivingEntity)entity;
            livingentity.m_6674_(InteractionHand.MAIN_HAND);
         } else if (p_104968_.m_131627_() == 3) {
            LivingEntity livingentity1 = (LivingEntity)entity;
            livingentity1.m_6674_(InteractionHand.OFF_HAND);
         } else if (p_104968_.m_131627_() == 2) {
            Player player = (Player)entity;
            player.m_6145_(false, false);
         } else if (p_104968_.m_131627_() == 4) {
            this.f_104888_.f_91061_.m_107329_(entity, ParticleTypes.f_123797_);
         } else if (p_104968_.m_131627_() == 5) {
            this.f_104888_.f_91061_.m_107329_(entity, ParticleTypes.f_123808_);
         }

      }
   }

   public void m_264143_(ClientboundHurtAnimationPacket p_265581_) {
      PacketUtils.m_131363_(p_265581_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_265581_.f_263825_());
      if (entity != null) {
         entity.m_6053_(p_265581_.f_263826_());
      }
   }

   public void m_7885_(ClientboundSetTimePacket p_105108_) {
      PacketUtils.m_131363_(p_105108_, this, this.f_104888_);
      this.f_104888_.f_91073_.m_104637_(p_105108_.m_133358_());
      this.f_104888_.f_91073_.m_104746_(p_105108_.m_133361_());
      this.f_194191_.m_261206_(p_105108_.m_133358_());
   }

   public void m_6571_(ClientboundSetDefaultSpawnPositionPacket p_105084_) {
      PacketUtils.m_131363_(p_105084_, this, this.f_104888_);
      this.f_104888_.f_91073_.m_104752_(p_105084_.m_133123_(), p_105084_.m_133126_());
      Screen screen = this.f_104888_.f_91080_;
      if (screen instanceof ReceivingLevelScreen receivinglevelscreen) {
         receivinglevelscreen.m_202375_();
      }

   }

   public void m_6403_(ClientboundSetPassengersPacket p_105102_) {
      PacketUtils.m_131363_(p_105102_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105102_.m_133286_());
      if (entity == null) {
         f_104883_.warn("Received passengers for unknown entity");
      } else {
         boolean flag = entity.m_20367_(this.f_104888_.f_91074_);
         entity.m_20153_();

         for(int i : p_105102_.m_133283_()) {
            Entity entity1 = this.f_104889_.m_6815_(i);
            if (entity1 != null) {
               entity1.m_7998_(entity, true);
               if (entity1 == this.f_104888_.f_91074_ && !flag) {
                  if (entity instanceof Boat) {
                     this.f_104888_.f_91074_.f_19859_ = entity.m_146908_();
                     this.f_104888_.f_91074_.m_146922_(entity.m_146908_());
                     this.f_104888_.f_91074_.m_5616_(entity.m_146908_());
                  }

                  Component component = Component.m_237110_("mount.onboard", this.f_104888_.f_91066_.f_92090_.m_90863_());
                  this.f_104888_.f_91065_.m_93063_(component, false);
                  this.f_104888_.m_240477_().m_168785_(component);
               }
            }
         }

      }
   }

   public void m_5599_(ClientboundSetEntityLinkPacket p_105090_) {
      PacketUtils.m_131363_(p_105090_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105090_.m_133172_());
      if (entity instanceof Mob) {
         ((Mob)entity).m_21506_(p_105090_.m_133175_());
      }

   }

   private static ItemStack m_104927_(Player p_104928_) {
      for(InteractionHand interactionhand : InteractionHand.values()) {
         ItemStack itemstack = p_104928_.m_21120_(interactionhand);
         if (itemstack.m_150930_(Items.f_42747_)) {
            return itemstack;
         }
      }

      return new ItemStack(Items.f_42747_);
   }

   public void m_7628_(ClientboundEntityEventPacket p_105010_) {
      PacketUtils.m_131363_(p_105010_, this, this.f_104888_);
      Entity entity = p_105010_.m_132094_(this.f_104889_);
      if (entity != null) {
         switch (p_105010_.m_132102_()) {
            case 21:
               this.f_104888_.m_91106_().m_120367_(new GuardianAttackSoundInstance((Guardian)entity));
               break;
            case 35:
               int i = 40;
               this.f_104888_.f_91061_.m_107332_(entity, ParticleTypes.f_123767_, 30);
               this.f_104889_.m_7785_(entity.m_20185_(), entity.m_20186_(), entity.m_20189_(), SoundEvents.f_12513_, entity.m_5720_(), 1.0F, 1.0F, false);
               if (entity == this.f_104888_.f_91074_) {
                  this.f_104888_.f_91063_.m_109113_(m_104927_(this.f_104888_.f_91074_));
               }
               break;
            case 63:
               this.f_104888_.m_91106_().m_120367_(new SnifferSoundInstance((Sniffer)entity));
               break;
            default:
               entity.m_7822_(p_105010_.m_132102_());
         }
      }

   }

   public void m_269082_(ClientboundDamageEventPacket p_270800_) {
      PacketUtils.m_131363_(p_270800_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_270800_.f_268504_());
      if (entity != null) {
         entity.m_269138_(p_270800_.m_269591_(this.f_104889_));
      }
   }

   public void m_5547_(ClientboundSetHealthPacket p_105098_) {
      PacketUtils.m_131363_(p_105098_, this, this.f_104888_);
      this.f_104888_.f_91074_.m_108760_(p_105098_.m_133247_());
      this.f_104888_.f_91074_.m_36324_().m_38705_(p_105098_.m_133250_());
      this.f_104888_.f_91074_.m_36324_().m_38717_(p_105098_.m_133251_());
   }

   public void m_6747_(ClientboundSetExperiencePacket p_105096_) {
      PacketUtils.m_131363_(p_105096_, this, this.f_104888_);
      this.f_104888_.f_91074_.m_108644_(p_105096_.m_133228_(), p_105096_.m_133231_(), p_105096_.m_133232_());
   }

   public void m_7992_(ClientboundRespawnPacket p_105066_) {
      PacketUtils.m_131363_(p_105066_, this, this.f_104888_);
      ResourceKey<Level> resourcekey = p_105066_.m_132955_();
      Holder<DimensionType> holder = this.f_104903_.m_247579_().m_175515_(Registries.f_256787_).m_246971_(p_105066_.m_237794_());
      LocalPlayer localplayer = this.f_104888_.f_91074_;
      int i = localplayer.m_19879_();
      if (resourcekey != localplayer.m_9236_().m_46472_()) {
         Scoreboard scoreboard = this.f_104889_.m_6188_();
         Map<String, MapItemSavedData> map = this.f_104889_.m_171684_();
         boolean flag = p_105066_.m_132959_();
         boolean flag1 = p_105066_.m_132960_();
         ClientLevel.ClientLevelData clientlevel$clientleveldata = new ClientLevel.ClientLevelData(this.f_104890_.m_5472_(), this.f_104890_.m_5466_(), flag1);
         this.f_104890_ = clientlevel$clientleveldata;
         this.f_104889_ = new ClientLevel(this, clientlevel$clientleveldata, resourcekey, holder, this.f_104897_, this.f_194190_, this.f_104888_::m_91307_, this.f_104888_.f_91060_, flag, p_105066_.m_132956_());
         this.f_104889_.m_104669_(scoreboard);
         this.f_104889_.m_171672_(map);
         this.f_104888_.m_91156_(this.f_104889_);
         this.f_104888_.m_91152_(new ReceivingLevelScreen());
      }

      String s = localplayer.m_108629_();
      this.f_104888_.f_91075_ = null;
      if (localplayer.m_242612_()) {
         localplayer.m_6915_();
      }

      LocalPlayer localplayer1;
      if (p_105066_.m_263558_((byte)2)) {
         localplayer1 = this.f_104888_.f_91072_.m_105250_(this.f_104889_, localplayer.m_108630_(), localplayer.m_108631_(), localplayer.m_6144_(), localplayer.m_20142_());
      } else {
         localplayer1 = this.f_104888_.f_91072_.m_105246_(this.f_104889_, localplayer.m_108630_(), localplayer.m_108631_());
      }

      localplayer1.m_20234_(i);
      this.f_104888_.f_91074_ = localplayer1;
      if (resourcekey != localplayer.m_9236_().m_46472_()) {
         this.f_104888_.m_91397_().m_120186_();
      }

      this.f_104888_.f_91075_ = localplayer1;
      if (p_105066_.m_263558_((byte)2)) {
         List<SynchedEntityData.DataValue<?>> list = localplayer.m_20088_().m_252804_();
         if (list != null) {
            localplayer1.m_20088_().m_135356_(list);
         }
      }

      if (p_105066_.m_263558_((byte)1)) {
         localplayer1.m_21204_().m_22159_(localplayer.m_21204_());
      }

      localplayer1.m_172530_();
      localplayer1.m_108748_(s);
      this.f_104889_.m_104630_(i, localplayer1);
      localplayer1.m_146922_(-180.0F);
      localplayer1.f_108618_ = new KeyboardInput(this.f_104888_.f_91066_);
      this.f_104888_.f_91072_.m_105221_(localplayer1);
      localplayer1.m_36393_(localplayer.m_36330_());
      localplayer1.m_108711_(localplayer.m_108632_());
      localplayer1.m_219749_(p_105066_.m_237785_());
      localplayer1.m_287199_(p_105066_.m_287149_());
      localplayer1.f_108589_ = localplayer.f_108589_;
      localplayer1.f_108590_ = localplayer.f_108590_;
      if (this.f_104888_.f_91080_ instanceof DeathScreen || this.f_104888_.f_91080_ instanceof DeathScreen.TitleConfirmScreen) {
         this.f_104888_.m_91152_((Screen)null);
      }

      this.f_104888_.f_91072_.m_171805_(p_105066_.m_132957_(), p_105066_.m_132958_());
   }

   public void m_7345_(ClientboundExplodePacket p_105012_) {
      PacketUtils.m_131363_(p_105012_, this, this.f_104888_);
      Explosion explosion = new Explosion(this.f_104888_.f_91073_, (Entity)null, p_105012_.m_132132_(), p_105012_.m_132133_(), p_105012_.m_132134_(), p_105012_.m_132135_(), p_105012_.m_132136_());
      explosion.m_46075_(true);
      this.f_104888_.f_91074_.m_20256_(this.f_104888_.f_91074_.m_20184_().m_82520_((double)p_105012_.m_132127_(), (double)p_105012_.m_132130_(), (double)p_105012_.m_132131_()));
   }

   public void m_6905_(ClientboundHorseScreenOpenPacket p_105018_) {
      PacketUtils.m_131363_(p_105018_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105018_.m_132208_());
      if (entity instanceof AbstractHorse) {
         LocalPlayer localplayer = this.f_104888_.f_91074_;
         AbstractHorse abstracthorse = (AbstractHorse)entity;
         SimpleContainer simplecontainer = new SimpleContainer(p_105018_.m_132207_());
         HorseInventoryMenu horseinventorymenu = new HorseInventoryMenu(p_105018_.m_132204_(), localplayer.m_150109_(), simplecontainer, abstracthorse);
         localplayer.f_36096_ = horseinventorymenu;
         this.f_104888_.m_91152_(new HorseInventoryScreen(horseinventorymenu, localplayer.m_150109_(), abstracthorse));
      }

   }

   public void m_5980_(ClientboundOpenScreenPacket p_105042_) {
      PacketUtils.m_131363_(p_105042_, this, this.f_104888_);
      MenuScreens.m_96201_(p_105042_.m_132628_(), this.f_104888_, p_105042_.m_132625_(), p_105042_.m_132629_());
   }

   public void m_5735_(ClientboundContainerSetSlotPacket p_105000_) {
      PacketUtils.m_131363_(p_105000_, this, this.f_104888_);
      Player player = this.f_104888_.f_91074_;
      ItemStack itemstack = p_105000_.m_131995_();
      int i = p_105000_.m_131994_();
      this.f_104888_.m_91301_().m_120568_(itemstack);
      if (p_105000_.m_131991_() == -1) {
         if (!(this.f_104888_.f_91080_ instanceof CreativeModeInventoryScreen)) {
            player.f_36096_.m_142503_(itemstack);
         }
      } else if (p_105000_.m_131991_() == -2) {
         player.m_150109_().m_6836_(i, itemstack);
      } else {
         boolean flag = false;
         Screen screen = this.f_104888_.f_91080_;
         if (screen instanceof CreativeModeInventoryScreen) {
            CreativeModeInventoryScreen creativemodeinventoryscreen = (CreativeModeInventoryScreen)screen;
            flag = !creativemodeinventoryscreen.m_258017_();
         }

         if (p_105000_.m_131991_() == 0 && InventoryMenu.m_150592_(i)) {
            if (!itemstack.m_41619_()) {
               ItemStack itemstack1 = player.f_36095_.m_38853_(i).m_7993_();
               if (itemstack1.m_41619_() || itemstack1.m_41613_() < itemstack.m_41613_()) {
                  itemstack.m_41754_(5);
               }
            }

            player.f_36095_.m_182406_(i, p_105000_.m_182716_(), itemstack);
         } else if (p_105000_.m_131991_() == player.f_36096_.f_38840_ && (p_105000_.m_131991_() != 0 || !flag)) {
            player.f_36096_.m_182406_(i, p_105000_.m_182716_(), itemstack);
         }
      }

   }

   public void m_6837_(ClientboundContainerSetContentPacket p_104996_) {
      PacketUtils.m_131363_(p_104996_, this, this.f_104888_);
      Player player = this.f_104888_.f_91074_;
      if (p_104996_.m_131954_() == 0) {
         player.f_36095_.m_182410_(p_104996_.m_182709_(), p_104996_.m_131957_(), p_104996_.m_182708_());
      } else if (p_104996_.m_131954_() == player.f_36096_.f_38840_) {
         player.f_36096_.m_182410_(p_104996_.m_182709_(), p_104996_.m_131957_(), p_104996_.m_182708_());
      }

   }

   public void m_8047_(ClientboundOpenSignEditorPacket p_105044_) {
      PacketUtils.m_131363_(p_105044_, this, this.f_104888_);
      BlockPos blockpos = p_105044_.m_132640_();
      BlockEntity $$3 = this.f_104889_.m_7702_(blockpos);
      if ($$3 instanceof SignBlockEntity signblockentity) {
         this.f_104888_.f_91074_.m_7739_(signblockentity, p_105044_.m_276774_());
      } else {
         BlockState blockstate = this.f_104889_.m_8055_(blockpos);
         SignBlockEntity signblockentity1 = new SignBlockEntity(blockpos, blockstate);
         signblockentity1.m_142339_(this.f_104889_);
         this.f_104888_.f_91074_.m_7739_(signblockentity1, p_105044_.m_276774_());
      }

   }

   public void m_7545_(ClientboundBlockEntityDataPacket p_104976_) {
      PacketUtils.m_131363_(p_104976_, this, this.f_104888_);
      BlockPos blockpos = p_104976_.m_131704_();
      this.f_104888_.f_91073_.m_141902_(blockpos, p_104976_.m_195645_()).ifPresent((p_205557_) -> {
         CompoundTag compoundtag = p_104976_.m_131708_();
         if (compoundtag != null) {
            p_205557_.m_142466_(compoundtag);
         }

         if (p_205557_ instanceof CommandBlockEntity && this.f_104888_.f_91080_ instanceof CommandBlockEditScreen) {
            ((CommandBlockEditScreen)this.f_104888_.f_91080_).m_98398_();
         }

      });
   }

   public void m_7257_(ClientboundContainerSetDataPacket p_104998_) {
      PacketUtils.m_131363_(p_104998_, this, this.f_104888_);
      Player player = this.f_104888_.f_91074_;
      if (player.f_36096_ != null && player.f_36096_.f_38840_ == p_104998_.m_131972_()) {
         player.f_36096_.m_7511_(p_104998_.m_131975_(), p_104998_.m_131976_());
      }

   }

   public void m_7277_(ClientboundSetEquipmentPacket p_105094_) {
      PacketUtils.m_131363_(p_105094_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105094_.m_133210_());
      if (entity != null) {
         p_105094_.m_133213_().forEach((p_205528_) -> {
            entity.m_8061_(p_205528_.getFirst(), p_205528_.getSecond());
         });
      }

   }

   public void m_7776_(ClientboundContainerClosePacket p_104994_) {
      PacketUtils.m_131363_(p_104994_, this, this.f_104888_);
      this.f_104888_.f_91074_.m_108763_();
   }

   public void m_7364_(ClientboundBlockEventPacket p_104978_) {
      PacketUtils.m_131363_(p_104978_, this, this.f_104888_);
      this.f_104888_.f_91073_.m_7696_(p_104978_.m_131725_(), p_104978_.m_131730_(), p_104978_.m_131728_(), p_104978_.m_131729_());
   }

   public void m_5943_(ClientboundBlockDestructionPacket p_104974_) {
      PacketUtils.m_131363_(p_104974_, this, this.f_104888_);
      this.f_104888_.f_91073_.m_6801_(p_104974_.m_131685_(), p_104974_.m_131688_(), p_104974_.m_131689_());
   }

   public void m_7616_(ClientboundGameEventPacket p_105016_) {
      PacketUtils.m_131363_(p_105016_, this, this.f_104888_);
      Player player = this.f_104888_.f_91074_;
      ClientboundGameEventPacket.Type clientboundgameeventpacket$type = p_105016_.m_132178_();
      float f = p_105016_.m_132181_();
      int i = Mth.m_14143_(f + 0.5F);
      if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132153_) {
         player.m_5661_(Component.m_237115_("block.minecraft.spawn.not_valid"), false);
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132154_) {
         this.f_104889_.m_6106_().m_5565_(true);
         this.f_104889_.m_46734_(0.0F);
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132155_) {
         this.f_104889_.m_6106_().m_5565_(false);
         this.f_104889_.m_46734_(1.0F);
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132156_) {
         this.f_104888_.f_91072_.m_105279_(GameType.m_46393_(i));
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132157_) {
         if (i == 0) {
            this.f_104888_.f_91074_.f_108617_.m_104955_(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            this.f_104888_.m_91152_(new ReceivingLevelScreen());
         } else if (i == 1) {
            this.f_104888_.m_91152_(new WinScreen(true, () -> {
               this.f_104888_.f_91074_.f_108617_.m_104955_(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
               this.f_104888_.m_91152_((Screen)null);
            }));
         }
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132158_) {
         Options options = this.f_104888_.f_91066_;
         if (f == 0.0F) {
            this.f_104888_.m_91152_(new DemoIntroScreen());
         } else if (f == 101.0F) {
            this.f_104888_.f_91065_.m_93076_().m_93785_(Component.m_237110_("demo.help.movement", options.f_92085_.m_90863_(), options.f_92086_.m_90863_(), options.f_92087_.m_90863_(), options.f_92088_.m_90863_()));
         } else if (f == 102.0F) {
            this.f_104888_.f_91065_.m_93076_().m_93785_(Component.m_237110_("demo.help.jump", options.f_92089_.m_90863_()));
         } else if (f == 103.0F) {
            this.f_104888_.f_91065_.m_93076_().m_93785_(Component.m_237110_("demo.help.inventory", options.f_92092_.m_90863_()));
         } else if (f == 104.0F) {
            this.f_104888_.f_91065_.m_93076_().m_93785_(Component.m_237110_("demo.day.6", options.f_92102_.m_90863_()));
         }
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132159_) {
         this.f_104889_.m_6263_(player, player.m_20185_(), player.m_20188_(), player.m_20189_(), SoundEvents.f_11686_, SoundSource.PLAYERS, 0.18F, 0.45F);
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132160_) {
         this.f_104889_.m_46734_(f);
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132161_) {
         this.f_104889_.m_46707_(f);
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132162_) {
         this.f_104889_.m_6263_(player, player.m_20185_(), player.m_20186_(), player.m_20189_(), SoundEvents.f_12295_, SoundSource.NEUTRAL, 1.0F, 1.0F);
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132163_) {
         this.f_104889_.m_7106_(ParticleTypes.f_123807_, player.m_20185_(), player.m_20186_(), player.m_20189_(), 0.0D, 0.0D, 0.0D);
         if (i == 1) {
            this.f_104889_.m_6263_(player, player.m_20185_(), player.m_20186_(), player.m_20189_(), SoundEvents.f_11880_, SoundSource.HOSTILE, 1.0F, 1.0F);
         }
      } else if (clientboundgameeventpacket$type == ClientboundGameEventPacket.f_132164_) {
         this.f_104888_.f_91074_.m_108711_(f == 0.0F);
      }

   }

   public void m_7633_(ClientboundMapItemDataPacket p_105032_) {
      PacketUtils.m_131363_(p_105032_, this, this.f_104888_);
      MapRenderer maprenderer = this.f_104888_.f_91063_.m_109151_();
      int i = p_105032_.m_132445_();
      String s = MapItem.m_42848_(i);
      MapItemSavedData mapitemsaveddata = this.f_104888_.f_91073_.m_7489_(s);
      if (mapitemsaveddata == null) {
         mapitemsaveddata = MapItemSavedData.m_164776_(p_105032_.m_178982_(), p_105032_.m_178983_(), this.f_104888_.f_91073_.m_46472_());
         this.f_104888_.f_91073_.m_257583_(s, mapitemsaveddata);
      }

      p_105032_.m_132437_(mapitemsaveddata);
      maprenderer.m_168765_(i, mapitemsaveddata);
   }

   public void m_7704_(ClientboundLevelEventPacket p_105024_) {
      PacketUtils.m_131363_(p_105024_, this, this.f_104888_);
      if (p_105024_.m_132274_()) {
         this.f_104888_.f_91073_.m_6798_(p_105024_.m_132277_(), p_105024_.m_132279_(), p_105024_.m_132278_());
      } else {
         this.f_104888_.f_91073_.m_46796_(p_105024_.m_132277_(), p_105024_.m_132279_(), p_105024_.m_132278_());
      }

   }

   public void m_5498_(ClientboundUpdateAdvancementsPacket p_105126_) {
      PacketUtils.m_131363_(p_105126_, this, this.f_104888_);
      this.f_104893_.m_104399_(p_105126_);
   }

   public void m_7553_(ClientboundSelectAdvancementsTabPacket p_105072_) {
      PacketUtils.m_131363_(p_105072_, this, this.f_104888_);
      ResourceLocation resourcelocation = p_105072_.m_133013_();
      if (resourcelocation == null) {
         this.f_104893_.m_104401_((Advancement)null, false);
      } else {
         Advancement advancement = this.f_104893_.m_104396_().m_139337_(resourcelocation);
         this.f_104893_.m_104401_(advancement, false);
      }

   }

   public void m_7443_(ClientboundCommandsPacket p_104990_) {
      PacketUtils.m_131363_(p_104990_, this, this.f_104888_);
      this.f_104899_ = new CommandDispatcher<>(p_104990_.m_237624_(CommandBuildContext.m_255418_(this.f_104903_.m_247579_(), this.f_244039_)));
   }

   public void m_7183_(ClientboundStopSoundPacket p_105116_) {
      PacketUtils.m_131363_(p_105116_, this, this.f_104888_);
      this.f_104888_.m_91106_().m_120386_(p_105116_.m_133476_(), p_105116_.m_133479_());
   }

   public void m_7589_(ClientboundCommandSuggestionsPacket p_104988_) {
      PacketUtils.m_131363_(p_104988_, this, this.f_104888_);
      this.f_104894_.m_105171_(p_104988_.m_131854_(), p_104988_.m_131857_());
   }

   public void m_6327_(ClientboundUpdateRecipesPacket p_105132_) {
      PacketUtils.m_131363_(p_105132_, this, this.f_104888_);
      this.f_104900_.m_44024_(p_105132_.m_133644_());
      ClientRecipeBook clientrecipebook = this.f_104888_.f_91074_.m_108631_();
      clientrecipebook.m_266394_(this.f_104900_.m_44051_(), this.f_104888_.f_91073_.m_9598_());
      this.f_104888_.m_231374_(SearchRegistry.f_119943_, clientrecipebook.m_90639_());
   }

   public void m_7244_(ClientboundPlayerLookAtPacket p_105054_) {
      PacketUtils.m_131363_(p_105054_, this, this.f_104888_);
      Vec3 vec3 = p_105054_.m_132785_(this.f_104889_);
      if (vec3 != null) {
         this.f_104888_.f_91074_.m_7618_(p_105054_.m_132793_(), vec3);
      }

   }

   public void m_6148_(ClientboundTagQueryPacket p_105120_) {
      PacketUtils.m_131363_(p_105120_, this, this.f_104888_);
      if (!this.f_104896_.m_90705_(p_105120_.m_133506_(), p_105120_.m_133509_())) {
         f_104883_.debug("Got unhandled response to tag query {}", (int)p_105120_.m_133506_());
      }

   }

   public void m_7271_(ClientboundAwardStatsPacket p_104970_) {
      PacketUtils.m_131363_(p_104970_, this, this.f_104888_);

      for(Map.Entry<Stat<?>, Integer> entry : p_104970_.m_131643_().entrySet()) {
         Stat<?> stat = entry.getKey();
         int i = entry.getValue();
         this.f_104888_.f_91074_.m_108630_().m_6085_(this.f_104888_.f_91074_, stat, i);
      }

      if (this.f_104888_.f_91080_ instanceof StatsUpdateListener) {
         ((StatsUpdateListener)this.f_104888_.f_91080_).m_7819_();
      }

   }

   public void m_8076_(ClientboundRecipePacket p_105058_) {
      PacketUtils.m_131363_(p_105058_, this, this.f_104888_);
      ClientRecipeBook clientrecipebook = this.f_104888_.f_91074_.m_108631_();
      clientrecipebook.m_12687_(p_105058_.m_132869_());
      ClientboundRecipePacket.State clientboundrecipepacket$state = p_105058_.m_132870_();
      switch (clientboundrecipepacket$state) {
         case REMOVE:
            for(ResourceLocation resourcelocation3 : p_105058_.m_132865_()) {
               this.f_104900_.m_44043_(resourcelocation3).ifPresent(clientrecipebook::m_12713_);
            }
            break;
         case INIT:
            for(ResourceLocation resourcelocation1 : p_105058_.m_132865_()) {
               this.f_104900_.m_44043_(resourcelocation1).ifPresent(clientrecipebook::m_12700_);
            }

            for(ResourceLocation resourcelocation2 : p_105058_.m_132868_()) {
               this.f_104900_.m_44043_(resourcelocation2).ifPresent(clientrecipebook::m_12723_);
            }
            break;
         case ADD:
            for(ResourceLocation resourcelocation : p_105058_.m_132865_()) {
               this.f_104900_.m_44043_(resourcelocation).ifPresent((p_272314_) -> {
                  clientrecipebook.m_12700_(p_272314_);
                  clientrecipebook.m_12723_(p_272314_);
                  if (p_272314_.m_271738_()) {
                     RecipeToast.m_94817_(this.f_104888_.m_91300_(), p_272314_);
                  }

               });
            }
      }

      clientrecipebook.m_90639_().forEach((p_205540_) -> {
         p_205540_.m_100499_(clientrecipebook);
      });
      if (this.f_104888_.f_91080_ instanceof RecipeUpdateListener) {
         ((RecipeUpdateListener)this.f_104888_.f_91080_).m_6916_();
      }

   }

   public void m_7915_(ClientboundUpdateMobEffectPacket p_105130_) {
      PacketUtils.m_131363_(p_105130_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105130_.m_133622_());
      if (entity instanceof LivingEntity) {
         MobEffect mobeffect = p_105130_.m_237878_();
         if (mobeffect != null) {
            MobEffectInstance mobeffectinstance = new MobEffectInstance(mobeffect, p_105130_.m_133625_(), p_105130_.m_133624_(), p_105130_.m_133627_(), p_105130_.m_133626_(), p_105130_.m_133628_(), (MobEffectInstance)null, Optional.ofNullable(p_105130_.m_237879_()));
            ((LivingEntity)entity).m_147215_(mobeffectinstance, (Entity)null);
         }
      }
   }

   public void m_5859_(ClientboundUpdateTagsPacket p_105134_) {
      PacketUtils.m_131363_(p_105134_, this, this.f_104888_);
      p_105134_.m_179482_().forEach(this::m_205560_);
      if (!this.f_104885_.m_129531_()) {
         Blocks.m_50758_();
      }

      CreativeModeTabs.m_258007_().m_257466_();
   }

   public void m_241155_(ClientboundUpdateEnabledFeaturesPacket p_251591_) {
      PacketUtils.m_131363_(p_251591_, this, this.f_104888_);
      this.f_244039_ = FeatureFlags.f_244280_.m_247416_(p_251591_.f_244610_());
   }

   private <T> void m_205560_(ResourceKey<? extends Registry<? extends T>> p_205561_, TagNetworkSerialization.NetworkPayload p_205562_) {
      if (!p_205562_.m_203966_()) {
         Registry<T> registry = this.f_104903_.m_247579_().m_6632_(p_205561_).orElseThrow(() -> {
            return new IllegalStateException("Unknown registry " + p_205561_);
         });
         Map<TagKey<T>, List<Holder<T>>> map = new HashMap<>();
         TagNetworkSerialization.m_203952_(p_205561_, registry, p_205562_, map::put);
         registry.m_203652_(map);
      }
   }

   public void m_142234_(ClientboundPlayerCombatEndPacket p_171771_) {
   }

   public void m_142058_(ClientboundPlayerCombatEnterPacket p_171773_) {
   }

   public void m_142747_(ClientboundPlayerCombatKillPacket p_171775_) {
      PacketUtils.m_131363_(p_171775_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_171775_.m_179078_());
      if (entity == this.f_104888_.f_91074_) {
         if (this.f_104888_.f_91074_.m_108632_()) {
            this.f_104888_.m_91152_(new DeathScreen(p_171775_.m_179079_(), this.f_104889_.m_6106_().m_5466_()));
         } else {
            this.f_104888_.f_91074_.m_7583_();
         }
      }

   }

   public void m_6664_(ClientboundChangeDifficultyPacket p_104984_) {
      PacketUtils.m_131363_(p_104984_, this, this.f_104888_);
      this.f_104890_.m_104851_(p_104984_.m_131820_());
      this.f_104890_.m_104858_(p_104984_.m_131817_());
   }

   public void m_6447_(ClientboundSetCameraPacket p_105076_) {
      PacketUtils.m_131363_(p_105076_, this, this.f_104888_);
      Entity entity = p_105076_.m_133059_(this.f_104889_);
      if (entity != null) {
         this.f_104888_.m_91118_(entity);
      }

   }

   public void m_142237_(ClientboundInitializeBorderPacket p_171767_) {
      PacketUtils.m_131363_(p_171767_, this, this.f_104888_);
      WorldBorder worldborder = this.f_104889_.m_6857_();
      worldborder.m_61949_(p_171767_.m_178886_(), p_171767_.m_178887_());
      long i = p_171767_.m_178890_();
      if (i > 0L) {
         worldborder.m_61919_(p_171767_.m_178889_(), p_171767_.m_178888_(), i);
      } else {
         worldborder.m_61917_(p_171767_.m_178888_());
      }

      worldborder.m_61923_(p_171767_.m_178891_());
      worldborder.m_61952_(p_171767_.m_178893_());
      worldborder.m_61944_(p_171767_.m_178892_());
   }

   public void m_142612_(ClientboundSetBorderCenterPacket p_171781_) {
      PacketUtils.m_131363_(p_171781_, this, this.f_104888_);
      this.f_104889_.m_6857_().m_61949_(p_171781_.m_179224_(), p_171781_.m_179223_());
   }

   public void m_142686_(ClientboundSetBorderLerpSizePacket p_171783_) {
      PacketUtils.m_131363_(p_171783_, this, this.f_104888_);
      this.f_104889_.m_6857_().m_61919_(p_171783_.m_179238_(), p_171783_.m_179239_(), p_171783_.m_179240_());
   }

   public void m_142238_(ClientboundSetBorderSizePacket p_171785_) {
      PacketUtils.m_131363_(p_171785_, this, this.f_104888_);
      this.f_104889_.m_6857_().m_61917_(p_171785_.m_179252_());
   }

   public void m_142696_(ClientboundSetBorderWarningDistancePacket p_171789_) {
      PacketUtils.m_131363_(p_171789_, this, this.f_104888_);
      this.f_104889_.m_6857_().m_61952_(p_171789_.m_179276_());
   }

   public void m_142056_(ClientboundSetBorderWarningDelayPacket p_171787_) {
      PacketUtils.m_131363_(p_171787_, this, this.f_104888_);
      this.f_104889_.m_6857_().m_61944_(p_171787_.m_179264_());
   }

   public void m_142766_(ClientboundClearTitlesPacket p_171765_) {
      PacketUtils.m_131363_(p_171765_, this, this.f_104888_);
      this.f_104888_.f_91065_.m_168713_();
      if (p_171765_.m_178788_()) {
         this.f_104888_.f_91065_.m_93006_();
      }

   }

   public void m_213672_(ClientboundServerDataPacket p_233704_) {
      PacketUtils.m_131363_(p_233704_, this, this.f_104888_);
      if (this.f_244115_ != null) {
         this.f_244115_.f_105365_ = p_233704_.m_271805_();
         p_233704_.m_271815_().ifPresent(this.f_244115_::m_271813_);
         this.f_244115_.m_242965_(p_233704_.m_242957_());
         ServerList.m_105446_(this.f_244115_);
         if (!p_233704_.m_242957_()) {
            SystemToast systemtoast = SystemToast.m_94847_(this.f_104888_, SystemToast.SystemToastIds.UNSECURE_SERVER_WARNING, f_242953_, f_242949_);
            this.f_104888_.m_91300_().m_94922_(systemtoast);
         }

      }
   }

   public void m_240695_(ClientboundCustomChatCompletionsPacket p_240832_) {
      PacketUtils.m_131363_(p_240832_, this, this.f_104888_);
      this.f_104894_.m_240713_(p_240832_.f_240661_(), p_240832_.f_240663_());
   }

   public void m_142456_(ClientboundSetActionBarTextPacket p_171779_) {
      PacketUtils.m_131363_(p_171779_, this, this.f_104888_);
      this.f_104888_.f_91065_.m_93063_(p_171779_.m_179210_(), false);
   }

   public void m_142442_(ClientboundSetTitleTextPacket p_171793_) {
      PacketUtils.m_131363_(p_171793_, this, this.f_104888_);
      this.f_104888_.f_91065_.m_168714_(p_171793_.m_179399_());
   }

   public void m_141913_(ClientboundSetSubtitleTextPacket p_171791_) {
      PacketUtils.m_131363_(p_171791_, this, this.f_104888_);
      this.f_104888_.f_91065_.m_168711_(p_171791_.m_179385_());
   }

   public void m_142185_(ClientboundSetTitlesAnimationPacket p_171795_) {
      PacketUtils.m_131363_(p_171795_, this, this.f_104888_);
      this.f_104888_.f_91065_.m_168684_(p_171795_.m_179415_(), p_171795_.m_179416_(), p_171795_.m_179417_());
   }

   public void m_6235_(ClientboundTabListPacket p_105118_) {
      PacketUtils.m_131363_(p_105118_, this, this.f_104888_);
      this.f_104888_.f_91065_.m_93088_().m_94558_(p_105118_.m_133489_().getString().isEmpty() ? null : p_105118_.m_133489_());
      this.f_104888_.f_91065_.m_93088_().m_94554_(p_105118_.m_133492_().getString().isEmpty() ? null : p_105118_.m_133492_());
   }

   public void m_6476_(ClientboundRemoveMobEffectPacket p_105062_) {
      PacketUtils.m_131363_(p_105062_, this, this.f_104888_);
      Entity entity = p_105062_.m_132901_(this.f_104889_);
      if (entity instanceof LivingEntity) {
         ((LivingEntity)entity).m_6234_(p_105062_.m_132909_());
      }

   }

   public void m_213565_(ClientboundPlayerInfoRemovePacket p_248731_) {
      PacketUtils.m_131363_(p_248731_, this, this.f_104888_);

      for(UUID uuid : p_248731_.f_244383_()) {
         this.f_104888_.m_91266_().m_100690_(uuid);
         PlayerInfo playerinfo = this.f_104892_.remove(uuid);
         if (playerinfo != null) {
            this.f_244156_.remove(playerinfo);
         }
      }

   }

   public void m_214045_(ClientboundPlayerInfoUpdatePacket p_250115_) {
      PacketUtils.m_131363_(p_250115_, this, this.f_104888_);

      for(ClientboundPlayerInfoUpdatePacket.Entry clientboundplayerinfoupdatepacket$entry : p_250115_.m_245290_()) {
         PlayerInfo playerinfo = new PlayerInfo(clientboundplayerinfoupdatepacket$entry.f_243688_(), this.m_253150_());
         if (this.f_104892_.putIfAbsent(clientboundplayerinfoupdatepacket$entry.f_244142_(), playerinfo) == null) {
            this.f_104888_.m_91266_().m_100676_(playerinfo);
         }
      }

      for(ClientboundPlayerInfoUpdatePacket.Entry clientboundplayerinfoupdatepacket$entry1 : p_250115_.m_246778_()) {
         PlayerInfo playerinfo1 = this.f_104892_.get(clientboundplayerinfoupdatepacket$entry1.f_244142_());
         if (playerinfo1 == null) {
            f_104883_.warn("Ignoring player info update for unknown player {}", (Object)clientboundplayerinfoupdatepacket$entry1.f_244142_());
         } else {
            for(ClientboundPlayerInfoUpdatePacket.Action clientboundplayerinfoupdatepacket$action : p_250115_.m_246097_()) {
               this.m_247639_(clientboundplayerinfoupdatepacket$action, clientboundplayerinfoupdatepacket$entry1, playerinfo1);
            }
         }
      }

   }

   private void m_247639_(ClientboundPlayerInfoUpdatePacket.Action p_248954_, ClientboundPlayerInfoUpdatePacket.Entry p_251310_, PlayerInfo p_251146_) {
      switch (p_248954_) {
         case INITIALIZE_CHAT:
            this.m_245842_(p_251310_, p_251146_);
            break;
         case UPDATE_GAME_MODE:
            if (p_251146_.m_105325_() != p_251310_.f_244162_() && this.f_104888_.f_91074_ != null && this.f_104888_.f_91074_.m_20148_().equals(p_251310_.f_244142_())) {
               this.f_104888_.f_91074_.m_287171_(p_251310_.f_244162_());
            }

            p_251146_.m_105317_(p_251310_.f_244162_());
            break;
         case UPDATE_LISTED:
            if (p_251310_.f_243700_()) {
               this.f_244156_.add(p_251146_);
            } else {
               this.f_244156_.remove(p_251146_);
            }
            break;
         case UPDATE_LATENCY:
            p_251146_.m_105313_(p_251310_.f_244322_());
            break;
         case UPDATE_DISPLAY_NAME:
            p_251146_.m_105323_(p_251310_.f_244512_());
      }

   }

   private void m_245842_(ClientboundPlayerInfoUpdatePacket.Entry p_248806_, PlayerInfo p_251136_) {
      GameProfile gameprofile = p_251136_.m_105312_();
      SignatureValidator signaturevalidator = this.f_104888_.m_231417_();
      if (signaturevalidator == null) {
         f_104883_.warn("Ignoring chat session from {} due to missing Services public key", (Object)gameprofile.getName());
         p_251136_.m_253204_(this.m_253150_());
      } else {
         RemoteChatSession.Data remotechatsession$data = p_248806_.f_244153_();
         if (remotechatsession$data != null) {
            try {
               RemoteChatSession remotechatsession = remotechatsession$data.m_247588_(gameprofile, signaturevalidator, ProfilePublicKey.f_243350_);
               p_251136_.m_246479_(remotechatsession);
            } catch (ProfilePublicKey.ValidationException profilepublickey$validationexception) {
               f_104883_.error("Failed to validate profile key for player: '{}'", gameprofile.getName(), profilepublickey$validationexception);
               p_251136_.m_253204_(this.m_253150_());
            }
         } else {
            p_251136_.m_253204_(this.m_253150_());
         }

      }
   }

   private boolean m_253150_() {
      return this.f_244115_ != null && this.f_244115_.m_242962_();
   }

   public void m_7231_(ClientboundKeepAlivePacket p_105020_) {
      this.m_269234_(new ServerboundKeepAlivePacket(p_105020_.m_132219_()), () -> {
         return !RenderSystem.isFrozenAtPollEvents();
      }, Duration.ofMinutes(1L));
   }

   private void m_269234_(Packet<ServerGamePacketListener> p_270433_, BooleanSupplier p_270843_, Duration p_270497_) {
      if (p_270843_.getAsBoolean()) {
         this.m_104955_(p_270433_);
      } else {
         this.f_268637_.add(new ClientPacketListener.DeferredPacket(p_270433_, p_270843_, Util.m_137550_() + p_270497_.toMillis()));
      }

   }

   private void m_269212_() {
      Iterator<ClientPacketListener.DeferredPacket> iterator = this.f_268637_.iterator();

      while(iterator.hasNext()) {
         ClientPacketListener.DeferredPacket clientpacketlistener$deferredpacket = iterator.next();
         if (clientpacketlistener$deferredpacket.f_268477_().getAsBoolean()) {
            this.m_104955_(clientpacketlistener$deferredpacket.f_268574_);
            iterator.remove();
         } else if (clientpacketlistener$deferredpacket.f_268654_() <= Util.m_137550_()) {
            iterator.remove();
         }
      }

   }

   public void m_5767_(ClientboundPlayerAbilitiesPacket p_105048_) {
      PacketUtils.m_131363_(p_105048_, this, this.f_104888_);
      Player player = this.f_104888_.f_91074_;
      player.m_150110_().f_35935_ = p_105048_.m_132677_();
      player.m_150110_().f_35937_ = p_105048_.m_132679_();
      player.m_150110_().f_35934_ = p_105048_.m_132674_();
      player.m_150110_().f_35936_ = p_105048_.m_132678_();
      player.m_150110_().m_35943_(p_105048_.m_132680_());
      player.m_150110_().m_35948_(p_105048_.m_132681_());
   }

   public void m_8068_(ClientboundSoundPacket p_105114_) {
      PacketUtils.m_131363_(p_105114_, this, this.f_104888_);
      this.f_104888_.f_91073_.m_262808_(this.f_104888_.f_91074_, p_105114_.m_133459_(), p_105114_.m_133460_(), p_105114_.m_133461_(), p_105114_.m_263229_(), p_105114_.m_133458_(), p_105114_.m_133462_(), p_105114_.m_133463_(), p_105114_.m_237848_());
   }

   public void m_5863_(ClientboundSoundEntityPacket p_105112_) {
      PacketUtils.m_131363_(p_105112_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105112_.m_133430_());
      if (entity != null) {
         this.f_104888_.f_91073_.m_213890_(this.f_104888_.f_91074_, entity, p_105112_.m_263456_(), p_105112_.m_133429_(), p_105112_.m_133431_(), p_105112_.m_133432_(), p_105112_.m_237837_());
      }
   }

   public void m_5587_(ClientboundResourcePackPacket p_105064_) {
      URL url = m_233709_(p_105064_.m_132924_());
      if (url == null) {
         this.m_105135_(ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD);
      } else {
         String s = p_105064_.m_132927_();
         boolean flag = p_105064_.m_179188_();
         if (this.f_244115_ != null && this.f_244115_.m_105387_() == ServerData.ServerPackStatus.ENABLED) {
            this.m_105135_(ServerboundResourcePackPacket.Action.ACCEPTED);
            this.m_104951_(this.f_104888_.m_247489_().m_246254_(url, s, true));
         } else if (this.f_244115_ != null && this.f_244115_.m_105387_() != ServerData.ServerPackStatus.PROMPT && (!flag || this.f_244115_.m_105387_() != ServerData.ServerPackStatus.DISABLED)) {
            this.m_105135_(ServerboundResourcePackPacket.Action.DECLINED);
            if (flag) {
               this.f_104885_.m_129507_(Component.m_237115_("multiplayer.requiredTexturePrompt.disconnect"));
            }
         } else {
            this.f_104888_.execute(() -> {
               this.f_104888_.m_91152_(new ConfirmScreen((p_233690_) -> {
                  this.f_104888_.m_91152_((Screen)null);
                  if (p_233690_) {
                     if (this.f_244115_ != null) {
                        this.f_244115_.m_105379_(ServerData.ServerPackStatus.ENABLED);
                     }

                     this.m_105135_(ServerboundResourcePackPacket.Action.ACCEPTED);
                     this.m_104951_(this.f_104888_.m_247489_().m_246254_(url, s, true));
                  } else {
                     this.m_105135_(ServerboundResourcePackPacket.Action.DECLINED);
                     if (flag) {
                        this.f_104885_.m_129507_(Component.m_237115_("multiplayer.requiredTexturePrompt.disconnect"));
                     } else if (this.f_244115_ != null) {
                        this.f_244115_.m_105379_(ServerData.ServerPackStatus.DISABLED);
                     }
                  }

                  if (this.f_244115_ != null) {
                     ServerList.m_105446_(this.f_244115_);
                  }

               }, flag ? Component.m_237115_("multiplayer.requiredTexturePrompt.line1") : Component.m_237115_("multiplayer.texturePrompt.line1"), m_171759_(flag ? Component.m_237115_("multiplayer.requiredTexturePrompt.line2").m_130944_(ChatFormatting.YELLOW, ChatFormatting.BOLD) : Component.m_237115_("multiplayer.texturePrompt.line2"), p_105064_.m_179189_()), flag ? CommonComponents.f_130659_ : CommonComponents.f_130657_, (Component)(flag ? Component.m_237115_("menu.disconnect") : CommonComponents.f_130658_)));
            });
         }

      }
   }

   private static Component m_171759_(Component p_171760_, @Nullable Component p_171761_) {
      return (Component)(p_171761_ == null ? p_171760_ : Component.m_237110_("multiplayer.texturePrompt.serverPrompt", p_171760_, p_171761_));
   }

   @Nullable
   private static URL m_233709_(String p_233710_) {
      try {
         URL url = new URL(p_233710_);
         String s = url.getProtocol();
         return !"http".equals(s) && !"https".equals(s) ? null : url;
      } catch (MalformedURLException malformedurlexception) {
         return null;
      }
   }

   private void m_104951_(CompletableFuture<?> p_104952_) {
      p_104952_.thenRun(() -> {
         this.m_105135_(ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
      }).exceptionally((p_233680_) -> {
         this.m_105135_(ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD);
         return null;
      });
   }

   private void m_105135_(ServerboundResourcePackPacket.Action p_105136_) {
      this.f_104885_.m_129512_(new ServerboundResourcePackPacket(p_105136_));
   }

   public void m_7685_(ClientboundBossEventPacket p_104982_) {
      PacketUtils.m_131363_(p_104982_, this, this.f_104888_);
      this.f_104888_.f_91065_.m_93090_().m_93711_(p_104982_);
   }

   public void m_7701_(ClientboundCooldownPacket p_105002_) {
      PacketUtils.m_131363_(p_105002_, this, this.f_104888_);
      if (p_105002_.m_132011_() == 0) {
         this.f_104888_.f_91074_.m_36335_().m_41527_(p_105002_.m_132008_());
      } else {
         this.f_104888_.f_91074_.m_36335_().m_41524_(p_105002_.m_132008_(), p_105002_.m_132011_());
      }

   }

   public void m_7410_(ClientboundMoveVehiclePacket p_105038_) {
      PacketUtils.m_131363_(p_105038_, this, this.f_104888_);
      Entity entity = this.f_104888_.f_91074_.m_20201_();
      if (entity != this.f_104888_.f_91074_ && entity.m_6109_()) {
         entity.m_19890_(p_105038_.m_132591_(), p_105038_.m_132594_(), p_105038_.m_132595_(), p_105038_.m_132596_(), p_105038_.m_132597_());
         this.f_104885_.m_129512_(new ServerboundMoveVehiclePacket(entity));
      }

   }

   public void m_6503_(ClientboundOpenBookPacket p_105040_) {
      PacketUtils.m_131363_(p_105040_, this, this.f_104888_);
      ItemStack itemstack = this.f_104888_.f_91074_.m_21120_(p_105040_.m_132608_());
      if (itemstack.m_150930_(Items.f_42615_)) {
         this.f_104888_.m_91152_(new BookViewScreen(new BookViewScreen.WrittenBookAccess(itemstack)));
      }

   }

   public void m_7413_(ClientboundCustomPayloadPacket p_105004_) {
      PacketUtils.m_131363_(p_105004_, this, this.f_104888_);
      ResourceLocation resourcelocation = p_105004_.m_132042_();
      FriendlyByteBuf friendlybytebuf = null;

      try {
         friendlybytebuf = p_105004_.m_132045_();
         if (ClientboundCustomPayloadPacket.f_132012_.equals(resourcelocation)) {
            String s = friendlybytebuf.m_130277_();
            this.f_104888_.f_91074_.m_108748_(s);
            this.f_194191_.m_260918_(s);
         } else if (ClientboundCustomPayloadPacket.f_132013_.equals(resourcelocation)) {
            int k1 = friendlybytebuf.readInt();
            float f = friendlybytebuf.readFloat();
            Path path = Path.m_77390_(friendlybytebuf);
            this.f_104888_.f_91064_.f_113413_.m_113611_(k1, path, f);
         } else if (ClientboundCustomPayloadPacket.f_132014_.equals(resourcelocation)) {
            long l1 = friendlybytebuf.m_130258_();
            BlockPos blockpos8 = friendlybytebuf.m_130135_();
            ((NeighborsUpdateRenderer)this.f_104888_.f_91064_.f_113418_).m_113596_(l1, blockpos8);
         } else if (ClientboundCustomPayloadPacket.f_132016_.equals(resourcelocation)) {
            DimensionType dimensiontype = this.f_104903_.m_247579_().m_175515_(Registries.f_256787_).m_7745_(friendlybytebuf.m_130281_());
            BoundingBox boundingbox = new BoundingBox(friendlybytebuf.readInt(), friendlybytebuf.readInt(), friendlybytebuf.readInt(), friendlybytebuf.readInt(), friendlybytebuf.readInt(), friendlybytebuf.readInt());
            int l3 = friendlybytebuf.readInt();
            List<BoundingBox> list = Lists.newArrayList();
            List<Boolean> list1 = Lists.newArrayList();

            for(int i = 0; i < l3; ++i) {
               list.add(new BoundingBox(friendlybytebuf.readInt(), friendlybytebuf.readInt(), friendlybytebuf.readInt(), friendlybytebuf.readInt(), friendlybytebuf.readInt(), friendlybytebuf.readInt()));
               list1.add(friendlybytebuf.readBoolean());
            }

            this.f_104888_.f_91064_.f_113420_.m_113682_(boundingbox, list, list1, dimensiontype);
         } else if (ClientboundCustomPayloadPacket.f_132017_.equals(resourcelocation)) {
            ((WorldGenAttemptRenderer)this.f_104888_.f_91064_.f_113422_).m_113737_(friendlybytebuf.m_130135_(), friendlybytebuf.readFloat(), friendlybytebuf.readFloat(), friendlybytebuf.readFloat(), friendlybytebuf.readFloat(), friendlybytebuf.readFloat());
         } else if (ClientboundCustomPayloadPacket.f_132021_.equals(resourcelocation)) {
            int i2 = friendlybytebuf.readInt();

            for(int k2 = 0; k2 < i2; ++k2) {
               this.f_104888_.f_91064_.f_113426_.m_113709_(friendlybytebuf.m_130157_());
            }

            int l2 = friendlybytebuf.readInt();

            for(int i4 = 0; i4 < l2; ++i4) {
               this.f_104888_.f_91064_.f_113426_.m_113711_(friendlybytebuf.m_130157_());
            }
         } else if (ClientboundCustomPayloadPacket.f_132019_.equals(resourcelocation)) {
            BlockPos blockpos2 = friendlybytebuf.m_130135_();
            String s9 = friendlybytebuf.m_130277_();
            int j4 = friendlybytebuf.readInt();
            BrainDebugRenderer.PoiInfo braindebugrenderer$poiinfo = new BrainDebugRenderer.PoiInfo(blockpos2, s9, j4);
            this.f_104888_.f_91064_.f_113425_.m_113226_(braindebugrenderer$poiinfo);
         } else if (ClientboundCustomPayloadPacket.f_132020_.equals(resourcelocation)) {
            BlockPos blockpos3 = friendlybytebuf.m_130135_();
            this.f_104888_.f_91064_.f_113425_.m_113228_(blockpos3);
         } else if (ClientboundCustomPayloadPacket.f_132018_.equals(resourcelocation)) {
            BlockPos blockpos4 = friendlybytebuf.m_130135_();
            int i3 = friendlybytebuf.readInt();
            this.f_104888_.f_91064_.f_113425_.m_113230_(blockpos4, i3);
         } else if (ClientboundCustomPayloadPacket.f_132022_.equals(resourcelocation)) {
            BlockPos blockpos5 = friendlybytebuf.m_130135_();
            int j3 = friendlybytebuf.readInt();
            int k4 = friendlybytebuf.readInt();
            List<GoalSelectorDebugRenderer.DebugGoal> list2 = Lists.newArrayList();

            for(int i6 = 0; i6 < k4; ++i6) {
               int j6 = friendlybytebuf.readInt();
               boolean flag = friendlybytebuf.readBoolean();
               String s1 = friendlybytebuf.m_130136_(255);
               list2.add(new GoalSelectorDebugRenderer.DebugGoal(blockpos5, j6, s1, flag));
            }

            this.f_104888_.f_91064_.f_113429_.m_113548_(j3, list2);
         } else if (ClientboundCustomPayloadPacket.f_132028_.equals(resourcelocation)) {
            int j2 = friendlybytebuf.readInt();
            Collection<BlockPos> collection = Lists.newArrayList();

            for(int l4 = 0; l4 < j2; ++l4) {
               collection.add(friendlybytebuf.m_130135_());
            }

            this.f_104888_.f_91064_.f_113428_.m_113663_(collection);
         } else if (ClientboundCustomPayloadPacket.f_132023_.equals(resourcelocation)) {
            double d0 = friendlybytebuf.readDouble();
            double d2 = friendlybytebuf.readDouble();
            double d4 = friendlybytebuf.readDouble();
            Position position = new PositionImpl(d0, d2, d4);
            UUID uuid = friendlybytebuf.m_130259_();
            int j = friendlybytebuf.readInt();
            String s2 = friendlybytebuf.m_130277_();
            String s3 = friendlybytebuf.m_130277_();
            int k = friendlybytebuf.readInt();
            float f1 = friendlybytebuf.readFloat();
            float f2 = friendlybytebuf.readFloat();
            String s4 = friendlybytebuf.m_130277_();
            Path path1 = friendlybytebuf.m_236868_(Path::m_77390_);
            boolean flag1 = friendlybytebuf.readBoolean();
            int l = friendlybytebuf.readInt();
            BrainDebugRenderer.BrainDump braindebugrenderer$braindump = new BrainDebugRenderer.BrainDump(uuid, j, s2, s3, k, f1, f2, position, s4, path1, flag1, l);
            int i1 = friendlybytebuf.m_130242_();

            for(int j1 = 0; j1 < i1; ++j1) {
               String s5 = friendlybytebuf.m_130277_();
               braindebugrenderer$braindump.f_113304_.add(s5);
            }

            int i8 = friendlybytebuf.m_130242_();

            for(int j8 = 0; j8 < i8; ++j8) {
               String s6 = friendlybytebuf.m_130277_();
               braindebugrenderer$braindump.f_113305_.add(s6);
            }

            int k8 = friendlybytebuf.m_130242_();

            for(int l8 = 0; l8 < k8; ++l8) {
               String s7 = friendlybytebuf.m_130277_();
               braindebugrenderer$braindump.f_113306_.add(s7);
            }

            int i9 = friendlybytebuf.m_130242_();

            for(int j9 = 0; j9 < i9; ++j9) {
               BlockPos blockpos = friendlybytebuf.m_130135_();
               braindebugrenderer$braindump.f_113308_.add(blockpos);
            }

            int k9 = friendlybytebuf.m_130242_();

            for(int l9 = 0; l9 < k9; ++l9) {
               BlockPos blockpos1 = friendlybytebuf.m_130135_();
               braindebugrenderer$braindump.f_113309_.add(blockpos1);
            }

            int i10 = friendlybytebuf.m_130242_();

            for(int j10 = 0; j10 < i10; ++j10) {
               String s8 = friendlybytebuf.m_130277_();
               braindebugrenderer$braindump.f_113307_.add(s8);
            }

            this.f_104888_.f_91064_.f_113425_.m_113219_(braindebugrenderer$braindump);
         } else if (ClientboundCustomPayloadPacket.f_132024_.equals(resourcelocation)) {
            double d1 = friendlybytebuf.readDouble();
            double d3 = friendlybytebuf.readDouble();
            double d5 = friendlybytebuf.readDouble();
            Position position1 = new PositionImpl(d1, d3, d5);
            UUID uuid1 = friendlybytebuf.m_130259_();
            int k6 = friendlybytebuf.readInt();
            BlockPos blockpos9 = friendlybytebuf.m_236868_(FriendlyByteBuf::m_130135_);
            BlockPos blockpos10 = friendlybytebuf.m_236868_(FriendlyByteBuf::m_130135_);
            int l6 = friendlybytebuf.readInt();
            Path path2 = friendlybytebuf.m_236868_(Path::m_77390_);
            BeeDebugRenderer.BeeInfo beedebugrenderer$beeinfo = new BeeDebugRenderer.BeeInfo(uuid1, k6, position1, path2, blockpos9, blockpos10, l6);
            int i7 = friendlybytebuf.m_130242_();

            for(int j7 = 0; j7 < i7; ++j7) {
               String s12 = friendlybytebuf.m_130277_();
               beedebugrenderer$beeinfo.f_113164_.add(s12);
            }

            int k7 = friendlybytebuf.m_130242_();

            for(int l7 = 0; l7 < k7; ++l7) {
               BlockPos blockpos11 = friendlybytebuf.m_130135_();
               beedebugrenderer$beeinfo.f_113165_.add(blockpos11);
            }

            this.f_104888_.f_91064_.f_113427_.m_113066_(beedebugrenderer$beeinfo);
         } else if (ClientboundCustomPayloadPacket.f_132025_.equals(resourcelocation)) {
            BlockPos blockpos6 = friendlybytebuf.m_130135_();
            String s10 = friendlybytebuf.m_130277_();
            int i5 = friendlybytebuf.readInt();
            int k5 = friendlybytebuf.readInt();
            boolean flag2 = friendlybytebuf.readBoolean();
            BeeDebugRenderer.HiveInfo beedebugrenderer$hiveinfo = new BeeDebugRenderer.HiveInfo(blockpos6, s10, i5, k5, flag2, this.f_104889_.m_46467_());
            this.f_104888_.f_91064_.f_113427_.m_113071_(beedebugrenderer$hiveinfo);
         } else if (ClientboundCustomPayloadPacket.f_132027_.equals(resourcelocation)) {
            this.f_104888_.f_91064_.f_113430_.m_5630_();
         } else if (ClientboundCustomPayloadPacket.f_132026_.equals(resourcelocation)) {
            BlockPos blockpos7 = friendlybytebuf.m_130135_();
            int k3 = friendlybytebuf.readInt();
            String s11 = friendlybytebuf.m_130277_();
            int l5 = friendlybytebuf.readInt();
            this.f_104888_.f_91064_.f_113430_.m_113524_(blockpos7, k3, s11, l5);
         } else if (ClientboundCustomPayloadPacket.f_178832_.equals(resourcelocation)) {
            GameEvent gameevent = BuiltInRegistries.f_256726_.m_7745_(new ResourceLocation(friendlybytebuf.m_130277_()));
            Vec3 vec3 = new Vec3(friendlybytebuf.readDouble(), friendlybytebuf.readDouble(), friendlybytebuf.readDouble());
            this.f_104888_.f_91064_.f_173815_.m_234513_(gameevent, vec3);
         } else if (ClientboundCustomPayloadPacket.f_178833_.equals(resourcelocation)) {
            ResourceLocation resourcelocation1 = friendlybytebuf.m_130281_();
            PositionSource positionsource = BuiltInRegistries.f_256972_.m_6612_(resourcelocation1).orElseThrow(() -> {
               return new IllegalArgumentException("Unknown position source type " + resourcelocation1);
            }).m_142281_(friendlybytebuf);
            int j5 = friendlybytebuf.m_130242_();
            this.f_104888_.f_91064_.f_173815_.m_173830_(positionsource, j5);
         } else {
            f_104883_.warn("Unknown custom packed identifier: {}", (Object)resourcelocation);
         }
      } finally {
         if (friendlybytebuf != null) {
            friendlybytebuf.release();
         }

      }

   }

   public void m_7957_(ClientboundSetObjectivePacket p_105100_) {
      PacketUtils.m_131363_(p_105100_, this, this.f_104888_);
      Scoreboard scoreboard = this.f_104889_.m_6188_();
      String s = p_105100_.m_133266_();
      if (p_105100_.m_133270_() == 0) {
         scoreboard.m_83436_(s, ObjectiveCriteria.f_83588_, p_105100_.m_133269_(), p_105100_.m_133271_());
      } else if (scoreboard.m_83459_(s)) {
         Objective objective = scoreboard.m_83477_(s);
         if (p_105100_.m_133270_() == 1) {
            scoreboard.m_83502_(objective);
         } else if (p_105100_.m_133270_() == 2) {
            objective.m_83314_(p_105100_.m_133271_());
            objective.m_83316_(p_105100_.m_133269_());
         }
      }

   }

   public void m_7519_(ClientboundSetScorePacket p_105106_) {
      PacketUtils.m_131363_(p_105106_, this, this.f_104888_);
      Scoreboard scoreboard = this.f_104889_.m_6188_();
      String s = p_105106_.m_133342_();
      switch (p_105106_.m_133344_()) {
         case CHANGE:
            Objective objective = scoreboard.m_83469_(s);
            Score score = scoreboard.m_83471_(p_105106_.m_133339_(), objective);
            score.m_83402_(p_105106_.m_133343_());
            break;
         case REMOVE:
            scoreboard.m_83479_(p_105106_.m_133339_(), scoreboard.m_83477_(s));
      }

   }

   public void m_5556_(ClientboundSetDisplayObjectivePacket p_105086_) {
      PacketUtils.m_131363_(p_105086_, this, this.f_104888_);
      Scoreboard scoreboard = this.f_104889_.m_6188_();
      String s = p_105086_.m_133142_();
      Objective objective = s == null ? null : scoreboard.m_83469_(s);
      scoreboard.m_7136_(p_105086_.m_133139_(), objective);
   }

   public void m_5582_(ClientboundSetPlayerTeamPacket p_105104_) {
      PacketUtils.m_131363_(p_105104_, this, this.f_104888_);
      Scoreboard scoreboard = this.f_104889_.m_6188_();
      ClientboundSetPlayerTeamPacket.Action clientboundsetplayerteampacket$action = p_105104_.m_179338_();
      PlayerTeam playerteam;
      if (clientboundsetplayerteampacket$action == ClientboundSetPlayerTeamPacket.Action.ADD) {
         playerteam = scoreboard.m_83492_(p_105104_.m_133311_());
      } else {
         playerteam = scoreboard.m_83489_(p_105104_.m_133311_());
         if (playerteam == null) {
            f_104883_.warn("Received packet for unknown team {}: team action: {}, player action: {}", p_105104_.m_133311_(), p_105104_.m_179338_(), p_105104_.m_179335_());
            return;
         }
      }

      Optional<ClientboundSetPlayerTeamPacket.Parameters> optional = p_105104_.m_179339_();
      optional.ifPresent((p_233670_) -> {
         playerteam.m_83353_(p_233670_.m_179363_());
         playerteam.m_83351_(p_233670_.m_179367_());
         playerteam.m_83342_(p_233670_.m_179366_());
         Team.Visibility team$visibility = Team.Visibility.m_83579_(p_233670_.m_179368_());
         if (team$visibility != null) {
            playerteam.m_83346_(team$visibility);
         }

         Team.CollisionRule team$collisionrule = Team.CollisionRule.m_83555_(p_233670_.m_179369_());
         if (team$collisionrule != null) {
            playerteam.m_83344_(team$collisionrule);
         }

         playerteam.m_83360_(p_233670_.m_179370_());
         playerteam.m_83365_(p_233670_.m_179371_());
      });
      ClientboundSetPlayerTeamPacket.Action clientboundsetplayerteampacket$action1 = p_105104_.m_179335_();
      if (clientboundsetplayerteampacket$action1 == ClientboundSetPlayerTeamPacket.Action.ADD) {
         for(String s : p_105104_.m_133315_()) {
            scoreboard.m_6546_(s, playerteam);
         }
      } else if (clientboundsetplayerteampacket$action1 == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
         for(String s1 : p_105104_.m_133315_()) {
            scoreboard.m_6519_(s1, playerteam);
         }
      }

      if (clientboundsetplayerteampacket$action == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
         scoreboard.m_83475_(playerteam);
      }

   }

   public void m_7406_(ClientboundLevelParticlesPacket p_105026_) {
      PacketUtils.m_131363_(p_105026_, this, this.f_104888_);
      if (p_105026_.m_132321_() == 0) {
         double d0 = (double)(p_105026_.m_132320_() * p_105026_.m_132317_());
         double d2 = (double)(p_105026_.m_132320_() * p_105026_.m_132318_());
         double d4 = (double)(p_105026_.m_132320_() * p_105026_.m_132319_());

         try {
            this.f_104889_.m_6493_(p_105026_.m_132322_(), p_105026_.m_132311_(), p_105026_.m_132314_(), p_105026_.m_132315_(), p_105026_.m_132316_(), d0, d2, d4);
         } catch (Throwable throwable1) {
            f_104883_.warn("Could not spawn particle effect {}", (Object)p_105026_.m_132322_());
         }
      } else {
         for(int i = 0; i < p_105026_.m_132321_(); ++i) {
            double d1 = this.f_104898_.m_188583_() * (double)p_105026_.m_132317_();
            double d3 = this.f_104898_.m_188583_() * (double)p_105026_.m_132318_();
            double d5 = this.f_104898_.m_188583_() * (double)p_105026_.m_132319_();
            double d6 = this.f_104898_.m_188583_() * (double)p_105026_.m_132320_();
            double d7 = this.f_104898_.m_188583_() * (double)p_105026_.m_132320_();
            double d8 = this.f_104898_.m_188583_() * (double)p_105026_.m_132320_();

            try {
               this.f_104889_.m_6493_(p_105026_.m_132322_(), p_105026_.m_132311_(), p_105026_.m_132314_() + d1, p_105026_.m_132315_() + d3, p_105026_.m_132316_() + d5, d6, d7, d8);
            } catch (Throwable throwable) {
               f_104883_.warn("Could not spawn particle effect {}", (Object)p_105026_.m_132322_());
               return;
            }
         }
      }

   }

   public void m_141955_(ClientboundPingPacket p_171769_) {
      PacketUtils.m_131363_(p_171769_, this, this.f_104888_);
      this.m_104955_(new ServerboundPongPacket(p_171769_.m_179025_()));
   }

   public void m_7710_(ClientboundUpdateAttributesPacket p_105128_) {
      PacketUtils.m_131363_(p_105128_, this, this.f_104888_);
      Entity entity = this.f_104889_.m_6815_(p_105128_.m_133588_());
      if (entity != null) {
         if (!(entity instanceof LivingEntity)) {
            throw new IllegalStateException("Server tried to update attributes of a non-living entity (actually: " + entity + ")");
         } else {
            AttributeMap attributemap = ((LivingEntity)entity).m_21204_();

            for(ClientboundUpdateAttributesPacket.AttributeSnapshot clientboundupdateattributespacket$attributesnapshot : p_105128_.m_133591_()) {
               AttributeInstance attributeinstance = attributemap.m_22146_(clientboundupdateattributespacket$attributesnapshot.m_133601_());
               if (attributeinstance == null) {
                  f_104883_.warn("Entity {} does not have attribute {}", entity, BuiltInRegistries.f_256951_.m_7981_(clientboundupdateattributespacket$attributesnapshot.m_133601_()));
               } else {
                  attributeinstance.m_22100_(clientboundupdateattributespacket$attributesnapshot.m_133602_());
                  attributeinstance.m_22132_();

                  for(AttributeModifier attributemodifier : clientboundupdateattributespacket$attributesnapshot.m_133603_()) {
                     attributeinstance.m_22118_(attributemodifier);
                  }
               }
            }

         }
      }
   }

   public void m_7339_(ClientboundPlaceGhostRecipePacket p_105046_) {
      PacketUtils.m_131363_(p_105046_, this, this.f_104888_);
      AbstractContainerMenu abstractcontainermenu = this.f_104888_.f_91074_.f_36096_;
      if (abstractcontainermenu.f_38840_ == p_105046_.m_132658_()) {
         this.f_104900_.m_44043_(p_105046_.m_132655_()).ifPresent((p_233667_) -> {
            if (this.f_104888_.f_91080_ instanceof RecipeUpdateListener) {
               RecipeBookComponent recipebookcomponent = ((RecipeUpdateListener)this.f_104888_.f_91080_).m_5564_();
               recipebookcomponent.m_7173_(p_233667_, abstractcontainermenu.f_38839_);
            }

         });
      }
   }

   public void m_183514_(ClientboundLightUpdatePacket p_194243_) {
      PacketUtils.m_131363_(p_194243_, this, this.f_104888_);
      int i = p_194243_.m_132349_();
      int j = p_194243_.m_132352_();
      ClientboundLightUpdatePacketData clientboundlightupdatepacketdata = p_194243_.m_195722_();
      this.f_104889_.m_194171_(() -> {
         this.m_194248_(i, j, clientboundlightupdatepacketdata);
      });
   }

   private void m_194248_(int p_194249_, int p_194250_, ClientboundLightUpdatePacketData p_194251_) {
      LevelLightEngine levellightengine = this.f_104889_.m_7726_().m_7827_();
      BitSet bitset = p_194251_.m_195740_();
      BitSet bitset1 = p_194251_.m_195751_();
      Iterator<byte[]> iterator = p_194251_.m_195754_().iterator();
      this.m_171734_(p_194249_, p_194250_, levellightengine, LightLayer.SKY, bitset, bitset1, iterator);
      BitSet bitset2 = p_194251_.m_195757_();
      BitSet bitset3 = p_194251_.m_195758_();
      Iterator<byte[]> iterator1 = p_194251_.m_195759_().iterator();
      this.m_171734_(p_194249_, p_194250_, levellightengine, LightLayer.BLOCK, bitset2, bitset3, iterator1);
      levellightengine.m_9335_(new ChunkPos(p_194249_, p_194250_), true);
   }

   public void m_7330_(ClientboundMerchantOffersPacket p_105034_) {
      PacketUtils.m_131363_(p_105034_, this, this.f_104888_);
      AbstractContainerMenu abstractcontainermenu = this.f_104888_.f_91074_.f_36096_;
      if (p_105034_.m_132468_() == abstractcontainermenu.f_38840_ && abstractcontainermenu instanceof MerchantMenu merchantmenu) {
         merchantmenu.m_40046_(new MerchantOffers(p_105034_.m_132471_().m_45388_()));
         merchantmenu.m_40066_(p_105034_.m_132473_());
         merchantmenu.m_40069_(p_105034_.m_132472_());
         merchantmenu.m_40048_(p_105034_.m_132474_());
         merchantmenu.m_40058_(p_105034_.m_132475_());
      }

   }

   public void m_7299_(ClientboundSetChunkCacheRadiusPacket p_105082_) {
      PacketUtils.m_131363_(p_105082_, this, this.f_104888_);
      this.f_104897_ = p_105082_.m_133108_();
      this.f_104888_.f_91066_.m_193770_(this.f_104897_);
      this.f_104889_.m_7726_().m_104416_(p_105082_.m_133108_());
   }

   public void m_183623_(ClientboundSetSimulationDistancePacket p_194245_) {
      PacketUtils.m_131363_(p_194245_, this, this.f_104888_);
      this.f_194190_ = p_194245_.f_195796_();
      this.f_104889_.m_194174_(this.f_194190_);
   }

   public void m_8065_(ClientboundSetChunkCacheCenterPacket p_105080_) {
      PacketUtils.m_131363_(p_105080_, this, this.f_104888_);
      this.f_104889_.m_7726_().m_104459_(p_105080_.m_133094_(), p_105080_.m_133097_());
   }

   public void m_214108_(ClientboundBlockChangedAckPacket p_233698_) {
      PacketUtils.m_131363_(p_233698_, this, this.f_104888_);
      this.f_104889_.m_233651_(p_233698_.f_237578_());
   }

   public void m_264308_(ClientboundBundlePacket p_265195_) {
      PacketUtils.m_131363_(p_265195_, this, this.f_104888_);

      for(Packet<ClientGamePacketListener> packet : p_265195_.m_264216_()) {
         packet.m_5797_(this);
      }

   }

   private void m_171734_(int p_171735_, int p_171736_, LevelLightEngine p_171737_, LightLayer p_171738_, BitSet p_171739_, BitSet p_171740_, Iterator<byte[]> p_171741_) {
      for(int i = 0; i < p_171737_.m_164446_(); ++i) {
         int j = p_171737_.m_164447_() + i;
         boolean flag = p_171739_.get(i);
         boolean flag1 = p_171740_.get(i);
         if (flag || flag1) {
            p_171737_.m_284126_(p_171738_, SectionPos.m_123173_(p_171735_, j, p_171736_), flag ? new DataLayer((byte[])p_171741_.next().clone()) : new DataLayer());
            this.f_104889_.m_104793_(p_171735_, j, p_171736_);
         }
      }

   }

   public Connection m_104910_() {
      return this.f_104885_;
   }

   public boolean m_6198_() {
      return this.f_104885_.m_129536_();
   }

   public Collection<PlayerInfo> m_246170_() {
      return this.f_244156_;
   }

   public Collection<PlayerInfo> m_105142_() {
      return this.f_104892_.values();
   }

   public Collection<UUID> m_105143_() {
      return this.f_104892_.keySet();
   }

   @Nullable
   public PlayerInfo m_104949_(UUID p_104950_) {
      return this.f_104892_.get(p_104950_);
   }

   @Nullable
   public PlayerInfo m_104938_(String p_104939_) {
      for(PlayerInfo playerinfo : this.f_104892_.values()) {
         if (playerinfo.m_105312_().getName().equals(p_104939_)) {
            return playerinfo;
         }
      }

      return null;
   }

   public GameProfile m_105144_() {
      return this.f_104886_;
   }

   public ClientAdvancements m_105145_() {
      return this.f_104893_;
   }

   public CommandDispatcher<SharedSuggestionProvider> m_105146_() {
      return this.f_104899_;
   }

   public ClientLevel m_105147_() {
      return this.f_104889_;
   }

   public DebugQueryHandler m_105149_() {
      return this.f_104896_;
   }

   public UUID m_105150_() {
      return this.f_104901_;
   }

   public Set<ResourceKey<Level>> m_105151_() {
      return this.f_104902_;
   }

   public RegistryAccess m_105152_() {
      return this.f_104903_.m_247579_();
   }

   public void m_242011_(PlayerChatMessage p_242356_, boolean p_242455_) {
      MessageSignature messagesignature = p_242356_.f_244279_();
      if (messagesignature != null && this.f_244346_.m_245220_(messagesignature, p_242455_) && this.f_244346_.m_245480_() > 64) {
         this.m_247711_();
      }

   }

   private void m_247711_() {
      int i = this.f_244346_.m_245313_();
      if (i > 0) {
         this.m_104955_(new ServerboundChatAckPacket(i));
      }

   }

   public void m_246175_(String p_249888_) {
      Instant instant = Instant.now();
      long i = Crypt.SaltSupplier.m_216113_();
      LastSeenMessagesTracker.Update lastseenmessagestracker$update = this.f_244346_.m_246442_();
      MessageSignature messagesignature = this.f_240902_.m_240988_(new SignedMessageBody(p_249888_, instant, i, lastseenmessagestracker$update.f_243872_()));
      this.m_104955_(new ServerboundChatPacket(p_249888_, instant, i, messagesignature, lastseenmessagestracker$update.f_244473_()));
   }

   public void m_246623_(String p_250092_) {
      Instant instant = Instant.now();
      long i = Crypt.SaltSupplier.m_216113_();
      LastSeenMessagesTracker.Update lastseenmessagestracker$update = this.f_244346_.m_246442_();
      ArgumentSignatures argumentsignatures = ArgumentSignatures.m_245158_(SignableCommand.m_246497_(this.m_245186_(p_250092_)), (p_247875_) -> {
         SignedMessageBody signedmessagebody = new SignedMessageBody(p_247875_, instant, i, lastseenmessagestracker$update.f_243872_());
         return this.f_240902_.m_240988_(signedmessagebody);
      });
      this.m_104955_(new ServerboundChatCommandPacket(p_250092_, instant, i, argumentsignatures, lastseenmessagestracker$update.f_244473_()));
   }

   public boolean m_246979_(String p_251509_) {
      if (SignableCommand.m_246497_(this.m_245186_(p_251509_)).f_244150_().isEmpty()) {
         LastSeenMessagesTracker.Update lastseenmessagestracker$update = this.f_244346_.m_246442_();
         this.m_104955_(new ServerboundChatCommandPacket(p_251509_, Instant.now(), 0L, ArgumentSignatures.f_240907_, lastseenmessagestracker$update.f_244473_()));
         return true;
      } else {
         return false;
      }
   }

   private ParseResults<SharedSuggestionProvider> m_245186_(String p_249982_) {
      return this.f_104899_.parse(p_249982_, this.f_104894_);
   }

   public void m_9933_() {
      if (this.f_104885_.m_129535_()) {
         ProfileKeyPairManager profilekeypairmanager = this.f_104888_.m_231465_();
         if (profilekeypairmanager.m_253130_()) {
            profilekeypairmanager.m_252904_().thenAcceptAsync((p_253339_) -> {
               p_253339_.ifPresent(this::m_260951_);
            }, this.f_104888_);
         }
      }

      this.m_269212_();
      this.f_194191_.m_261056_();
   }

   public void m_260951_(ProfileKeyPair p_261475_) {
      if (this.f_104886_.getId().equals(this.f_104888_.m_91094_().m_240411_())) {
         if (this.f_252517_ == null || !this.f_252517_.f_243926_().equals(p_261475_)) {
            this.f_252517_ = LocalChatSession.m_245157_(p_261475_);
            this.f_240902_ = this.f_252517_.m_247507_(this.f_104886_.getId());
            this.m_104955_(new ServerboundChatSessionUpdatePacket(this.f_252517_.m_245584_().m_245986_()));
         }
      }
   }

   @Nullable
   public ServerData m_245416_() {
      return this.f_244115_;
   }

   public FeatureFlagSet m_247016_() {
      return this.f_244039_;
   }

   public boolean m_246351_(FeatureFlagSet p_250605_) {
      return p_250605_.m_247715_(this.m_247016_());
   }

   @OnlyIn(Dist.CLIENT)
   static record DeferredPacket(Packet<ServerGamePacketListener> f_268574_, BooleanSupplier f_268477_, long f_268654_) {
   }
}
