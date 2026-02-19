package com.wzz.better_entity_render.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wzz.better_entity_render.Config;
import com.wzz.better_entity_render.EntityOcclusionCuller;
import com.wzz.better_entity_render.ShadowCache;
import com.wzz.better_entity_render.ShadowRendererNative;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Shadow
    private static void renderBlockShadow(
            PoseStack.Pose p_277956_, VertexConsumer p_277533_,
            ChunkAccess p_277501_, LevelReader p_277622_,
            BlockPos p_277911_,
            double p_277682_, double p_278099_, double p_277806_,
            float p_277844_, float p_277496_) {
    }

    @Shadow
    @Final
    private static RenderType SHADOW_RENDER_TYPE;

    /**
     * render() 前检查遮挡，被遮挡直接跳过。
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    public <E extends Entity> void onRender(
            E entity,
            double x, double y, double z,
            float yRot, float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light,
            CallbackInfo ci) {

        if (!Config.OCCLUSION_CULL.get()) return;
        if (entity instanceof net.minecraft.world.entity.player.Player) return;

        Level level = entity.level();
        if (!(level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel)) return;

        if (!EntityOcclusionCuller.isVisible(entity, clientLevel)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
    private static void renderShadow(
            PoseStack poseStack, MultiBufferSource buffers,
            Entity entity, float shadowStrength, float partialTick,
            LevelReader level, float radius,
            CallbackInfo ci) {

        if (!Config.SHADOW_RENDER.get()) return;

        ci.cancel();

        boolean isBaby = entity instanceof Mob mob && mob.isBaby();
        int entityId  = entity.getId();

        // 同一帧同一实体直接复用
        float[] data = ShadowCache.get(entityId);
        if (data == null) {
            data = ShadowRendererNative.computeShadowData(
                    entity.xOld, entity.getX(),
                    entity.yOld, entity.getY(),
                    entity.zOld, entity.getZ(),
                    partialTick, shadowStrength, radius, isBaby
            );
            if (data == null || data.length == 0) return;
            ShadowCache.put(entityId, data);
        }

        int count = Float.floatToRawIntBits(data[0]);
        if (count <= 0) return;

        // 头部参数
        double d2 = data[1];
        double d0 = data[2];
        double d1 = data[3];
        float  f  = data[4];

        PoseStack.Pose       pose     = poseStack.last();
        VertexConsumer       consumer = buffers.getBuffer(SHADOW_RENDER_TYPE);
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        // chunk 列缓存：Z/X 外层循环保证同列 Y 共用同一个 ChunkAccess
        int         lastCX      = Integer.MIN_VALUE;
        int         lastCZ      = Integer.MIN_VALUE;
        ChunkAccess cachedChunk = null;

        for (int idx = 0; idx < count; idx++) {
            int base = 5 + idx * 4;
            int bx   = Float.floatToRawIntBits(data[base]);
            int by   = Float.floatToRawIntBits(data[base + 1]);
            int bz   = Float.floatToRawIntBits(data[base + 2]);
            float f2 = data[base + 3];

            mpos.set(bx, by, bz);

            int cx = bx >> 4, cz = bz >> 4;
            if (cx != lastCX || cz != lastCZ) {
                cachedChunk = level.getChunk(mpos);
                lastCX = cx;
                lastCZ = cz;
            }

            renderBlockShadow(pose, consumer, cachedChunk, level, mpos,
                    d2, d0, d1, f, f2);
        }
    }

    // 相机位置缓存：避免每个实体调用 distanceToSqr 时反复 getPosition()
    @Unique
    private double better_entity_render$cachedCamX;
    @Unique
    private double better_entity_render$cachedCamY;
    @Unique
    private double better_entity_render$cachedCamZ;

    @Inject(method = "prepare", at = @At("TAIL"))
    public void onPrepare(Level level, Camera camera,
                          Entity crosshair, CallbackInfo ci) {
        Vec3 pos  = camera.getPosition();
        better_entity_render$cachedCamX = pos.x();
        better_entity_render$cachedCamY = pos.y();
        better_entity_render$cachedCamZ = pos.z();
        ShadowCache.nextFrame();
        if (!Config.OCCLUSION_CULL.get()) return;
        EntityOcclusionCuller.updateCamera(pos.x(), pos.y(), pos.z());
        EntityOcclusionCuller.flushTasks();
    }

    @Inject(
            method = "distanceToSqr(DDD)D",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onDistanceToSqr(double x, double y, double z,
                                 CallbackInfoReturnable<Double> cir) {
        double dx = better_entity_render$cachedCamX - x;
        double dy = better_entity_render$cachedCamY - y;
        double dz = better_entity_render$cachedCamZ - z;
        cir.setReturnValue(dx * dx + dy * dy + dz * dz);
    }

    @Inject(
            method = "distanceToSqr(Lnet/minecraft/world/entity/Entity;)D",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onDistanceToSqr(Entity entity,
                                 CallbackInfoReturnable<Double> cir) {
        double dx = better_entity_render$cachedCamX - entity.getX();
        double dy = better_entity_render$cachedCamY - entity.getY();
        double dz = better_entity_render$cachedCamZ - entity.getZ();
        cir.setReturnValue(dx * dx + dy * dy + dz * dz);
    }
}