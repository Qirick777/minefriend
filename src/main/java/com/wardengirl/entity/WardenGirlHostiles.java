package com.wardengirl.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * T7·T8 <b>임시 적대 판정</b>. 좀비 하나만 대상으로 삼는 검증용 규칙을 여기 한 곳에 모았다.
 *
 * <h2>교체 지점</h2>
 *
 * 최종 적대 기준이 정해지면 <b>이 파일의 세 메서드만</b> 바꾸면 된다. Goal 들은 여기를 통해서만
 * 대상을 얻으므로 {@code instanceof Zombie} 가 프로젝트 어디에도 흩어져 있지 않다.
 *
 * <p>진영·태그·범용 적대 체계도, 미래용 인터페이스도 만들지 않았다. 지금 필요한 세 가지
 * 질문(가장 가까운 대상 / 사거리 안인가 / 전방에 몇이나 있나)에만 답한다.
 */
public final class WardenGirlHostiles {

    private WardenGirlHostiles() {
    }

    /** 바닐라 {@code SonicBoom} 과 같은 수평·수직 사거리. */
    public static final double RANGE_XZ = 15.0D;
    public static final double RANGE_Y = 20.0D;

    /** 군중 판정의 전방 반각. 프로젝트가 4.12 에서 이미 쓰는 "전방" 값과 같다. */
    public static final double FRONT_HALF_ANGLE = 60.0D;

    /** 군중으로 보는 최소 마릿수. */
    public static final int CROWD = 3;

    private static final TargetingConditions NEAREST =
            TargetingConditions.forCombat().range(RANGE_XZ).ignoreLineOfSight();

    /** 임시 규칙 — 좀비만 적으로 본다. */
    private static boolean isHostile(LivingEntity e) {
        return e instanceof Zombie;
    }

    /** 사거리 안에서 가장 가까운 적. 없으면 {@code null}. */
    public static LivingEntity nearest(WardenGirlEntity mob) {
        AABB box = mob.getBoundingBox().inflate(RANGE_XZ, RANGE_Y, RANGE_XZ);
        return mob.level().getNearestEntity(Zombie.class, NEAREST, mob,
                mob.getX(), mob.getEyeY(), mob.getZ(), box);
    }

    /** 소닉붐 사거리 안인가. 바닐라 {@code Warden.closerThan(target, 15, 20)} 과 같다. */
    public static boolean inBoomRange(WardenGirlEntity mob, LivingEntity target) {
        return mob.closerThan(target, RANGE_XZ, RANGE_Y);
    }

    /**
     * {@code yBodyRot} 기준 전방 ±{@value #FRONT_HALF_ANGLE}°, 수평 {@value #RANGE_XZ} ·
     * 수직 {@value #RANGE_Y} 안의 <b>살아 있는</b> 적 수.
     *
     * <p>{@code getTarget()} 은 보지 않는다. 뒤쪽·사거리 밖·수직 범위 밖은 제외되므로 멀리 있는
     * 좀비 때문에 성립하지 않는다.
     */
    public static int frontCrowd(WardenGirlEntity mob) {
        AABB box = mob.getBoundingBox().inflate(RANGE_XZ, RANGE_Y, RANGE_XZ);
        List<Zombie> list = mob.level().getEntitiesOfClass(Zombie.class, box,
                z -> z.isAlive() && isHostile(z) && inBoomRange(mob, z));
        int n = 0;
        for (Zombie z : list) {
            double azimuth = Math.toDegrees(Math.atan2(z.getZ() - mob.getZ(),
                    z.getX() - mob.getX())) - 90.0D;
            double diff = Math.abs(wrap180(azimuth - mob.yBodyRot));
            if (diff <= FRONT_HALF_ANGLE) {
                n++;
            }
        }
        return n;
    }

    private static double wrap180(double deg) {
        double d = deg % 360.0D;
        if (d >= 180.0D) {
            d -= 360.0D;
        }
        if (d < -180.0D) {
            d += 360.0D;
        }
        return d;
    }
}
