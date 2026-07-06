package com.victorfaurschou.fasterboats.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

public class FasterBoatsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("faster-boats")
                        .then(ClientCommands.literal("version")
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
