package com.wzz.better_entity_render.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wzz.better_entity_render.Config;
import com.wzz.better_entity_render.EntityRenderLOD;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 远处实体降精度（连续、无闪烁），三层手段：
 *   1. 几何部件裁剪：render HEAD 隐藏次要部件，RETURN 还原。
 *   2. 冻结动画：@Redirect 掉 render 内对 model.setupAnim 的调用，远处不执行。
 *   3. 跳过高开销附加层：@Redirect 掉 render 内对 layer.render 的调用，远处不执行。
 * 距离平方在 HEAD 算一次，缓存到字段供两个 @Redirect 复用（渲染在主线程串行，
 * 每个实体走完整 HEAD→...→RETURN 流程，字段不会被并发污染）。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {

    @Shadow
    protected M model;

    // 本帧本实体被裁掉的部件，RETURN 时还原
    @Unique
    private List<ModelPart> better_entity_render$culledParts;

    // 本帧本实体到相机的距离平方；MAX_VALUE 表示 LOD 未启用/无法取相机
    @Unique
    private double better_entity_render$distSq;

    @Inject(method = "render*", at = @At("HEAD"))
    private void better_entity_render$lodHead(
            T entity, float entityYaw, float partialTicks,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight,
            CallbackInfo ci) {

        better_entity_render$culledParts = null;
        better_entity_render$distSq = Double.MAX_VALUE;

        if (!Config.LOD_ENABLED.get()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double dx = cam.x - entity.getX();
        double dy = cam.y - entity.getY();
        double dz = cam.z - entity.getZ();
        better_entity_render$distSq = dx * dx + dy * dy + dz * dz;

        // 几何部件裁剪
        if (EntityRenderLOD.shouldCullParts(entity, better_entity_render$distSq)) {
            better_entity_render$culledParts = EntityRenderLOD.cullMinorParts(this.model);
        }
    }

    @Inject(method = "render*", at = @At("RETURN"))
    private void better_entity_render$lodReturn(
            T entity, float entityYaw, float partialTicks,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight,
            CallbackInfo ci) {
        // 还原部件，避免污染同模型的近处实体
        EntityRenderLOD.restoreParts(better_entity_render$culledParts);
        better_entity_render$culledParts = null;
    }

    /**
     * 冻结动画：远处直接不调用 setupAnim，模型保持上次/默认姿势。
     * 只匹配 LivingEntityRenderer.render 方法体内的那一次 setupAnim 调用。
     */
    @Redirect(
            method = "render*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"
            )
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void better_entity_render$redirectSetupAnim(
            EntityModel instance,
            net.minecraft.world.entity.Entity e,
            float limbSwing, float limbSwingAmount, float ageInTicks,
            float netHeadYaw, float headPitch) {

        if (better_entity_render$distSq != Double.MAX_VALUE
                && e instanceof LivingEntity le
                && EntityRenderLOD.shouldFreezeAnim(le, better_entity_render$distSq)) {
            return; // 跳过动画
        }
        instance.setupAnim(e, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
    }

//    /**
//     * 跳过高开销附加层：远处不调用任何 layer.render（装备、发光眼、鞍、披风等）。
//     * &#064;Redirect  接管 render 内 for 循环里对 RenderLayer.render 的每次调用。
//     */
//    @Redirect(
//            method = "render*",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"
//            )
//    )
//    private void better_entity_render$redirectLayerRender(
//            RenderLayer<T, M> layer,
//            PoseStack poseStack, MultiBufferSource buffer, int packedLight,
//            T entity, float limbSwing, float limbSwingAmount,
//            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
//
//        if (better_entity_render$distSq != Double.MAX_VALUE
//                && EntityRenderLOD.shouldSkipEffects(entity, better_entity_render$distSq)) {
//            return; // 跳过该附加层
//        }
//        layer.render(poseStack, buffer, packedLight, entity,
//                limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
//    }
}
