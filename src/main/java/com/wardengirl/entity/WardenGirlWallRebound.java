package com.wardengirl.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * T31 — <b>정확히 2블록</b> 벽 박차고 넘기. 4차 설계서 4.4.
 *
 * <h2>책임</h2>
 *
 * 벽 후보 판정, 도움닫기 공간 확인, 두 구간 궤적 계산, 도움닫기 접근, 최초 발사, <b>실제</b>
 * 벽 충돌 확인, 반동 1회, 벽 뒤 착지 판정이 전부 여기 있다.
 *
 * <p>MOVE 점유는 {@link WardenGirlSpecialMovementGoal}, 계획·종료는
 * {@link WardenGirlSpecialMovement}, 착지 안전은 {@link WardenGirlMovementSafety}, 문은
 * {@link WardenGirlDoorHelper}, 유격은 {@link WardenGirlGapJump} 가 그대로 맡는다 — 여기서
 * 복제하지 않는다.
 *
 * <h2>왜 반동이 "위로 크게"인가 (바이트코드 확인)</h2>
 *
 * {@code Entity.move} 는 충돌한 수평 축의 속도를 0 으로 만든다
 * ({@code setDeltaMovement(blockedX ? 0 : x, y, blockedZ ? 0 : z)}). 그런데 그 앞의
 * {@code collideWithShapes} 는 <b>Y 를 먼저</b> 풀고 그 결과로 옮긴 AABB 로 X·Z 를 푼다.
 *
 * <p>따라서 반동 tick 한 번의 Y 이동으로 발이 벽 상단을 넘지 못하면, 전방 속도는 그 tick 과
 * 이후 모든 tick 에서 0 이 되어 벽에 붙어 수직으로만 올라갔다가 제자리로 떨어진다. 그래서
 * 구간 A 는 <b>벽 높은 곳에 아직 상승 중</b>일 때 닿도록 풀고, 반동 수직 속도는
 * {@code 벽 상단 + ε − 접촉 발 높이} 이상이어야 한다. 이 값이 "기어오름"이 아니라 "한 번
 * 박차고 넘어감"으로 보이는 물리적 이유이기도 하다.
 *
 * <h2>순간이동·보정 없음</h2>
 *
 * 위치를 직접 쓰지 않는다. mod 가 속도를 쓰는 곳은 <b>최초 발사 1회</b>와 <b>실제 충돌 tick
 * 의 반동 1회</b> 뿐이고 나머지는 전부 바닐라 중력·항력·충돌이다.
 */
public final class WardenGirlWallRebound {

    // ---- 벽 분류 ---------------------------------------------------------------------------

    /** 설계서 4.4 — 정확히 이 높이만 다룬다. */
    public static final int WALL_HEIGHT = 2;
    /** 설계서 4.4.2 — 도움닫기 공간 최소 3블록. */
    public static final double RUNUP_DISTANCE = 3.0D;
    /** 진행 방향으로 훑어보는 최대 거리. */
    private static final double SCAN_DISTANCE = 6.0D;
    private static final double SCAN_STEP = 0.2D;
    private static final double MIN_DIRECTION_SQR = 1.0E-4D;

    // ---- 물리 (T29·T30 에서 바이트코드로 확인한 값과 같다) ------------------------------------

    private static final double DEFAULT_GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98D;
    private static final double AIR_DRAG = 0.91D;

    /** 최초 발사 수평 속도 상한. */
    public static final double MAX_LAUNCH_SPEED = 0.80D;
    /** 최초 발사 수직 속도 상한. */
    public static final double MAX_LAUNCH_JUMP = 0.62D;
    /** 반동 수평 속도 상한. */
    public static final double MAX_REBOUND_SPEED = 0.80D;
    /** 반동 수직 속도 상한. */
    public static final double MAX_REBOUND_JUMP = 0.72D;

    /** 반동 tick 한 번으로 벽 상단을 넘기 위한 여유. */
    private static final double CLEAR_EPS = 0.03D;
    /** 접촉 발 높이 하한. 너무 낮게 닿으면 반동이 비현실적으로 커진다. */
    private static final double CONTACT_MIN_HEIGHT = 1.05D;
    /**
     * 접촉 발 높이 상한(출발 지면 기준 상승량). 벽 상단(2.0)을 이미 넘으면 충돌하지 않는다.
     *
     * <p>1.80 에서 올렸다. 접촉이 낮을수록 벽 상단까지 남은 거리가 커져 반동 Y 가 커지고,
     * 그 반동이 그대로 정점과 낙하 시간을 늘린다(실측: 1.80 → 총 16틱·정점 초과 +0.243).
     * 1.90 이면 반동 Y 가 0.23 → 0.13 으로 줄어 총 15틱·초과 +0.06 이 된다.
     *
     * <p>1.95 까지 올리면 14틱·초과 +0.030 이지만 몸과 벽의 겹침이 0.05블록뿐이라 실제
     * 충돌을 놓칠 위험이 있어 쓰지 않는다. 1.90 은 0.10블록 겹침을 남긴다.
     */
    private static final double CONTACT_MAX_HEIGHT = 1.90D;

    private static final int EXECUTION_MARGIN_TICKS = 12;
    private static final int TAKEOFF_GRACE_TICKS = 3;
    private static final int MAX_SEGMENT_TICKS = 24;

    private static final double LANDING_Y_TOLERANCE = 0.51D;
    private static final double LANDING_SIDE_TOLERANCE = 1.20D;
    private static final double FALL_TOLERANCE = 1.20D;
    private static final double FLUSH_PROBE_DEPTH = 0.02D;
    private static final double SAMPLE_STEP = 0.40D;

    /** 도약 지점이 벽 앞면에서 떨어진 거리 후보. 달려온 뒤 벽 가까이서 뛴다. */
    private static final double TAKEOFF_MIN = 0.80D;
    private static final double TAKEOFF_MAX = 1.80D;
    private static final double TAKEOFF_STEP = 0.10D;
    /** 도약 지점 도달 판정. */
    private static final double TAKEOFF_ARRIVE = 0.22D;

