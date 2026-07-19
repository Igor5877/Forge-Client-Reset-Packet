package gg.chaldea.client.reset.packet;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@OnlyIn(Dist.CLIENT)
public class FrozenFrameScreen extends Screen {

    private static final Logger logger = LogManager.getLogger();
    private static final ResourceLocation TEXTURE_LOC = new ResourceLocation("clientresetpacket", "frozen_frame");

    private DynamicTexture texture;
    private boolean hasTexture = false;
    // Safety valve: never hold the frozen frame longer than this, even if the
    // terrain-compiled check below never turns true (mirrors vanilla
    // ReceivingLevelScreen's 30s limit, but shorter - a stuck frame feels worse
    // than a brief void flash).
    private static final long MAX_HOLD_MS = 10_000L;
    private final long createdAt = System.currentTimeMillis();

    private FrozenFrameScreen() {
        super(Component.empty());
    }

    /**
     * Captures the current framebuffer content and returns a screen that renders it.
     * Must be called on the render thread before clearLevel().
     */
    public static FrozenFrameScreen capture(Minecraft mc) {
        FrozenFrameScreen screen = new FrozenFrameScreen();
        try {
            RenderSystem.assertOnRenderThreadOrInit();
            var rt = mc.getMainRenderTarget();
            RenderSystem.bindTexture(rt.getColorTextureId());
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, rt.width, rt.height, false);
            image.downloadTexture(0, false);
            image.flipY();
            screen.texture = new DynamicTexture(image);
            mc.getTextureManager().register(TEXTURE_LOC, screen.texture);
            screen.hasTexture = true;
        } catch (Exception e) {
            logger.warn("[SeamlessTransition] Failed to capture frozen frame, will use black screen: {}", e.getMessage());
        }
        return screen;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!hasTexture) {
            // Fallback: solid black screen
            guiGraphics.fill(0, 0, width, height, 0xFF000000);
            return;
        }

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, TEXTURE_LOC);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // UV Y is not flipped because we already called image.flipY() during capture
        buf.vertex(0,     height, 0).uv(0, 1).endVertex();
        buf.vertex(width, height, 0).uv(1, 1).endVertex();
        buf.vertex(width, 0,      0).uv(1, 0).endVertex();
        buf.vertex(0,     0,      0).uv(0, 0).endVertex();
        tess.end();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        // Still handshaking / no world yet - keep holding the frame.
        if (mc.level == null || mc.player == null) {
            return;
        }
        // Same readiness condition vanilla's ReceivingLevelScreen uses: dismiss
        // only once the chunk section at the player's feet is actually compiled
        // (or the player can't meaningfully wait for one). Dismissing merely on
        // level+player being present dropped the frame ~1s before any terrain
        // existed, exposing the raw void - the opposite of seamless.
        var pos = mc.player.blockPosition();
        boolean terrainReady = mc.level.isOutsideBuildHeight(pos.getY())
                || mc.levelRenderer.isChunkCompiled(pos)
                || mc.player.isSpectator()
                || !mc.player.isAlive();
        if (terrainReady || System.currentTimeMillis() > createdAt + MAX_HOLD_MS) {
            SeamlessTransition.end();
            mc.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        SeamlessTransition.end();
        cleanup();
        super.onClose();
    }

    @Override
    public void removed() {
        // End the transition however this screen is dismissed. checkAndCloseLoadingScreen
        // dismisses us via setScreen(null), which routes through removed() (NOT onClose),
        // so without this the `active` flag leaked true forever and every subsequent
        // chunk packet closed the player's open GUI. end() is idempotent.
        SeamlessTransition.end();
        cleanup();
        super.removed();
    }

    private void cleanup() {
        if (hasTexture) {
            hasTexture = false;
            Minecraft.getInstance().getTextureManager().release(TEXTURE_LOC);
            // DynamicTexture.close() is handled by TextureManager after release
        }
    }
}
