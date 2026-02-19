package com.wzz.better_entity_render;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "better_entity_render", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientEventHandler {

    @SubscribeEvent
    public static void onGameShutdown(GameShuttingDownEvent event) {
        EntityOcclusionCuller.shutdown();
    }
}