package com.wzz.better_entity_render;

import net.minecraftforge.common.ForgeConfigSpec;

public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue SHADOW_RENDER;
    public static final ForgeConfigSpec.BooleanValue OCCLUSION_CULL;
    public static final ForgeConfigSpec.BooleanValue COLLISION_OPT;
    public static final ForgeConfigSpec.DoubleValue MAX_OCCLUSION_DISTANCE;

    // ---- 远处实体降精度（几何裁剪 + 停动画 + 禁高开销层） ----
    public static final ForgeConfigSpec.BooleanValue LOD_ENABLED;
    public static final ForgeConfigSpec.DoubleValue LOD_DETAIL_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue LOD_ANIM_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue LOD_EFFECT_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue LOD_CULL_PARTS;
    public static final ForgeConfigSpec.BooleanValue LOD_FREEZE_ANIM;
    public static final ForgeConfigSpec.BooleanValue LOD_SKIP_EFFECTS;
    public static final ForgeConfigSpec.BooleanValue LOD_SKIP_PLAYERS;

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
        MAX_OCCLUSION_DISTANCE = builder
                .comment("Maximum distance (in blocks) for occlusion detection. " +
                        "Entities beyond this distance will be rendered without occlusion check. " +
                        "Set to -1 for infinite (check all entities).")
                .defineInRange("maxDistance", -1.0, -1.0, 512.0);
        builder.pop();

        builder.push("collision");
        COLLISION_OPT = builder
                .comment("Reduce redundant block collision queries in step-height path")
                .define("enabled", true);
        builder.pop();

        builder.push("lod");
        LOD_ENABLED = builder
                .comment("Reduce render precision for distant entities (continuous, no flicker):",
                         "cull minor model parts, freeze animation, and skip expensive render layers.")
                .define("enabled", true);
        LOD_CULL_PARTS = builder
                .comment("Hide minor model parts (overlay/2nd-layer, hats, ears, etc.) on distant entities.")
                .define("cullParts", true);
        LOD_DETAIL_DISTANCE = builder
                .comment("Distance (blocks) beyond which minor model parts are hidden.")
                .defineInRange("detailDistance", 32.0, 0.0, 512.0);
        LOD_FREEZE_ANIM = builder
                .comment("Skip animation/pose setup (setupAnim) on distant entities to save CPU.")
                .define("freezeAnim", true);
        LOD_ANIM_DISTANCE = builder
                .comment("Distance (blocks) beyond which animation is frozen.")
                .defineInRange("animDistance", 48.0, 0.0, 512.0);
        LOD_SKIP_EFFECTS = builder
                .comment("Skip expensive render layers (glow/emissive outline, etc.) on distant entities.")
                .define("skipEffects", true);
        LOD_EFFECT_DISTANCE = builder
                .comment("Distance (blocks) beyond which expensive render layers are skipped.")
                .defineInRange("effectDistance", 24.0, 0.0, 512.0);
        LOD_SKIP_PLAYERS = builder
                .comment("If true, players are exempt from all LOD and always rendered at full detail.")
                .define("skipPlayers", true);
        builder.pop();
    }

    static final ForgeConfigSpec SPEC = BUILDER.build();
}
