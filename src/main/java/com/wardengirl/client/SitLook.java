package com.wardengirl.client;

import com.wardengirl.entity.WardenGirlEntity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * 4.14 (1). 탑승 중 시선. <b>바닐라 Goal 두 개 + LookControl 을 클라이언트에서 재현한다.</b>
 *
 * <h2>왜 재현인가 — 세 층 전부 막혀 있다</h2>
 *
 * <ul>
 *   <li>{@code LookControl} 은 클라이언트에서 <b>한 번도 tick 되지 않는다</b> — {@code aiStep} 이
 *       클라에서 돌지 않기 때문이다;</li>
 *   <li>서버가 계산한 {@code yHeadRot} 은 같은 틱 안에서 {@code Boat.clampRotation} 이
 *       {@code yHeadRot = yRot} 로 지운다;</li>
 *   <li>패킷에는 {@code yHeadRot} 만 실린다 — lookAt 목표는 서버 전용 필드다.</li>
 * </ul>
 *
 * <h2>값은 전부 바닐라 실측이다. 파라미터로 열지 않는다</h2>
 *
 * 여는 순간 지상과 탑승이 갈릴 수 있고, "같아야 한다"가 이 작업의 요구였다. 아래 상수는
 * 1.20.1 바이트코드에서 그대로 뽑은 것이고 출처를 각 필드에 적었다.
 *
 * <p>난수만 클라이언트 것이다 — 4.12 킁킁과 같은 성질이라 멀티플레이에서 사람마다 다른 순간에
 * 딴 데를 보게 된다. 1차 범위에서 허용된 성질이다.
 */
public final class SitLook {

    /** {@code LookAtPlayerGoal(this, Player.class, 12.0F)} — 우리 등록값. */
    private static final double LOOK_DISTANCE = 12.0D;
    /** {@code LookAtPlayerGoal.DEFAULT_PROBABILITY} 와 {@code RandomLookAroundGoal.canUse} 둘 다. */
    private static final float PROBABILITY = 0.02F;
    /** {@code LookAtPlayerGoal.start()} = {@code 40 + nextInt(40)} → 40~79틱. */
    private static final int PLAYER_TIME_BASE = 40;
    private static final int PLAYER_TIME_RAND = 40;
    /** {@code RandomLookAroundGoal.start()} = {@code 20 + nextInt(20)} → 20~39틱. */
    private static final int RANDOM_TIME_BASE = 20;
    private static final int RANDOM_TIME_RAND = 20;
    /** {@code Mob.getHeadRotSpeed()} = 10. {@code LookControl} 의 yaw 속도 상한. */
    private static final float HEAD_ROT_SPEED = 10.0F;
    /** {@code Mob.getMaxHeadXRot()} = 40. */
    private static final float MAX_HEAD_X_ROT = 40.0F;
    /** {@code Mob.getMaxHeadYRot()} = 75. */
    private static final float MAX_HEAD_Y_ROT = 75.0F;
    /** {@code TargetingConditions.test} 의 {@code Math.max(range * visibility, 2.0)}. */
    private static final double MIN_RANGE = 2.0D;
    /** {@code LookControl.setLookAt} 이 매번 다시 넣는 값. 목표가 끊겨도 2틱은 유지된다. */
    private static final int LOOK_COOLDOWN = 2;

    /** 지금 어느 Goal 이 LOOK 플래그를 잡고 있는가. */
    public enum Mode { NONE, PLAYER, RANDOM }

    private final java.util.Random rng = new java.util.Random();

    private Mode mode = Mode.NONE;
    private int lookTime;
    private Player target;
    private double relX;
    private double relZ;

    /** 시뮬레이션된 절대 {@code yHeadRot}. 첫 틱에 몸통으로 초기화한다. */
    private double headYaw = Double.NaN;
    /** 시뮬레이션된 {@code xRot} (바닐라 부호: + 가 아래). */
    private double xRot;
    private double wantX;
    private double wantY;
    private double wantZ;
    private int cooldown;
    private int lastTick = Integer.MIN_VALUE;

    // ---- 계측. 상태기계를 건드리지 않는다 --------------------------------------------------
    private int playerStarts;
    private int randomStarts;
    private int playerTicks;
    private int randomTicks;
    private int idleTicks;
    private int inRangeTicks;
    private int outRangeTicks;

    public Mode mode() {
        return this.mode;
    }

