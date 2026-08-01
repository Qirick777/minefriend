package com.wardengirl.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * T20 — 저체력 후퇴. 설계서 4.2.
 *
 * <p>체력이 25% 이하로 떨어지면 일반 전투를 통째로 접고 행동 중심 12블록 안에서 활성 위협과
 * 거리를 벌린다. 45% 이상으로 회복하면 원래 행동으로 돌아간다. 상태 자체는
 * {@link WardenGirlEntity#isRetreating()} 이 들고 있고 이 Goal 은 그것을 읽기만 한다.
 *
 * <h2>왜 MOVE 만 잡는가</h2>
 *
 * priority 1 에 MOVE 하나만 잡으면 소닉(2)·근접(3)·추종(5)·배회(6) 가 전부 MOVE 를 쓰므로
 * {@code GoalSelector} 가 그 넷을 선점하며 {@code stop()} 까지 불러 준다. LOOK 은 잡지 않는다 —
 * 도망가는 동안 고개는 바닐라 이동 방향 회전에 맡기는 편이 자연스럽고, 시선 Goal 을 굳이
 * 막을 이유도 없다.
 *
 * <p>다만 우선순위 충돌<b>만</b>에 의존하지 않는다. 근접과 소닉은 각자
 * {@code isRetreating()} 을 시작·유지 조건에 직접 넣어 두 겹으로 막는다.
 *
 * <h2>후보 경로를 깔기 전에 navigation 을 멈추는 이유</h2>
 *
 * {@code PathNavigation.createPath} 는 성공하면 {@code targetPos} 와 {@code reachRange} 를
 * 덮어쓴다(바이트코드 확인). 그 둘을 읽는 곳은 {@code recomputePath()} 인데
 * {@code shouldRecomputePath} 가 <b>활성 경로가 있을 때만</b> 참이 된다. 그래서 T14 와 같이
 * 경로가 없는 상태에서만 후보를 물어본다.
 *
 * <p>선택이 끝난 뒤에도 {@code targetPos} 에는 <b>마지막으로 물어본 후보</b>가 남는다 —
 * 공개 API 로 되돌릴 방법이 없다. 그래서 {@link #usable} 이 {@code path.getTarget()} 과 현재
 * 목적지가 같은지를 본다. 블록 변경으로 {@code recomputePath()} 가 엉뚱한 곳으로 경로를 다시
 * 깔면 그 검사가 어긋나 다음 재평가에서 목적지를 다시 고른다.
 */
public class WardenGirlRetreatGoal extends Goal {

    /** 설계서 4.2.4 — 후퇴 이동속도 배율. */
    public static final double RETREAT_SPEED = 1.6D;
    /** 설계서 4.2.3 — 행동 중심에서의 최대 후퇴 반경. */
    public static final double CENTER_RADIUS = 12.0D;
    /** 설계서 4.2.3 — 선호하는 중심과의 거리. */
    public static final double PREFER_MIN = 8.0D;
    public static final double PREFER_MAX = 12.0D;
    /** 설계서 4.2.3 — 적과의 권장 최소 거리. */
    public static final double THREAT_CLEARANCE = 8.0D;
    /** 설계서 4.2.4 — 안전 지점 재평가 주기(서버 틱). */
    public static final int REEVAL_INTERVAL = 10;
    /** 설계서 4.2.4 — 도착 판정. */
    public static final double ARRIVE_DISTANCE = 1.5D;
    /** 설계서 4.2.4 — 후보 수 상한. 바닐라 임의 위치 API 호출 횟수와 같다. */
    public static final int MAX_CANDIDATES = 12;

    /** 임의 위치 탐색 반경·높이. 바닐라 회피 Goal 과 같은 형태다. */
    private static final int SEARCH_RADIUS = 12;
    private static final int SEARCH_HEIGHT = 4;

    /**
     * 후보 경로 탐색 반경. {@code createPath(BlockPos, accuracy)} 는 탐색 반경으로
     * {@code Attributes.FOLLOW_RANGE}(이 엔티티는 기본값 16)를 쓰므로 반경을 직접 받는
     * 공개 오버로드를 써서 명시한다. 후보는 워든걸에서 12 안이라 16 이면 여유가 있다.
     */
    private static final int PATH_RANGE = 16;

    private final WardenGirlEntity mob;

    /** 현재 후퇴 목적지. {@code null} 이면 아직 고르지 못했다. */
    @Nullable
    private Vec3 destination;
    @Nullable
    private BlockPos destinationPos;

    /** 다음 재평가를 허용할 {@code mob.tickCount}. T19 와 같은 deadline 방식이다. */
    private int nextEvalTick;

    public WardenGirlRetreatGoal(WardenGirlEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return this.mob.isRetreating();
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isRetreating();
    }

    /** 진행 중이던 추격·추종 navigation 을 끊고 즉시 첫 안전 지점을 고른다. */
    @Override
    public void start() {
        this.mob.getNavigation().stop();
        this.destination = null;
        this.destinationPos = null;
        this.holding = false;
        this.nextEvalTick = this.mob.tickCount;      // 이번 틱에 바로 평가한다
        tick();
    }

    /**
     * T22 — 지금 도주(FLEE)가 아니라 제자리 대기(HOLD)인가.
     *
     * <p>영구 상태도 synched 값도 아니다. 재평가마다 활성 위협 유무 하나로 정해지는 이 Goal
     * 내부의 runtime boolean 이다. {@code isRetreating()} 은 저체력 보호 상태 그대로 유지되고,
     * 이 Goal 은 계속 실행되며 MOVE 도 놓지 않는다 — 놓으면 추종·배회가 끼어들어 적도 없는데
     * 다시 돌아다니게 된다.
     */
    private boolean holding;

    /** T22 — 지금 제자리 대기 중인가. 계측·보고용 조회다. */
    public boolean isHolding() {
        return this.holding;
    }

    @Override
    public void tick() {
        // T22 — 대상이 죽거나·제거되거나·다른 차원으로 갔으면 10틱 주기를 기다리지 않는다.
        // AABB 조회가 아니라 현재 target 한 개만 보는 값싼 검사다.
        LivingEntity target = this.mob.getTarget();
        if (!this.holding && target != null && !this.mob.isValidCombatTarget(target)) {
            this.nextEvalTick = this.mob.tickCount;
        }
        if (this.mob.tickCount - this.nextEvalTick < 0) {
            return;
        }
        this.nextEvalTick = this.mob.tickCount + REEVAL_INTERVAL;

        Vec3 center = this.mob.retreatCenter();
        List<LivingEntity> threats = WardenGirlThreats.collect(this.mob);
        PathNavigation nav = this.mob.getNavigation();

        // T22 4.2.6 — 활성 위협이 하나도 없으면 도주할 이유가 없다. 후보 생성도 Path probe 도
        // LandRandomPos 호출도 여기서 끊긴다.
        if (threats.isEmpty()) {
            if (!this.holding) {
                this.holding = true;
                nav.stop();
                this.destination = null;
                this.destinationPos = null;
            }
            return;
        }
        if (this.holding) {
            // 위협이 다시 생겼다. 이전 목적지를 되쓰지 않고 지금 위협 위치로 새로 고른다.
            this.holding = false;
            this.destination = null;
            this.destinationPos = null;
        }

        // 유효한 목적지를 붙들고 있는 동안에는 후보를 <b>아예 만들지 않는다</b>.
        boolean incumbentUsable = usable(center, nav);
        if (incumbentUsable
                && WardenGirlThreats.minDistance(this.destination, threats) >= THREAT_CLEARANCE) {
            return;
        }
        select(center, threats, nav, incumbentUsable);
    }

    /**
     * 현재 목적지를 그대로 쓸 수 있는가. 위협 거리는 여기서 보지 않는다 — 그것만 어긋난
     * 경우에는 후보를 다시 만들되 <b>사전식 비교에서 실제로 더 나은 후보</b>가 있을 때만
     * 바꾸기 때문이다(설계서 4.2.5).
     */
    private boolean usable(Vec3 center, PathNavigation nav) {
        if (this.destination == null || this.destinationPos == null) {
            return false;
        }
        if (this.destination.distanceToSqr(center) > CENTER_RADIUS * CENTER_RADIUS) {
            return false;                           // 행동 중심이 바뀌었거나 반경 밖이다
        }
        if (!safeSpot(this.destinationPos)) {
            return false;                           // 지형이 변했다
        }
        if (nav.isDone()) {
            return false;                           // 도착 전에 경로가 끝났다
        }
        Path path = nav.getPath();
        if (path == null || !this.destinationPos.equals(path.getTarget())) {
            return false;                           // 경로가 다른 곳을 향하고 있다
        }
        return this.mob.position().distanceTo(this.destination) > ARRIVE_DISTANCE;
    }

    /**
     * 후보를 최대 12개 만들어 사전식으로 고른다.
     *
     * @param keepIncumbent 현재 목적지가 위협 거리 말고는 멀쩡한가. 참이면 그 자리를 첫 후보로
     *                      세워, 새 후보가 <b>실제로 더 나을 때만</b> 교체되게 한다 — 10틱마다
     *                      흔들리지 않게 하는 유일한 장치다.
     */
    private void select(Vec3 center, List<LivingEntity> threats, PathNavigation nav,
                        boolean keepIncumbent) {
        boolean hasThreats = !threats.isEmpty();
        Vec3 threatCenter = WardenGirlThreats.center(threats);
        BlockPos incumbentPos = keepIncumbent ? this.destinationPos : null;

        // T14 와 같은 보호. 경로가 없는 상태에서만 후보를 물어본다.
        nav.stop();

        Candidate best = null;
        int made = 0;
        if (incumbentPos != null) {
            best = probe(incumbentPos, center, threats, threatCenter, nav);
            made++;
        }
        while (made < MAX_CANDIDATES) {
            // 두 방향을 번갈아 뽑는다. 바닐라 임의 위치 API 는 <b>워든걸</b> 기준으로 자리를
            // 만드는데 채택 조건은 <b>행동 중심</b> 기준 12블록이다. 위협 회피 방향만 뽑으면,
            // 소유자가 워든걸과 위협 사이에 놓이는 순간 "위협에서 먼 쪽" 과 "중심에 가까운 쪽"
            // 이 정반대가 되어 뽑는 족족 반경 밖으로 떨어진다 — 실측에서 워든걸이 경계
            // (중심 거리 11.82)에 닿은 뒤 후보 862개 중 856개가 반경에서 잘리고 65초 동안
            // 목적지를 하나도 고르지 못한 채 멈춰 섰다. 중심 방향 후보를 섞으면 그 자리에서도
            // 소유자를 돌아가는 자리가 나온다.
            boolean away = threatCenter != null && (made % 2 == 0);
            made++;
            Vec3 raw = away
                    ? LandRandomPos.getPosAway(this.mob, SEARCH_RADIUS, SEARCH_HEIGHT, threatCenter)
                    : LandRandomPos.getPosTowards(this.mob, SEARCH_RADIUS, SEARCH_HEIGHT, center);
            if (raw == null) {
                continue;
            }
            Candidate c = probe(BlockPos.containing(raw), center, threats, threatCenter, nav);
            if (c != null && (best == null || better(c, best, hasThreats))) {
                best = c;                           // 완전 동률이면 먼저 만든 쪽을 둔다
            }
        }

        if (best != null) {
            this.destination = best.point;
            this.destinationPos = best.pos;
            nav.moveTo(best.path, RETREAT_SPEED);
            return;
        }
        // 갈 만한 곳이 하나도 없다. 제자리에 선다 — 위험한 곳으로 억지로 가지 않는다.
        this.destination = null;
        this.destinationPos = null;
    }

    /**
     * 한 자리를 후보로 세워 본다. 경로는 <b>여기서 정확히 한 번</b> 만들고, 도달 판정과
     * 경로 길이가 그 하나를 같이 쓴다. 통과하지 못하면 {@code null}.
     */
    @Nullable
    private Candidate probe(BlockPos pos, Vec3 center, List<LivingEntity> threats,
                            @Nullable Vec3 threatCenter, PathNavigation nav) {
        Vec3 point = Vec3.atBottomCenterOf(pos);
        if (point.distanceToSqr(center) > CENTER_RADIUS * CENTER_RADIUS) {
            return null;                            // 행동 중심 12 밖
        }
        if (!safeSpot(pos)) {
            return null;                            // 위험 지형·바닥 없음·충돌 공간 부족
        }
        Path path = nav.createPath(pos, 0, PATH_RANGE);
        if (path == null || !path.canReach()) {
            return null;                            // 순간이동하지 않는다 — 못 가면 후보가 아니다
        }
        return new Candidate(point, pos, path,
                WardenGirlThreats.minDistance(point, threats),
                threatCenter == null ? 0.0D : threatCenter.distanceTo(point),
                preferred(point, center),
                length(path, point),
                turn(point));
    }

    /**
     * 설계서 4.2.5 사전식 비교. {@code a} 가 {@code b} 보다 <b>엄격히</b> 나은가.
     *
     * <pre>
     *   1. 가장 가까운 활성 위협과의 3D 거리가 큰 쪽
     *   2. 활성 위협 평균점과의 3D 거리가 큰 쪽
     *   3. 행동 중심 선호 거리 8~12 안인 쪽
     *   4. 경로 길이가 짧은 쪽
     *   5. 현재 이동 방향에서 방향 전환이 작은 쪽
     * </pre>
     *
     * 위협이 없으면 1·2 는 동률로 두고 3부터 본다. 전부 같으면 거짓 — 먼저 만든 쪽이 남는다.
     */
    private static boolean better(Candidate a, Candidate b, boolean hasThreats) {
        if (hasThreats) {
            if (a.minThreat != b.minThreat) {
                return a.minThreat > b.minThreat;
            }
            if (a.threatCenter != b.threatCenter) {
                return a.threatCenter > b.threatCenter;
            }
        }
        if (a.preferred != b.preferred) {
            return a.preferred;
        }
        if (a.length != b.length) {
            return a.length < b.length;
        }
        if (a.turn != b.turn) {
            return a.turn < b.turn;
        }
        return false;
    }

    /**
     * 안전한 자리인가.
     *
     * <p>핵심은 바닐라 {@code WalkNodeEvaluator.getBlockPathTypeStatic} 한 줄이다. 그 값이
     * 정확히 {@code WALKABLE} 이어야 통과하는데, 그 하나로 다음이 전부 걸러진다 — 아래가
     * 비어 있으면 {@code OPEN}(낭떠러지), 용암은 {@code LAVA}, 불·마그마·용암 가마솥은
     * {@code DAMAGE_FIRE}, 선인장·달콤한 열매는 {@code DAMAGE_OTHER}, 옆이 불·용암이면
     * {@code DANGER_FIRE}, 옆이 선인장이면 {@code DANGER_OTHER}, 옆이 물이면
     * {@code WATER_BORDER}, 물은 {@code WATER}, 가루눈은 {@code POWDER_SNOW}, 꿀은
     * {@code STICKY_HONEY}, 울타리·담장은 {@code FENCE}, 나뭇잎은 {@code LEAVES}, 닫힌 문은
     * {@code DOOR_*} 다. 문 관련 타입은 <b>새로 허용하지 않는다</b>.
     *
     * <p>불붙은 캠프파이어만 이 판정에서 빠진다 — 바닐라 {@code getBlockPathTypeRaw} 는
     * 캠프파이어를 특별 취급하지 않고, 캠프파이어는 온전한 충돌 블록이라 그 위가 그냥
     * {@code WALKABLE} 로 나온다. 그래서 바닐라 {@code CampfireBlock.isLitCampfire} 로 따로 본다.
     *
     * <p>머리 위 공간과 개체 충돌은 {@code noCollision} 이 본다 — {@code getBlockPathTypeStatic}
     * 은 자기 칸과 바로 아래 칸만 보고 키를 재지 않는다.
     */
    private boolean safeSpot(BlockPos pos) {
        Level level = this.mob.level();
        // T28 — 위험 블록 목록을 두 곳에 적지 않기 위해 공통 helper 로 옮겼다. 판정 의미는
        // 이전과 같다: getBlockPathTypeStatic == WALKABLE 그리고 켜진 모닥불 아님.
        if (!WardenGirlMovementSafety.safeFloorBlock(level, pos)) {
            return false;
        }
        Vec3 point = Vec3.atBottomCenterOf(pos);
        AABB box = this.mob.getBoundingBox().move(point.subtract(this.mob.position()));
        return level.noCollision(this.mob, box);
    }

    private static boolean preferred(Vec3 point, Vec3 center) {
        double d = point.distanceTo(center);
        return d >= PREFER_MIN && d <= PREFER_MAX;
    }

    /** 워든걸 → 첫 노드 → … → 마지막 노드 → 목적지 의 실제 길이. */
    private double length(Path path, Vec3 point) {
        Vec3 prev = this.mob.position();
        double sum = 0.0D;
        for (int i = 0; i < path.getNodeCount(); i++) {
            Vec3 at = path.getEntityPosAtNode(this.mob, i);
            sum += prev.distanceTo(at);
            prev = at;
        }
        return sum + prev.distanceTo(point);
    }

    /**
     * 현재 이동 방향에서 목적지 방향까지의 각도(도). 서 있어서 이동 벡터가 사실상 0이면
     * 몸통이 향한 쪽을 현재 방향으로 본다.
     */
    private double turn(Vec3 point) {
        Vec3 move = this.mob.getDeltaMovement();
        double ax = move.x;
        double az = move.z;
        if (ax * ax + az * az < 1.0E-6D) {
            float yaw = this.mob.getYRot() * ((float) Math.PI / 180.0F);
            ax = -Mth.sin(yaw);
            az = Mth.cos(yaw);
        }
        Vec3 to = point.subtract(this.mob.position());
        double la = Math.sqrt(ax * ax + az * az);
        double lb = Math.sqrt(to.x * to.x + to.z * to.z);
        if (la == 0.0D || lb == 0.0D) {
            return 0.0D;
        }
        double cos = Mth.clamp((ax * to.x + az * to.z) / (la * lb), -1.0D, 1.0D);
        return Math.toDegrees(Math.acos(cos));
    }

    /**
     * 후퇴가 끝났다. 후퇴 navigation 과 목적지를 지운다 — target 은 건드리지 않고,
     * 현지 anchor 는 후퇴 상태 해제가 이미 지웠다.
     */
    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.destination = null;
        this.destinationPos = null;
        this.holding = false;
    }

    /** 현재 후퇴 목적지. 없으면 {@code null}. */
    @Nullable
    public Vec3 destination() {
        return this.destination;
    }

    private record Candidate(Vec3 point, BlockPos pos, Path path,
                             double minThreat, double threatCenter,
                             boolean preferred, double length, double turn) {
    }
}
