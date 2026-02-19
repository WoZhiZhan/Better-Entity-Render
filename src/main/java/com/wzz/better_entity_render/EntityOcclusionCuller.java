package com.wzz.better_entity_render;

import com.wzz.better_entity_render.util.LoaderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Queue;
import java.util.concurrent.*;

/**
 * 异步实体遮挡剔除 v3。
 *
 * 架构：
 *   1. 主线程 isVisible() 快速返回缓存值
 *   2. 后台 worker 线程执行检测：
 *      a. native computeRayPositions() → 得到所有射线经过的方块位置（去重）
 *      b. 主线程（通过 CompletableFuture 回调）查询每个位置的 block state
 *      c. native evaluateVisibility() → 判断是否有射线通畅
 *   3. 迟滞策略防闪烁：可见→遮挡需 3 次连续确认，遮挡→可见 1 次即生效
 *
 * 修复：
 *   - 旁观者模式直接跳过（相机能穿墙）
 *   - 换用 Amanatides & Woo 精确体素遍历，消除半砖等边界浮点误差
 *   - block state 查询放回主线程，避免并发访问 chunk 数据
 */
public final class EntityOcclusionCuller {

    // ---- 迟滞参数 ----
    private static final int    HIDE_CONFIRM_COUNT  = 3;
    private static final long   REFRESH_INTERVAL_MS = 80;
    private static final double MAX_RAY_LENGTH      = 128.0;

    private static final int MAX_TASKS_PER_FRAME = 48;

    // ---- 状态缓存（主线程读写 visible，worker 写 pending） ----
    private static final ConcurrentHashMap<Integer, EntityState> STATES
            = new ConcurrentHashMap<>();

    // ---- 两阶段任务队列 ----
    // Phase1：等待 native 计算位置列表（worker 线程处理）
    private static final Queue<Phase1Task> PHASE1_QUEUE = new ConcurrentLinkedQueue<>();
    // Phase2：等待主线程做 block state 查询，然后 native 评估（主线程在 flushTasks 里处理）
    private static final Queue<Phase2Task> PHASE2_QUEUE = new ConcurrentLinkedQueue<>();

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entity-occlusion-worker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static volatile double camX, camY, camZ;

    private EntityOcclusionCuller() {}

    /**
     * 用 Amanatides & Woo 遍历 9 条射线，返回所有需要检查的去重方块位置。
     * 返回格式：int[] = [anyRayOpen(0/1), count, x0,y0,z0, x1,y1,z1, ...]
     * anyRayOpen=1 表示至少一条射线超出 maxRayLength（直接可见，无需继续）
     */
    public static native int[] computeRayPositions(
            double camX, double camY, double camZ,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            double maxRayLength
    );

    /**
     * 给定方块位置列表和实心标记，判断是否有任意射线通畅。
     * solid[i] 对应 positions 中第 i 个方块（x=positions[i*3], ...）
     */
    public static native boolean evaluateVisibility(
            double camX, double camY, double camZ,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            double maxRayLength,
            int[]     positions,
            boolean[] solid,
            boolean   anyRayOpen
    );

    static {
        LoaderUtil.load("better_entity_render");
    }

    public static void updateCamera(double x, double y, double z) {
        camX = x;
        camY = y + 1.62; // 眼睛高度
        camZ = z;
    }

    /**
     * 主线程查询可见性。
     * 旁观者模式下直接返回 true（相机能穿墙，遮挡无意义）。
     */
    public static boolean isVisible(Entity entity, Level level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.isSpectator()) return true;

        if (entity instanceof Player) return true;

        int  id   = entity.getId();
        AABB aabb = entity.getBoundingBox();

        EntityState state = STATES.get(id);

        if (state == null) {
            state = new EntityState(aabb);
            STATES.put(id, state);
            submitPhase1(id, aabb, level);
            return true;
        }

