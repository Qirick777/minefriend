package com.wardengirl.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * T20 — <b>활성 위협 수집 하나만</b> 하는 helper.
 *
 * <p>후퇴 안전 지점 평가와 T21 후퇴 소닉이 같은 집합을 봐야 하므로 한 곳에 모았다. 여기에
 * 있는 것은 "지금 이 순간 무엇이 위협인가" 를 계산하는 순수 조회 하나뿐이다 — 저장하지
 * 않고, 캐시하지 않고, {@code setTarget} 을 부르지 않고, 전역 상태도 갖지 않는다.
 *
 * <h2>무엇을 위협으로 세는가</h2>
 *
 * <pre>
 *   1. 워든걸의 현재 유효 target
 *   2. 워든걸을 현재 target 으로 삼은 Mob
 *   3. 소유자를 현재 target 으로 삼은 Mob
 *   4. 워든걸의 현재 최근 공격자
 *   5. 소유자의 현재 최근 공격자
 * </pre>
 *
 * <p>2·3 은 워든걸 중심 상자 안만 훑는다. 1·4·5 는 <b>범위 밖이어도</b> 본다 — 이미 확정된
 * 관계라서 거리로 지울 이유가 없다.
 *
 * <p>몹 <b>종류 목록은 쓰지 않는다</b>. 위 다섯 관계 중 하나에 해당하면 무엇이든 위협이다.
 *
 * <p>최근 공격자는 바닐라 {@code LivingEntity.lastHurtByMob} 을 그대로 읽는다. 그 필드는
 * {@code baseTick} 이 100틱 뒤 스스로 지우므로 별도 시간창을 만들지 않았다.
 */
public final class WardenGirlThreats {

    /** 2·3번 관계를 훑는 상자. 워든걸 중심 수평 24 · 수직 ±12. */
    public static final double SCAN_HORIZONTAL = 24.0D;
    public static final double SCAN_VERTICAL = 12.0D;

    private WardenGirlThreats() {
    }

    /**
     * 지금 이 순간의 활성 위협. 중복은 제거되고 순서는 위 1~5 번호 순이다.
     *
     * <p>공통 필터는 {@code isValidCombatTarget} 하나다 — 살아 있음·제거되지 않음·같은
     * 레벨·자기 자신 아님·소유자 아님·같은 소유자 워든걸 아님·크리에이티브·관전자·
     * {@code canAttack} 을 그 안에서 전부 본다. 여기서 다시 쓰지 않는다.
     */
    public static List<LivingEntity> collect(WardenGirlEntity mob) {
        List<LivingEntity> found = new ArrayList<>();
        Player owner = mob.serverOwner();

        add(found, mob, mob.getTarget());
        add(found, mob, mob.getLastHurtByMob());
        if (owner != null) {
            add(found, mob, owner.getLastHurtByMob());
        }

        AABB box = new AABB(
                mob.getX() - SCAN_HORIZONTAL, mob.getY() - SCAN_VERTICAL, mob.getZ() - SCAN_HORIZONTAL,
                mob.getX() + SCAN_HORIZONTAL, mob.getY() + SCAN_VERTICAL, mob.getZ() + SCAN_HORIZONTAL);
        for (Mob m : mob.level().getEntitiesOfClass(Mob.class, box,
                m -> m.getTarget() == mob || (owner != null && m.getTarget() == owner))) {
            add(found, mob, m);
        }
        return found;
    }

    /** 활성 위협들의 평균 위치. 비어 있으면 {@code null}. */
    @Nullable
    public static Vec3 center(List<LivingEntity> threats) {
        if (threats.isEmpty()) {
            return null;
        }
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (LivingEntity e : threats) {
            x += e.getX();
            y += e.getY();
            z += e.getZ();
        }
        return new Vec3(x / threats.size(), y / threats.size(), z / threats.size());
    }

    /** 한 위치에서 가장 가까운 활성 위협까지의 3D 거리. 위협이 없으면 {@link Double#MAX_VALUE}. */
    public static double minDistance(Vec3 point, List<LivingEntity> threats) {
        double best = Double.MAX_VALUE;
        for (LivingEntity e : threats) {
            best = Math.min(best, e.position().distanceTo(point));
        }
        return best;
    }

    /**
     * 중복 없이 담는다. {@code List.contains} 는 {@code Entity.equals} 를 쓰고 그것은
     * 엔티티 id 비교이므로, 같은 개체는 참조로 오든 UUID 로 오든 한 번만 들어간다.
     */
    private static void add(List<LivingEntity> found, WardenGirlEntity mob,
                            @Nullable LivingEntity candidate) {
        if (candidate == null || !mob.isValidCombatTarget(candidate)) {
            return;
        }
        if (!found.contains(candidate)) {
            found.add(candidate);
        }
    }
}
