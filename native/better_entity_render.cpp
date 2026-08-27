#include <jni.h>
#include <cmath>
#include <vector>
#include <algorithm>
#include <cstring>
#include <unordered_set>

static inline int mth_floor(double d) {
    int i = (int)d;
    return d < (double)i ? i - 1 : i;
}

static inline double mth_lerp(double pct, double from, double to) {
    return from + pct * (to - from);
}

/**
 * 合并版 shadow 计算：一次 JNI 调用返回所有需要的数据
 *
 * 返回 float[] 布局：
 *   [0]         = count（int bits，有效 block 记录数）
 *   [1]         = d2（插值 X，强转 float，精度够用）
 *   [2]         = d0（插值 Y）
 *   [3]         = d1（插值 Z）
 *   [4]         = f （实际 radius，已处理 baby 缩放）
 *   [5 + i*4]   = blockX（int bits）
 *   [6 + i*4]   = blockY（int bits）
 *   [7 + i*4]   = blockZ（int bits）
 *   [8 + i*4]   = f2（float，已过滤 <= 0 的无效块）
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wzz_better_1entity_1render_ShadowRendererNative_computeShadowData(
    JNIEnv* env,
    jclass  clazz,
    jdouble xOld, jdouble x,
    jdouble yOld, jdouble y,
    jdouble zOld, jdouble z,
    jfloat  partialTick,
    jfloat  shadowStrength,
    jfloat  radius,
    jboolean isBaby
) {
    float f = isBaby ? radius * 0.5f : (float)radius;

    double d2 = mth_lerp((double)partialTick, xOld, x);
    double d0 = mth_lerp((double)partialTick, yOld, y);
    double d1 = mth_lerp((double)partialTick, zOld, z);

    float f1 = std::min(shadowStrength / 0.5f, f);

    int minX = mth_floor(d2 - (double)f);
    int maxX = mth_floor(d2 + (double)f);
    int minY = mth_floor(d0 - (double)f1);
    int maxY = mth_floor(d0);
    int minZ = mth_floor(d1 - (double)f);
    int maxZ = mth_floor(d1 + (double)f);

    int xCount = maxX - minX + 1;
    int yCount = maxY - minY + 1;
    int zCount = maxZ - minZ + 1;

    // 边界保护：避免异常大半径或负值
    if (xCount <= 0 || yCount <= 0 || zCount <= 0 ||
        (long long)xCount * yCount * zCount > 65536) {
        return env->NewFloatArray(0);
    }

    std::vector<float> result;
    result.reserve((size_t)(xCount * yCount * zCount) * 4 + 5);

    // 头部 5 个槽
    result.push_back(0.0f);      // [0] count placeholder
    result.push_back((float)d2); // [1] d2
    result.push_back((float)d0); // [2] d0
    result.push_back((float)d1); // [3] d1
    result.push_back(f);         // [4] f

    int count = 0;

    // 循环顺序与原版一致：Z -> X -> Y
    for (int kz = minZ; kz <= maxZ; ++kz) {
        for (int lx = minX; lx <= maxX; ++lx) {
            for (int iy = minY; iy <= maxY; ++iy) {
                float f2 = shadowStrength - (float)(d0 - (double)iy) * 0.5f;
                if (f2 <= 0.0f) continue; // 提前过滤无效块

                float fx, fy, fz;
                int tmp;
                tmp = lx; memcpy(&fx, &tmp, 4);
                tmp = iy; memcpy(&fy, &tmp, 4);
                tmp = kz; memcpy(&fz, &tmp, 4);

                result.push_back(fx);
                result.push_back(fy);
                result.push_back(fz);
                result.push_back(f2);
                ++count;
            }
        }
    }

    // 写回 count
    memcpy(&result[0], &count, 4);

    jfloatArray out = env->NewFloatArray((jsize)result.size());
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, (jsize)result.size(), result.data());
    return out;
}

static inline int fast_floor(double d) {
    int i = (int)d;
    return d < i ? i - 1 : i;
}

// 64 位 pack：把 (x, y, z) 压进一个 int64，用于去重
// x/z 范围 ±1M 格，y 范围 ±4096，足够
static inline int64_t pack_pos(int x, int y, int z) {
    // x: 21 bits, y: 12 bits, z: 21 bits  (带符号，偏移存储)
    return ((int64_t)(x + 1048576) << 33)
        | ((int64_t)(y + 4096) << 21)
        | (int64_t)(z + 1048576);
}

/**
 * 对单条射线做 A&W 遍历，将路径上的方块位置写入 out_positions。
 * 不加入起点和终点方块（即相机所在格和实体所在格）。
 *
 * @param ox,oy,oz   射线起点（相机眼睛位置）
 * @param tx,ty,tz   射线终点（AABB 采样点）
 * @param max_dist   最大射线长度（格），超过则认为可见（不写入）
 * @param positions  输出：去重集合
 * @return true = 射线长度超过 max_dist（视为可见，无需检查）
 */