        // 检测移动（AABB 中心偏移 > 0.5 格重置计数）
        double prevCX = (state.aabbMinX + state.aabbMaxX) * 0.5;
        double prevCY = (state.aabbMinY + state.aabbMaxY) * 0.5;
        double prevCZ = (state.aabbMinZ + state.aabbMaxZ) * 0.5;
        double curCX  = (aabb.minX + aabb.maxX) * 0.5;
        double curCY  = (aabb.minY + aabb.maxY) * 0.5;
        double curCZ  = (aabb.minZ + aabb.maxZ) * 0.5;
        double movedSq = sq(curCX-prevCX) + sq(curCY-prevCY) + sq(curCZ-prevCZ);
        if (movedSq > 0.25) {
            state.hideConfirmCount = 0;
            state.aabbMinX = aabb.minX; state.aabbMinY = aabb.minY; state.aabbMinZ = aabb.minZ;
            state.aabbMaxX = aabb.maxX; state.aabbMaxY = aabb.maxY; state.aabbMaxZ = aabb.maxZ;
        }

        // 到期时提交刷新
        long now = System.currentTimeMillis();
        if (now - state.lastCheckMs > REFRESH_INTERVAL_MS && !state.taskPending) {
            state.taskPending = true;
            submitPhase1(id, aabb, level);
        }

