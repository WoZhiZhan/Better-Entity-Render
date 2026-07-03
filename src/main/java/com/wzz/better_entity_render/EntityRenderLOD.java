package com.wzz.better_entity_render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 远处实体降精度（连续、无闪烁）。
 *
 * 三层手段，各自独立的距离阈值，从近到远逐层叠加：
 *
 *   1. 几何部件裁剪（cullParts）—— 真·降精度，减少提交的 cube / 顶点。
 *      远处隐藏次要 ModelPart：玩家/人形怪的第二层皮肤（帽子层 hat、外套 jacket、
 *      袖子 sleeve、裤腿 pants、耳朵 ear）。这些层在远处只占亚像素，省掉无视觉损失。
 *      仅对 HumanoidModel / PlayerModel 生效（覆盖玩家 + 僵尸/骷髅/村民等绝大多数
 *      人形实体）；其它模型不裁部件，只享受停动画与禁特效，保证不会把关键部件
 *      误关导致实体变形。
 *
 *   2. 冻结动画（freezeAnim）—— 省 CPU。远处跳过 setupAnim 的摆动/插值，静态姿势。
 *
 *   3. 跳过高开销渲染层（skipEffects）—— 省 GPU。远处禁用发光/自发光轮廓等额外 pass。
 *
 * 距离一律用相机到实体的距离平方比较，省去开方。
 */
public final class EntityRenderLOD {

    private EntityRenderLOD() {}

    private static boolean exempt(Entity entity) {
        return Config.LOD_SKIP_PLAYERS.get() && entity instanceof Player;
    }

    private static double sq(double d) {
        return d * d;
    }

    /** 是否冻结动画（跳过 setupAnim）。 */
    public static boolean shouldFreezeAnim(Entity entity, double distanceSq) {
        if (!Config.LOD_ENABLED.get() || !Config.LOD_FREEZE_ANIM.get()) return false;
        if (exempt(entity)) return false;
        return distanceSq >= sq(Config.LOD_ANIM_DISTANCE.get());
    }

    /** 是否跳过高开销渲染层。 */
    public static boolean shouldSkipEffects(Entity entity, double distanceSq) {
        if (!Config.LOD_ENABLED.get() || !Config.LOD_SKIP_EFFECTS.get()) return false;
        if (exempt(entity)) return false;
        return distanceSq >= sq(Config.LOD_EFFECT_DISTANCE.get());
    }

    /** 是否裁剪次要几何部件。 */
    public static boolean shouldCullParts(Entity entity, double distanceSq) {
        if (!Config.LOD_ENABLED.get() || !Config.LOD_CULL_PARTS.get()) return false;
        if (exempt(entity)) return false;
        return distanceSq >= sq(Config.LOD_DETAIL_DISTANCE.get());
    }

    /**
     * 隐藏模型的次要部件（第二层）。
     * 用 skipDraw 而非 visible：skipDraw 只跳过该部件自身的 cube 提交，
     * 但保留其子部件渲染——对第二层这种叶子部件二者等价，且 skipDraw 不影响
     * 其它 mod/层对 visible 的逻辑判断，副作用更小。
     *
     * @return 本次被改动的部件列表，渲染后必须调用 {@link #restoreParts} 还原
     *         （模型实例在同类型实体间共享，不还原会污染近处实体）
     */
    public static List<ModelPart> cullMinorParts(Model model) {
        if (!(model instanceof HumanoidModel<?> humanoid)) {
            return null;
        }
        List<ModelPart> changed = new ArrayList<>(6);

        // PlayerModel 的玩家专属第二层（均为 public 字段；cloak/ear 是 private 不可访问）
        if (model instanceof PlayerModel<?> pm) {
            hide(pm.hat,          changed);
            hide(pm.jacket,       changed);
            hide(pm.leftSleeve,   changed);
            hide(pm.rightSleeve,  changed);
            hide(pm.leftPants,    changed);
            hide(pm.rightPants,   changed);
        } else {
            // 通用人形模型的帽子层（僵尸/骷髅/村民/盔甲架等）
            hide(humanoid.hat, changed);
        }

        return changed.isEmpty() ? null : changed;
    }

    private static void hide(ModelPart part, List<ModelPart> changed) {
        if (part != null && !part.skipDraw) {
            part.skipDraw = true;
            changed.add(part);
        }
    }

    /** 还原被隐藏的部件。 */
    public static void restoreParts(List<ModelPart> changed) {
        if (changed == null) return;
        for (int i = 0; i < changed.size(); i++) {
            changed.get(i).skipDraw = false;
        }
    }
}
