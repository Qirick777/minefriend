package com.wardengirl.entity;

import com.wardengirl.anim.AnimRegistry;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * T21 — 후퇴 소닉 안전 판정. 설계서 4.3.
 *
 * <p>하는 일은 하나다 — <b>지금 활성 위협 전부가 주어진 시간보다 늦게 도착하는가</b>.
 * 저장 상태도, 캐시도, 위협 목록 소유권도 없다. 위협 집합은 T20 의
 * {@link WardenGirlThreats#collect} 를 그대로 다시 부른다.
 *
 * <h2>왜 위협의 실제 navigation 을 쓰지 않는가</h2>
 *
 * {@code threat.getNavigation().createPath(...)} 는 성공하면 그 위협의 {@code targetPos} 와
 * {@code reachRange} 를 덮어쓰고 stuck 타이머를 초기화한다(바이트코드 확인). 위협이 소유자를
 * 쫓고 있으면 그 내부 목표가 워든걸 자리로 오염된다. 그래서 위협마다 <b>독립</b>
 * {@link GroundPathNavigation} 을 새로 만들어 거기서 묻는다 — 그 객체는 자기 {@code path},
 * {@code targetPos}, {@code reachRange} 를 갖고 있고 몹의 실제 navigation 필드를 교체하지
 * 않는다. {@code PathNavigation} 생성자가 몹의 {@code FOLLOW_RANGE} 로 자기 {@code PathFinder}
 * 를 만들고 {@code NodeEvaluator} 가 몹의 크기·malus 를 그대로 읽으므로 판정 기준도 같다.
 *
 * <h2>지원하는 이동 방식</h2>
 *
 * {@code getNavigation().getClass() == GroundPathNavigation.class} 인 경우만 육상 경로 시간으로
 * 예측한다. <b>{@code instanceof} 로는 안 된다</b> — {@code WallClimberNavigation} 이
 * {@code GroundPathNavigation} 을 상속하므로(확인) 거미가 육상으로 통과해 버린다. 나머지
 * (비행·수중·양서·벽타기)와 공중·수중·용암·탑승 상태는 전부 <b>판정 불가</b>이고, 판정 불가는
 * 안전하지 않은 것으로 처리한다. 몹 종류 목록은 쓰지 않는다.
 */
public final class WardenGirlRetreatSonicSafety {

    /** 방출 age. 기존 소닉 값을 그대로 읽는다. */
    public static final int EMIT_TICK = AnimRegistry.SONIC_EMIT_TICK;
    /** 설계서 4.3.2 — 안전 여유. */
    public static final double SAFETY_MARGIN_TICKS = 10.0D;
    /** 설계서 4.3.3 — 이 수를 넘으면 경로를 <b>계산하지 않고</b> 즉시 실패한다. */
    public static final int MAX_THREATS = 16;
    /** 설계서 4.3.5 — 예상 속도 하한과 배율. */
    public static final double MIN_ESTIMATED_SPEED = 0.10D;
    public static final double SPEED_FACTOR = 1.5D;

    public enum Failure {
        NONE,
        TOO_MANY_THREATS,
        TARGET_NOT_IN_THREATS,
        NOT_A_MOB,
        UNSUPPORTED_NAVIGATION,
        AIRBORNE,
        IN_FLUID,
        PASSENGER,
        PROBE_FAILED,
        BAD_PATH_LENGTH,
        BAD_SPEED,
        ARRIVES_TOO_SOON
    }

    /**
     * 한 번의 판정 결과.
     *
     * @param minimumArrivalTicks 전체 위협 중 가장 작은 예상 도달 틱. 판정 불가로 끝났으면
     *                            {@link Double#NaN}, 위협이 없거나 전부 도달 불가면 무한대다.
     * @param limitingThreat      그 최솟값을 만든 위협, 또는 판정 불가를 낸 위협.
     */
    public record Result(boolean safe, int threatCount, double minimumArrivalTicks,
                         @Nullable LivingEntity limitingThreat, Failure failure) {
    }

    private WardenGirlRetreatSonicSafety() {
    }

    /**
     * 설계서 4.3.7 — 지금 age 에서 요구되는 도달 시간.
     *
     * <pre>
     *   age 0  → 34 + 10 = 44 초과
     *   age 10 → 24 + 10 = 34 초과
     *   age 33 →  1 + 10 = 11 초과
     *   age 34 →  0 + 10 = 10 초과   (방출 직전 최종 게이트)
     * </pre>
     */
    public static double requiredArrival(int age) {
        return (double) (EMIT_TICK - age) + SAFETY_MARGIN_TICKS;
    }

    /**
     * 지금 이 순간 후퇴 소닉이 안전한가. 비교는 <b>엄격한</b> {@code >} 다 — 요구값과 정확히
     * 같으면 안전하지 않다.
     */
    public static Result evaluate(WardenGirlEntity mob, @Nullable LivingEntity target,
                                  double requiredArrivalTicks) {
        List<LivingEntity> threats = WardenGirlThreats.collect(mob);
        int count = threats.size();
        if (count > MAX_THREATS) {
            return new Result(false, count, Double.NaN, null, Failure.TOO_MANY_THREATS);
        }
        // 현재 target 이 위협 목록에서 빠졌다면 상태가 어긋난 것이다 — 안전으로 보지 않는다.
        if (target != null && !threats.contains(target)) {
            return new Result(false, count, Double.NaN, target, Failure.TARGET_NOT_IN_THREATS);
        }

        double minimum = Double.POSITIVE_INFINITY;
        LivingEntity limiting = null;
        for (LivingEntity threat : threats) {
            Eta eta = eta(mob, threat);
            if (eta.failure != Failure.NONE) {
                return new Result(false, count, Double.NaN, threat, eta.failure);
            }
            if (eta.ticks < minimum) {
                minimum = eta.ticks;
                limiting = threat;
            }
        }
        if (!(minimum > requiredArrivalTicks)) {
            return new Result(false, count, minimum, limiting, Failure.ARRIVES_TOO_SOON);
        }
        return new Result(true, count, minimum, limiting, Failure.NONE);
    }

    /** 한 위협의 예상 도달 틱. {@code failure != NONE} 이면 판정 불가다. */
    private record Eta(double ticks, Failure failure) {
        static Eta of(double ticks) {
            return new Eta(ticks, Failure.NONE);
        }

        static Eta fail(Failure reason) {
            return new Eta(Double.NaN, reason);
        }
    }

    private static Eta eta(WardenGirlEntity mob, LivingEntity threat) {
        if (!(threat instanceof Mob m)) {
            return Eta.fail(Failure.NOT_A_MOB);         // navigation 이 없다 — 예측 불가
        }
        // 4.3.4 — 이미 근접 사거리 안이면 0틱이다. 다른 어떤 조건보다 먼저 본다.
        if (m.isWithinMeleeAttackRange(mob)) {
            return Eta.of(0.0D);
        }
        if (m.getNavigation().getClass() != GroundPathNavigation.class) {
            return Eta.fail(Failure.UNSUPPORTED_NAVIGATION);
        }
        if (!m.onGround()) {
            return Eta.fail(Failure.AIRBORNE);
        }
        if (m.isInWater() || m.isInLava() || m.isFallFlying()) {
            return Eta.fail(Failure.IN_FLUID);
        }
        if (m.isPassenger()) {
            return Eta.fail(Failure.PASSENGER);
        }

        Path path;
        try {
            // 위협의 실제 navigation 은 건드리지 않는다. 이 객체는 이 호출에서만 산다.
            path = new GroundPathNavigation(m, m.level()).createPath(mob, 0);
        } catch (RuntimeException e) {
            return Eta.fail(Failure.PROBE_FAILED);
        }
        if (path == null || !path.canReach()) {
            // 4.3.6 — 안정적인 지상 위협이고 계산은 정상이었다. 닿지 못한다는 뜻이다.
            return Eta.of(Double.POSITIVE_INFINITY);
        }

        double length = pathLength(m, path, mob.position());
        if (!Double.isFinite(length) || length < 0.0D) {
            return Eta.fail(Failure.BAD_PATH_LENGTH);
        }
        double meleeReach = Math.sqrt(m.getMeleeAttackRangeSqr(mob));
        if (!Double.isFinite(meleeReach) || meleeReach < 0.0D) {
            return Eta.fail(Failure.BAD_PATH_LENGTH);
        }
        double raw;
        try {
            raw = m.getAttributeValue(Attributes.MOVEMENT_SPEED);
        } catch (RuntimeException e) {
            return Eta.fail(Failure.BAD_SPEED);
        }
        if (!Double.isFinite(raw) || raw < 0.0D) {
            return Eta.fail(Failure.BAD_SPEED);
        }
        double blocksPerTick = Math.max(MIN_ESTIMATED_SPEED, raw * SPEED_FACTOR);
        double travel = Math.max(0.0D, length - meleeReach);
        return Eta.of(travel / blocksPerTick);
    }

    /** 위협 현재 위치 → 첫 노드 → … → 마지막 노드 → 워든걸 현재 위치. 같은 Path 를 한 번만 쓴다. */
    private static double pathLength(Mob threat, Path path, Vec3 destination) {
        Vec3 prev = threat.position();
        double sum = 0.0D;
        for (int i = 0; i < path.getNodeCount(); i++) {
            Vec3 at = path.getEntityPosAtNode(threat, i);
            sum += prev.distanceTo(at);
            prev = at;
        }
        return sum + prev.distanceTo(destination);
    }
}
