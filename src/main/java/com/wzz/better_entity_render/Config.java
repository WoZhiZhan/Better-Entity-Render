package com.wzz.better_entity_render;

import net.minecraftforge.common.ForgeConfigSpec;

public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue SHADOW_RENDER;

    public static final ForgeConfigSpec.BooleanValue OCCLUSION_CULL;
    public static final ForgeConfigSpec.BooleanValue COLLISION_OPT;
    // 遮挡检测射线步长（格），越小越精确，越大越快
    public static final ForgeConfigSpec.DoubleValue  OCCLUSION_RAY_STEP;

    // 遮挡结果缓存时长（毫秒），越大越省 CPU，越小越准
    public static final ForgeConfigSpec.IntValue     OCCLUSION_CACHE_MS;

    static {
        ForgeConfigSpec.Builder builder = BUILDER;
        builder.push("shadow");
        SHADOW_RENDER = builder
                .comment("Enable native-accelerated shadow rendering")
                .define("enabled", true);
        builder.pop();

        builder.push("occlusion");
        OCCLUSION_CULL = builder
                .comment("Skip rendering entities fully hidden behind solid blocks")
                .define("enabled", true);
        OCCLUSION_RAY_STEP = builder
                .comment("Ray march step size in blocks. Smaller = more accurate, more CPU.")
                .defineInRange("rayStep", 0.5, 0.1, 2.0);
        OCCLUSION_CACHE_MS = builder
                .comment("How long (ms) to cache occlusion results per entity. Higher = less CPU, less accurate.")
                .defineInRange("cacheMs", 100, 16, 500);
        builder.pop();

        builder.push("collision");
        COLLISION_OPT = builder
                .comment("Reduce redundant block collision queries in step-height path")
                .define("enabled", true);
        builder.pop();
    }

    static final ForgeConfigSpec SPEC = BUILDER.build();
}
