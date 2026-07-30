package com.wardengirl.entity;

import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * T13 — 7.1 / 7.3 소유자 주변 자율 배회.
 *
 * <h2>바닐라 배회를 그대로 쓴다</h2>
 *
 * {@code WaterAvoidingRandomStrollGoal} 을 상속해 <b>목적지 고르는 함수 하나만</b> 바꾼다.
 * 발동 간격({@code interval} 240틱 중 1/240 확률), {@code noActionTime} 확인, 경로가 끝나면
 * 종료하는 {@code canContinueToUse}, {@code start} 의 {@code moveTo}, {@code stop} 의
 * {@code navigation.stop()} 은 전부 바닐라 것이다. 새 상태기계도, 새 타이머도, 매 틱 경로
 * 재생성도 없다.
 *
 * <p>소유자가 없거나(야생) 찾을 수 없으면 {@code super.getPosition()} 을 그대로 돌려준다 —
 * 그러면 이 Goal 은 바닐라 배회와 완전히 같아진다. 야생 개체가 근처 플레이어를 생활 중심으로
 * 삼는 경로는 존재하지 않는다.
 *
 * <h2>반경 8은 목적지 생성에서 지킨다</h2>
 *
 * 좌표를 사후에 깎거나 순간이동으로 되돌리지 않는다. {@code LandRandomPos.getPos} 의 가중치
 * 판을 쓰면 바닐라가 후보 10개를 뽑아 <b>가중치가 가장 큰 하나</b>를 고르고(모두 탈락이면
 * {@code null}), {@code null} 이면 바닐라 {@code canUse} 가 false 가 되어 다음 기회를 기다린다.
 * 그래서 "반경 밖 후보는 가중치 {@code -∞}" 한 줄이면 <b>생성 자체가 반경을 반영</b>한다.
 *
 * <p>통과한 후보는 모두 가중치 {@code 0} 이다. 바닐라 선택 루프가 <b>더 큰 값일 때만</b>
 * 교체하므로 결과는 "고리 안에 들어온 첫 후보" — 즉 방향이 매번 무작위다. 거리로 가중치를 주면
 * 항상 소유자에게 가장 가까운 후보(밀착) 또는 항상 가장 먼 후보(고리 테두리)만 골라 한쪽으로
 * 굳는다.
 */
public class WardenGirlOwnerStrollGoal extends WaterAvoidingRandomStrollGoal {

    /** 2차 설계서 7.2 — 소유자 주변 자율 배회 중심 반경. */
    public static final double OWNER_RADIUS = 8.0D;

    /**
     * 소유자에게서 이만큼은 떨어진 곳만 목적지로 고른다.
     *
     * <p>"소유자의 정확한 발 위치를 반복해 고르지 않는다"와 "같은 블록으로 들어가려는 경로를
     * 의도적으로 만들지 않는다"를 목적지 생성에서 지키는 값이다. 밀어내는 보정이 아니라
     * <b>후보 탈락 조건</b>이므로 물리적으로 밀거나 좌표를 바꾸는 일이 없다.
     */
    private static final double OWNER_KEEPOUT = 2.0D;

    private static final double RADIUS_SQR = OWNER_RADIUS * OWNER_RADIUS;
    private static final double KEEPOUT_SQR = OWNER_KEEPOUT * OWNER_KEEPOUT;

    /** 바닐라 배회와 같은 후보 범위다. 소유자 고리 전체를 덮을 만큼 넉넉하다. */
    private static final int CANDIDATE_XZ = 10;
    private static final int CANDIDATE_Y = 7;

    private final WardenGirlEntity girl;

    public WardenGirlOwnerStrollGoal(WardenGirlEntity girl, double speedModifier) {
        super(girl, speedModifier);
        this.girl = girl;
    }

    @Override
    protected Vec3 getPosition() {
        Player owner = this.girl.serverOwner();
        if (owner == null) {
            return super.getPosition();         // 야생·오프라인·다른 차원 → 현재 위치 기준 배회
        }
        // 중심은 저장하지 않는다. 매번 소유자의 <b>현재</b> 위치에서 파생하므로 소유자가 천천히
        // 움직이면 다음 배회 중심도 따라온다.
        Vec3 center = owner.position();
        return LandRandomPos.getPos(this.girl, CANDIDATE_XZ, CANDIDATE_Y, pos -> {
            double dx = (pos.getX() + 0.5D) - center.x;
            double dz = (pos.getZ() + 0.5D) - center.z;
            double d2 = dx * dx + dz * dz;       // 수평 거리다. 반경 규칙이 수평이다.
            if (d2 > RADIUS_SQR || d2 < KEEPOUT_SQR) {
                return Double.NEGATIVE_INFINITY; // 후보 탈락. 전부 탈락이면 이번 회차는 쉰다.
            }
            return 0.0D;
        });
    }
}
