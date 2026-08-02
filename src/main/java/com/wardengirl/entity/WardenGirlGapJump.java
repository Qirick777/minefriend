package com.wardengirl.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * T29 — 같은 높이 <b>유격 1~3블록</b> 일반 점프. 4차 설계서 T29.
 *
 * <h2>책임</h2>
 *
 * 진행 방향 계산, 출발 가장자리 확인, 유격 길이 측정, 같은 높이 착지 지면 탐색, 실제 바닐라
 * 물리에 맞춘 궤적 계산, T28 {@link WardenGirlSpecialMovement.Plan} 생성과 시작 요청,
 * 실행 중 tick, 착지·실패 판정이 전부 여기 있다.
 *
 * <p>상태·계획·실패 기억은 {@link WardenGirlSpecialMovement} 가, 안전 판정은
 * {@link WardenGirlMovementSafety} 가, MOVE 점유는 {@link WardenGirlSpecialMovementGoal} 이
 * 그대로 맡는다 — 그 세 책임을 여기서 복제하지 않는다.
 *
 * <h2>바닐라 경로 우선</h2>
 *
 * 목적지까지 닿는 정상 path 가 있으면 아무것도 하지 않는다. path 가 없거나 유격 앞에서
 * 부분 경로로 끝났을 때만 유격을 본다. 앞에 구멍이 있다는 이유만으로는 뛰지 않는다.
 *
 * <h2>순간이동·보정 없음</h2>
 *
 * 위치를 직접 쓰지 않는다. 도약 순간 속도를 <b>한 번</b> 넣고 나머지는 바닐라 중력·항력·
 * 충돌이 전부 처리한다. 공중 재추진도, 착지 스냅도, 속도 원복도 없다.
 */
public final class WardenGirlGapJump {

    // ---- 유격 분류 -------------------------------------------------------------------------

    /** T29 가 다루는 최소 유격(블록). */
    public static final int MIN_GAP = 1;
    /** T29 가 다루는 최대 유격(블록). 4 이상은 T30 영역이라 여기서 거절한다. */
    public static final int MAX_GAP = 3;
    /** 진행 방향으로 훑어보는 최대 거리(블록). 유격 최대 + 출발 칸 + 착지 칸 + 착지 뒤 한 칸. */
    private static final double SCAN_DISTANCE = 5 + 3.0D;   // DIVE_MAX_GAP + 출발/착지/착지 뒤
    /** 블록 열을 빠짐없이 지나가기 위한 훑기 간격. */
    private static final double SCAN_STEP = 0.2D;

    // ---- 방향 -----------------------------------------------------------------------------

    /** 이보다 짧은 수평 방향 벡터로는 후보를 만들지 않는다. */
    private static final double MIN_DIRECTION_SQR = 1.0E-4D;

    // ---- 물리 (바이트코드 확인값) -----------------------------------------------------------
    //
    // LivingEntity.travel 기준이다.
    //   d0(중력)        = 0.08, forge:entity_gravity 속성이 있으면 그 값
    //   수직 항력       = 0.98
    //   수평 항력       = onGround ? blockFriction * 0.91 : 0.91
    //     — onGround 는 move() <b>전</b>에 읽으므로 도약 tick 한 번은 지면 마찰이 걸린다.
    //   점프 초기 수직속도 = LivingEntity.getJumpPower() = 0.42 * blockJumpFactor + 점프부스트

    /** 중력 속성이 없을 때의 바닐라 기본값. */
    private static final double DEFAULT_GRAVITY = 0.08D;
    /** 수직 속도에 매 tick 곱하는 값. */
    private static final double VERTICAL_DRAG = 0.98D;
    /** 공중 수평 항력. */
    private static final double AIR_DRAG = 0.91D;

    /**
     * 허용하는 최대 도약 수평 속도(블록/tick). 이보다 큰 값이 필요하면 점프하지 않는다 —
     * 일반 점프로 보이지 않을 만큼 빠른 도약을 임의로 확정하지 않기 위한 상한이다.
     */
    public static final double MAX_TAKEOFF_SPEED = 0.80D;

    // ---- T30 다이브 (유격 4~5) --------------------------------------------------------------

    /** T30 이 다루는 최소 유격. 이 미만은 T29 일반 점프다. */
    public static final int DIVE_MIN_GAP = 4;
    /** T30 이 다루는 최대 유격. 6 이상은 어느 쪽도 아니다. */
    public static final int DIVE_MAX_GAP = 5;
    /**
     * 다이브 전용 수직 초기 속도. 바닐라 점프(0.42)보다 약간 높여 비행 시간을 벌고 도약이
     * 힘있게 읽히게 한다. 거리별 표를 두지 않고 이 값 하나로 비행 tick 을 계산한다.
     */
    public static final double DIVE_JUMP_POWER = 0.50D;
    /** 다이브 전용 최대 도약 수평 속도. 하나만 쓴다. */
    public static final double DIVE_MAX_TAKEOFF_SPEED = 1.30D;

