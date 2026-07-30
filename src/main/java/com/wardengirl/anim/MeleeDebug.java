package com.wardengirl.anim;

/**
 * 근접 공격 <b>체감 조절용 임시 개발 도구</b>. 밸런스 확정 뒤 제거한다.
 *
 * <h2>제거 방법</h2>
 *
 * <ol>
 *   <li>이 파일
 *   <li>{@code WardenGirlCommand} 의 {@code debug} 리터럴 블록과 {@code meleeDebug*} 메서드
 *   <li>{@code ModNetwork.broadcastMeleeDebug} 와 {@code MeleeDebugPacket} 레코드, 그 등록 한 줄
 *   <li>{@code WardenGirlAttackGoal} 의 {@code motionWindup} · {@code motionEnd} 두 필드와
 *       {@code windup()} 호출 한 줄 — {@link #DEFAULT_WINDUP} 을 {@code HIT_TICK} 으로 되돌린다
 *   <li>{@code ActionMotion} 의 {@code timeScale} 필드와 5인자 {@code syncTo} 오버로드
 *   <li>{@code WardenGirlModel} 의 {@code attack} 분기에서 배속을 넘기는 두 곳
 * </ol>
 *
 * <h2>왜 {@code AnimParams} 가 아닌가</h2>
 *
 * {@code ClientConfig} 가 {@code AnimParams.all()} 을 <b>전부</b> 순회해
 * {@code ForgeConfigSpec.defineInRange} 로 정의하므로, 여기에 키를 하나 넣으면 그 값이 클라이언트
 * TOML 설정 파일에 저장된다. 임시 도구를 저장 데이터에 결합하지 않기 위해 값을 이 클래스의
 * 메모리 정적 필드로만 둔다 — 서버 재시작이면 기본값으로 돌아간다.
 *
 * <h2>두 값은 서로 다른 경로다</h2>
 *
 * {@link #windup()} 은 <b>서버</b> {@code WardenGirlAttackGoal} 의 모션 나이 비교에만 쓰이고,
 * {@link #animSpeed()} 는 <b>클라이언트</b> {@code ActionMotion} 의 재생 시간축에만 쓰인다. 한쪽을
 * 바꾸는 코드가 다른 쪽 값을 읽거나 보정하는 자리는 없다. 공격 시작 최소 간격
 * ({@code MELEE_COOLDOWN}), 타격 거리({@code STRIKE_REACH_SQR}), 공격력({@code ATTACK_DAMAGE})
 * 은 둘 중 어느 값도 참조하지 않는다.
 */
public final class MeleeDebug {

    private MeleeDebug() {
    }

    /** 현재 기준 선딜레이(틱). {@code WardenGirlAttackGoal.HIT_TICK} 과 같은 값이다. */
    public static final int DEFAULT_WINDUP = 8;

    /** 현재 기준 재생 배속. */
    public static final double DEFAULT_ANIM_SPEED = 1.0D;

    /**
     * 선딜레이 허용 범위. 0 은 <b>반드시</b> 허용한다 — 공격 시작과 같은 서버 틱에 타격한다.
     *
     * <p>상한은 모션 길이 18틱보다 넉넉히 크게 잡았다. 18을 넘는 값은 몰래 깎지 않고 실제로
     * 그 틱까지 기다린다 — 자세한 처리는 {@code WardenGirlAttackGoal.tick()} 주석에 있다.
     */
    public static final int WINDUP_MIN = 0;
    public static final int WINDUP_MAX = 100;

    /**
     * 배속 허용 범위.
     *
     * <p>하한이 0 이 아닌 이유: 0 이면 재생 시간축이 멈춰 클립이 영원히 끝나지 않고
     * {@code ActionMotion.age} 가 계속 0 을 돌려주므로 첫 프레임 자세로 굳는다. 상한 8 은
     * 18틱 클립이 2.25틱에 끝나는 값이며, 그보다 빠르면 클라이언트가 프레임을 하나도 그리지
     * 못하고 지나갈 수 있다(20 TPS · 60 FPS 면 틱당 3프레임).
     */
    public static final double ANIM_SPEED_MIN = 0.05D;
    public static final double ANIM_SPEED_MAX = 8.0D;

    private static int windup = DEFAULT_WINDUP;
    private static double animSpeed = DEFAULT_ANIM_SPEED;

    /** 서버 전용. 공격 회차가 시작될 때 한 번 읽어 그 회차 동안 고정한다. */
    public static int windup() {
        return windup;
    }

    /** 클라이언트 재생 시간축 배율. 서버는 {@code get} 표시와 방송에만 쓴다. */
    public static double animSpeed() {
        return animSpeed;
    }

    /** @return 범위를 벗어나면 false — 호출자가 거절 메시지를 낸다. */
    public static boolean setWindup(int ticks) {
        if (ticks < WINDUP_MIN || ticks > WINDUP_MAX) {
            return false;
        }
        windup = ticks;
        return true;
    }

    /** NaN·무한대·범위 밖을 모두 거절한다. */
    public static boolean setAnimSpeed(double multiplier) {
        if (!Double.isFinite(multiplier)
                || multiplier < ANIM_SPEED_MIN || multiplier > ANIM_SPEED_MAX) {
            return false;
        }
        animSpeed = multiplier;
        return true;
    }

    public static void reset() {
        windup = DEFAULT_WINDUP;
        animSpeed = DEFAULT_ANIM_SPEED;
    }
}
