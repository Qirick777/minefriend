package com.wardengirl.client;

import com.wardengirl.anim.AnimParams;

/**
 * 4.5.1 headgear 감쇠 스프링 — three independent axes, in degrees.
 *
 * <h2>The difference, not the angle</h2>
 *
 * {@code headgear} is a child of {@code head}, so head's rotation is <em>already</em> inherited by
 * the decoration through the bone hierarchy. What this spring contributes is only the part the
 * decoration fails to keep up with:
 *
 * <pre>
 *   headgear.rotationDeg = clamp((angleDeg - targetDeg) * AMPLITUDE, ±MAX_ANGLE)
 * </pre>
 *
 * Applying {@code angleDeg} itself would turn the decoration twice — the doc's warning
 * <em>"놓치면 장식이 두 배로 돈다"</em>. So the applied value is a lag <b>error</b>, and it is zero
 * whenever the spring has caught up. That is also why a still head produces a still decoration
 * rather than a permanently offset one.
 *
 * <h2>Ticks, not frames</h2>
 *
 * The recurrence below is a difference equation whose constants are per <em>tick</em>. Stepping it
 * once per rendered frame would make the decoration's stiffness depend on the framerate — 200fps
 * would settle ten times faster than 20fps, and every tuning judgement made by eye would be
 * invalidated by a different machine. So the spring advances only when the entity's tick counter
 * advances, and the rendered value is interpolated between the last two tick states by
 * {@code partialTick} (4.5.1 "partialTick 보간 필수").
 *
 * <h2>Assignment, not addition</h2>
 *
 * {@link #output} is <b>assigned</b> to the bone, never added. That matters for the same reason the
 * legs spun in T2: {@code GeoBone}'s setters call {@code markRotationAsChanged()} and
 * {@code AnimationProcessor} then skips its reset for that bone forever. With {@code +=} the bone
 * would accumulate one spring output per frame. With {@code =} every frame overwrites the last, so
 * accumulation is structurally impossible and {@code headgear} needs no {@code rotation} channel in
 * the animation json — which keeps Part 10.4's "headgear 키프레임 금지" intact.
 */
public final class HeadgearSpring {

    /** x, y, z — in degrees. */
    private final double[] angle = new double[3];
    private final double[] prevAngle = new double[3];
    private final double[] velocity = new double[3];

    private int lastTick = Integer.MIN_VALUE;

    /**
     * Multiplier on {@code STIFFNESS} for this instance. 1.0 for the right tendril, slightly less
     * for the left, so the two sides do not move as one rigid piece — see
     * {@link AnimParams#HEADGEAR_ASYMMETRY}.
     */
    private final double stiffnessScale;

    public HeadgearSpring(double stiffnessScale) {
        this.stiffnessScale = stiffnessScale;
    }

    /**
     * Cap on catch-up steps after a pause (game paused, window unfocused, chunk reload). Without
     * it, a one-minute gap would run 1200 iterations in a single frame and the spring would arrive
     * having "rung" through the whole backlog. One second is enough to look continuous.
     */
    private static final int MAX_CATCHUP_TICKS = 20;

    /**
     * Advances the spring to {@code tickCount}, chasing {@code targetDeg} (head's current rotation
     * in degrees, x/y/z).
     *
     * @return true if at least one step ran
     */
    public boolean advanceTo(int tickCount, double[] targetDeg) {
        if (needsReseed()) {
            this.lastTick = Integer.MIN_VALUE;
        }
        if (this.lastTick == Integer.MIN_VALUE) {
            // First sight of this entity: start settled on the target so a freshly summoned mob
            // does not fling its decoration through a full swing on frame one.
            for (int i = 0; i < 3; i++) {
                this.angle[i] = targetDeg[i];
                this.prevAngle[i] = targetDeg[i];
                this.velocity[i] = 0.0D;
            }
            this.lastTick = tickCount;
            return false;
        }
        int steps = tickCount - this.lastTick;
        if (steps <= 0) {
            return false;
        }
        this.lastTick = tickCount;
        steps = Math.min(steps, MAX_CATCHUP_TICKS);

        double stiffness = AnimParams.HEADGEAR_STIFFNESS.get() * this.stiffnessScale;
        double damping = AnimParams.HEADGEAR_DAMPING.get();
        for (int s = 0; s < steps; s++) {
            for (int i = 0; i < 3; i++) {
                this.prevAngle[i] = this.angle[i];
                this.velocity[i] += (targetDeg[i] - this.angle[i]) * stiffness
                        - this.velocity[i] * damping;
                this.angle[i] += this.velocity[i];
            }
        }
        return true;
    }

