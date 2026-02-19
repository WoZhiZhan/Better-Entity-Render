package com.wzz.better_entity_render;

import net.minecraftforge.common.ForgeConfigSpec;

public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue SHADOW_RENDER;

    public static final ForgeConfigSpec.BooleanValue OCCLUSION_CULL;
    public static final ForgeConfigSpec.BooleanValue COLLISION_OPT;

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
        builder.pop();

        builder.push("collision");
        COLLISION_OPT = builder
                .comment("Reduce redundant block collision queries in step-height path")
                .define("enabled", true);
        builder.pop();
    }

    static final ForgeConfigSpec SPEC = BUILDER.build();
}
