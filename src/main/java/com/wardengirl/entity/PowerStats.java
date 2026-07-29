package com.wardengirl.entity;

/**
 * 강화 스택 → 스탯. <b>2차 설계서 6.2 / 6.3 의 공식이 있는 유일한 곳이다.</b>
 *
 * <p>순수 함수만 있다. 상태도, 캐시도, 엔티티 참조도 없다 — 명령어나 소닉 Goal 이 공식을
 * 복사하지 않고 여기를 부르게 하려는 것이 목적이다.
 *
 * <h2>0~100 은 선형, 그 위는 수렴</h2>
 *
 * <pre>
 *   s ≤ 100 :  체력 30 + 4.7·s     근접 7 + 0.38·s   소닉 5 + 0.10·s   저항 0.01·s
 *   s > 100 :  x = s − 100
 *              체력 1020 − 520·e^(−x/150)
 *              근접  100 −  55·e^(−x/150)
 *              소닉   30 −  15·e^(−x/150)
 *              저항 1.0
 * </pre>
 *
 * 두 구간은 s = 100 에서 정확히 이어진다 — x = 0 이면 e^0 = 1 이라 1020 − 520 = 500,
 * 100 − 55 = 45, 30 − 15 = 15 이고 선형식도 같은 값을 낸다. 선형식이 100스택에서 문서의
 * 500 / 45 / 15 / 1.0 을 <b>정확히</b> 내는 것은 double 로 확인했다.
 *
 * <h2>천장은 float 기준으로 자른다</h2>
 *
 * {@code Math.nextDown(1020.0D)} 로는 부족하다. 이 값들은 결국 float 로 소비되기 때문이다 —
 * {@code LivingEntity.getMaxHealth()} 는 {@code getAttributeValue(MAX_HEALTH)} 뒤에 {@code d2f}
 * 가 붙어 있고, 근접 피해와 소닉 피해도 {@code hurt(source, float)} 로 들어간다. double 로
 * 한 칸 내린 1019.9999999999999 는 float 로 바뀌는 순간 다시 1020.0F 가 된다.
 *
 * <p>그래서 각 천장을 <b>float 한 칸 아래</b>로 자른다. 임의의 여유를 빼지 않고 표현 가능한
 * 가장 큰 값을 쓴다.
 *
 * <pre>
 *   1020 → 1019.9999389648438
 *    100 →   99.99999237060547
 *     30 →   29.999998092651367
 * </pre>
 *
 * {@code Long.MAX_VALUE} 에서도 안전하다. {@code s − 100} 은 long 에서 넘치지 않고,
 * {@code Math.exp(−6.1e16)} 은 {@code NaN} 이나 {@code Infinity} 가 아니라 정확히 0.0 이라
 * 결과가 천장이 되며, 그 뒤 위 상한이 한 칸 아래로 끌어내린다.
 */
public final class PowerStats {

    private PowerStats() {
    }

    /** 0스택 기본값. 2차 설계서 5.1. */
    public static final double HEALTH_BASE = 30.0D;
    public static final double ATTACK_BASE = 7.0D;
    public static final double SONIC_BASE = 5.0D;

    /** 스택당 선형 증가분. */
    private static final double HEALTH_PER_STACK = 4.7D;
    private static final double ATTACK_PER_STACK = 0.38D;
    private static final double SONIC_PER_STACK = 0.10D;

    /** 선형 구간의 끝. */
    public static final long LINEAR_STACKS = 100L;

    /** 수렴 구간의 천장과 진폭. */
    private static final double HEALTH_CAP = 1020.0D;
    private static final double ATTACK_CAP = 100.0D;
    private static final double SONIC_CAP = 30.0D;
    private static final double HEALTH_SPAN = 520.0D;
    private static final double ATTACK_SPAN = 55.0D;
    private static final double SONIC_SPAN = 15.0D;

    /** 감쇠 상수. 100스택 뒤로 스택 150마다 남은 거리가 1/e 로 줄어든다. */
    private static final double DECAY = 150.0D;

    /** float 로 소비된 뒤에도 천장 미만으로 남는 가장 큰 값. */
    private static final double HEALTH_LIMIT = Math.nextDown((float) HEALTH_CAP);
    private static final double ATTACK_LIMIT = Math.nextDown((float) ATTACK_CAP);
    private static final double SONIC_LIMIT = Math.nextDown((float) SONIC_CAP);

    /** {@code s > 100} 구간의 감쇠 계수 {@code e^(−x/150)}. 0.0 이 될 수는 있어도 NaN 은 없다. */
    private static double decay(long stacks) {
        return Math.exp(-((double) (stacks - LINEAR_STACKS)) / DECAY);
    }

    public static double health(long stacks) {
        long s = Math.max(0L, stacks);
        double v = s <= LINEAR_STACKS
                ? HEALTH_BASE + HEALTH_PER_STACK * s
                : HEALTH_CAP - HEALTH_SPAN * decay(s);
        return Math.min(v, HEALTH_LIMIT);
    }

    public static double attack(long stacks) {
        long s = Math.max(0L, stacks);
        double v = s <= LINEAR_STACKS
                ? ATTACK_BASE + ATTACK_PER_STACK * s
                : ATTACK_CAP - ATTACK_SPAN * decay(s);
        return Math.min(v, ATTACK_LIMIT);
    }

    public static double sonic(long stacks) {
        long s = Math.max(0L, stacks);
        double v = s <= LINEAR_STACKS
                ? SONIC_BASE + SONIC_PER_STACK * s
                : SONIC_CAP - SONIC_SPAN * decay(s);
        return Math.min(v, SONIC_LIMIT);
    }

    /** 0~1. 100스택에서 1.0 이고 그 위로는 계속 1.0 이다 — 여기만 천장에 정확히 닿는다. */
    public static double knockbackResistance(long stacks) {
        long s = Math.max(0L, stacks);
        return s >= LINEAR_STACKS ? 1.0D : 0.01D * s;
    }
}