    /**
     * The value to assign to {@code headgear}'s axis, in degrees.
     *
     * @param targetDeg head's rotation on this axis <em>right now</em>, i.e. at render time with
     *                  C1 and the look offset already applied — the same quantity the decoration is
     *                  lagging behind.
     */
    public double output(int axis, float partialTick, double targetDeg) {
        double interpolated = this.prevAngle[axis]
                + (this.angle[axis] - this.prevAngle[axis]) * partialTick;
        double raw = (interpolated - targetDeg) * AnimParams.HEADGEAR_AMPLITUDE.get();
        double max = Math.abs(AnimParams.HEADGEAR_MAX_ANGLE.get());
        return Math.max(-max, Math.min(max, raw));
    }

    /** The stiffness this instance actually integrates with, for the stability report. */
    public double effectiveStiffness() {
        return AnimParams.HEADGEAR_STIFFNESS.get() * this.stiffnessScale;
    }

    /** Spring angle, x/y/z degrees — for the trace readback. */
    public double[] angles() {
        return new double[]{this.angle[0], this.angle[1], this.angle[2]};
    }

    /** Spring velocity, x/y/z degrees per tick — for the trace readback. */
    public double[] velocities() {
        return new double[]{this.velocity[0], this.velocity[1], this.velocity[2]};
    }

    /**
     * Sanity bound on the integrated angle, in degrees.
     *
     * <p>The head can reach {@code LOOK_YAW_MAX + 그 밖의 항} ≈ 105°, and a healthy underdamped
     * spring overshoots that by roughly a quarter. 1000° is therefore about eight times any value
     * this can legitimately hold — but it is a decisive distance from any value a <em>diverging</em>
     * one holds.
     */
    private static final double SANITY_LIMIT_DEG = 1000.0D;

    /**
     * Whether the state has to be thrown away and re-seeded.
     *
     * <p>Found by verification, not by reasoning: setting {@code headgear_damping} to 2.5 violates
     * the Jury condition and the integrated angle reached ~1e83 within a couple of hundred ticks.
     * Setting the parameter back to 0.35 makes the recurrence stable again — but stable means
     * {@code |λ| = 0.806 per tick}, so decaying from 1e83 back to a visible range takes about
     * <b>885 ticks, 45 seconds</b>. For all of that time the decoration sits pinned at the
     * ±MAX_ANGLE clamp.
     *
     * <p>That matters because tuning STIFFNESS/DAMPING by eye is exactly what this parameter exists
     * for. Without this guard the tuning loop is: try a bad value, see it break, put it back, and
     * conclude that putting it back did not help. The state is re-seeded instead, so a corrected
     * value takes effect on the next frame.
     *
     * <p>It also catches NaN and infinity, which no amount of waiting recovers from at all.
     */
    private boolean needsReseed() {
        for (int i = 0; i < 3; i++) {
            if (!Double.isFinite(this.angle[i]) || !Double.isFinite(this.velocity[i])
                    || Math.abs(this.angle[i]) > SANITY_LIMIT_DEG) {
                return true;
            }
        }
        return false;
    }

    /** Drops all state, so the next {@link #advanceTo} re-seeds on the target. */
    public void reset() {
        this.lastTick = Integer.MIN_VALUE;
        for (int i = 0; i < 3; i++) {
            this.angle[i] = 0.0D;
            this.prevAngle[i] = 0.0D;
            this.velocity[i] = 0.0D;
        }
    }
}
