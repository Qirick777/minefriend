package com.wardengirl.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;

/**
 * 바닐라 {@link LookAtPlayerGoal} 에 <b>읽기 전용 getter 하나만</b> 더한 것.
 *
 * <h2>왜 필요한가</h2>
 *
 * 4.14 의 서버 권위 시선은 감쇠 계수를 "몹과 <em>실제로 선택된 대상</em> 사이의 거리"로 정한다.
 * 그 대상은 {@code LookAtPlayerGoal.lookAt} 에 들어 있는데 {@code protected} 라 외부에서 못 읽는다.
 * 하위 클래스는 읽을 수 있으므로 <b>리플렉션 없이</b> 꺼낸다.
 *
 * <h2>정책은 바닐라 그대로다</h2>
 *
 * {@code canUse} / {@code canContinueToUse} / {@code start} / {@code stop} / {@code tick} 을
 * <b>하나도 재정의하지 않는다.</b> 확률 0.02, 지속 40~79틱, 거리 12, 대상 선정 조건이 전부 바닐라다.
 * 생성자 인수도 그대로 상위에 넘긴다.
 *
 * <h2>반환형이 {@code Entity} 인 이유</h2>
 *
 * 필드 선언이 {@code protected net.minecraft.world.entity.Entity lookAt} 이다(바이트코드 확인).
 * 캐스팅도 {@code instanceof} 필터도 넣지 않는다 — 제품 코드에서 타입을 좁히면 실제로 일어나지
 * 않는 분기가 생기고, 그 분기가 검증 카운터를 오염시킨다. {@code Player.class} 로 등록했으므로
 * 정상 런타임 값은 {@code Player} 아니면 {@code null} 이고, <b>그 확인은 검증 불변조건으로만</b>
 * 둔다. {@code Entity.distanceTo(Entity)} 가 그대로 받으므로 감쇠 거리 계산에도 캐스팅이 없다.
 */
public class WardenGirlLookAtPlayerGoal extends LookAtPlayerGoal {

    public WardenGirlLookAtPlayerGoal(Mob mob, Class<? extends LivingEntity> lookAtType,
                                      float lookDistance) {
        super(mob, lookAtType, lookDistance);
    }

    /** 지금 이 Goal 이 보고 있는 대상. 없으면 {@code null}. */
    public Entity getLookAt() {
        return this.lookAt;
    }
}
