package com.wzz.better_entity_render;

import com.wzz.better_entity_render.util.LoaderUtil;

public final class ShadowRendererNative {
    static {
        LoaderUtil.load("better_entity_render");
    }

    /**
     * 一次性计算阴影所需全部数据。
     * 返回 float[] 布局：
     *   [0]         = count（Float.intBitsToFloat 还原为 int）
     *   [1]         = d2（插值 X）
     *   [2]         = d0（插值 Y）
     *   [3]         = d1（插值 Z）
     *   [4]         = f （实际 radius）
     *   [5 + i*4]   = blockX（Float.floatToRawIntBits 还原为 int）
     *   [6 + i*4]   = blockY
     *   [7 + i*4]   = blockZ
     *   [8 + i*4]   = f2（float，已过滤 <= 0）
     * 返回空数组或 null 表示无需渲染。
     */
    public static native float[] computeShadowData(
            double xOld, double x,
            double yOld, double y,
            double zOld, double z,
            float partialTick,
            float shadowStrength,
            float radius,
            boolean isBaby
    );
}