    /** 예상 비행 tick 에 더해 주는 실행 제한 여유. */
    private static final int EXECUTION_MARGIN_TICKS = 10;
    /** 도약 직후 이 tick 안에 공중에 뜨지 않으면 실행 실패다. */
    private static final int TAKEOFF_GRACE_TICKS = 3;

    /** 착지 판정에서 허용하는 높이 오차. */
    private static final double LANDING_Y_TOLERANCE = 0.51D;
    /** 계획 착지점보다 이만큼 앞이면 착지로 인정한다(정확한 중심 일치를 요구하지 않는다). */
    private static final double LANDING_BACK_TOLERANCE = 0.60D;
    /** 계획 착지 높이보다 이만큼 아래로 내려가면 떨어진 것이다. */
    private static final double FALL_TOLERANCE = 1.20D;

    /** 지면 판정을 위한 <b>얇은</b> 발밑 탐침 깊이. 반 블록 단차를 지면으로 인정하지 않는다. */
    private static final double FLUSH_PROBE_DEPTH = 0.02D;
    /** 궤적 표본을 만들 때 한 tick 이동을 잘게 쪼개는 목표 간격. */
    private static final double SAMPLE_STEP = 0.40D;

    private final WardenGirlEntity mob;

    /** 실행 중 한 번이라도 공중에 떴는가. */
    private boolean airborne;
    /** 실행 중 실제로 공중에 있던 tick. */
    private int airTicks;
    /** 이번 계획의 유격 길이. 보고·state get 용이다. */
    private int gapLength;
    /** 이번 계획의 예상 비행 tick. */
    private int predictedFlightTicks;
    /** 이번 계획의 도약 순간 속도. */
    @Nullable
    private Vec3 takeoffVelocity;

    WardenGirlGapJump(WardenGirlEntity mob) {
        this.mob = mob;
    }

    // ---- 조회 (state get) --------------------------------------------------------------------

    public int gapLength() {
        return this.gapLength;
    }

    public int predictedFlightTicks() {
        return this.predictedFlightTicks;
    }

    public int airTicks() {
        return this.airTicks;
    }

    @Nullable
    public Vec3 takeoffVelocity() {
        return this.takeoffVelocity;
    }

    // ---- 시작 --------------------------------------------------------------------------------

    /**
     * 매 서버 tick {@link WardenGirlEntity#customServerAiStep()} 이 부른다. 조건이 하나라도
     * 맞지 않으면 아무것도 하지 않는다 — 평지 이동에서는 여기서 전부 걸러진다.
     */
    void tryStart() {
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        if (sm.isActive() || this.mob.isPassenger()) {
            clearApproach();
            return;
        }
        if (!this.mob.onGround() || this.approach != null) {
            return;
        }
        Objective objective = currentObjective();
        if (objective == null) {
            return;
        }
        Path path = this.mob.getNavigation().getPath();
        if (path != null && path.canReach()) {
            return;                                 // 정상 바닐라 경로가 있다 — 개입하지 않는다
        }
        Vec3 pathDir = pathDirection(path);
        Vec3 goalDir = flat(objective.goal().subtract(this.mob.position()));
        GapGeometry geom = pathDir == null ? null : findGeometry(pathDir, objective);
        if (geom == null && goalDir != null && !sameDirection(pathDir, goalDir)) {
            geom = findGeometry(goalDir, objective);
        }
        if (geom == null) {
            return;
        }
        if (geom.gap() >= DIVE_MIN_GAP) {
            // T30 — 다이브는 즉석 도약이 없다. 가장자리까지 접근한 뒤 정지·준비를 거친다.
            if (geom.takeoffTarget() == null) {
                return;
            }
            if (this.mob.position().distanceTo(geom.takeoffTarget()) <= APPROACH_ARRIVE) {
                beginDive(geom, objective);
            } else {
                this.approach = geom;
                this.lastDistance = this.mob.position().distanceTo(geom.takeoffTarget());
                this.stalledTicks = 0;
            }
            return;
        }
        JumpSolution sol = solveFrom(this.mob.position(), geom);
        if (sol != null) {
            jump(geom, sol, objective);
            return;
        }
        // 속도 상한 초과는 "지형 후보 없음" 이 아니다. 도약 가능한 위치까지 걸어간다.
        if (geom.takeoffTarget() != null
                && geom.takeoffTarget().distanceToSqr(this.mob.position()) > 1.0E-4D) {
            this.approach = geom;
            this.lastDistance = this.mob.position().distanceTo(geom.takeoffTarget());
            this.stalledTicks = 0;
        }
    }