        return state.visible;
    }

    /**
     * 每帧主线程调用：
     * 1. 处理 Phase2 任务（block state 查询 + native 评估，必须在主线程）
     * 2. 将新的 Phase1 任务提交给 worker
     */
    public static void flushTasks() {
        // ---- Phase2：主线程执行 block state 查询 ----
        int p2Count = 0;
        Phase2Task p2;
        while (p2Count < MAX_TASKS_PER_FRAME && (p2 = PHASE2_QUEUE.poll()) != null) {
            processPhase2(p2);
            p2Count++;
        }

        // ---- Phase1：提交给 worker ----
        int p1Count = 0;
        Phase1Task p1;
        while (p1Count < MAX_TASKS_PER_FRAME && (p1 = PHASE1_QUEUE.poll()) != null) {
            final Phase1Task task = p1;
            WORKER.submit(() -> processPhase1(task));
            p1Count++;
        }
    }

    public static void shutdown() {
        WORKER.shutdownNow();
        STATES.clear();
    }

    private static void submitPhase1(int entityId, AABB aabb, Level level) {
        PHASE1_QUEUE.offer(new Phase1Task(entityId, aabb, level, camX, camY, camZ));
    }

    /** Worker 线程：调用 native 计算需要检查的方块位置 */
    private static void processPhase1(Phase1Task task) {
        int[] raw = computeRayPositions(
                task.camX, task.camY, task.camZ,
                task.aabbMinX, task.aabbMinY, task.aabbMinZ,
                task.aabbMaxX, task.aabbMaxY, task.aabbMaxZ,
                MAX_RAY_LENGTH
        );

        if (raw == null || raw.length < 2) {
            // 异常：保守可见
            applyResult(task.entityId, true);
            return;
        }

        boolean anyRayOpen = raw[0] == 1;
        int count          = raw[1];

        if (anyRayOpen || count == 0) {
            // 有射线直接可见，无需查询方块
            applyResult(task.entityId, true);
            return;
        }

        // 提取方块位置数组
        int[] positions = new int[count * 3];
        System.arraycopy(raw, 2, positions, 0, count * 3);

        // 提交 Phase2（需要主线程）
        PHASE2_QUEUE.offer(new Phase2Task(
                task.entityId,
                task.aabbMinX, task.aabbMinY, task.aabbMinZ,
                task.aabbMaxX, task.aabbMaxY, task.aabbMaxZ,
                task.level,
                task.camX, task.camY, task.camZ,
                positions,
                anyRayOpen
        ));
    }

    /** 主线程：查询 block state，调用 native 评估 */
    private static void processPhase2(Phase2Task task) {
        int count      = task.positions.length / 3;
        boolean[] solid = new boolean[count];

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < count; i++) {
            int bx = task.positions[i*3];
            int by = task.positions[i*3+1];
            int bz = task.positions[i*3+2];
            mpos.set(bx, by, bz);
            try {
                BlockState bs = task.level.getChunk(mpos).getBlockState(mpos);
                solid[i] = bs.isCollisionShapeFullBlock(task.level, mpos);
            } catch (Exception e) {
                solid[i] = false; // chunk 未加载，保守不遮挡
            }
        }

        boolean visible = evaluateVisibility(
                task.camX, task.camY, task.camZ,
                task.aabbMinX, task.aabbMinY, task.aabbMinZ,
                task.aabbMaxX, task.aabbMaxY, task.aabbMaxZ,
                MAX_RAY_LENGTH,
                task.positions,
                solid,
                task.anyRayOpen
        );

        applyResult(task.entityId, visible);
    }

    /** 将检测结果应用到 state（迟滞逻辑） */
    private static void applyResult(int entityId, boolean rawVisible) {
        STATES.compute(entityId, (id, state) -> {
            if (state == null) return null;

            state.taskPending = false;
            state.lastCheckMs = System.currentTimeMillis();

            if (rawVisible) {
                // 遮挡→可见：立即切换，重置计数
                state.visible          = true;
                state.hideConfirmCount = 0;
            } else {
                // 可见→遮挡：需要连续确认
                state.hideConfirmCount++;
                if (state.hideConfirmCount >= HIDE_CONFIRM_COUNT) {
                    state.visible          = false;
                    state.hideConfirmCount = HIDE_CONFIRM_COUNT; // 防溢出
                }
            }

            return state;
        });
    }

    private static double sq(double v) { return v * v; }

    private static final class EntityState {
        volatile boolean visible          = true;
        int              hideConfirmCount = 0;
        long             lastCheckMs      = 0;
        volatile boolean taskPending      = false;

        double aabbMinX, aabbMinY, aabbMinZ;
        double aabbMaxX, aabbMaxY, aabbMaxZ;

        EntityState(AABB aabb) {
            aabbMinX = aabb.minX; aabbMinY = aabb.minY; aabbMinZ = aabb.minZ;
            aabbMaxX = aabb.maxX; aabbMaxY = aabb.maxY; aabbMaxZ = aabb.maxZ;
        }
    }

    private static final class Phase1Task {
        final int    entityId;
        final double aabbMinX, aabbMinY, aabbMinZ;
        final double aabbMaxX, aabbMaxY, aabbMaxZ;
        final Level  level;
        final double camX, camY, camZ;

        Phase1Task(int entityId, AABB aabb, Level level,
                   double camX, double camY, double camZ) {
            this.entityId = entityId;
            aabbMinX = aabb.minX; aabbMinY = aabb.minY; aabbMinZ = aabb.minZ;
            aabbMaxX = aabb.maxX; aabbMaxY = aabb.maxY; aabbMaxZ = aabb.maxZ;
            this.level = level;
            this.camX = camX; this.camY = camY; this.camZ = camZ;
        }
    }

    private static final class Phase2Task {
        final int      entityId;
        final double   aabbMinX, aabbMinY, aabbMinZ;
        final double   aabbMaxX, aabbMaxY, aabbMaxZ;
        final Level    level;
        final double   camX, camY, camZ;
        final int[]    positions;
        final boolean  anyRayOpen;

        Phase2Task(int entityId,
                   double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ,
                   Level level,
                   double camX, double camY, double camZ,
                   int[] positions, boolean anyRayOpen) {
            this.entityId   = entityId;
            aabbMinX = minX; aabbMinY = minY; aabbMinZ = minZ;
            aabbMaxX = maxX; aabbMaxY = maxY; aabbMaxZ = maxZ;
            this.level      = level;
            this.camX = camX; this.camY = camY; this.camZ = camZ;
            this.positions  = positions;
            this.anyRayOpen = anyRayOpen;
        }
    }
}