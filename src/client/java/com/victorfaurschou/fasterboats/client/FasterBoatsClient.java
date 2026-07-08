package com.victorfaurschou.fasterboats.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.victorfaurschou.fasterboats.FasterBoatsConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

public class FasterBoatsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("faster-boats")
                        .then(ClientCommandManager.literal("enable")
                                .then(ClientCommandManager.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            boolean value = BoolArgumentType.getBool(ctx, "value");
                                            FasterBoatsConfig.enabled = value;
                                            ctx.getSource()
                                                    .sendFeedback(Component.literal("[faster-boats] " + (value ? "enabled" : "disabled")));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("version")
                                .executes(ctx -> {
                                    String version = FabricLoader.getInstance()
                                            .getModContainer("faster-boats")
                                            .map(c -> c.getMetadata().getVersion().getFriendlyString())
                                            .orElse("unknown");
                                    ctx.getSource().sendFeedback(Component.literal("Faster Boats " + version));
                                    return 1;
                                }))));
    }
}