    /**
     * T30 — 준비 시작. 도약 위치에서 다이브 물리 해가 나올 때만 T28 Plan 을 시작하고
     * {@code GAP_DIVE_PREPARE} 로 들어간다. 발사는 {@link #tickDive} 가 READY 뒤에 한다.
     */
    private void beginDive(GapGeometry geom, Objective objective) {
        clearApproach();
        JumpSolution sol = solveDive(this.mob.position(), geom);
        if (sol == null) {
            return;                                 // 여기서도 상한을 넘으면 다이브하지 않는다
        }
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        WardenGirlSpecialMovement.Plan plan = new WardenGirlSpecialMovement.Plan(
                WardenGirlSpecialMovement.Kind.GAP_DIVE,
                this.mob.position(), geom.landing(), objective.purpose(), objective.goal(),
                objective.relatedEntity(), this.mob.level().dimension(),
                this.mob.level().getGameTime(),
                this.mob.level().getGameTime()
                        + sol.flightTicks() + EXECUTION_MARGIN_TICKS,
                null, null, true);
        if (sm.tryBegin(plan) != null) {
            return;
        }
        this.airborne = false;
        this.airTicks = 0;
        this.gapLength = geom.gap();
        this.predictedFlightTicks = sol.flightTicks();
        this.takeoffVelocity = null;                // 발사 tick 에 실제 위치로 다시 푼다
        this.diveGeometry = geom;
        // 이동을 멈추고 <b>같은 tick 에</b> 발사한다. 눈에 보이는 차징 구간은 두지 않는다.
        this.mob.getNavigation().stop();
        this.mob.getMoveControl().setWantedPosition(
                this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0D);
        this.mob.setSprinting(false);
        launchDive(sol);
    }

    /**
     * 실제 발사. 초기 속도를 한 번 넣고 같은 tick 에 AIR 로 넘어가며 클립을 튼다.
     * {@code GAP_DIVE_PREPARE} 는 T28 이 계획 종류로부터 정하는 첫 상태라 한 tick 도 머물지
     * 않고 지나간다 — 사용자가 볼 수 있는 준비 자세는 없다.
     */
    private void launchDive(JumpSolution sol) {
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        // T30 — 도약 순간의 수평 방향이 공중 내내 몸통 방향을 소유한다. 공중에서 소유자·
        // 전투 대상이 움직여도 몸이 새 목표를 향해 뒤집히지 않게 한다.
        this.diveYaw = (float) (Mth.atan2(sol.velocity().z, sol.velocity().x)
                * (180.0D / Math.PI)) - 90.0F;
        this.diveFacingHeld = true;
        this.takeoffVelocity = sol.velocity();
        this.predictedFlightTicks = sol.flightTicks();
        this.mob.setDeltaMovement(sol.velocity());
        this.mob.hasImpulse = true;
        sm.transitionTo(WardenGirlSpecialMovement.State.GAP_DIVE_AIR);
        this.mob.playAction(com.wardengirl.anim.AnimRegistry.GAP_DIVE_AIR);
    }

    /** 도약 순간 확정된 진행 방향 yaw. 공중 구간 동안 몸통이 이 값을 유지한다. runtime only. */
    private float diveYaw;
    /** 방향 소유권이 살아 있는가. 착지·실패로 계획이 끝나면 해제한다. */
    private boolean diveFacingHeld;

    /** 다이브 중 머리가 발사 방향에서 벗어날 수 있는 최대 각. 몸통은 벗어나지 않는다. */
    private static final float DIVE_HEAD_FREEDOM_DEG = 45.0F;

    /**
     * T30 — 공중 구간의 방향 소유권. {@code aiStep()} 이 {@code super.aiStep()} <b>뒤</b>에
     * 부르므로 LookControl 과 {@code tickHeadTurn} 이 돌려놓은 값을 덮는다.
     *
     * <p>회전만 고정한다. 위치·속도·낙하는 건드리지 않고, yaw 를 목표를 향해 가속하지도
     * 않는다. 계획이 끝나면 소유권을 놓아 기존 시선·이동 체계로 돌아간다.
     */
    void holdDiveFacing() {
        if (!this.diveFacingHeld) {
            return;
        }
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        if (sm.getState() != WardenGirlSpecialMovement.State.GAP_DIVE_AIR
                && sm.getState() != WardenGirlSpecialMovement.State.GAP_DIVE_PREPARE) {
            this.diveFacingHeld = false;            // 착지·실패 — 소유권 해제
            return;
        }
        this.mob.setYRot(this.diveYaw);
        this.mob.yBodyRot = this.diveYaw;
        this.mob.yBodyRotO = this.diveYaw;
        float headOffset = Mth.wrapDegrees(this.mob.yHeadRot - this.diveYaw);
        this.mob.yHeadRot = this.diveYaw
                + Mth.clamp(headOffset, -DIVE_HEAD_FREEDOM_DEG, DIVE_HEAD_FREEDOM_DEG);
    }

    /** 이번 다이브의 지형. PREPARE 재검사와 발사 계산에 쓴다. runtime only. */
    @Nullable
    private GapGeometry diveGeometry;