static bool trace_ray(
    double ox, double oy, double oz,
    double tx, double ty, double tz,
    double max_dist,
    std::unordered_set<int64_t>& positions,
    std::vector<int>& pos_list  // 顺序列表，用于射线评估
) {
    double dx = tx - ox, dy = ty - oy, dz = tz - oz;
    double dist = std::sqrt(dx * dx + dy * dy + dz * dz);
    if (dist < 1e-7) return true;
    if (dist > max_dist) return true; // 太远，保守可见

    // 当前体素
    int x = fast_floor(ox), y = fast_floor(oy), z = fast_floor(oz);
    // 终点体素
    int ex = fast_floor(tx), ey = fast_floor(ty), ez = fast_floor(tz);

    // 方向步进
    int sx = dx > 0 ? 1 : -1;
    int sy = dy > 0 ? 1 : -1;
    int sz = dz > 0 ? 1 : -1;

    // 到第一个边界的 t 值
    double inv_dx = std::abs(dx) > 1e-12 ? 1.0 / std::abs(dx) : 1e18;
    double inv_dy = std::abs(dy) > 1e-12 ? 1.0 / std::abs(dy) : 1e18;
    double inv_dz = std::abs(dz) > 1e-12 ? 1.0 / std::abs(dz) : 1e18;

    double tmax_x = ((sx > 0 ? (double)(x + 1) : (double)x) - ox) * (dx > 0 ? inv_dx : -inv_dx) * (dx > 0 ? 1.0 : 1.0);
    // 更简洁的算法
    tmax_x = dx > 0 ? ((x + 1) - ox) / dx : (x - ox) / dx;
    double tmax_y = dy > 0 ? ((y + 1) - oy) / dy : (y - oy) / dy;
    double tmax_z = dz > 0 ? ((z + 1) - oz) / dz : (z - oz) / dz;

    double tdelta_x = std::abs(inv_dx);
    double tdelta_y = std::abs(inv_dy);
    double tdelta_z = std::abs(inv_dz);

    // 跳过起点体素
    // 遍历直到到达终点体素
    int max_steps = (int)(dist / 0.5) + 10; // 保险上限
    for (int step = 0; step < max_steps; ++step) {
        // 步进到下一个体素
        if (tmax_x < tmax_y && tmax_x < tmax_z) {
            x += sx;
            tmax_x += tdelta_x;
        }
        else if (tmax_y < tmax_z) {
            y += sy;
            tmax_y += tdelta_y;
        }
        else {
            z += sz;
            tmax_z += tdelta_z;
        }

        // 到达终点体素，停止（终点是实体位置，不检查）
        if (x == ex && y == ey && z == ez) break;

        int64_t key = pack_pos(x, y, z);
        if (positions.find(key) == positions.end()) {
            positions.insert(key);
            pos_list.push_back(x);
            pos_list.push_back(y);
            pos_list.push_back(z);
        }
    }

    return false; // 需要检查
}

/**
 * 计算所有射线需要检查的方块位置（去重）。
 *
 * 输入：相机坐标 + AABB 6 个 float
 * 返回：int[] = [count, x0,y0,z0, x1,y1,z1, ...] 去重方块列表
 *
 * 注意：如果某条射线超过 max_dist，对应射线标记为"直接可见"，
 * 这会在 evaluateVisibility 中处理——为此我们在 count 前多加一个
 * "any_ray_exceeds_dist" 标志位（index 0），count 在 index 1。
 */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_wzz_better_1entity_1render_EntityOcclusionCuller_computeRayPositions(
    JNIEnv* env, jclass clazz,
    jdouble cam_x, jdouble cam_y, jdouble cam_z, // 眼睛高度已加好
    jdouble min_x, jdouble min_y, jdouble min_z,
    jdouble max_x, jdouble max_y, jdouble max_z,
    jdouble max_ray_length
) {
    double mid_x = (min_x + max_x) * 0.5;
    double mid_y = (min_y + max_y) * 0.5;
    double mid_z = (min_z + max_z) * 0.5;

    double targets[9][3] = {
        {min_x, min_y, min_z}, {max_x, min_y, min_z},
        {min_x, max_y, min_z}, {max_x, max_y, min_z},
        {min_x, min_y, max_z}, {max_x, min_y, max_z},
        {min_x, max_y, max_z}, {max_x, max_y, max_z},
        {mid_x, mid_y, mid_z}
    };

    std::unordered_set<int64_t> seen;
    std::vector<int> pos_list;
    pos_list.reserve(128);

    bool any_ray_open = false; // 任何一条射线直接超出距离（可见）

    for (int i = 0; i < 9; ++i) {
        bool open = trace_ray(
            cam_x, cam_y, cam_z,
            targets[i][0], targets[i][1], targets[i][2],
            max_ray_length,
            seen, pos_list
        );
        if (open) {
            any_ray_open = true;
        }
    }

    // 格式：[any_ray_open(0/1), count, x0,y0,z0, ...]
    int count = (int)(pos_list.size() / 3);
    std::vector<int> result;
    result.reserve(pos_list.size() + 2);
    result.push_back(any_ray_open ? 1 : 0);
    result.push_back(count);
    result.insert(result.end(), pos_list.begin(), pos_list.end());

    jintArray out = env->NewIntArray((jsize)result.size());
    if (!out) return nullptr;
    env->SetIntArrayRegion(out, 0, (jsize)result.size(), (const jint*)result.data());
    return out;
}

