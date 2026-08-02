package com.wardengirl.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * T28 — 특수 이동 <b>controller</b>. 4차 설계서 Part 2 · T28.
 *
 * <p>개체 하나에 하나이며 전부 서버 runtime 이다. NBT·SynchedEntityData·SavedData·전역
 * static·패킷 어디에도 남지 않으므로 청크 재로드·재접속 뒤에는 {@link State#NONE} 에서
 * 정상 AI 가 다시 계산된다.
 *
 * <h2>책임</h2>
 *
 * 현재 상태와 계획을 들고, 시작·전환·성공·실패·취소를 처리하고, 원래 이동 목적이 아직
 * 유효한지 확인하고, 같은 연결의 실패를 40틱 기억한다. <b>궤적도 후보 탐색도 여기 없다</b> —
 * 그것은 T29 이후 각 이동 태스크가 만들고 {@link #tryBegin} 으로 넘긴다.
 *
 * <p>안전 검사는 {@link WardenGirlMovementSafety} 에, MOVE 점유는
 * {@link WardenGirlSpecialMovementGoal} 에 있다. 세 책임을 섞지 않는다.
 */
public final class WardenGirlSpecialMovement {

    /** 4차 설계서 2.2 의 상태 전체. T28 에서 자연 발생하는 것은 {@link #NONE} 뿐이다. */
    public enum State {
        NONE,
        SPRINT_GAP_JUMP,
        GAP_DIVE_PREPARE,
        GAP_DIVE_AIR,
        WALL_REBOUND_RUNUP,
        WALL_REBOUND_AIR,
        SHORT_DROP,
        WALL_DESCENT_GRIP,
        WALL_DESCENT_RELEASE,
        SIDE_HOP,
        BACK_DODGE,
        DIAGONAL_COUNTER,
        PLAYER_AIM_EVADE
    }

    /** 계획의 종류. 상태와 <b>분리</b>돼 있다 — 한 종류가 여러 단계 상태를 지난다. */
    public enum Kind {
        SPRINT_GAP_JUMP,
        GAP_DIVE,
        WALL_REBOUND,
        SHORT_DROP,
        WALL_DESCENT,
        SIDE_HOP,
        BACK_DODGE,
        DIAGONAL_COUNTER,
        PLAYER_AIM_EVADE
    }

    /** 특수 이동이 이어받는 <b>원래</b> 이동 목적. 무작위 배회는 여기 없다(설계서 2.1). */
    public enum Purpose {
        TEST_DESTINATION,
        OWNER_FOLLOW,
        COMBAT_TARGET,
        RETREAT_DESTINATION
    }

    /** 실패·취소 이유. 보고와 {@code state get} 에만 쓴다. */
    public enum Reason {
        ALREADY_ACTIVE,
        NOT_ON_GROUND,
        PURPOSE_INVALID,
        ORIGIN_MOVED,
        LANDING_UNSAFE,
        PATH_UNSAFE,
        NO_PROGRESS,
        RECENT_FAILURE,
        LEASH,
        EXECUTION_FAILED,
        ADMIN_CANCEL,
        PURPOSE_LOST
    }

    /**
     * 시작 시점에 <b>고정</b>되는 일회성 계획. 불변이다 — 단계가 바뀌어도 출발점과 착지점은
     * 그대로다(설계서 2.7).
     *
     * @param kind             이동 종류
     * @param origin           출발 발 좌표
     * @param landing          고정 착지·종료 발 좌표
     * @param purpose          원래 이동 목적
     * @param goal             원래 목적 좌표. 대상 추격이면 시작 시점의 대상 좌표다
     * @param relatedEntity    관련 대상 UUID. 없으면 {@code null}
     * @param dimension        시작 차원
     * @param startGameTime    시작 서버 {@code gameTime}
     * @param expectedEndTick  예상 종료 {@code gameTime}. 모르면 {@code null}
     * @param wall             관련 벽 블록. 없으면 {@code null}
     * @param projectile       관련 투사체 UUID. 없으면 {@code null}
     * @param requiresGroundStart 시작 순간 지상이어야 하는가
     */
    public record Plan(Kind kind,
                       Vec3 origin,
                       Vec3 landing,
                       Purpose purpose,
                       Vec3 goal,
                       @Nullable UUID relatedEntity,
                       net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                       long startGameTime,
                       @Nullable Long expectedEndTick,
                       @Nullable BlockPos wall,
                       @Nullable UUID projectile,
                       boolean requiresGroundStart) {
    }

    /**
     * 같은 <b>연결</b>을 가리키는 키. 정밀 좌표가 아니라 지지 블록 위치를 쓴다 — 몇 센티미터
     * 흔들림 때문에 매번 다른 키가 되면 기억이 무의미해진다.
     */
    public record FailureKey(Kind kind, BlockPos originSupport, BlockPos landingSupport) {
    }

    /** 최근 실패 한 건. 캐시도 만료 Map 도 만들지 않는다. */
    public record Failure(FailureKey key, long gameTime, Reason reason) {
    }

    /** 같은 키를 다시 시도하기까지 기다리는 틱. 4차 설계서 2.8 의 확정값이다. */
    public static final long FAILURE_MEMORY_TICKS = 40L;

    /** 시작 직전 재검사에서 허용하는 출발점 이동량. 이보다 벌어지면 계획을 버린다. */
    public static final double ORIGIN_TOLERANCE = 0.75D;

    private final WardenGirlEntity mob;
    private State state = State.NONE;
    @Nullable
    private Plan plan;
    private long stateStartTick;
    @Nullable
    private Failure lastFailure;

    WardenGirlSpecialMovement(WardenGirlEntity mob) {
        this.mob = mob;
    }

    // ---- 조회 ------------------------------------------------------------------------------

    public boolean isActive() {
        return this.state != State.NONE;
    }

    public State getState() {
        return this.state;
    }

    /** 현재 상태가 시작된 뒤 흐른 서버 틱. 비활성이면 0. */
    public long getStateAge() {
        return this.state == State.NONE ? 0L : now() - this.stateStartTick;
    }

    @Nullable
    public Plan getPlan() {
        return this.plan;
    }

    @Nullable
    public Failure getLastFailure() {
        return this.lastFailure;
    }

    /** 실패 기억이 아직 남은 틱. 없거나 만료됐으면 0. */
    public long failureRemainingTicks() {
        if (this.lastFailure == null) {
            return 0L;
        }
        long passed = now() - this.lastFailure.gameTime();
        return passed >= FAILURE_MEMORY_TICKS ? 0L : FAILURE_MEMORY_TICKS - passed;
    }

    private long now() {
        return this.mob.level().getGameTime();
    }

    // ---- 시작 ------------------------------------------------------------------------------

    /**
     * 계획을 실제로 시작한다. 4차 설계서 2.7 · T28 12절의 최종 재검사를 <b>같은 서버 틱</b>에
     * 전부 다시 돌린다.
     *
     * <p>이미 활성이면 <b>반드시</b> 실패한다 — 한 개체에 특수 이동은 최대 하나다.
     *
     * @return 시작했으면 {@code null}, 거절했으면 이유
     */
    @Nullable
    public Reason tryBegin(Plan candidate) {
        if (isActive()) {
            return Reason.ALREADY_ACTIVE;           // 실패 연결로 기록하지 않는다
        }
        if (candidate.requiresGroundStart() && !this.mob.onGround()) {
            return Reason.NOT_ON_GROUND;
        }
        if (blockedByFailure(candidate)) {
            return Reason.RECENT_FAILURE;
        }
        if (!purposeValid(candidate)) {
            return failNow(candidate, Reason.PURPOSE_INVALID);
        }
        if (this.mob.position().distanceTo(candidate.origin()) > ORIGIN_TOLERANCE) {
            return failNow(candidate, Reason.ORIGIN_MOVED);
        }
        if (!WardenGirlMovementSafety.safeLanding(this.mob, candidate.landing())) {
            return failNow(candidate, Reason.LANDING_UNSAFE);
        }
        if (!WardenGirlMovementSafety.improvesProgress(
                candidate.origin(), candidate.landing(), candidate.goal())) {
            return failNow(candidate, Reason.NO_PROGRESS);
        }
        if (!leashOk(candidate)) {
            return failNow(candidate, Reason.LEASH);
        }
        this.plan = candidate;
        this.state = firstStateOf(candidate.kind());
        this.stateStartTick = now();
        return null;
    }

    /** 계획 종류의 첫 상태. 여러 단계를 가진 종류만 준비 단계에서 시작한다. */
    private static State firstStateOf(Kind kind) {
        return switch (kind) {
            case SPRINT_GAP_JUMP -> State.SPRINT_GAP_JUMP;
            case GAP_DIVE -> State.GAP_DIVE_PREPARE;
            case WALL_REBOUND -> State.WALL_REBOUND_RUNUP;
            case SHORT_DROP -> State.SHORT_DROP;
            case WALL_DESCENT -> State.WALL_DESCENT_GRIP;
            case SIDE_HOP -> State.SIDE_HOP;
            case BACK_DODGE -> State.BACK_DODGE;
            case DIAGONAL_COUNTER -> State.DIAGONAL_COUNTER;
            case PLAYER_AIM_EVADE -> State.PLAYER_AIM_EVADE;
        };
    }

    /** 실행 중 단계 전환. 계획은 그대로 두고 상태와 나이만 바꾼다. */
    public void transitionTo(State next) {
        if (this.state == State.NONE || next == State.NONE) {
            return;                                 // 시작·종료는 전용 API 로만 한다
        }
        this.state = next;
        this.stateStartTick = now();
    }

    // ---- 종료 ------------------------------------------------------------------------------

    /** 성공. 계획을 버리고 그 연결의 실패 기억을 지운 뒤 MOVE 를 돌려준다. */
    public void finishSuccess() {
        Plan p = this.plan;
        if (p != null && this.lastFailure != null
                && this.lastFailure.key().equals(keyOf(p))) {
            this.lastFailure = null;
        }
        release();
    }

    /** 실행 중 실패. 같은 연결을 40틱 기억한다. 되돌리기·순간이동·속도 롤백은 하지 않는다. */
    public void fail(Reason reason) {
        Plan p = this.plan;
        if (p != null) {
            this.lastFailure = new Failure(keyOf(p), now(), reason);
        }
        release();
    }

    /** 관리자 취소·목적 소멸. <b>실패 기억을 새로 만들지 않는다.</b> */
    public void cancel(Reason reason) {
        release();
    }

    /**
     * 상태를 비우고 MOVE 를 돌려준다. stale path 를 그대로 쓰지 않도록 navigation 을 멈추기만
     * 하고, 새 경로는 기존 Goal 이 다음 평가에서 스스로 만든다(route resume, 설계서 15절).
     */
    private void release() {
        this.mob.gapJump().reset();
        this.mob.wallRebound().reset();             // T31
        this.plan = null;
        this.state = State.NONE;
        this.stateStartTick = 0L;
        this.mob.getNavigation().stop();
        this.mob.resumeRouteAfterSpecialMovement();
    }

    // ---- 실패 기억 -------------------------------------------------------------------------

    /** 계획의 실패 키. 출발·착지의 <b>지지 블록</b>(발 아래 한 칸)을 쓴다. */
    public static FailureKey keyOf(Plan p) {
        return new FailureKey(p.kind(),
                BlockPos.containing(p.origin()).below(),
                BlockPos.containing(p.landing()).below());
    }

    private boolean blockedByFailure(Plan candidate) {
        Failure f = this.lastFailure;
        if (f == null) {
            return false;
        }
        if (!f.key().equals(keyOf(candidate))) {
            return false;                           // 다른 연결은 즉시 검사 가능
        }
        return now() - f.gameTime() < FAILURE_MEMORY_TICKS;
    }

    /** 구체적 계획이 만들어진 뒤의 거절이므로 실패 연결로 기록한다(설계서 13절). */
    private Reason failNow(Plan candidate, Reason reason) {
        this.lastFailure = new Failure(keyOf(candidate), now(), reason);
        return reason;
    }

    // ---- 원래 목적 -------------------------------------------------------------------------

    /**
     * 원래 이동 목적이 아직 유효한가. 4차 설계서 6절. 거리·leash 상수를 여기 복사하지 않고
     * 기존 판정 API 를 그대로 부른다.
     */
    public boolean purposeValid(Plan p) {
        if (this.mob.level().dimension() != p.dimension()) {
            return false;
        }
        return switch (p.purpose()) {
            case TEST_DESTINATION -> {
                Vec3 dest = this.mob.getTestDestination();
                yield dest != null && dest.distanceToSqr(p.goal()) < 1.0E-6D;
            }
            case OWNER_FOLLOW -> {
                Player owner = this.mob.serverOwner();
                yield this.mob.hasOwner() && owner != null && owner.isAlive()
                        && owner.level() == this.mob.level()
                        && this.mob.localAnchor() == null
                        && this.mob.distanceTo(owner) <= WardenGirlEntity.GIVE_UP_DISTANCE;
            }
            case COMBAT_TARGET -> {
                LivingEntity t = this.mob.getTarget();
                yield t != null && this.mob.isValidCombatTarget(t)
                        && Objects.equals(t.getUUID(), p.relatedEntity())
                        && this.mob.canKeepLeashedCombat(t);
            }
            case RETREAT_DESTINATION -> this.mob.isRetreating();
        };
    }

    /**
     * 기존 leash 를 그대로 재사용한다. 전투는 유지 목줄, 추종은 T14 장거리 포기, 후퇴는 행동
     * 중심 반경이다. 시험 목적지는 전투·후퇴가 시작되면 {@link #purposeValid} 쪽에서 양보한다.
     */
    private boolean leashOk(Plan p) {
        return switch (p.purpose()) {
            case COMBAT_TARGET -> {
                LivingEntity t = this.mob.getTarget();
                yield t != null && this.mob.canKeepLeashedCombat(t);
            }
            case OWNER_FOLLOW -> {
                Player owner = this.mob.serverOwner();
                yield owner != null
                        && owner.position().distanceTo(p.landing())
                        <= WardenGirlEntity.GIVE_UP_DISTANCE;
            }
            case RETREAT_DESTINATION -> this.mob.retreatCenter().distanceTo(p.landing())
                    <= WardenGirlRetreatGoal.CENTER_RADIUS;
            case TEST_DESTINATION -> true;
        };
    }

    /**
     * 매 서버틱 {@link WardenGirlSpecialMovementGoal} 이 부른다. T28 에는 실제로 굴릴 이동이
     * 없으므로 목적이 사라졌는지만 보고 사라졌으면 취소한다.
     */
    void tick() {
        Plan p = this.plan;
        if (p == null) {
            return;
        }
        if (!purposeValid(p)) {
            cancel(Reason.PURPOSE_LOST);
            return;
        }
        // T29 — 실제로 굴릴 이동이 생긴 종류는 각자의 helper 로 한 줄만 넘긴다.
        if (p.kind() == Kind.SPRINT_GAP_JUMP) {
            this.mob.gapJump().tickActive(p);
        } else if (p.kind() == Kind.GAP_DIVE) {
            this.mob.gapJump().tickDive(p);         // T30
        } else if (p.kind() == Kind.WALL_REBOUND) {
            this.mob.wallRebound().tickActive(p);   // T31
        }
    }
}
