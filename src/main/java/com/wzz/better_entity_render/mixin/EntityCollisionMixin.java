package com.wzz.better_entity_render.mixin;

import com.google.common.collect.ImmutableList;
import com.wzz.better_entity_render.Config;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(Entity.class)
public abstract class EntityCollisionMixin {

    @Shadow
    public abstract AABB getBoundingBox();

    @Shadow
    public abstract boolean onGround();

    @Shadow
    public Level level;

    @Shadow private float maxUpStep;

    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void onCollide(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        if (!Config.COLLISION_OPT.get()) return;

        AABB aabb = this.getBoundingBox();
        Entity self = (Entity)(Object)this;

        List<VoxelShape> entityShapes = this.level.getEntityCollisions(self, aabb.expandTowards(movement));

        Vec3 vec3 = movement.lengthSqr() == 0.0 ? movement
                : Entity.collideBoundingBox(self, movement, aabb, this.level, entityShapes);

        boolean xChanged = movement.x != vec3.x;
        boolean yChanged = movement.y != vec3.y;
        boolean zChanged = movement.z != vec3.z;
        boolean onGround  = this.onGround() || (yChanged && movement.y < 0.0);
        float   stepHeight = this.maxUpStep;

        if (stepHeight <= 0.0F || !onGround || (!xChanged && !zChanged)) {
            cir.setReturnValue(vec3);
            return;
        }

        // 计算三次 collideBoundingBox 调用所需的最大 AABB 超集，
        // 只做一次 getBlockCollisions。
        double sx = movement.x, sy = movement.y, sz = movement.z;

        // 三次调用涉及的 movement 向量：
        //   call1: (sx, stepHeight, sz)  on aabb
        //   call2: (0, stepHeight, 0)    on aabb.expandTowards(sx, 0, sz)
        //   call3: (sx, 0, sz)           on aabb.move(vec32)  ← vec32 未知，但 y 方向最多 stepHeight
        // 超集：expandTowards 覆盖所有可能方向
        double maxDx = Math.max(Math.abs(sx), 0.0) + 1.0;
        double maxDy = stepHeight + 1.0;
        double maxDz = Math.max(Math.abs(sz), 0.0) + 1.0;
        AABB superAABB = aabb.inflate(maxDx, maxDy, maxDz);

        Iterable<VoxelShape> blockShapes = this.level.getBlockCollisions(self, superAABB);
        // 合并实体形状和方块形状，后续复用
        ImmutableList.Builder<VoxelShape> allShapesBuilder = ImmutableList.builder();
        allShapesBuilder.addAll(entityShapes);
        for (VoxelShape s : blockShapes) allShapesBuilder.add(s);
        ImmutableList<VoxelShape> allShapes = allShapesBuilder.build();

        WorldBorder border = this.level.getWorldBorder();
        boolean nearBorder = border.isInsideCloseToBorder(self, aabb.expandTowards(movement));

        ImmutableList<VoxelShape> shapesWithBorder;
        if (nearBorder) {
            shapesWithBorder = ImmutableList.<VoxelShape>builder()
                    .addAll(allShapes)
                    .add(border.getCollisionShape())
                    .build();
        } else {
            shapesWithBorder = allShapes;
        }

        Vec3 vec31 = collideWithShapesStatic(new Vec3(sx, stepHeight, sz), aabb, shapesWithBorder);

        Vec3 vec32 = collideWithShapesStatic(
                new Vec3(0.0, stepHeight, 0.0),
                aabb.expandTowards(sx, 0.0, sz),
                shapesWithBorder
        );

        if (vec32.y < stepHeight) {
            Vec3 vec33 = collideWithShapesStatic(
                    new Vec3(sx, 0.0, sz),
                    aabb.move(vec32),
                    shapesWithBorder
            ).add(vec32);

            if (vec33.horizontalDistanceSqr() > vec31.horizontalDistanceSqr()) {
                vec31 = vec33;
            }
        }

        if (vec31.horizontalDistanceSqr() > vec3.horizontalDistanceSqr()) {
            Vec3 yCorrection = collideWithShapesStatic(
                    new Vec3(0.0, -vec31.y + sy, 0.0),
                    aabb.move(vec31),
                    shapesWithBorder
            );
            cir.setReturnValue(vec31.add(yCorrection));
        } else {
            cir.setReturnValue(vec3);
        }
    }

    private static Vec3 collideWithShapesStatic(Vec3 movement, AABB aabb, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) return movement;

        double dx = movement.x;
        double dy = movement.y;
        double dz = movement.z;

        if (dy != 0.0) {
            dy = Shapes.collide(net.minecraft.core.Direction.Axis.Y, aabb, shapes, dy);
            if (dy != 0.0) aabb = aabb.move(0.0, dy, 0.0);
        }

        // 先处理绝对值较小的水平分量
        boolean zFirst = Math.abs(dx) < Math.abs(dz);

        if (zFirst && dz != 0.0) {
            dz = Shapes.collide(net.minecraft.core.Direction.Axis.Z, aabb, shapes, dz);
            if (dz != 0.0) aabb = aabb.move(0.0, 0.0, dz);
        }

        if (dx != 0.0) {
            dx = Shapes.collide(net.minecraft.core.Direction.Axis.X, aabb, shapes, dx);
            if (!zFirst && dx != 0.0) aabb = aabb.move(dx, 0.0, 0.0);
        }

        if (!zFirst && dz != 0.0) {
            dz = Shapes.collide(net.minecraft.core.Direction.Axis.Z, aabb, shapes, dz);
        }

        return new Vec3(dx, dy, dz);
    }
}