    public int playerStarts() {
        return this.playerStarts;
    }

    public int randomStarts() {
        return this.randomStarts;
    }

    public int playerTicks() {
        return this.playerTicks;
    }

    public int randomTicks() {
        return this.randomTicks;
    }

    public int idleTicks() {
        return this.idleTicks;
    }

    public int inRangeTicks() {
        return this.inRangeTicks;
    }

    public int outRangeTicks() {
        return this.outRangeTicks;
    }

    /**
     * 틱당 한 번만 전진한다. 프레임마다 부르면 Goal 확률이 프레임률만큼 자주 굴려진다.
     *
     * @return {yaw, pitch} — {@code EntityModelData} 와 같은 본 규약
     */
    public double[] advance(WardenGirlEntity mob, int tickCount) {
        if (Double.isNaN(this.headYaw)) {
            this.headYaw = mob.yBodyRot;
        }
        if (tickCount != this.lastTick) {
            int elapsed = this.lastTick == Integer.MIN_VALUE ? 1
                    : Math.max(1, Math.min(20, tickCount - this.lastTick));
            this.lastTick = tickCount;
            for (int i = 0; i < elapsed; i++) {
                stepGoals(mob);
                stepLookControl(mob);
            }
        }
        return new double[]{-wrapDeg(this.headYaw - mob.yBodyRot), -this.xRot};
    }

    /**
     * {@code GoalSelector.tick()} 재현.
     *
     * <p>둘 다 {@code Goal.Flag.LOOK} 을 잡으므로 <b>배타</b>이고, 우선순위는
     * {@code LookAtPlayerGoal} 7 &lt; {@code RandomLookAroundGoal} 8 이라 플레이어 쪽이 이긴다.
     * 러닝 중이 아닌 goal 은 <b>매 틱</b> {@code canUse} 를 굴리므로, 랜덤 시선이 도는 중에도
     * 플레이어 쪽이 성공하면 선점한다.
     */
    private void stepGoals(WardenGirlEntity mob) {
        if (this.mode != Mode.PLAYER && this.rng.nextFloat() < PROBABILITY) {
            Player p = nearestPlayer(mob);
            if (p != null) {
                this.mode = Mode.PLAYER;
                this.target = p;
                this.lookTime = PLAYER_TIME_BASE + this.rng.nextInt(PLAYER_TIME_RAND);
                this.playerStarts++;
            }
        }
        if (this.mode == Mode.PLAYER && !canContinuePlayer(mob)) {
            this.mode = Mode.NONE;
            this.target = null;
        }
        if (this.mode == Mode.RANDOM && this.lookTime <= 0) {
            this.mode = Mode.NONE;
        }
        if (this.mode == Mode.NONE && this.rng.nextFloat() < PROBABILITY) {
            // RandomLookAroundGoal.start(): d0 = 2π × nextDouble(), 반경 1 원 위의 한 점.
            // 각도 범위는 수평 360° 균등이다 - 바이트코드 값 그대로다.
            double d0 = Math.PI * 2.0D * this.rng.nextDouble();
            this.relX = Math.cos(d0);
            this.relZ = Math.sin(d0);
            this.lookTime = RANDOM_TIME_BASE + this.rng.nextInt(RANDOM_TIME_RAND);
            this.mode = Mode.RANDOM;
            this.randomStarts++;
        }
        switch (this.mode) {
            case PLAYER -> {
                this.lookTime--;
                this.playerTicks++;
                setLookAt(this.target.getX(), this.target.getEyeY(), this.target.getZ());
            }
            case RANDOM -> {
                this.lookTime--;
                this.randomTicks++;
                setLookAt(mob.getX() + this.relX, mob.getEyeY(), mob.getZ() + this.relZ);
            }
            default -> this.idleTicks++;
        }
    }

    private void setLookAt(double x, double y, double z) {
        this.wantX = x;
        this.wantY = y;
        this.wantZ = z;
        this.cooldown = LOOK_COOLDOWN;
    }

    /**
     * {@code LookAtPlayerGoal.canContinueToUse()} — 살아 있고, 거리² 안이고, 시간이 남아 있을 것.
     */
    private boolean canContinuePlayer(WardenGirlEntity mob) {
        return this.target != null && this.target.isAlive() && this.lookTime > 0
                && mob.distanceToSqr(this.target) <= LOOK_DISTANCE * LOOK_DISTANCE;
    }

