package com.wardengirl.client;

import com.wardengirl.anim.AnimParams;

/**
 * C1 상시 레이어 — 설계서 4.3. <b>Java 가산이다. GeckoLib 컨트롤러가 아니다.</b>
 *
 * <h2>왜 Java 로 옮겼는가</h2>
 *
 * 두 GeckoLib 컨트롤러가 같은 본·축을 쓰면 <b>뒤가 앞을 덮는다.</b> 실측: C1 을 컨트롤러로
 * 돌리는 동안 C2 를 얹자 C1 이 만들던 진동 폭이 일곱 축 전부 정확히 {@code 0.000} 으로
 * 사라졌다. 원인은 {@code AnimationProcessor} 가 회전을
 * {@code setRotX(애니메이션값 + bone.getInitialSnapshot().getRotX())} 로 쓰기 때문이다 —
 * "가산" 이지만 더하는 대상이 직전 컨트롤러의 결과가 아니라 <em>본의 초기 스냅샷</em>이라,
 * 나중에 처리되는 컨트롤러가 앞의 기여를 지운다.
 *
 * <p>커스텀 {@code GeoBone} 으로 {@code setRotX} 를 진짜 가산으로 바꾸는 우회로는 배제했다.
 * {@code AnimationProcessor} 가 전이용 스냅샷을 {@code bone.getRotX()} 에서 갱신하는데,
 * 가산 모드에서 그 값은 모든 컨트롤러의 <em>합</em>이 되어 전이 6틱 동안 C1 이 두 배로
 * 들어간다. 상세는 설계서 Part 4.2 와 Part 11.
 *
 * <p>{@code setCustomAnimations} 는 애니메이션 처리가 <b>끝난 뒤</b> 실행되므로 여기서 더한
 * 값은 어떤 컨트롤러도 덮지 못한다. 4.3.4 정적 오프셋이 T2 부터 그 자리에서 정상 작동해 온
 * 것이 증거다. C1 도 같은 자리로 옮겨 합성 규칙을 하나로 통일한다.
 *
 * <h2>부호</h2>
 *
 * json 키프레임 경로를 벗어나므로 {@code JsonAxisConvention} 의 x/y 반전을 타지 않는다.
 * <b>그러나 값을 조정할 필요는 없다.</b> 그 변환은 라이브러리의 반전을 되돌려 "파라미터 값이
 * 본에 그대로 도달" 하게 만드는 것이었고, Java 경로는 애초에 그대로 도달한다. 양쪽 다
 * 파라미터 값 = 본 값이므로 이관해도 부호도 크기도 바뀌지 않는다.
 *
 * <h2>시간</h2>
 *
 * {@code query.wg_time} 과 같은 양 — {@code tickCount + partialTick}, 되감기지 않는 틱이다.
 * 60 / 53 / 79 틱 세 주기가 루프 지점에서 위상이 튀지 않게 하려던 것이 그 이유였고, Java 로
 * 와도 같다.
 *
 * <p>{@code Math.sin} 은 라디안 입력이다. json 의 {@code math.sin} 은 도 입력이었으므로
 * {@code 360 / period} 로 위상을 만들던 식이 여기서는 {@code 2π / period} 가 된다. 같은
 * 곡선이다.
 */
public final class VitalMotion {

    private VitalMotion() {
    }

    /** 한 프레임분 C1 기여. 회전은 도, 위치는 모델 픽셀. */
    public record Contribution(double bodyRotX, double bodyRotY, double bodyRotZ,
                               double bodyPosY, double headRotX, double headRotZ,
                               double armRightRotZ, double armLeftRotZ, double rootPosY) {
    }

    /**
     * @param timeTicks {@code tickCount + partialTick} — 되감기지 않는 연속 시간
     */
    public static Contribution evaluate(double timeTicks) {
        double breathP = AnimParams.BREATH_PERIOD.get();
        double swayP = AnimParams.SWAY_PERIOD.get();
        double weightP = AnimParams.WEIGHT_SHIFT_PERIOD.get();
        double bounceP = AnimParams.BOUNCE_PERIOD.get();

        double breath = phase(timeTicks, 0.0D, breathP);
        double breathHead = phase(timeTicks, 2.0D, breathP);
        double breathArm = phase(timeTicks, 1.5D, breathP);
        double sway = phase(timeTicks, 0.0D, swayP);
        double swayBodyY = phase(timeTicks, 2.9444D, swayP);
        double swayHead = phase(timeTicks, 2.0611D, swayP);
        double weight = phase(timeTicks, 0.0D, weightP);
        double weightHead = phase(timeTicks, 5.2667D, weightP);
        double bounce = phase(timeTicks, 0.0D, bounceP);

        return new Contribution(
                AnimParams.BREATH_BODY_X.get() * Math.sin(breath),
                AnimParams.SWAY_BODY_Y.get() * Math.sin(swayBodyY),
                AnimParams.SWAY_BODY_Z.get() * Math.sin(sway)
                        + AnimParams.WEIGHT_SHIFT_AMP.get() * Math.sin(weight),
                AnimParams.BREATH_BODY_Y.get() * Math.sin(breath),
                AnimParams.BREATH_HEAD_X.get() * Math.sin(breathHead),
                AnimParams.SWAY_HEAD_Z.get() * Math.sin(swayHead)
                        - AnimParams.WEIGHT_SHIFT_HEAD_Z.get() * Math.sin(weightHead),
                // 팔은 바깥 방향 바이어스. (1 + sin) 이 값을 0 ~ 2x진폭 으로 만들어 안쪽으로
                // 가지 않게 한다 — 팔 안쪽면과 몸통 바깥면이 x=±4 로 간격 0 이라 안쪽 각도는
                // 크기와 무관하게 즉시 관통한다. 4.3.1.
                AnimParams.BREATH_ARM_R_Z.get() * (1.0D + Math.sin(breathArm)),
                AnimParams.BREATH_ARM_L_Z.get() * (1.0D + Math.sin(breathArm)),
                AnimParams.BOUNCE_AMPLITUDE.get() * Math.sin(bounce));
    }

    /**
     * 위상, 라디안. 지연은 도가 아니라 <b>틱</b>으로 준다.
     *
     * <p>도로 박아두면 {@code param set breath_period} 로 주기를 바꾸는 순간 지연의 실제
     * 길이가 같이 늘거나 줄어 버린다. 틱으로 쓰면 주기와 무관하게 D틱이다. json 이 그렇게
     * 되어 있었고 그 이유는 이관해도 같다.
     */
    private static double phase(double timeTicks, double delayTicks, double period) {
        if (period == 0.0D) {
            return 0.0D;
        }
        return (timeTicks - delayTicks) * 2.0D * Math.PI / period;
    }
}