    /** {@link WardenGirlSpecialMovement#tick()} 이 GAP_DIVE 일 때만 부른다. */
    void tickDive(WardenGirlSpecialMovement.Plan plan) {
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        Long limit = plan.expectedEndTick();
        if (limit != null && this.mob.level().getGameTime() > limit) {
            this.mob.playAction("");
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;
        }
        if (sm.getState() == WardenGirlSpecialMovement.State.GAP_DIVE_PREPARE) {
            // 같은 tick 에 AIR 로 넘어가므로 여기 남아 있으면 발사가 실패한 것이다.
            this.mob.playAction("");
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;
        }
        // ---- GAP_DIVE_AIR ----
        if (!this.airborne) {
            if (!this.mob.onGround()) {
                this.airborne = true;
            } else if (sm.getStateAge() > TAKEOFF_GRACE_TICKS) {
                this.mob.playAction("");
                sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            }
            return;
        }
        this.airTicks++;
        if (this.mob.getY() < plan.landing().y - FALL_TOLERANCE) {
            this.mob.playAction("");
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;
        }
        if (!this.mob.onGround()) {
            return;
        }
        if (landedOnPlan(plan)) {
            // 실제 착지를 확인한 tick 에만 land 를 튼다. 별도 서버 상태는 두지 않는다.
            this.mob.playActionFor(com.wardengirl.anim.AnimRegistry.GAP_DIVE_LAND,
                    com.wardengirl.anim.AnimRegistry.GAP_DIVE_LAND_TICKS);
            sm.finishSuccess();
        } else {
            this.mob.playAction("");
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
        }
    }

    private void jump(GapGeometry geom, JumpSolution sol, Objective objective) {
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        WardenGirlSpecialMovement.Plan plan = new WardenGirlSpecialMovement.Plan(
                WardenGirlSpecialMovement.Kind.SPRINT_GAP_JUMP,
                sol.origin(), geom.landing(), objective.purpose(), objective.goal(),
                objective.relatedEntity(), this.mob.level().dimension(),
                this.mob.level().getGameTime(),
                this.mob.level().getGameTime() + sol.flightTicks() + EXECUTION_MARGIN_TICKS,
                null, null, true);
        clearApproach();
        if (sm.tryBegin(plan) != null) {
            return;
        }
        this.airborne = false;
        this.airTicks = 0;
        this.gapLength = geom.gap();
        this.predictedFlightTicks = sol.flightTicks();
        this.takeoffVelocity = sol.velocity();
        launch(sol.velocity());
    }

    // ---- 가장자리 접근 (runtime only, 저장하지 않는다) --------------------------------------

    @Nullable
    private GapGeometry approach;
    private double lastDistance;
    private int stalledTicks;

    private static final int APPROACH_STALL_LIMIT = 40;
    private static final double APPROACH_MIN_GAIN = 0.01D;
    private static final double APPROACH_ARRIVE = 0.35D;

    public boolean hasApproachTarget() {
        return this.approach != null;
    }

    private void clearApproach() {
        this.approach = null;
        this.stalledTicks = 0;
    }

    /**
     * 접근 중 원래 목적이 아직 이 착지점과 맞는가.
     *
     * <p>고정 좌표 목적({@code TEST_DESTINATION}·{@code RETREAT_DESTINATION})은 좌표가 그대로여야
     * 하지만, 살아 움직이는 목적({@code OWNER_FOLLOW}·{@code COMBAT_TARGET})은 대상이 조금
     * 움직였다는 이유만으로 취소하지 않는다. 같은 대상인지와, <b>지금</b> 대상 위치에 대해
     * 착지점이 여전히 진행 개선인지만 본다.
     */
    private boolean objectiveStillFits(Objective now, GapGeometry geom) {
        return switch (now.purpose()) {
            case OWNER_FOLLOW, COMBAT_TARGET ->
                    java.util.Objects.equals(now.relatedEntity(), geom.relatedEntity())
                    && WardenGirlMovementSafety.improvesProgress(
                            this.mob.position(), geom.landing(), now.goal());
            case TEST_DESTINATION, RETREAT_DESTINATION ->
                    now.goal().distanceToSqr(geom.goal()) <= 1.0E-4D;
        };
    }

    /** 관리자 취소용. 접근만 즉시 지운다 — 실패 기억을 만들지 않는다. */
    public void cancelApproach() {
        clearApproach();
    }

    /** 접근 Goal 이 매 tick 부른다. 실패 기억은 만들지 않는다. */
    void tickApproach() {
        GapGeometry geom = this.approach;
        if (geom == null) {
            return;
        }
        Objective now = currentObjective();
        if (now == null || now.purpose() != geom.purpose() || !objectiveStillFits(now, geom)
                || this.mob.specialMovement().isActive() || !this.mob.onGround()
                || this.mob.level().dimension() != geom.dimension()
                || geom.takeoffTarget() == null
                || !WardenGirlMovementSafety.safeLanding(this.mob, geom.landing())
                || !standable(geom.takeoffTarget())) {
            clearApproach();
            return;
        }
        if (geom.gap() < DIVE_MIN_GAP) {
            JumpSolution sol = solveFrom(this.mob.position(), geom);
            if (sol != null) {
                jump(geom, sol, now);
                return;
            }
        }
        double d = this.mob.position().distanceTo(geom.takeoffTarget());
        if (d <= APPROACH_ARRIVE) {
            if (geom.gap() >= DIVE_MIN_GAP) {
                beginDive(geom, now);               // 다이브는 가장자리에서 준비로 들어간다
            } else {
                clearApproach();                    // 도착했는데도 상한 초과 — 점프하지 않는다
            }
            return;
        }
        if (this.lastDistance - d < APPROACH_MIN_GAIN) {
            if (++this.stalledTicks > APPROACH_STALL_LIMIT) {
                clearApproach();
                return;
            }
        } else {
            this.stalledTicks = 0;
        }
        this.lastDistance = d;
        Vec3 t = geom.takeoffTarget();
        // 가장자리 근처에서는 걸음을 늦춰 관성 미끄러짐을 줄인다. 속도를 직접 깎지는 않는다.
        this.mob.getMoveControl().setWantedPosition(t.x, t.y, t.z,
                d < 2.0D ? 1.0D : WardenGirlFollowOwnerGoal.CRUISE_SPEED);
    }