/**
 * 给定方块位置列表和每个位置是否实心的布尔数组，
 * 重新跑一遍 A&W 判断是否有任意一条射线通畅。
 *
 * solid[i] 对应 positions[i*3 .. i*3+2] 是否为实心方块。
 *
 * 返回：true = 可见（至少一条射线通畅）
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_wzz_better_1entity_1render_EntityOcclusionCuller_evaluateVisibility(
    JNIEnv* env, jclass clazz,
    jdouble cam_x, jdouble cam_y, jdouble cam_z,
    jdouble min_x, jdouble min_y, jdouble min_z,
    jdouble max_x, jdouble max_y, jdouble max_z,
    jdouble max_ray_length,
    jintArray positions_arr,  // [x0,y0,z0, x1,y1,z1, ...]
    jbooleanArray solid_arr,  // solid[i] = positions[i] 是否实心
    jboolean any_ray_open     // computeRayPositions 返回的标志
) {
    if (any_ray_open) return JNI_TRUE;

    jsize pos_count = env->GetArrayLength(positions_arr) / 3;
    if (pos_count == 0) return JNI_TRUE; // 射线极短，可见

    jint* positions = env->GetIntArrayElements(positions_arr, nullptr);
    jboolean* solid = env->GetBooleanArrayElements(solid_arr, nullptr);

    // 建立实心集合（快速查询）
    std::unordered_set<int64_t> solid_set;
    for (int i = 0; i < pos_count; ++i) {
        if (solid[i]) {
            solid_set.insert(pack_pos(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]));
        }
    }

    env->ReleaseIntArrayElements(positions_arr, positions, JNI_ABORT);
    env->ReleaseBooleanArrayElements(solid_arr, solid, JNI_ABORT);

    double mid_x = (min_x + max_x) * 0.5;
    double mid_y = (min_y + max_y) * 0.5;
    double mid_z = (min_z + max_z) * 0.5;

    double targets[9][3] = {
        {min_x, min_y, min_z}, {max_x, min_y, min_z},
        {min_x, max_y, min_z}, {max_x, max_y, min_z},
        {min_x, min_y, max_z}, {max_x, min_y, max_z},
        {min_x, max_y, max_z}, {max_x, max_y, max_z},
        {mid_x, mid_y, mid_z}
    };

    for (int ri = 0; ri < 9; ++ri) {
        double tx = targets[ri][0], ty = targets[ri][1], tz = targets[ri][2];
        double dx = tx - cam_x, dy = ty - cam_y, dz = tz - cam_z;
        double dist = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1e-7 || dist > max_ray_length) return JNI_TRUE;

        int x = fast_floor(cam_x), y = fast_floor(cam_y), z = fast_floor(cam_z);
        int ex = fast_floor(tx), ey = fast_floor(ty), ez = fast_floor(tz);

        int sx = dx > 0 ? 1 : -1;
        int sy = dy > 0 ? 1 : -1;
        int sz = dz > 0 ? 1 : -1;

        double tmax_x = dx > 0 ? ((x + 1) - cam_x) / dx : (x - cam_x) / dx;
        double tmax_y = dy > 0 ? ((y + 1) - cam_y) / dy : (y - cam_y) / dy;
        double tmax_z = dz > 0 ? ((z + 1) - cam_z) / dz : (z - cam_z) / dz;
        double tdelta_x = std::abs(dx) > 1e-12 ? 1.0 / std::abs(dx) : 1e18;
        double tdelta_y = std::abs(dy) > 1e-12 ? 1.0 / std::abs(dy) : 1e18;
        double tdelta_z = std::abs(dz) > 1e-12 ? 1.0 / std::abs(dz) : 1e18;

        bool blocked = false;
        int max_steps = (int)(dist)+10;
        for (int step = 0; step < max_steps; ++step) {
            if (tmax_x < tmax_y && tmax_x < tmax_z) {
                x += sx; tmax_x += tdelta_x;
            }
            else if (tmax_y < tmax_z) {
                y += sy; tmax_y += tdelta_y;
            }
            else {
                z += sz; tmax_z += tdelta_z;
            }
            if (x == ex && y == ey && z == ez) break;

            if (solid_set.count(pack_pos(x, y, z))) {
                blocked = true;
                break;
            }
        }

        if (!blocked) return JNI_TRUE; // 这条射线通畅
    }

    return JNI_FALSE; // 所有射线都被遮挡
}