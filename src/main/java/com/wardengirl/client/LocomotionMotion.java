package com.wardengirl.client;

import com.wardengirl.anim.AnimRegistry;

/**
 * C2 playback position and idle↔walk cross-fade, for one entity. Design doc 4.4.1 / 4.4.2.
 *
 * <h2>Why C2 left the controller</h2>
 *
 * Problem 3: the walk cycle is 26 ticks regardless of how fast the mob actually moves, and the
 * measured mismatch is 3.20×. Fixing it means driving the playhead from accumulated distance rather
 * than from time — and <b>GeckoLib offers no way to inject a playhead.</b> The one lever it has,
 * {@code setAnimationSpeed}, multiplies <em>elapsed</em> time
 * ({@code speed × (tick − tickOffset)}, 4.8.4 {@code adjustTick}), so every speed change rescales
 * the whole history and the playhead jumps. For a mob that accelerates and decelerates, that is
 * every tick. The transition runs on the same clock, so it would jump too.
 *
 * <p>Evaluating the clip ourselves does not work around that defect — <b>it never meets it.</b>
 *
 * <h2>The controller keeps running, and that is what removes the transition bugs</h2>
 *
 * The locomotion controller now plays {@code idle} unconditionally (or {@code signtest} when that
 * debug flag is on) and <b>never changes animation</b>. So:
 *
 * <ul>
 *   <li>it still assigns all seven locomotion channels every frame, which is what stops Java's
 *       {@code +=} writes from accumulating — {@code base} does not have to take them back;</li>
 *   <li>there is no controller transition left to have its snapshot polluted. The defect that took
 *       the longest to find in T5 is now structurally absent, not merely fixed.</li>
 * </ul>
 *
 * <h2>The stop fade freezes the pose. Measured — a cross-fade is worse.</h2>
 *
 * The first attempt cross-faded the two clips' <em>live</em> poses: {@code walkPose(phase) × (1−u)}.
 * That reads well on paper — no derivative break where the fade starts — but its rate is a
 * <em>sum</em>: {@code |d(walkPose)/dt| × (1−u) + |walkPose| × du/dt}. Measured, the walk's own rate
 * is 2.74 °/tick and the fade adds up to {@code 18/6 = 3} °/tick, so the worst case sits near 5.7.
 * The stop transition duly measured <b>5.27 °/tick</b> against <b>1.79</b> before the migration —
 * a regression on the exact metric this problem has been tracked by all along.
 *
 * <p>So the fade-out matches GeckoLib's semantics instead: the walk phase is <b>frozen</b> at the
 * moment walking ends, and that constant pose is scaled to zero. The rate is then only
 * {@code |frozen| × du/dt ≤ 3} °/tick, with no walk-cycle term in it.
 *
 * <p>The fade-<em>in</em> stays a cross-fade. There the source is idle's constant zero, so the sum
 * collapses to one term anyway, and it measured clean both before and after the migration.
 *
 * <h2>Round 1 keeps the clock as it was</h2>
 *
 * The playhead still advances one tick per tick. Distance-driven phase and speed-scaled amplitude
 * are round 2. This split exists so the regression check has exactly one variable: if the numbers
 * move here, it is the migration and not the new clock.
 */
public final class LocomotionMotion {

    /** json {@code animation_length: 1.3} seconds; GeckoLib multiplies by 20. */
    public static final double WALK_LENGTH_TICKS = 26.0D;

    /** Ticks into the walk cycle, wrapped. */
    private double phase;
    /** Fade weight, 0 = pure idle, 1 = pure walk. */
    private double weight;
    private double lastTime = Double.NaN;
    private boolean wasWalking = false;
    /** Walk phase frozen at the moment walking ended; the fade-out scales this pose down. */
    private double frozenPhase = Double.NaN;

    /**
     * Advances to {@code now} and returns the walk clip's blend weight.
     *
     * @param now     {@code tickCount + partialTick}
     * @param walking whether C2 should be playing the walk cycle
     */
    public double advance(double now, boolean walking) {
        if (Double.isNaN(this.lastTime)) {
            this.lastTime = now;
            this.weight = walking ? 1.0D : 0.0D;
            this.wasWalking = walking;
            return this.weight;
        }
        double dt = now - this.lastTime;
        this.lastTime = now;
        // Guard against a rewound clock (entity re-spawn, dimension change): advancing by a
        // negative delta would run the cycle backwards, which reads as the mob moonwalking.
        if (dt < 0.0D) {
            dt = 0.0D;
        }

        this.phase = (this.phase + dt) % WALK_LENGTH_TICKS;
        if (this.wasWalking && !walking) {
            this.frozenPhase = this.phase;
        } else if (walking) {
            this.frozenPhase = Double.NaN;
        }
        this.wasWalking = walking;

        double step = AnimRegistry.TRANSITION_TICKS <= 0 ? 1.0D
                : dt / AnimRegistry.TRANSITION_TICKS;
        this.weight = walking ? Math.min(1.0D, this.weight + step)
                : Math.max(0.0D, this.weight - step);
        return this.weight;
    }

    /**
     * Playhead in ticks.
     *
     * <p>Not reset when the walk stops. GeckoLib restarted the clip from tick 0 on every start
     * ({@code justStopped} → {@code adjustTick} → {@code tickOffset = tick}), which is what made a
     * momentary velocity dip snap the legs back to the cycle's first frame. Letting the phase run
     * on means a stop-and-go resumes mid-stride, and the fade covers the seam.
     *
     * <p>Returns the frozen phase while a stop fade is in progress — see the class doc.
     */
    public double phase() {
        return Double.isNaN(this.frozenPhase) ? this.phase : this.frozenPhase;
    }

    public double weight() {
        return this.weight;
    }
}