    /**
     * 실제 도약. 속도를 <b>한 번</b> 넣는 것이 전부다.
     *
     * <p>수직 성분은 바닐라 {@link net.minecraft.world.entity.LivingEntity#jumpFromGround()} 가
     * 넣는다 — 점프 부스트·블록 점프 계수 같은 실제 효과의 의미를 그대로 유지하기 위해서다.
     * 그 메서드는 x·z 를 건드리지 않으므로 수평 성분은 미리 넣어 둔다.
     *
     * <p>달리기 상태에서는 바닐라가 <b>시선 방향</b>으로 0.2 를 더한다. 시선은 진행 방향과
     * 다를 수 있어 궤적이 옆으로 꺾이므로 도약 순간에는 달리기를 끈다.
     */
    private void launch(Vec3 velocity) {
        this.mob.getNavigation().stop();
        // 공중에서 MoveControl 이 다시 밀지 않게 한다. 현재 위치를 목표로 주면 바닐라가
        // 스스로 zza 를 0 으로 두고 WAIT 로 넘어간다 — 속도를 직접 건드리지 않는 방법이다.
        this.mob.getMoveControl().setWantedPosition(
                this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0D);
        this.mob.setSprinting(false);
        this.mob.setDeltaMovement(velocity.x, this.mob.getDeltaMovement().y, velocity.z);
        this.mob.gapJumpFromGround();               // 수직 성분은 바닐라 경로로
    }

    // ---- 실행 --------------------------------------------------------------------------------

    /** {@link WardenGirlSpecialMovement#tick()} 이 SPRINT_GAP_JUMP 일 때만 부른다. */
    void tickActive(WardenGirlSpecialMovement.Plan plan) {
        WardenGirlSpecialMovement sm = this.mob.specialMovement();
        long age = sm.getStateAge();
        Long limit = plan.expectedEndTick();
        if (limit != null && this.mob.level().getGameTime() > limit) {
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;
        }
        if (!this.airborne) {
            if (!this.mob.onGround()) {
                this.airborne = true;
            } else if (age > TAKEOFF_GRACE_TICKS) {
                sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            }
            return;                                 // 아직 지면을 떠나지 않았다
        }
        this.airTicks++;
        if (this.mob.getY() < plan.landing().y - FALL_TOLERANCE) {
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
            return;                                 // 유격 아래로 떨어졌다. 구조하지 않는다
        }
        if (!this.mob.onGround()) {
            return;
        }
        if (landedOnPlan(plan)) {
            sm.finishSuccess();
        } else {
            sm.fail(WardenGirlSpecialMovement.Reason.EXECUTION_FAILED);
        }
    }

    /**
     * 계획한 착지 지면에 실제로 안전하게 섰는가. 4차 설계서 11절 — 중심 좌표 완전 일치를
     * 요구하지 않고, 같은 높이의 안전한 착지 영역 안이면 성공으로 본다.
     */
    private boolean landedOnPlan(WardenGirlSpecialMovement.Plan plan) {
        Vec3 feet = this.mob.position();
        if (Math.abs(feet.y - plan.landing().y) > LANDING_Y_TOLERANCE) {
            return false;
        }
        BlockPos feetPos = BlockPos.containing(feet);
        if (!WardenGirlMovementSafety.safeFloorBlock(this.mob.level(), feetPos)) {
            return false;
        }
        Vec3 dir = plan.landing().subtract(plan.origin());
        Vec3 flat = new Vec3(dir.x, 0.0D, dir.z);
        if (flat.lengthSqr() < MIN_DIRECTION_SQR) {
            return false;
        }
        Vec3 unit = flat.normalize();
        double planned = plan.landing().subtract(plan.origin()).dot(unit);
        double actual = feet.subtract(plan.origin()).dot(unit);
        return actual >= planned - LANDING_BACK_TOLERANCE;
    }

    // ---- 후보 탐색 ---------------------------------------------------------------------------

    /**
     * 진행 방향. 4차 설계서 5.1 — 시선이 아니라 실제 이동 목적을 기준으로 한다. 현재 path 의
     * 다음 노드를 먼저 쓰고, 쓸 수 없으면 목적 좌표를 쓴다. 수평 성분만 정규화한다.
     */
    @Nullable
    private Vec3 pathDirection(@Nullable Path path) {
        if (path == null || path.isDone()) {
            return null;
        }
        return flat(path.getNextEntityPos(this.mob).subtract(this.mob.position()));
    }

