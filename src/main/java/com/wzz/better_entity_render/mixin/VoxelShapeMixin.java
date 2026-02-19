package com.wzz.better_entity_render.mixin;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 缓存 VoxelShape.bounds() 的计算结果。
 * 原版每次调用都执行 6 次 shape.firstFull/lastFull + getCoords 查找，
 * 而 VoxelShape 创建后形状不再改变，结果可以永久缓存。
 * bounds() 在碰撞、渲染、阴影计算中频繁调用，
 * 对于常见的 Shapes.block()（完整方块）效果最显著。
 */
@Mixin(VoxelShape.class)
public abstract class VoxelShapeMixin {

    @Unique
    private AABB better_entity_render$cachedBounds = null;

    @Inject(method = "bounds", at = @At("HEAD"), cancellable = true)
    private void onBounds(CallbackInfoReturnable<AABB> cir) {
        if (better_entity_render$cachedBounds != null) {
            cir.setReturnValue(better_entity_render$cachedBounds);
        }
    }

    @Inject(method = "bounds", at = @At("RETURN"))
    private void onBoundsReturn(CallbackInfoReturnable<AABB> cir) {
        if (better_entity_render$cachedBounds == null) {
            better_entity_render$cachedBounds = cir.getReturnValue();
        }
    }
}