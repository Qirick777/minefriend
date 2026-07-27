package com.wardengirl.client;

import com.wardengirl.anim.AnimParams;

/**
 * 4.4.3 방향 전환 — banks {@code hip} into the turn, with an overshoot on the way out.
 *
 * <h2>Ticks, not frames</h2>
 *
 * Same rule as {@link HeadgearSpring} and {@link LookDamper}: the yaw rate is degrees per
 * <em>tick</em>, and the smoothing coefficients are per tick. Sampling {@code yBodyRot} once per
 * frame and differencing would report a rate that scaled with the framerate.
 *
 * <h2>The overshoot is on the way out, not the way in</h2>
 *
 * 4.4.3 says "종료 시 오버슈트 1.2배 후 정착". A body that has been leaning into a turn does not
 * snap upright when the turn ends — it rebounds slightly past vertical and settles. So the target
 * is {@code ±LEAN} while turning, and on release the lean is driven to {@code −0.2 ×} the lean it
 * was holding before decaying to zero. That is what produces a 1.2× excursion measured from the
 * held value: from {@code +3} through {@code 0} to {@code −0.6} is {@code 3.6 = 1.2 × 3}.
 *
 * <h2>Yaw rate, not yaw</h2>
 *
 * What decides "is this a left turn" is the sign of the per-tick change in {@code yBodyRot}, not
 * its value. Using the value would mean a mob walking due west leaned permanently.
 */
public final class TurnLean {

    /** Degrees of hip roll at full turn rate. 4.4.3. */
    private static final double LEAN_DEG = 3.0D;

    /**
     * Yaw rate, in degrees per tick, at which the lean reaches {@code LEAN_DEG}.
     *
     * <p>Vanilla caps a pathfinding mob's body turn at {@code getMaxHeadYRot()} per tick and in
     * practice a stroll turn runs far below that. 6°/tick is a normal cornering rate, so the lean
     * is at full value for an ordinary corner and simply saturates for a sharper one rather than
     * scaling past 3°.
     */
    private static final double FULL_RATE_DEG_PER_TICK = 6.0D;

    /** How fast the lean chases its target. Per tick. */
    private static final double ATTACK = 0.25D;

    /** How fast the rebound decays once the turn has ended. Per tick. */
    private static final double RELEASE = 0.18D;

    /** 4.4.3 "오버슈트 1.2배" — see the class doc for why this is 0.2 and not 1.2. */
    private static final double OVERSHOOT = 0.2D;

    private double lean;
    private double prevLean;
    private double rawRate;
    private double lastYaw;
    private double heldLean;
    private boolean turning;
    private int lastTick = Integer.MIN_VALUE;

    /** Same cap and reason as {@link HeadgearSpring#advanceTo}. */
    private static final int MAX_CATCHUP_TICKS = 20;

    /**
     * @param yBodyRot the entity's body yaw this tick, degrees
     * @param moving   whether the entity is actually travelling — a mob turning on the spot while
     *                 standing still is looking around, not cornering, and must not bank
     * @return the hip roll to apply, degrees, interpolated by {@code partialTick}
     */
    public double advanceTo(int tickCount, double yBodyRot, boolean moving, float partialTick) {
        if (this.lastTick == Integer.MIN_VALUE) {
            this.lastYaw = yBodyRot;
            this.lastTick = tickCount;
            return 0.0D;
        }
        int steps = tickCount - this.lastTick;
        if (steps > 0) {
            this.lastTick = tickCount;
            steps = Math.min(steps, MAX_CATCHUP_TICKS);
            double delta = wrapDegrees(yBodyRot - this.lastYaw);
            this.lastYaw = yBodyRot;
            // The whole delta accumulated over `steps` ticks, spread evenly.
            this.rawRate = moving ? delta / steps : 0.0D;
            // 좌회전 +3, 우회전 -3. Minecraft yaw increases clockwise (0 = +Z, 90 = -X), so a LEFT
            // turn decreases yaw — hence the negation. Nothing else in the project flips a sign,
            // and this one is a convention conversion, not a correction.
            double target = clamp(-this.rawRate / FULL_RATE_DEG_PER_TICK, -1.0D, 1.0D) * LEAN_DEG;
            boolean nowTurning = Math.abs(this.rawRate) > 0.05D;
            for (int s = 0; s < steps; s++) {
                this.prevLean = this.lean;
                if (nowTurning) {
                    this.lean += (target - this.lean) * ATTACK;
                    this.heldLean = this.lean;
                } else {
                    // Release: aim past zero by OVERSHOOT of whatever was being held, then decay.
                    double rebound = -this.heldLean * OVERSHOOT;
                    this.lean += (rebound - this.lean) * RELEASE;
                    this.heldLean *= (1.0D - RELEASE);
                }
            }
            this.turning = nowTurning;
        }
        double interpolated = this.prevLean + (this.lean - this.prevLean) * partialTick;
        return AnimParams.TURN_LEAN_SCALE.get() * interpolated;
    }

    /** Degrees per tick of body yaw change, for the trace readback. */
    public double rawRate() {
        return this.rawRate;
    }

    public boolean isTurning() {
        return this.turning;
    }

    private static double wrapDegrees(double d) {
        d %= 360.0D;
        if (d >= 180.0D) {
            d -= 360.0D;
        }
        if (d < -180.0D) {
            d += 360.0D;
        }
        return d;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