    /** 정규화된 두 방향이 사실상 같은가. 각도 문턱이 아니라 점 비교다. */
    private static boolean sameDirection(@Nullable Vec3 a, @Nullable Vec3 b) {
        return a != null && b != null && a.distanceToSqr(b) < 1.0E-6D;
    }

    @Nullable
    private static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0.0D, v.z);
        return f.lengthSqr() < MIN_DIRECTION_SQR ? null : f.normalize();
    }

    /** 열 하나의 분류. */
    private enum Column {
        /** 같은 높이에 안전하게 설 수 있는 지면. */
        GROUND,
        /** 몸이 지나갈 수 있는 빈 유격. */
        EMPTY,
        /** 지형·울타리·문·유체·위험 블록 등. 유격으로도 지면으로도 인정하지 않는다. */
        BLOCKED
    }

    /** 지형 연결만 표현한다. 물리 해가 없어도 폐기하지 않는다. */
    public record GapGeometry(Vec3 direction, BlockPos originSupport, int gap,
                              BlockPos landingSupport, Vec3 landing, @Nullable Vec3 takeoffTarget,
                              WardenGirlSpecialMovement.Purpose purpose, Vec3 goal,
                              @Nullable UUID relatedEntity,
                              net.minecraft.resources.ResourceKey<Level> dimension) {
    }

    /** 실제 출발 위치 기준의 물리 해. */
    public record JumpSolution(Vec3 origin, Vec3 velocity, int flightTicks, List<Vec3> samples) {
    }

    /**
     * 진행 방향을 따라 국소적으로 열을 훑어 유격 후보를 만든다. 4차 설계서 5.2~5.4.
     *
     * <p>월드 축으로 제한하지 않는다 — 대각선 접근이면 대각선으로 훑는다.
     */
    @Nullable
    private GapGeometry findGeometry(Vec3 dir, Objective objective) {
        Vec3 feet = this.mob.position();
        int floorY = BlockPos.containing(feet).below().getY();

        List<BlockPos> columns = new ArrayList<>();
        BlockPos prev = null;
        for (double t = 0.0D; t <= SCAN_DISTANCE; t += SCAN_STEP) {
            Vec3 p = feet.add(dir.scale(t));
            BlockPos col = new BlockPos(Mth.floor(p.x), floorY, Mth.floor(p.z));
            if (!col.equals(prev)) {
                columns.add(col);
                prev = col;
            }
        }
        if (columns.size() < 3) {
            return null;
        }
        // 5.2 — 지금 서 있는 칸이 곧 출발 가장자리여야 한다. 앞칸이 아직 지면이면
        // 기존 이동으로 더 걸어간다. 가장자리로 순간이동시키지 않는다.
        if (classify(columns.get(0), floorY) != Column.GROUND) {
            return null;
        }
        int gap = 0;
        while (gap + 1 < columns.size()
                && classify(columns.get(gap + 1), floorY) == Column.EMPTY) {
            gap++;
            if (gap > DIVE_MAX_GAP) {
                return null;                        // 6칸 이상 — T29 도 T30 도 아니다                        // 4칸 이상 — T29 가 아니다
            }
        }
        if (gap < MIN_GAP) {
            return null;                            // 바로 앞이 지면이거나 막혀 있다
        }
        int landingIndex = gap + 1;
        if (landingIndex >= columns.size()
                || classify(columns.get(landingIndex), floorY) != Column.GROUND) {
            return null;                            // 착지 지면이 없거나 높이가 다르다
        }
        // 5.4 — 착지 뒤로 진행 방향 한 칸은 실제로 설 수 있어야 한다
        if (landingIndex + 1 >= columns.size()
                || classify(columns.get(landingIndex + 1), floorY) != Column.GROUND) {
            return null;
        }
        BlockPos landingCol = columns.get(landingIndex);
        Vec3 landing = new Vec3(landingCol.getX() + 0.5D, floorY + 1.0D, landingCol.getZ() + 0.5D);
        if (!WardenGirlMovementSafety.safeLanding(this.mob, landing)) {
            return null;
        }
        if (!WardenGirlMovementSafety.improvesProgress(feet, landing, objective.goal())) {
            return null;                            // 목적에서 멀어지는 방향이다
        }
        return new GapGeometry(dir, BlockPos.containing(feet).below(), gap, landingCol, landing,
                takeoffTarget(dir, floorY), objective.purpose(), objective.goal(),
                objective.relatedEntity(), this.mob.level().dimension());
    }

    /**
     * 출발 지면 위에서 진행 방향으로 가장 앞선, 실제 AABB 가 완전히 지지되는 위치.
     * 임의 여백 상수를 쓰지 않고 {@code getBoundingBox()} 를 옮겨 검사한다.
     */
    @Nullable
    private Vec3 takeoffTarget(Vec3 dir, int floorY) {
        Vec3 feet = this.mob.position();
        Vec3 best = null;
        double half = this.mob.getBbWidth() / 2.0D;
        for (double t = 0.0D; t <= 2.0D; t += 0.05D) {
            Vec3 p = feet.add(dir.scale(t));
            Vec3 q = new Vec3(p.x, floorY + 1.0D, p.z);
            // 발 중심만이 아니라 진행 방향 앞쪽 발끝까지 지면 열 위에 있어야 한다 — 접근
            // 관성으로 몇 틱 미끄러져도 발 중심이 유격 열로 넘어가지 않게 반폭만큼 물러선다.
            boolean leadingOk = WardenGirlMovementSafety.safeFloorBlock(this.mob.level(),
                    BlockPos.containing(q.add(dir.scale(half))));
            if (standable(q) && leadingOk) {
                best = q;
            } else if (best != null) {
                break;
            }
        }
        return best;
    }

    /** 이 발 위치에 실제 AABB 로 안전하게 설 수 있는가. */
    private boolean standable(Vec3 feet) {
        Level level = this.mob.level();
        AABB box = WardenGirlMovementSafety.destinationBox(this.mob, feet);
        return WardenGirlMovementSafety.chunksLoaded(level, box)
                && level.noCollision(this.mob, box)
                && flushFloor(feet, box)
                && WardenGirlMovementSafety.safeFloorBlock(level, BlockPos.containing(feet));
    }

    /**
     * 같은 높이에 안전하게 설 수 있는 지면인가. {@link WardenGirlMovementSafety} 의 검사에
     * <b>얇은</b> 발밑 탐침을 더해 반 블록 단차를 지면으로 인정하지 않는다.
     */
    private Column classify(BlockPos column, int floorY) {
        Level level = this.mob.level();
        Vec3 standFeet = new Vec3(column.getX() + 0.5D, floorY + 1.0D, column.getZ() + 0.5D);
        AABB box = WardenGirlMovementSafety.destinationBox(this.mob, standFeet);
        if (!WardenGirlMovementSafety.chunksLoaded(level, box)) {
            return Column.BLOCKED;
        }
        boolean bodyClear = level.noCollision(this.mob, box);
        boolean support = flushFloor(standFeet, box);
        if (bodyClear && support
                && WardenGirlMovementSafety.safeFloorBlock(level, BlockPos.containing(standFeet))) {
            return Column.GROUND;
        }
        if (!bodyClear || support) {
            return Column.BLOCKED;                  // 막혔거나, 반 블록·중간 발판이 있다
        }
        // 몸은 지나가는데 지지면이 없다 = 빈 유격. 유체·위험 블록은 유격으로 인정하지 않는다.
        BlockPos gapPos = BlockPos.containing(standFeet);
        if (!level.getFluidState(column).isEmpty() || !level.getFluidState(gapPos).isEmpty()) {
            return Column.BLOCKED;
        }
        return Column.EMPTY;
    }

    /** 발밑 바로 아래 아주 얇은 층에 충돌 면이 있는가. 반 블록 낮은 바닥은 여기서 걸린다. */
    private boolean flushFloor(Vec3 feet, AABB box) {
        AABB probe = new AABB(box.minX, feet.y - FLUSH_PROBE_DEPTH, box.minZ,
                box.maxX, feet.y - 0.001D, box.maxZ);
        return !this.mob.level().noCollision(this.mob, probe);
    }

    // ---- 궤적 --------------------------------------------------------------------------------

    /**
     * 고정 착지점까지 닿는 초기 속도를 구하고, <b>같은 계산</b>으로 예상 궤적 표본을 만들어
     * 안전 검사까지 마친다. 4차 설계서 7·8절.
     *
     * <p>바닐라 물리에서 수직과 수평은 서로 영향을 주지 않으므로 비행 tick 수는 수평 속도와
     * 무관하다. 그래서 비행 tick 을 먼저 구하고, 그 tick 동안의 수평 이동 계수 K 를 더해
     * {@code s = 필요거리 / K} 로 초기 수평 속도를 한 번에 얻는다. 거리별 상수를 쓰지 않는다.
     */
    @Nullable
    private JumpSolution solveFrom(Vec3 feet, GapGeometry geom) {
        return solveWith(feet, geom, this.mob.gapJumpPower(), MAX_TAKEOFF_SPEED);
    }

    /** T30 — 다이브 물리 해. 수직 속도와 상한만 다르고 계산은 T29 와 같은 식이다. */
    @Nullable
    private JumpSolution solveDive(Vec3 feet, GapGeometry geom) {
        return solveWith(feet, geom, DIVE_JUMP_POWER, DIVE_MAX_TAKEOFF_SPEED);
    }

    @Nullable
    private JumpSolution solveWith(Vec3 feet, GapGeometry geom, double jumpPower,
                                   double maxSpeed) {
        Vec3 landing = geom.landing();
        int floorY = geom.landingSupport().getY();
        double gravity = gravity();
        int ticks = flightTicks(jumpPower, gravity);
        if (ticks < 2) {
            return null;
        }
        double groundDrag = groundFriction(floorY) * AIR_DRAG;
        double k = 1.0D;                            // 도약 tick 은 초기 속도 그대로 이동한다
        double factor = groundDrag;                 // 도약 tick 뒤의 수평 속도 계수
        for (int i = 1; i < ticks; i++) {
            k += factor;
            factor *= AIR_DRAG;
        }
        Vec3 delta = landing.subtract(feet);
        Vec3 flat = new Vec3(delta.x, 0.0D, delta.z);
        if (flat.lengthSqr() < MIN_DIRECTION_SQR) {
            return null;
        }
        Vec3 unit = flat.normalize();
        double needed = flat.length() / k;
        // 7.2 — 지금 앞으로 나아가는 속도가 이미 충분하면 그대로 살린다. 줄이지 않는다.
        double current = new Vec3(this.mob.getDeltaMovement().x, 0.0D,
                this.mob.getDeltaMovement().z).dot(unit);
        double speed = Math.max(needed, current);
        if (speed > maxSpeed) {
            return null;                            // 종류별 상한을 넘는 속도는 쓰지 않는다
        }
        Vec3 velocity = new Vec3(unit.x * speed, jumpPower, unit.z * speed);
        List<Vec3> samples = trajectory(feet, velocity, gravity, groundDrag, ticks);
        if (!WardenGirlMovementSafety.sweepClear(this.mob, samples)) {
            return null;
        }
        Vec3 predicted = samples.get(samples.size() - 1);
        if (!WardenGirlMovementSafety.safeLanding(this.mob, predicted)) {
            return null;                            // 실제로 닿을 자리가 안전한지도 본다
        }
        return new JumpSolution(feet, velocity, ticks, samples);
    }

    /** 발이 다시 출발 높이까지 내려오는 데 걸리는 tick. 수평 속도와 무관하다. */
    private static int flightTicks(double jumpPower, double gravity) {
        double vy = jumpPower;
        double y = 0.0D;
        for (int t = 1; t <= 200; t++) {
            y += vy;
            vy = (vy - gravity) * VERTICAL_DRAG;
            if (y <= 0.0D) {
                return t;
            }
        }
        return -1;
    }

    /**
     * 예상 궤적 표본. {@link #solve} 가 쓴 것과 <b>같은</b> 초기 속도·같은 항력으로 만든다 —
     * 검사용 공식과 실행용 공식을 따로 두지 않는다.
     *
     * <p>한 tick 이동이 {@link WardenGirlMovementSafety#MAX_SAMPLE_STEP} 보다 길 수 있으므로
     * tick 안을 다시 잘게 나눈다. 마지막 표본은 착지 높이로 맞춘다.
     */
    private static List<Vec3> trajectory(Vec3 start, Vec3 velocity, double gravity,
                                         double groundDrag, int ticks) {
        List<Vec3> out = new ArrayList<>();
        out.add(start);
        Vec3 pos = start;
        Vec3 v = velocity;
        for (int t = 1; t <= ticks; t++) {
            Vec3 next = pos.add(v);
            if (t == ticks) {
                next = new Vec3(next.x, start.y, next.z);   // 착지 tick 은 바닥에서 멈춘다
            }
            double len = next.subtract(pos).length();
            int parts = Math.max(1, Mth.ceil(len / SAMPLE_STEP));
            for (int i = 1; i <= parts; i++) {
                out.add(pos.add(next.subtract(pos).scale((double) i / parts)));
            }
            pos = next;
            double drag = t == 1 ? groundDrag : AIR_DRAG;
            v = new Vec3(v.x * drag, (v.y - gravity) * VERTICAL_DRAG, v.z * drag);
        }
        return out;
    }

    /** 현재 중력. forge:entity_gravity 속성이 있으면 그 값이 바닐라 travel 이 쓰는 값이다. */
    private double gravity() {
        var attr = this.mob.getAttribute(net.minecraftforge.common.ForgeMod.ENTITY_GRAVITY.get());
        return attr == null ? DEFAULT_GRAVITY : attr.getValue();
    }

    /** 도약 tick 에 걸리는 지면 마찰. 바닐라와 같은 발밑 블록을 본다. */
    private float groundFriction(int floorY) {
        BlockPos below = new BlockPos(Mth.floor(this.mob.getX()), floorY, Mth.floor(this.mob.getZ()));
        Level level = this.mob.level();
        return level.getBlockState(below).getFriction(level, below, this.mob);
    }

    // ---- 현재 이동 목적 ----------------------------------------------------------------------

    private record Objective(WardenGirlSpecialMovement.Purpose purpose, Vec3 goal,
                             @Nullable UUID relatedEntity) {
    }

    /**
     * 지금 실제로 따르고 있는 이동 목적. Goal 우선순위와 같은 순서로 본다 —
     * 후퇴(2) · 전투(4) · 시험 목적지(5) · 추종(6). 목적 없는 배회는 여기 없다.
     */
    @Nullable
    private Objective currentObjective() {
        if (this.mob.isRetreating()) {
            Path path = this.mob.getNavigation().getPath();
            BlockPos target = path == null ? null : path.getTarget();
            if (target == null) {
                return null;                        // 후퇴 목적지를 알 수 없으면 개입하지 않는다
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

    /** 계획이 끝날 때 T28 이 부른다. 실행 상태만 지운다. */
    void reset() {
        this.airborne = false;
        this.airTicks = 0;
        this.diveGeometry = null;
        this.diveFacingHeld = false;
        clearApproach();
    }
}