    /** 도움닫기 도착 판정. */
    private static final double RUNUP_ARRIVE = 0.30D;
    private static final int RUNUP_STALL_LIMIT = 40;
    private static final double RUNUP_MIN_GAIN = 0.01D;

    private final WardenGirlEntity mob;

    WardenGirlWallRebound(WardenGirlEntity mob) {
        this.mob = mob;
    }

    // ---- 계획 기하 -------------------------------------------------------------------------

    /**
     * 한 번의 벽 반동에 필요한 고정 기하.
     *
     * @param direction   벽을 향하는 수평 단위 방향(축 정렬)
     * @param wall        벽 하단 블록
     * @param face        벽에서 워든걸을 향하는 면
     * @param faceCoord   벽 앞면의 축 좌표
     * @param wallTop     벽 상단 Y (발 기준 착지 가능 높이)
     * @param start       도움닫기 출발 발 좌표 (여기서 벽을 향해 <b>달린다</b>)
     * @param takeoff     실제 도약 발 좌표. 설계서 4.4.3 의 "달려오면서 … 점프" 지점이다
     * @param landing     벽 뒤 착지 발 좌표
     */
    record WallGeometry(Vec3 direction, BlockPos wall, Direction face, double faceCoord,
                        double wallTop, Vec3 start, Vec3 takeoff, Vec3 landing,
                        WardenGirlSpecialMovement.Purpose purpose, Vec3 goal,
                        @Nullable UUID relatedEntity,
                        net.minecraft.resources.ResourceKey<Level> dimension) {
    }

    /** 두 구간의 실제 속도 해. */
    record Solution(Vec3 launch, Vec3 rebound, int ticksA, int ticksB,
                    double contactHeight, double apex, List<Vec3> samples, Vec3 takeoff) {
    }

    // ---- runtime 상태 (저장하지 않는다) -------------------------------------------------------

    @Nullable
    private WallGeometry geometry;
    @Nullable
    private Solution solution;
    /** 도움닫기 대상. 계획 시작 전 접근 단계에서만 쓴다. */
    @Nullable
    private WallGeometry approach;
    private double lastDistance;
    private int stalledTicks;
    /** 최초 발사가 실제로 나갔는가. */
    private boolean launched;
    /** 반동을 이미 썼는가. 한 계획당 최대 1회. */
    private boolean rebounded;
    /** 실제 피해로 중단된 계획인가. 반동을 영구히 막는다. */
    private boolean aborted;
    private boolean airborne;
    private int airTicks;

    public boolean hasApproachTarget() {
        return this.approach != null;
    }

    public boolean rebounded() {
        return this.rebounded;
    }

    public boolean aborted() {
        return this.aborted;
    }

    public int airTicks() {
        return this.airTicks;
    }

    @Nullable
    public BlockPos wallPos() {
        WallGeometry g = this.geometry;
        return g == null ? null : g.wall();
    }

    // ---- 시작 -------------------------------------------------------------------------------