    /**
     * {@code Level.getNearestPlayer(lookAtContext, mob, x, eyeY, z)} 재현.
     *
     * <p>{@code lookAtContext} 는 {@code TargetingConditions.forNonCombat().range(12)} 에
     * {@code Player.class} 일 때만 붙는 {@code EntitySelector.notRiding(mob)} 선택자다. 그래서
     * <b>같은 탈것에 탄 플레이어는 대상이 아니다</b> — 보트에 함께 탄 사람은 안 본다. 바닐라 그대로다.
     *
     * <p>{@code TargetingConditions.test} 의 첫 검사가 {@code canBeSeenByAnyone()} 이라
     * <b>관전 모드는 제외</b>되고, 거리는 {@code max(range × 가시성, 2.0)} 이다 — 투명하거나 웅크린
     * 플레이어는 더 가까워야 보인다.
     */
    private Player nearestPlayer(WardenGirlEntity mob) {
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (Player p : mob.level().players()) {
            if (!p.canBeSeenByAnyone()) {
                continue;
            }
            // EntitySelector.notRiding(mob): 나와 같은 탈것을 공유하지 않을 것.
            if (p == mob.getVehicle() || (p.getVehicle() != null && p.getVehicle() == mob.getVehicle())
                    || p.getPassengers().contains(mob)) {
                continue;
            }
            double range = Math.max(LOOK_DISTANCE * p.getVisibilityPercent(mob), MIN_RANGE);
            double dSq = p.distanceToSqr(mob.getX(), mob.getEyeY(), mob.getZ());
            if (dSq > range * range || dSq >= bestSq) {
                continue;
            }
            bestSq = dSq;
            best = p;
        }
        return best;
    }

    /**
     * {@code LookControl.tick()} 재현.
     *
     * <p><b>유휴 구간의 거동도 바닐라 그대로다</b> — 목표가 없으면 {@code yHeadRot} 이
     * {@code yBodyRot} 쪽으로 10°/틱 으로 돌아가고, {@code resetXRotOnTick()} 이 true 라
     * 피치는 매 틱 0 으로 리셋된다. 우리가 새로 정한 것이 아니다.
     */
    private void stepLookControl(WardenGirlEntity mob) {
        this.xRot = 0.0D;
        if (this.cooldown > 0) {
            this.cooldown--;
            double dx = this.wantX - mob.getX();
            double dy = this.wantY - mob.getEyeY();
            double dz = this.wantZ - mob.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (Math.abs(dx) > 1.0E-5D || Math.abs(dz) > 1.0E-5D) {
                double wantYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
                this.headYaw = rotateTowards(this.headYaw, wantYaw, HEAD_ROT_SPEED);
            }
            if (Math.abs(dy) > 1.0E-5D || horiz > 1.0E-5D) {
                double wantXRot = -Math.toDegrees(Math.atan2(dy, horiz));
                this.xRot = rotateTowards(0.0D, wantXRot, MAX_HEAD_X_ROT);
            }
        } else {
            this.headYaw = rotateTowards(this.headYaw, mob.yBodyRot, 10.0F);
        }
        // clampHeadRotationToBody(): Mth.rotateIfNecessary(yHeadRot, yBodyRot, getMaxHeadYRot()).
        double excess = wrapDeg(this.headYaw - mob.yBodyRot);
        if (excess > MAX_HEAD_Y_ROT) {
            this.headYaw = mob.yBodyRot + MAX_HEAD_Y_ROT;
        } else if (excess < -MAX_HEAD_Y_ROT) {
            this.headYaw = mob.yBodyRot - MAX_HEAD_Y_ROT;
        }
    }

    private static double rotateTowards(double from, double to, float maxDelta) {
        return from + Mth.clamp((float) wrapDeg(to - from), -maxDelta, maxDelta);
    }

    static double wrapDeg(double deg) {
        double d = deg % 360.0D;
        if (d >= 180.0D) {
            d -= 360.0D;
        }
        if (d < -180.0D) {
            d += 360.0D;
        }
        return d;
    }

    /** 계측용. 이번 틱 플레이어가 12블록 안이었는지 기록만 한다. */
    public void noteRange(boolean inRange) {
        if (inRange) {
            this.inRangeTicks++;
        } else {
            this.outRangeTicks++;
        }
    }
}
