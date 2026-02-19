package com.wzz.better_entity_render;

import java.util.HashMap;
import java.util.Map;

/**
 * 帧内阴影数据缓存。
 * 同一帧内，同一实体的 computeShadowData 结果只算一次。
 */
public final class ShadowCache {

    // entityId -> 当帧计算结果
    private static final Map<Integer, float[]> DATA    = new HashMap<>();
    // entityId -> 计算时的帧号
    private static final Map<Integer, Long>    FRAMES  = new HashMap<>();

    private static long currentFrame = 0;

    private ShadowCache() {}

    /** 每帧调用一次，推进帧号。map 超限时顺带清理。 */
    public static void nextFrame() {
        currentFrame++;
        // 防止长时间运行导致 map 无限增长（正常情况下不会触发）
        if (DATA.size() > 1024) {
            DATA.clear();
            FRAMES.clear();
        }
    }

    public static long getCurrentFrame() {
        return currentFrame;
    }

    /**
     * 尝试获取当前帧的缓存。
     * @return null 表示未命中
     */
    public static float[] get(int entityId) {
        Long frame = FRAMES.get(entityId);
        if (frame != null && frame == currentFrame) {
            return DATA.get(entityId);
        }
        return null;
    }

    /** 将本帧计算结果存入缓存。 */
    public static void put(int entityId, float[] data) {
        DATA.put(entityId, data);
        FRAMES.put(entityId, currentFrame);
    }
}