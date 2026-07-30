package gg.chaldea.server.fastlogin;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class FastLoginConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue SEND_TIMING_ENABLED;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("debug");
        SEND_TIMING_ENABLED = builder
            .comment(
                "Log every key packet (JoinGame/FirstChunk/UpdateRecipes/UpdateTags/PlayerPosition) with a",
                "timing delta from the player's last login. Diagnostic only — once a player has been connected",
                "for a while, every routine PlayerPosition correction gets logged with a large, meaningless delta",
                "(minutes since login, not actual lag). Off by default; toggle live with /fastlogin sendtiming <true|false>.")
            .define("sendTimingEnabled", false);
        builder.pop();
        SPEC = builder.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "fastlogin-common.toml");
    }
}
