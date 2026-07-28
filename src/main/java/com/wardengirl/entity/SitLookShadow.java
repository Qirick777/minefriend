package com.wardengirl.entity;

import com.wardengirl.anim.AnimParams;
import com.wardengirl.anim.LookDamping;

import net.minecraft.util.Mth;

/**
 * 4.14 1-B-1. 탑승 중 시선의 <b>서버 권위 shadow</b>. 서버에서만 돌고, 아직 본에 적용되지 않는다.
 *
 * <h2>왜 서버가 전부 계산하는가</h2>
 *
 * 클라이언트가 좌표에서 속도 제한과 감쇠를 누적하면 그 상태가 클라마다 갈린다 — 프레임률,
 * 접속 시점, 패킷 병합이 전부 입력이 된다. 상태성 계산을 서버 한 곳에 두면 모든 클라가 같은
 * 최종각을 받는다.
 *
 * <h2>실제 {@code yHeadRot} 을 쓰지 않는다</h2>
 *
 * 탑승 중 {@code Boat.clampRotation} 이 매 틱 {@code yHeadRot = yRot} 로 덮으므로, 그 필드를
 * 누적 상태로 쓰면 매 틱 지워진다. 그래서 {@link #headYaw} 를 이 클래스가 따로 들고 간다.
 *
 * <h2>단계 순서</h2>
 *
 * <ol>
 *   <li>원시 목표 world yaw / entity pitch — {@code wanted} 일 때만 계산한다</li>
 *   <li>바닐라 틱당 속도 제한 (yaw 10, pitch 40)</li>
 *   <li>몸통 대비 yaw 제한 (±75)</li>
 *   <li>GeckoLib 본 규약 변환</li>
 *   <li>프로젝트 gain</li>
 *   <li>프로젝트 clamp (yaw 75, pitch 35)</li>
 *   <li>지수 감쇠</li>
 * </ol>
 *
 * <p>clamp 를 감쇠보다 먼저 하는 것은 4.6 의 결정을 그대로 따른 것이다 — 감쇠 뒤에 자르면 목표가
 * 범위 밖인 동안 머리가 한계에 붙박이고 접근 궤적이 직선으로 잘린다.
 *
 * <h2>{@code SitLook.stepLookControl()} 과 대조</h2>
 *
 * 수식·순서·wrapping·상한·몸통 제한·idle 분기가 전부 같다. 다른 점은 둘이다.
 * <ul>
 *   <li>이쪽은 Goal 정책을 <b>재현하지 않는다</b> — 서버의 진짜 Goal 이 정한 {@code wanted} 와
 *       좌표를 받는다. {@code SitLook} 은 확률과 지속시간까지 재현해야 했다.</li>
 *   <li>이쪽은 뒤에 gain / clamp / 감쇠가 붙는다. {@code SitLook} 은 본 규약까지만 만들고
 *       그 뒤는 {@code applyLook} 이 했다.</li>
 * </ul>
 */
public final class SitLookShadow {

    /** {@code Mob.getHeadRotSpeed()}. */
    private static final float HEAD_ROT_SPEED = 10.0F;
    /** {@code Mob.getMaxHeadXRot()}. */
    private static final float MAX_HEAD_X_ROT = 40.0F;
    /** {@code Mob.getMaxHeadYRot()}. */
    private static final float MAX_HEAD_Y_ROT = 75.0F;

    /** 어느 LOOK Goal 이 잡고 있는가. 클래스 이름이 아니라 인스턴스 동일성으로 정해진다. */
    public enum Goal { NONE, PLAYER, RANDOM }

    /**
     * 한 틱의 모든 중간값. <b>계측 전용 구조체</b> — 판정은 로그 분석 스크립트가 한다.
     *
     * <p>제품 클래스에 카운터를 흩뿌리지 않기 위해, 계산기가 단계값을 구조화해 돌려주고
     * 엔티티는 한 줄만 찍는다.
     */
    public static final class Step {
        public boolean wanted;
        public Goal goal = Goal.NONE;
        public double wantX;
        public double wantY;
        public double wantZ;
        /** {@code wanted=false} 구간에서 stale 좌표를 계산에 넣었는가. 항상 false 여야 한다. */
        public boolean staleUsed;
        public double rawYaw = Double.NaN;
        public double rawPitch = Double.NaN;
        public double prevHeadYaw;
        public double prevPitch;
        public double limitedHeadYaw;
        public double limitedPitch;
        public double bodyClampedYaw;
        public double boneYaw;
        public double bonePitch;
        public double gainClampYaw;
        public double gainClampPitch;
        public double damping;
        public double prevOutYaw;
        public double prevOutPitch;
        public double outYaw;
        public double outPitch;
    }

    /** 누적 상태. world 기준 절대 yaw. */
    private double headYaw = Double.NaN;
    /** 누적 상태. 바닐라 부호의 entity pitch (+ 가 아래). */
    private double xRot;
    private final LookDamping damper = new LookDamping();
    private boolean riding;

    public boolean isRiding() {
        return this.riding;
    }

    /** 하차하거나 대상이 바뀌면 부른다. 다음 탑승 때 몸통 정면 / pitch 0 에서 다시 시작한다. */
    public void reset() {
        this.headYaw = Double.NaN;
        this.xRot = 0.0D;
        this.damper.reset();
        this.riding = false;
    }

