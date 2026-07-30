package gg.chaldea.server.fastlogin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

public class FastLoginCommands {

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("fastlogin")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("sendtiming")
                .executes(ctx -> {
                    boolean current = FastLoginConfig.SEND_TIMING_ENABLED.get();
                    ctx.getSource().sendSuccess(
                        () -> Component.literal("[FastLogin] sendTiming is currently " + current), false);
                    return 1;
                })
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(ctx -> {
                        boolean value = BoolArgumentType.getBool(ctx, "enabled");
                        FastLoginConfig.SEND_TIMING_ENABLED.set(value);
                        FastLoginConfig.SEND_TIMING_ENABLED.save();
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("[FastLogin] sendTiming set to " + value), true);
                        return 1;
                    }))));
    }
}
