package com.wardengirl.client;

import com.wardengirl.anim.AnimParams;

/**
 * 4.6 시선 감쇠 — one first-order lag per axis, in degrees.
 *
 * <pre>
 *   current += (target - current) * damping        // per TICK
 * </pre>
 *
 * <h2>Ticks, not frames</h2>
 *
 * Same rule as {@link HeadgearSpring}. {@code damping} is a per-tick coefficient, so stepping it
 * once per rendered frame would make the neck's responsiveness depend on the framerate — at 200fps
 * it would catch up ten times faster than at 20fps and every judgement made by eye would be
 * invalidated by a different machine. The filter advances only when the entity's tick counter
 * advances, and the rendered value is interpolated between the last two tick states by
 * {@code partialTick}.
 *
 * <h2>1.0 은 우회 스위치다</h2>
 *
 * At {@code damping >= 1} the recurrence already collapses to {@code current = target}, but the
 * tick gating and the partialTick interpolation would still hold the output one tick behind. That
 * is not good enough: the point of the switch is to reproduce T3 <em>exactly</em>, so the two can be
 * compared on screen in one session. {@link #advanceTo} therefore assigns the state outright and
 * {@link #output} returns the target itself — bit-for-bit what T3 fed the bone.
 *
 * <h2>What it does NOT smooth</h2>
 *
 * The target arriving here is {@code netHeadYaw = yHeadRot - yBodyRot}, and vanilla has already
 * shaped both halves — but not with a lag of this kind. Verified in the 1.20.1 bytecode:
 * <ul>
 *   <li>{@code LookControl.tick} moves {@code yHeadRot} with {@code rotateTowards(from, to, max)} =
 *       {@code from + clamp(wrapDegrees(to-from), ±max)} at {@code getHeadRotSpeed()} = <b>10°/tick</b>.
 *       That is a slew-rate limit, not an exponential filter: it has no tail, and once the head has
 *       caught up it contributes exactly zero lag.</li>
 *   <li>{@code Mth.rotlerp(a, b, max)} = {@code b - clamp(wrapDegrees(b-a), ±max)} is, despite the
 *       name, a <em>clamp</em> — it returns {@code a} unchanged whenever the two are within
 *       {@code max}. It is what holds the head within {@code getMaxHeadYRot()} = 75° of the body.</li>
 *   <li>{@code LivingEntity.tickHeadTurn}'s {@code yBodyRot += wrapDegrees(...) * 0.3F} <b>is</b> a
 *       real exponential lerp — but {@code Mob} overrides that method to call
 *       {@code BodyRotationControl.clientTick()} and return its argument untouched, so for this
 *       entity the 0.3 never runs.</li>
 * </ul>
 * So this is the only exponential stage in the chain. What sits upstream is a rate cap, which is
 * why "smoothed twice" does not describe the composite: while the head is slewing the cap dominates
 * and this filter adds its lag on top; once the head arrives the cap is inert and only this filter
 * is still moving.
 */
public final class LookDamper {

    public static final int YAW = 0;
    public static final int PITCH = 1;

    private final double[] current = new double[2];
    private final double[] prev = new double[2];
    private int lastTick = Integer.MIN_VALUE;
    private boolean bypassed = false;

    /** Cap on catch-up steps after a pause. Same reasoning as {@link HeadgearSpring}. */
    private static final int MAX_CATCHUP_TICKS = 20;

    /**
     * The coefficient actually used at {@code distance} blocks from the player.
     *
     * <p>Linear in distance across the whole {@code 0 .. look_near_distance} span rather than a
     * step at the boundary. A step would mean the neck's angular speed jumps by the ratio of the
     * two coefficients — better than 2× at the defaults — the instant a walking player crosses the
     * 3-block line, which reads as the mob flinching. Ramping the whole way in also means there is
     * no second discontinuity hidden inside the near zone.
     *
     * <p>{@code distance < 0} (no local player) falls back to the far value.
     */
    public static double dampingFor(double distance) {
        double far = AnimParams.LOOK_DAMPING.get();
        double near = AnimParams.LOOK_DAMPING_NEAR.get();
        double span = AnimParams.LOOK_NEAR_DISTANCE.get();
        if (distance < 0 || span <= 0) {
            return clampCoefficient(far);
        }
        double t = Math.min(1.0D, distance / span);
        return clampCoefficient(near + (far - near) * t);
    }

    /**
     * Keeps the recurrence in the range where it converges.
     *
     * <p>Below 0 the step moves <em>away</em> from the target and the state runs off to infinity;
     * above 1 it overshoots every tick and rings. The spring taught this lesson the expensive way —
     * a single out-of-range value left the decoration pinned at its clamp for 45 seconds after the
     * value had been put back, because the state itself had already blown up. A coefficient is
     * cheap to clamp at the point of use, so there is no state to rescue.
     */
    private static double clampCoefficient(double k) {
        if (!Double.isFinite(k)) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, k));
    }

    /**
     * Advances the filter to {@code tickCount}, chasing {@code target} (yaw, pitch in degrees).
     *
     * @param damping the effective coefficient from {@link #dampingFor}
     */
    public void advanceTo(int tickCount, double[] target, double damping) {
        if (damping >= 1.0D) {
            // Bypass. Keep the state pinned to the target so that turning damping back down
            // resumes from where the head actually is instead of snapping from a stale value.
            this.bypassed = true;
            for (int i = 0; i < 2; i++) {
                this.current[i] = target[i];
                this.prev[i] = target[i];
            }
            this.lastTick = tickCount;
            return;
        }
        this.bypassed = false;
        if (this.lastTick == Integer.MIN_VALUE) {
            // First sight of this entity: start settled, so a freshly spawned mob does not sweep
            // its head across from zero on the frame it appears.
            for (int i = 0; i < 2; i++) {
                this.current[i] = target[i];
                this.prev[i] = target[i];
            }
            this.lastTick = tickCount;
            return;
        }
        int steps = tickCount - this.lastTick;
        if (steps <= 0) {
            return;
        }
        this.lastTick = tickCount;
        steps = Math.min(steps, MAX_CATCHUP_TICKS);
        for (int s = 0; s < steps; s++) {
            for (int i = 0; i < 2; i++) {
                this.prev[i] = this.current[i];
                this.current[i] += (target[i] - this.current[i]) * damping;
            }
        }
    }

    /**
     * The damped angle to put on the bone, in degrees.
     *
     * @param target this frame's target on the same axis — returned unchanged while bypassed
     */
    public double output(int axis, float partialTick, double target) {
        if (this.bypassed) {
            return target;
        }
        return this.prev[axis] + (this.current[axis] - this.prev[axis]) * partialTick;
    }

    /** Filter state, yaw/pitch degrees — for the trace readback. */
    public double[] state() {
        return new double[]{this.current[0], this.current[1]};
    }

    /** Drops all state, so the next {@link #advanceTo} re-seeds on the target. */
    public void reset() {
        this.lastTick = Integer.MIN_VALUE;
        this.bypassed = false;
        for (int i = 0; i < 2; i++) {
            this.current[i] = 0.0D;
            this.prev[i] = 0.0D;
        }
    }
}