    /**
     * 서버 틱당 정확히 한 번 전진한다.
     *
     * @param bodyYaw    이번 틱의 {@code yBodyRot}
     * @param eyeY       몹 눈높이
     * @param goal       실행 중인 LOOK Goal (인스턴스 동일성으로 판정된 것)
     * @param dampingDistance {@link Goal#PLAYER} 일 때 실제 대상까지의 거리, 아니면 음수
     */
    public Step advance(double mobX, double eyeY, double mobZ, double bodyYaw,
                        boolean wanted, double wantX, double wantY, double wantZ,
                        Goal goal, double dampingDistance) {
        Step s = new Step();
        s.wanted = wanted;
        s.goal = goal;
        s.wantX = wantX;
        s.wantY = wantY;
        s.wantZ = wantZ;

        if (!this.riding || Double.isNaN(this.headYaw)) {
            // 탑승 시작. 몸통 정면과 pitch 0 이 기준이다.
            this.headYaw = bodyYaw;
            this.xRot = 0.0D;
            this.damper.reset();
            this.riding = true;
        }
        s.prevHeadYaw = this.headYaw;
        s.prevPitch = this.xRot;
        s.prevOutYaw = this.damper.get(LookDamping.YAW);
        s.prevOutPitch = this.damper.get(LookDamping.PITCH);

        // --- 1. 원시 목표각. wanted 가 아니면 좌표를 읽지도 않는다 ------------------------
        if (wanted) {
            double dx = wantX - mobX;
            double dy = wantY - eyeY;
            double dz = wantZ - mobZ;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (Math.abs(dx) > 1.0E-5D || Math.abs(dz) > 1.0E-5D) {
                s.rawYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
            }
            if (Math.abs(dy) > 1.0E-5D || horiz > 1.0E-5D) {
                s.rawPitch = -Math.toDegrees(Math.atan2(dy, horiz));
            }
            s.staleUsed = true;   // 계측: wanted=true 이므로 정상이다
        }

        // --- 2. 바닐라 틱당 속도 제한 ---------------------------------------------------
        if (wanted) {
            if (!Double.isNaN(s.rawYaw)) {
                this.headYaw = rotateTowards(this.headYaw, s.rawYaw, HEAD_ROT_SPEED);
            }
            if (!Double.isNaN(s.rawPitch)) {
                this.xRot = rotateTowards(0.0D, s.rawPitch, MAX_HEAD_X_ROT);
            }
        } else {
            // idle: yaw 는 몸통으로 10°/틱 복귀, pitch 는 0 으로 복귀.
            // resetXRotOnTick() 이 true 라 바닐라는 매 틱 0 으로 되돌린 뒤 목표가 있을 때만
            // 다시 벌린다. 목표가 없으므로 0 이 그대로 남는다.
            this.headYaw = rotateTowards(this.headYaw, bodyYaw, HEAD_ROT_SPEED);
            this.xRot = rotateTowards(0.0D, 0.0D, MAX_HEAD_X_ROT);
        }
        s.limitedHeadYaw = this.headYaw;
        s.limitedPitch = this.xRot;

        // --- 3. 몸통 대비 yaw 제한 ------------------------------------------------------
        double excess = wrapDeg(this.headYaw - bodyYaw);
        if (excess > MAX_HEAD_Y_ROT) {
            this.headYaw = bodyYaw + MAX_HEAD_Y_ROT;
        } else if (excess < -MAX_HEAD_Y_ROT) {
            this.headYaw = bodyYaw - MAX_HEAD_Y_ROT;
        }
        s.bodyClampedYaw = this.headYaw;

        // --- 4. 본 규약 변환 -------------------------------------------------------------
        s.boneYaw = -wrapDeg(this.headYaw - bodyYaw);
        s.bonePitch = -this.xRot;

        // --- 5~6. gain, 프로젝트 clamp ---------------------------------------------------
        double gain = AnimParams.LOOK_GAIN.get();
        s.gainClampYaw = clampAbs(s.boneYaw * gain, AnimParams.LOOK_YAW_MAX.get());
        s.gainClampPitch = clampAbs(s.bonePitch * gain, AnimParams.LOOK_PITCH_MAX.get());

        // --- 7. 지수 감쇠 ----------------------------------------------------------------
        // 거리 감쇠는 "지금 실제로 그 대상을 보고 있을 때"만 쓴다. Goal 이 running 이어도
        // wanted=false 인 틱(LookControl 쿨다운이 그 틱에 소진된 경우)에는 목표가 없으므로
        // 거리 계수를 쓰면 유휴 복귀에 근거리 계수가 섞인다.
        s.damping = wanted && goal == Goal.PLAYER && dampingDistance >= 0.0D
                ? LookDamping.dampingFor(dampingDistance)
                : LookDamping.farDamping();
        this.damper.advanceTick(s.gainClampYaw, s.gainClampPitch, s.damping);
        s.outYaw = this.damper.get(LookDamping.YAW);
        s.outPitch = this.damper.get(LookDamping.PITCH);
        return s;
    }

    private static double rotateTowards(double from, double to, float maxDelta) {
        return from + Mth.clamp((float) wrapDeg(to - from), -maxDelta, maxDelta);
    }

    private static double clampAbs(double v, double max) {
        return v > max ? max : (v < -max ? -max : v);
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
}
