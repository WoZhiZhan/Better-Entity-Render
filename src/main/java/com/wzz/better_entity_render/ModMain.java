package com.wzz.better_entity_render;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(ModMain.MODID)
public class ModMain {

    public static final String MODID = "better_entity_render";
    private static final Logger LOGGER = LogUtils.getLogger();

    @SuppressWarnings("removal")
    public ModMain() {
        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