    /**
     * 매 서버 tick {@link WardenGirlEntity#customServerAiStep()} 이 부른다. T29 유격이 먼저
     * 보고 지나간 뒤라, 여기까지 온 것은 유격 후보가 아니라는 뜻이다.
     */
    void tryStart() {
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        if (sm.isActive() || this.mob.isPassenger()) {
            clearApproach();
            return;
        }
        if (!this.mob.onGround() || this.mob.gapJump().hasApproachTarget()) {
            return;                                 // 유격 접근이 이미 MOVE 를 쓰고 있다
        }
        Objective objective = currentObjective();
        if (objective == null) {
            clearApproach();
            return;
        }
        Path path = this.mob.getNavigation().getPath();
        if (path != null && path.canReach()) {
            clearApproach();
            return;                                 // 옆으로 우회하는 정상 경로가 있다
        }
        if (this.approach != null) {
            tickApproach(objective);
            return;
        }
        Vec3 pathDir = pathDirection(path);
        Vec3 goalDir = flat(objective.goal().subtract(this.mob.position()));
        WallGeometry geom = pathDir == null ? null : findWall(pathDir, objective);
        if (geom == null && goalDir != null && !sameDirection(pathDir, goalDir)) {
            geom = findWall(goalDir, objective);
        }
        if (geom == null) {
            return;
        }
        // 도움닫기 3블록은 <b>공간</b> 요구이지 정지해야 할 지점이 아니다. 이미 벽에서
        // 그만큼 떨어져 있으면 그대로 계획을 시작하고 RUNUP 이 달린다. 너무 가까울 때만
        // 뒤로 물러나는 접근을 쓴다.
        double toFace = Math.abs(axisCoord(geom, this.mob.position()) - geom.faceCoord());
        if (toFace >= RUNUP_DISTANCE) {
            begin(geom, objective);
        } else {
            this.approach = geom;
            this.lastDistance = this.mob.position().distanceTo(geom.start());
            this.stalledTicks = 0;
            com.wardengirl.WardenGirlMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "T31V ASET t=%d pos=(%.3f,%.3f,%.3f) wall=%s toFace=%.3f",
                    this.mob.level().getGameTime(), this.mob.getX(), this.mob.getY(),
                    this.mob.getZ(), geom.wall().toShortString(),
                    Math.abs(axisCoord(geom, this.mob.position()) - geom.faceCoord())));
        }
    }

    /** 접근 Goal 이 매 tick 부른다. */
    void tickApproach() {
        Objective now = currentObjective();
        if (now == null) {
            clearApproach();
            return;
        }
        tickApproach(now);
    }

    /** 도움닫기 출발 위치로 걸어간다. 벽을 통과하거나 위치를 직접 옮기지 않는다. */
    private void tickApproach(Objective now) {
        WallGeometry geom = this.approach;
        if (geom == null) {
            return;
        }
        if (now.purpose() != geom.purpose() || !objectiveStillFits(now, geom)
                || !this.mob.onGround() || this.mob.level().dimension() != geom.dimension()
                || !stillExactlyTwoHigh(geom) || !standable(geom.start())
                || !WardenGirlMovementSafety.safeLanding(this.mob, geom.landing())) {
            clearApproach();
            return;
        }
        double toFace = Math.abs(axisCoord(geom, this.mob.position()) - geom.faceCoord());
        if (toFace >= RUNUP_DISTANCE) {
            begin(geom, now);
            return;
        }
        double d = this.mob.position().distanceTo(geom.start());
        if (this.lastDistance - d < RUNUP_MIN_GAIN) {
            if (++this.stalledTicks > RUNUP_STALL_LIMIT) {
                clearApproach();
                return;
            }
        } else {
            this.stalledTicks = 0;
        }
        this.lastDistance = d;
        Vec3 s = geom.start();
        this.mob.getMoveControl().setWantedPosition(s.x, s.y, s.z,
                d < 2.0D ? 1.0D : WardenGirlFollowOwnerGoal.CRUISE_SPEED);
    }

    /** T28 Plan 을 시작하고 {@code WALL_REBOUND_RUNUP} 으로 들어간다. 발사는 다음 tick 이다. */
    private void begin(WallGeometry geom, Objective objective) {
        clearApproach();
        Solution sol = solve(geom);
        if (sol == null) {
            // T31V-TEMP
            com.wardengirl.WardenGirlMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "T31V SOLVEFAIL t=%d pos=(%.3f,%.3f,%.3f) toFace=%.3f wall=%s reject=%s",
                    this.mob.level().getGameTime(), this.mob.getX(), this.mob.getY(),
                    this.mob.getZ(),
                    Math.abs(axisCoord(geom, this.mob.position()) - geom.faceCoord()),
                    geom.wall().toShortString(), this.tempReject));
            return;                                 // 자연스러운 해가 없으면 실행하지 않는다
        }
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        WardenGirlSpecialMovement.Plan plan = new WardenGirlSpecialMovement.Plan(
                WardenGirlSpecialMovement.Kind.WALL_REBOUND,
                this.mob.position(), geom.landing(), objective.purpose(), objective.goal(),
                objective.relatedEntity(), this.mob.level().dimension(),
                this.mob.level().getGameTime(),
                this.mob.level().getGameTime() + sol.ticksA() + sol.ticksB()
                        + EXECUTION_MARGIN_TICKS,
                geom.wall(), null, true);
        if (sm.tryBegin(plan) != null) {
            return;
        }
        // T31V-TEMP
        com.wardengirl.WardenGirlMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                "T31V PLAN t=%d wall=%s face=%s start=(%.3f,%.3f,%.3f) landing=(%.3f,%.3f,%.3f) "
                + "launch=(%.5f,%.5f,%.5f) rebound=(%.5f,%.5f,%.5f) tA=%d tB=%d contactH=%.3f "
                + "apex=%.3f",
                this.mob.level().getGameTime(), geom.wall().toShortString(), geom.face(),
                geom.start().x, geom.start().y, geom.start().z,
                geom.landing().x, geom.landing().y, geom.landing().z,
                sol.launch().x, sol.launch().y, sol.launch().z,
                sol.rebound().x, sol.rebound().y, sol.rebound().z,
                sol.ticksA(), sol.ticksB(), sol.contactHeight(), sol.apex()));
        this.geometry = geom;
        this.solution = sol;
        this.launched = false;
        this.rebounded = false;
        this.aborted = false;
        this.airborne = false;
        this.airTicks = 0;
        this.mob.getNavigation().stop();
        this.mob.getMoveControl().setWantedPosition(
                this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0D);
        this.mob.setSprinting(false);
        // 접근 관성이 발사 해를 흐트러뜨리지 않게 수평만 0 으로 만든다. 위치는 건드리지 않는다.
        Vec3 dm = this.mob.getDeltaMovement();
        this.mob.setDeltaMovement(0.0D, dm.y, 0.0D);
    }

    // ---- 실행 -------------------------------------------------------------------------------

    /** {@link WardenGirlSpecialMovement#tick()} 이 WALL_REBOUND 일 때만 부른다. */
    void tickActive(WardenGirlSpecialMovement.Plan plan) {
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        WallGeometry geom = this.geometry;
        Solution sol = this.solution;
        if (geom == null || sol == null) {
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;
        }
        Long limit = plan.expectedEndTick();
        if (limit != null && this.mob.level().getGameTime() > limit) {
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;
        }
        if (this.aborted) {
            // 실제 피해로 중단됐다. 속도·위치를 되돌리지 않고 계획만 끝낸다.
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;
        }
        if (sm.getState() == WardenGirlSpecialMovement.State.WALL_REBOUND_RUNUP) {
            tickRunup(geom, sol, sm);
            return;
        }
        tickAir(plan, geom, sol, sm);
    }

    /**
     * RUNUP — 설계서 4.4.3 의 "벽을 보며 달리기". 출발 위치에서 도약 지점까지 <b>지상으로
     * 달린 뒤</b> 그 자리에서 한 번 발사한다. 서서 뛰지 않는다.
     */
    private void tickRunup(WallGeometry geom, Solution sol, WardenGirlSpecialMovement sm) {
        if (!this.mob.onGround()) {
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;
        }
        Objective now = currentObjective();
        if (now == null || now.purpose() != geom.purpose() || !objectiveStillFits(now, geom)
                || !stillExactlyTwoHigh(geom)
                || !WardenGirlMovementSafety.safeLanding(this.mob, geom.landing())) {
            sm.cancel(WardenGirlSpecialMovement.Reason.PURPOSE_LOST);
            return;
        }
        Vec3 t = sol.takeoff();
        double toFace = Math.abs(axisCoord(geom, this.mob.position()) - geom.faceCoord());
        double planned = Math.abs(axisCoord(geom, t) - geom.faceCoord());
        if (toFace > planned + TAKEOFF_ARRIVE) {
            // 아직 달리는 중이다. 위치를 직접 옮기지 않고 MoveControl 로만 민다.
            this.mob.getMoveControl().setWantedPosition(t.x, t.y, t.z,
                    WardenGirlFollowOwnerGoal.CRUISE_SPEED);
            return;
        }
        // 임계값을 넘었다. 계획 지점이 아니라 <b>지금 발 좌표</b>로 다시 풀어 발사한다.
        Solution now2 = solve(geom, toFace, toFace, this.mob.position());
        if (now2 == null) {
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;
        }
        this.solution = now2;
        sol = now2;
        // 발사 — mod-side velocity write #1
        this.mob.tempWrites++;                                   // T31V-TEMP
        com.wardengirl.WardenGirlMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                "T31V LAUNCH t=%d age=%d pos=(%.3f,%.3f,%.3f) takeoff=(%.3f,%.3f,%.3f) "
                + "v=(%.5f,%.5f,%.5f) writes=%d",
                this.mob.level().getGameTime(), sm.getStateAge(),
                this.mob.getX(), this.mob.getY(), this.mob.getZ(), t.x, t.y, t.z,
                sol.launch().x, sol.launch().y, sol.launch().z, this.mob.tempWrites));
        this.mob.setDeltaMovement(sol.launch());
        this.mob.hasImpulse = true;
        this.launched = true;
        sm.transitionTo(WardenGirlSpecialMovement.State.WALL_REBOUND_AIR);
    }

    /** AIR — 실제 벽 충돌을 확인해 반동 1회, 그 뒤 벽 뒤 착지 판정. */
    private void tickAir(WardenGirlSpecialMovement.Plan plan, WallGeometry geom, Solution sol,
                         WardenGirlSpecialMovement sm) {
        if (!this.airborne) {
            if (!this.mob.onGround()) {
                this.airborne = true;
            } else if (sm.getStateAge() > TAKEOFF_GRACE_TICKS) {
                sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            }
            return;
        }
        this.airTicks++;
        if (!this.rebounded) {
            // 접촉 판정은 타이머가 아니라 바닐라가 지난 tick move() 에서 세운 실제 깃발이다.
            if (this.mob.horizontalCollision) {
                if (!touchingPlannedWall(geom)) {
                    com.wardengirl.WardenGirlMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                            "T31V FAIL t=%d reason=WRONG_BLOCK pos=(%.3f,%.3f,%.3f)",
                            this.mob.level().getGameTime(), this.mob.getX(), this.mob.getY(),
                            this.mob.getZ()));                   // T31V-TEMP
                    sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
                    return;
                }
                // 반동 — mod-side velocity write #2
                this.mob.tempWrites++;                           // T31V-TEMP
                com.wardengirl.WardenGirlMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                        "T31V REBOUND t=%d airTicks=%d pos=(%.3f,%.3f,%.3f) feetH=%.3f "
                        + "hColl=%s wall=%s v=(%.5f,%.5f,%.5f) writes=%d",
                        this.mob.level().getGameTime(), this.airTicks,
                        this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                        this.mob.getY() - geom.start().y, this.mob.horizontalCollision,
                        geom.wall().toShortString(),
                        sol.rebound().x, sol.rebound().y, sol.rebound().z, this.mob.tempWrites));
                this.mob.setDeltaMovement(sol.rebound());
                this.mob.hasImpulse = true;
                this.rebounded = true;
                return;
            }
            if (this.mob.onGround()) {
                sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);  // 닿지 못했다
                return;
            }
            if (this.mob.getY() < geom.start().y - FALL_TOLERANCE) {
                sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
                return;
            }
            return;
        }
        if (this.mob.getY() < plan.landing().y - FALL_TOLERANCE) {
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;
        }
        if (!this.mob.onGround()) {
            return;
        }
        boolean ok = landedBehindWall(plan, geom);
        com.wardengirl.WardenGirlMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                "T31V LAND t=%d ok=%s pos=(%.3f,%.3f,%.3f) planned=(%.3f,%.3f,%.3f) "
                + "airTicks=%d rebounded=%s writes=%d",
                this.mob.level().getGameTime(), ok, this.mob.getX(), this.mob.getY(),
                this.mob.getZ(), plan.landing().x, plan.landing().y, plan.landing().z,
                this.airTicks, this.rebounded, this.mob.tempWrites));   // T31V-TEMP
        if (ok) {
            sm.finishSuccess();
        } else {
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
        }
    }

    /**
     * 지금 실제로 닿아 있는 것이 계획한 그 벽인가. {@code horizontalCollision} 만으로는 어떤
     * 블록인지 알 수 없으므로, 진행 방향으로 아주 얇게 부풀린 상자가 계획 벽의 두 칸과
     * 겹치는지 본다.
     */
    private boolean touchingPlannedWall(WallGeometry geom) {
        AABB probe = this.mob.getBoundingBox().inflate(0.06D, 0.0D, 0.06D);
        Level level = this.mob.level();
        BlockPos lower = geom.wall();
        for (int dy = 0; dy < WALL_HEIGHT; dy++) {
            BlockPos at = lower.above(dy);
            VoxelShape shape = level.getBlockState(at).getCollisionShape(level, at);
            if (shape.isEmpty()) {
                continue;
            }
            if (probe.intersects(shape.bounds().move(at))) {
                return true;
            }
        }
        return false;
    }

    /** 벽 <b>뒤</b>의 계획 착지 지면에 실제로 섰는가. 벽 위 착지는 성공이 아니다. */
    private boolean landedBehindWall(WardenGirlSpecialMovement.Plan plan, WallGeometry geom) {
        Vec3 feet = this.mob.position();
        if (Math.abs(feet.y - plan.landing().y) > LANDING_Y_TOLERANCE) {
            return false;                           // 벽 위(2블록 높음)면 여기서 걸린다
        }
        Vec3 d = geom.direction();
        Vec3 rel = feet.subtract(plan.landing());
        double along = rel.x * d.x + rel.z * d.z;
        double side = Math.abs(rel.x * d.z - rel.z * d.x);
        if (side > LANDING_SIDE_TOLERANCE) {
            return false;
        }
        if (along < -LANDING_SIDE_TOLERANCE || along > LANDING_SIDE_TOLERANCE) {
            return false;
        }
        // 벽 앞면보다 확실히 뒤여야 한다.
        double coord = geom.face().getAxis() == Direction.Axis.X ? feet.x : feet.z;
        double sign = geom.face().getAxis() == Direction.Axis.X ? d.x : d.z;
        return (coord - geom.faceCoord()) * sign > 0.0D;
    }

    // ---- 후보 판정 -------------------------------------------------------------------------

    /**
     * 진행 방향에서 정확히 2블록 벽을 찾는다.
     *
     * <p><b>블록 이름이나 태그로 판정하지 않는다.</b> 아래 두 칸이 <b>충돌 형상 기준 꽉 찬
     * 블록</b>이고 세 번째 칸이 통과 가능한 경우만 벽이다. 울타리·철창·문·다락문·울타리문은
     * 충돌 형상이 꽉 찬 블록이 아니므로 이 검사 하나에서 전부 떨어진다(설계서 4.4.2 제외 목록).
     */
    @Nullable
    private WallGeometry findWall(Vec3 dir, Objective objective) {
        Direction face = Direction.getNearest(dir.x, 0.0D, dir.z).getOpposite();
        Vec3 axis = new Vec3(-face.getStepX(), 0.0D, -face.getStepZ());   // 워든걸 → 벽
        if (axis.lengthSqr() < MIN_DIRECTION_SQR) {
            return null;
        }
        Level level = this.mob.level();
        Vec3 feet = this.mob.position();
        double floorY = Math.floor(feet.y);
        BlockPos found = null;
        for (double t = SCAN_STEP; t <= SCAN_DISTANCE; t += SCAN_STEP) {
            Vec3 q = feet.add(axis.scale(t));
            BlockPos p = new BlockPos(Mth.floor(q.x), (int) floorY, Mth.floor(q.z));
            if (fullCollision(level, p)) {
                found = p.immutable();
                break;
            }
        }
        if (found == null) {
            return null;
        }
        // 정확히 2블록: 아래 두 칸이 꽉 찬 블록, 세 번째 칸은 통과 가능.
        if (!fullCollision(level, found.above())) {
            return null;                            // 1블록 장애물 — 바닐라 이동에 맡긴다
        }
        if (fullCollision(level, found.above(2)) || !passable(level, found.above(2))) {
            return null;                            // 3블록 이상 — T31 대상이 아니다
        }
        // 폭: 몸 너비를 덮는 옆 칸도 같은 2블록이어야 모서리를 스치지 않는다.
        Direction side = face.getClockWise();
        for (int s = -1; s <= 1; s += 2) {
            BlockPos n = found.relative(side, s);
            if (!fullCollision(level, n) || !fullCollision(level, n.above())) {
                return null;
            }
        }
        double faceCoord = face.getAxis() == Direction.Axis.X
                ? (face.getStepX() > 0 ? found.getX() + 1.0D : found.getX())
                : (face.getStepZ() > 0 ? found.getZ() + 1.0D : found.getZ());
        double wallTop = floorY + WALL_HEIGHT;

        // 벽 뒤 착지: 벽 반대편 첫 칸부터 같은 높이 지면을 찾는다.
        Vec3 landing = null;
        for (int back = 1; back <= 3; back++) {
            BlockPos lp = found.relative(face.getOpposite(), back);
            if (fullCollision(level, lp) || fullCollision(level, lp.above())) {
                continue;                           // 벽이 두꺼우면 더 뒤를 본다
            }
            Vec3 cand = new Vec3(lp.getX() + 0.5D, floorY, lp.getZ() + 0.5D);
            if (standable(cand) && WardenGirlMovementSafety.safeLanding(this.mob, cand)) {
                landing = cand;
                break;
            }
        }
        if (landing == null) {
            return null;
        }
        if (!WardenGirlMovementSafety.improvesProgress(feet, landing, objective.goal())) {
            return null;
        }
        // 도움닫기 3블록: 벽 앞면에서 뒤로 RUNUP_DISTANCE 지점이 서 있을 수 있고 그 사이가 비었다.
        boolean axisX = face.getAxis() == Direction.Axis.X;
        double sign = axisX ? axis.x : axis.z;
        Vec3 start = null;
        for (double t = RUNUP_DISTANCE; t <= RUNUP_DISTANCE + 1.0D; t += 0.25D) {
            Vec3 cand = axisX
                    ? new Vec3(faceCoord - sign * t, floorY, found.getZ() + 0.5D)
                    : new Vec3(found.getX() + 0.5D, floorY, faceCoord - sign * t);
            if (standable(cand) && runupClear(cand, axis, t)) {
                start = cand;
                break;
            }
        }
        if (start == null) {
            return null;                            // 도움닫기 공간 3블록 미만
        }
        Vec3 takeoff = axisX
                ? new Vec3(faceCoord - sign * TAKEOFF_MAX, floorY, found.getZ() + 0.5D)
                : new Vec3(found.getX() + 0.5D, floorY, faceCoord - sign * TAKEOFF_MAX);
        return new WallGeometry(axis, found, face, faceCoord, wallTop, start, takeoff, landing,
                objective.purpose(), objective.goal(), objective.relatedEntity(),
                level.dimension());
    }

    /** 도움닫기 구간이 실제로 비어 있고 발판이 이어지는가. */
    private boolean runupClear(Vec3 start, Vec3 axis, double length) {
        // 벽 앞면까지가 아니라 <b>몸 반폭 앞</b>까지만 본다. 앞면까지 찍으면 마지막 표본이
        // 벽 안이라 어떤 후보도 통과하지 못한다.
        double limit = length - this.mob.getBbWidth() / 2.0D;
        for (double t = 0.0D; t <= limit; t += 0.5D) {
            Vec3 q = start.add(axis.scale(t));
            AABB box = WardenGirlMovementSafety.destinationBox(this.mob, q);
            if (!this.mob.level().noCollision(this.mob, box)) {
                return false;
            }
        }
        return true;
    }

    /** 계획한 벽이 아직 정확히 2블록인가. 시작 전·도중 재검사에 같은 규칙을 쓴다. */
    private boolean stillExactlyTwoHigh(WallGeometry geom) {
        Level level = this.mob.level();
        BlockPos lower = geom.wall();
        return fullCollision(level, lower) && fullCollision(level, lower.above())
                && !fullCollision(level, lower.above(2)) && passable(level, lower.above(2));
    }

    /**
     * 충돌 형상이 <b>꽉 찬 1×1×1</b> 인가. 이름·태그를 보지 않는다 — 얇거나 불규칙한 면
     * (울타리·철창·문·다락문·울타리문·판재 반블록)은 전부 여기서 떨어진다.
     */
    private static boolean fullCollision(Level level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        AABB b = shape.bounds();
        return b.minX <= 1.0E-6D && b.minY <= 1.0E-6D && b.minZ <= 1.0E-6D
                && b.maxX >= 1.0D - 1.0E-6D && b.maxY >= 1.0D - 1.0E-6D
                && b.maxZ >= 1.0D - 1.0E-6D;
    }

    /** 몸이 지나갈 수 있는가. 충돌 형상이 비어 있어야 한다. */
    private static boolean passable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    // ---- 물리 해 --------------------------------------------------------------------------

    /**
     * 두 구간을 실제 1.20.1 중력·항력으로 푼다.
     *
     * <p>구간 A 는 "벽 앞면에 <b>아직 상승 중</b>이면서 충분히 높이 닿는" 해를, 구간 B 는
     * "반동 tick 한 번의 Y 이동으로 벽 상단을 넘고 벽 뒤 착지점에 내려서는" 해를 찾는다.
     */
    @Nullable
    private Solution solve(WallGeometry geom) {
        return solve(geom, TAKEOFF_MIN, TAKEOFF_MAX, null);
    }

    /**
     * @param origin {@code null} 이면 {@code td} 로 정한 계획 도약 지점에서, 아니면 <b>실제</b>
     *        발 좌표에서 푼다. 발사 직전 재계산이 후자다 — 달려온 실제 위치가 계획 지점과
     *        조금 달라도 궤적이 어긋나지 않게 한다.
     */
    /** T31V-TEMP — solve 거절 사유별 횟수. */
    private final java.util.Map<String, Integer> tempReject = new java.util.LinkedHashMap<>();

    private void rej(String k) {                                  // T31V-TEMP
        this.tempReject.merge(k, 1, Integer::sum);
    }

    @Nullable
    private Solution solve(WallGeometry geom, double tdLo, double tdHi, @Nullable Vec3 origin) {
        this.tempReject.clear();                                  // T31V-TEMP
        Solution best = null;
        double g = gravity();
        double floorY = geom.start().y;
        double wallTop = geom.wallTop();
        double half = this.mob.getBbWidth() / 2.0D;
        double friction = blockFriction();
        // 접촉 높이를 <b>가장 바깥에서 높은 쪽부터</b> 본다. 높이 닿을수록 필요한 반동이
        // 작아져 궤적이 낮고 빨라진다 — 설계서 4.4.3 의 "빠르게 넘김"이 여기서 정해진다.
        for (double hc = CONTACT_MAX_HEIGHT; hc >= CONTACT_MIN_HEIGHT; hc -= 0.05D) {
        for (double td = tdLo; td <= tdHi + 1.0E-9D; td += TAKEOFF_STEP) {
            Vec3 from = origin != null ? origin : takeoffAt(geom, td);
            double distA = Math.abs(axisCoord(geom, from) - geom.faceCoord()) - half;
            if (distA <= 0.0D) {
                continue;
            }
            for (int ta = 2; ta <= MAX_SEGMENT_TICKS; ta++) {
                Double vy = solveJumpFor(hc, ta, g);
                if (vy == null) { rej("noJumpFit"); continue; }
                if (vy > MAX_LAUNCH_JUMP) { rej("jumpCap"); continue; }
                if (verticalAt(vy, ta, g) < 0.0D) { rej("descending"); continue; }
                double ka = horizontalSum(ta, friction);
                double vx = distA / ka;
                if (vx > MAX_LAUNCH_SPEED || vx <= 0.0D) { rej("vxCap"); continue; }
                double vyB = (wallTop - (floorY + hc)) + CLEAR_EPS;
                if (vyB <= 0.0D || vyB > MAX_REBOUND_JUMP) { rej("vyBRange"); continue; }
                // 반동 tick 이후 착지까지의 비행 tick.
                Integer tb = fallTicks(vyB, floorY + hc, floorY, g);
                if (tb == null) { rej("noFall"); continue; }
                double contactCoord = geom.faceCoord() - signOf(geom) * half;
                double distB = Math.abs(axisCoord(geom, geom.landing()) - contactCoord);
                double kb = airHorizontalSum(tb);
                double vxB = distB / kb;
                if (vxB > MAX_REBOUND_SPEED || vxB <= 0.0D) { rej("vxBCap"); continue; }
                Vec3 d = geom.direction();
                Vec3 launch = new Vec3(d.x * vx, vy, d.z * vx);
                Vec3 rebound = new Vec3(d.x * vxB, vyB, d.z * vxB);
                List<Vec3> samples = new ArrayList<>();
                Vec3 contact = simulate(from, launch, ta, friction, g, samples, false, null);
                Vec3 end = simulate(contact, rebound, tb + 2, 1.0D, g, samples, true, floorY);
                if (Math.abs(end.y - floorY) > LANDING_Y_TOLERANCE) { rej("landY"); continue; }
                if (!WardenGirlMovementSafety.sweepClear(this.mob, samples)) {
                    rej("sweep"); continue; }
                double apex = 0.0D;
                for (Vec3 s : samples) {
                    apex = Math.max(apex, s.y - floorY);
                }
                Solution cand = new Solution(launch, rebound, ta, tb, hc, apex, samples, from);
                if (best == null || better(cand, best, geom)) {
                    best = cand;                    // 첫 해로 끝내지 않고 전부 비교한다
                }
            }
        }
        }
        return best;
    }

    /**
     * 유효 해 사이의 우선순위. 실제 충돌·관통 없음·안전 착지는 이미 위에서 걸렀으므로
     * 여기서는 <b>궤적의 낮음</b>부터 본다.
     *
     * <p>이전 기준(접촉을 높은 쪽부터 골라 반동을 줄인다)은 폐기했다. 높은 접촉을 만들려면
     * 최초 Y 속도를 크게 줘야 하고, 그러면 벽에 닿기도 전에 몸이 벽보다 훨씬 높이 올라가
     * 전체 동작이 슈퍼점프가 된다. 낮은 정점을 1순위로 두면 그 원인이 사라진다.
     *
     * <pre>
     *   1. 최고 feet Y 최소   (apex)
     *   2. 총 공중 tick 최소  (ticksA + ticksB)
     *   3. 반동 속도 크기 최소
     * </pre>
     */
    private boolean better(Solution a, Solution b, WallGeometry geom) {
        int at = a.ticksA() + a.ticksB();
        int bt = b.ticksA() + b.ticksB();
        if (at != bt) {
            return at < bt;                         // 4. 총 공중 시간 최소
        }
        if (Math.abs(a.launch().y - b.launch().y) > 1.0E-6D) {
            return a.launch().y < b.launch().y;     // 5. 최초 수직 속도 최소
        }
        if (Math.abs(a.apex() - b.apex()) > 1.0E-6D) {
            return a.apex() < b.apex();             // 6. 발 최고점 최소
        }
        return a.rebound().length() < b.rebound().length();   // 7. 반동 속도 최소
    }

    /** 벽 앞면에서 {@code td} 만큼 떨어진 도약 발 좌표. */
    private Vec3 takeoffAt(WallGeometry geom, double td) {
        double sign = signOf(geom);
        return geom.face().getAxis() == Direction.Axis.X
                ? new Vec3(geom.faceCoord() - sign * td, geom.start().y, geom.takeoff().z)
                : new Vec3(geom.takeoff().x, geom.start().y, geom.faceCoord() - sign * td);
    }

    /** {@code ta} tick 뒤 상승량이 {@code target} 이 되는 초기 수직 속도. 없으면 null. */
    @Nullable
    private static Double solveJumpFor(double target, int ta, double g) {
        double lo = 0.0D;
        double hi = 1.2D;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2.0D;
            if (riseAfter(mid, ta, g) < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double v = (lo + hi) / 2.0D;
        return Math.abs(riseAfter(v, ta, g) - target) < 0.02D ? v : null;
    }

    private static double riseAfter(double vy0, int ticks, double g) {
        double vy = vy0;
        double y = 0.0D;
        for (int i = 0; i < ticks; i++) {
            y += vy;
            vy = (vy - g) * VERTICAL_DRAG;
        }
        return y;
    }

    private static double verticalAt(double vy0, int ticks, double g) {
        double vy = vy0;
        for (int i = 0; i < ticks; i++) {
            vy = (vy - g) * VERTICAL_DRAG;
        }
        return vy;
    }

    /** 반동 tick 부터 {@code fromY} 에서 {@code toY} 로 내려오기까지의 tick. */
    @Nullable
    private static Integer fallTicks(double vy0, double fromY, double toY, double g) {
        double vy = vy0;
        double y = fromY;
        for (int i = 1; i <= MAX_SEGMENT_TICKS; i++) {
            y += vy;
            vy = (vy - g) * VERTICAL_DRAG;
            if (y <= toY && vy < 0.0D) {
                return i;
            }
        }
        return null;
    }

    /** 도약 tick 만 지면 마찰, 이후 공중 항력. */
    private static double horizontalSum(int ticks, double friction) {
        double sum = 0.0D;
        double f = 1.0D;
        for (int i = 0; i < ticks; i++) {
            sum += f;
            f *= (i == 0 ? friction * AIR_DRAG : AIR_DRAG);
        }
        return sum;
    }

    /** 공중에서 시작하는 구간은 전부 공중 항력이다. */
    private static double airHorizontalSum(int ticks) {
        double sum = 0.0D;
        double f = 1.0D;
        for (int i = 0; i < ticks; i++) {
            sum += f;
            f *= AIR_DRAG;
        }
        return sum;
    }

    /**
     * @param yFirstOnFirstTick 첫 tick 을 <b>Y 먼저, 그 다음 수평</b>으로 표본화한다.
     *        {@code Entity.collideWithShapes} 가 실제로 그 순서로 푼다 — 벽에 붙은 채
     *        대각선으로 보간하면 표본이 벽 안을 지나 sweep 이 무조건 실패한다.
     */
    private Vec3 simulate(Vec3 from, Vec3 v0, int ticks, double firstFriction, double g,
                          List<Vec3> out, boolean yFirstOnFirstTick,
                          @Nullable Double stopAtY) {
        Vec3 p = from;
        Vec3 v = v0;
        for (int i = 0; i < ticks; i++) {
            Vec3 next = p.add(v);
            // 바닥에 닿으면 바닐라는 거기서 멈춘다. 정해진 tick 수만큼 계속 내려보내면
            // 마지막 점이 지면 아래로 파고들어 착지 오차·sweep 이 무조건 실패한다.
            if (stopAtY != null && v.y < 0.0D && next.y <= stopAtY) {
                double span = p.y - next.y;
                double frac = span <= 1.0E-9D ? 1.0D : (p.y - stopAtY) / span;
                Vec3 hit = p.add(next.subtract(p).scale(Mth.clamp(frac, 0.0D, 1.0D)));
                sample(p, hit, out);
                return hit;
            }
            if (i == 0 && yFirstOnFirstTick) {
                Vec3 up = new Vec3(p.x, next.y, p.z);
                sample(p, up, out);
                sample(up, next, out);
            } else {
                sample(p, next, out);
            }
            p = next;
            double f = (i == 0 ? firstFriction * AIR_DRAG : AIR_DRAG);
            v = new Vec3(v.x * f, (v.y - g) * VERTICAL_DRAG, v.z * f);
        }
        return p;
    }

    private static void sample(Vec3 a, Vec3 b, List<Vec3> out) {
        double d = a.distanceTo(b);
        int n = Math.max(1, (int) Math.ceil(d / SAMPLE_STEP));
        for (int i = 1; i <= n; i++) {
            out.add(a.add(b.subtract(a).scale((double) i / n)));
        }
    }

    private double axisCoord(WallGeometry geom, Vec3 p) {
        return geom.face().getAxis() == Direction.Axis.X ? p.x : p.z;
    }

    private static double signOf(WallGeometry geom) {
        return geom.face().getAxis() == Direction.Axis.X ? geom.direction().x : geom.direction().z;
    }

    // ---- 피격 중단 -------------------------------------------------------------------------

    /**
     * 실제 체력이 줄어든 tick 에 {@link WardenGirlEntity#hurt} 가 부른다. 피해량·넉백은
     * 건드리지 않고 계획만 끊는다 — 속도 롤백도 위치 복구도 없다.
     */
    void onActualDamage() {
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        WardenGirlSpecialMovement.Plan p = sm.getPlan();
        if (p == null || p.kind() != WardenGirlSpecialMovement.Kind.WALL_REBOUND) {
            return;
        }
        // T31V-TEMP
        Vec3 dm = this.mob.getDeltaMovement();
        com.wardengirl.WardenGirlMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                "T31V DAMAGE t=%d state=%s launched=%s rebounded=%s pos=(%.3f,%.3f,%.3f) "
                + "dm=(%.5f,%.5f,%.5f) writes=%d",
                this.mob.level().getGameTime(), sm.getState(), this.launched, this.rebounded,
                this.mob.getX(), this.mob.getY(), this.mob.getZ(), dm.x, dm.y, dm.z,
                this.mob.tempWrites));
        this.aborted = true;                        // 이후 반동을 영구히 막는다
        this.mob.playAction("");
        sm.cancel(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
    }

    // ---- 목적 ------------------------------------------------------------------------------

    private record Objective(WardenGirlSpecialMovement.Purpose purpose, Vec3 goal,
                             @Nullable UUID relatedEntity) {
    }

    /**
     * 지금 따르고 있는 이동 목적. T29 와 같은 우선순위를 보지만 그 클래스의 private 판정을
     * 열지 않기 위해 여기서 다시 읽는다 — 설계서 2절이 금지한 "책임 중복"(MOVE 점유·계획·
     * 착지 안전·문·유격)에는 해당하지 않는다.
     */
    @Nullable
    private Objective currentObjective() {
        if (this.mob.isRetreating()) {
            Path path = this.mob.getNavigation().getPath();
            BlockPos target = path == null ? null : path.getTarget();
            if (target == null) {
                return null;
            }
            return new Objective(WardenGirlSpecialMovement.Purpose.RETREAT_DESTINATION,
                    Vec3.atBottomCenterOf(target), null);
        }
        LivingEntity target = this.mob.getTarget();
        if (target != null && this.mob.isValidCombatTarget(target)) {
            return new Objective(WardenGirlSpecialMovement.Purpose.COMBAT_TARGET,
                    target.position(), target.getUUID());
        }
        Vec3 dest = this.mob.getTestDestination();
        if (dest != null) {
            return new Objective(WardenGirlSpecialMovement.Purpose.TEST_DESTINATION, dest, null);
        }
        Player owner = this.mob.serverOwner();
        if (this.mob.hasOwner() && owner != null && owner.isAlive()
                && owner.level() == this.mob.level() && this.mob.localAnchor() == null) {
            return new Objective(WardenGirlSpecialMovement.Purpose.OWNER_FOLLOW,
                    owner.position(), owner.getUUID());
        }
        return null;
    }

    private boolean objectiveStillFits(Objective now, WallGeometry geom) {
        return switch (now.purpose()) {
            case OWNER_FOLLOW, COMBAT_TARGET ->
                    java.util.Objects.equals(now.relatedEntity(), geom.relatedEntity())
                    && WardenGirlMovementSafety.improvesProgress(
                            this.mob.position(), geom.landing(), now.goal());
            case TEST_DESTINATION, RETREAT_DESTINATION ->
                    now.goal().distanceToSqr(geom.goal()) <= 1.0E-4D;
        };
    }

    // ---- 공통 ------------------------------------------------------------------------------

    public void cancelApproach() {
        clearApproach();
    }

    private void clearApproach() {
        if (this.approach != null) {                              // T31V-TEMP
            com.wardengirl.WardenGirlMod.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "T31V ACLEAR t=%d pos=(%.3f,%.3f,%.3f) wall=%s",
                    this.mob.level().getGameTime(), this.mob.getX(), this.mob.getY(),
                    this.mob.getZ(), this.approach.wall().toShortString()));
        }
        this.approach = null;
        this.stalledTicks = 0;
    }

    private boolean standable(Vec3 feet) {
        Level level = this.mob.level();
        AABB box = WardenGirlMovementSafety.destinationBox(this.mob, feet);
        return WardenGirlMovementSafety.chunksLoaded(level, box)
                && level.noCollision(this.mob, box)
                && flushFloor(feet, box)
                && WardenGirlMovementSafety.safeFloorBlock(level, BlockPos.containing(feet));
    }

    private boolean flushFloor(Vec3 feet, AABB box) {
        AABB probe = new AABB(box.minX, feet.y - FLUSH_PROBE_DEPTH, box.minZ,
                box.maxX, feet.y, box.maxZ);
        return !this.mob.level().noCollision(this.mob, probe);
    }

    private double gravity() {
        var attr = this.mob.getAttribute(net.minecraftforge.common.ForgeMod.ENTITY_GRAVITY.get());
        return attr == null ? DEFAULT_GRAVITY : attr.getValue();
    }

    private double blockFriction() {
        BlockPos below = BlockPos.containing(this.mob.position()).below();
        Level level = this.mob.level();
        return level.getBlockState(below).getFriction(level, below, this.mob);
    }

    @Nullable
    private Vec3 pathDirection(@Nullable Path path) {
        if (path == null || path.isDone()) {
            return null;
        }
        return flat(path.getNextEntityPos(this.mob).subtract(this.mob.position()));
    }

    private static boolean sameDirection(@Nullable Vec3 a, @Nullable Vec3 b) {
        return a != null && b != null && a.distanceToSqr(b) < 1.0E-6D;
    }

    @Nullable
    private static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0.0D, v.z);
        return f.lengthSqr() < MIN_DIRECTION_SQR ? null : f.normalize();
    }

    /** 계획이 끝날 때 T28 이 부른다. 실행 상태만 지운다. */
    void reset() {
        this.geometry = null;
        this.solution = null;
        this.launched = false;
        this.rebounded = false;
        this.aborted = false;
        this.airborne = false;
        this.airTicks = 0;
        clearApproach();
    }
}
