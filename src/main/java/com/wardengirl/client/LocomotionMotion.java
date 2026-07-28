package com.wardengirl.client;

import com.wardengirl.anim.AnimParams;
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
 * <h2>Round 2: the playhead is driven by distance</h2>
 *
 * The cycle no longer advances with time. It advances with how far the mob has actually gone, and
 * the distance one cycle covers is computed from the leg amplitude that will be drawn:
 *
 * <pre>
 *   한 걸음  = 2 × 12px × sin(유효 다리 진폭) / 16      (레버 12px, 발바닥 y=0)
 *   한 사이클 = 2 걸음
 *   위상 전진 = (이번 틱 이동거리 / 한 사이클) × 26틱 / walk_cycle_scale
 * </pre>
 *
 * <b>Self-consistent by construction.</b> The stride the amplitude implies <em>is</em> the distance
 * the cycle takes, so {@code walk_leg_amp_scale} changes the silhouette without ever breaking
 * contact — the foot-slip ratio stays 1.0 at any amplitude. That is why the amplitude is a free
 * tuning knob and the cycle length is not.
 */
public final class LocomotionMotion {

    /** json {@code animation_length: 1.3} seconds; GeckoLib multiplies by 20. */
    public static final double WALK_LENGTH_TICKS = 26.0D;

    /** 4.4.2 leg amplitude, degrees. {@code walk_leg_amp_scale} and limbSwingAmount multiply it. */
    public static final double LEG_AMPLITUDE_DEG = 18.0D;

    /**
     * Blocks the body must cover per walk cycle, for a given drawn leg amplitude.
     *
     * <p>The leg pivots at {@code y = 12px} and the sole sits at {@code y = 0}, so a sweep to
     * {@code ±A} moves the sole {@code 2 × 12 × sin A} pixels — one step. A cycle is two steps.
     * Divided by 16 to get blocks.
     */
    public static double cycleBlocks(double legAmplitudeDeg) {
        return 2.0D * (2.0D * 12.0D * Math.sin(Math.toRadians(Math.abs(legAmplitudeDeg))) / 16.0D);
    }

    /**
     * Horizontal distance the <em>drawn</em> body covered since the previous frame, in blocks.
     *
     * <h2>Why the phase left the tick</h2>
     *
     * The first distance-driven version consumed a whole tick's travel on that tick's first frame
     * ({@code walkDistanceThisTickForAnimation} returned 0 on every other frame). So the phase was a
     * staircase and the drawn leg angle held still for a whole tick and then jumped. Measured, the
     * cycle is only <b>3.78 ticks</b> long at the default amplitude — an inscribed polygon with
     * fewer than four vertices per cycle, and an inscribed polygon is always shorter than the curve.
     * Offline integration of the real clip put that loss at <b>0.8196</b> against a geometric target
     * of 1.018, which is 75% of the whole measured foot-slip shortfall.
     *
     * <p>The input is the <b>rendered</b> position, {@code Mth.lerp(partialTick, xOld, getX())}.
     * Verified from the 1.20.1 bytecode rather than assumed: {@code ClientLevel.tickNonPassenger}
     * calls {@code setOldPosAndRot()} immediately before {@code Entity.tick()}, so {@code xOld} is
     * the position at the start of the tick; and {@code EntityRenderDispatcher.render} computes the
     * draw position with that exact expression. Driving the phase from it means the legs are locked
     * to the body <em>as drawn</em>, not as ticked.
     *
     * @return 0 on the first call, and 0 for a jump larger than {@link #TELEPORT_BLOCKS} — a
     *         teleport is not locomotion, and feeding one in would spin the cycle.
     */
    private static final double TELEPORT_BLOCKS = 0.5D;

    public double frameDistance(double x, double z) {
        if (Double.isNaN(this.lastX)) {
            this.lastX = x;
            this.lastZ = z;
            return 0.0D;
        }
        double dx = x - this.lastX;
        double dz = z - this.lastZ;
        this.lastX = x;
        this.lastZ = z;
        double d = Math.sqrt(dx * dx + dz * dz);
        return d > TELEPORT_BLOCKS ? 0.0D : d;
    }

    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;

    /** Ticks into the walk cycle, wrapped. */
    private double phase;
    /** Fade weight, 0 = pure idle, 1 = pure walk. */
    private double weight;
    /**
     * 4.13 앉기 가중치. 0 = 서 있음, 1 = 앉음.
     *
     * <p>걷기와 <b>배타</b>다 — 탑승 중에는 {@code isWalkingForAnimation()} 이 false 이므로
     * {@link #weight} 가 0 으로 내려간다. 그래서 다리에 두 자세가 동시에 실릴 수 없고,
     * 그것이 (a)안을 고른 이유다. 전이는 걷기와 같은 {@code TRANSITION_TICKS} 를 쓴다.
     */
    private double sitWeight;

    public double sitWeight() {
        return this.sitWeight;
    }

    /** 탑승 여부를 받아 앉기 가중치를 전진시킨다. 걷기와 같은 dt 를 쓴다. */
    public void advanceSit(boolean riding, double dt) {
        double step = AnimRegistry.TRANSITION_TICKS <= 0 ? 1.0D
                : dt / AnimRegistry.TRANSITION_TICKS;
        this.sitWeight = riding ? Math.min(1.0D, this.sitWeight + step)
                : Math.max(0.0D, this.sitWeight - step);
    }
    private double lastTime = Double.NaN;
    private boolean wasWalking = false;
    /** Walk phase frozen at the moment walking ended; the fade-out scales this pose down. */
    private double frozenPhase = Double.NaN;
    /** Frame delta of the last {@link #advance}, for the fade diagnostic. */
    private double lastDt = 0.0D;

    public double lastDt() {
        return this.lastDt;
    }

    /** Ticks since the previous frame, for callers that need to convert a speed into a distance. */
    public double lastFrameTicks(double now) {
        return Double.isNaN(this.lastTime) ? 0.0D : Math.max(0.0D, now - this.lastTime);
    }

    /**
     * Advances to {@code now} and returns the walk clip's blend weight.
     *
     * @param now     {@code tickCount + partialTick}
     * @param walking whether C2 should be playing the walk cycle
     */
    public double advance(double now, boolean walking, double distanceBlocks,
                          double legAmplitudeDeg) {
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

        // Distance, not time. A cycle covers exactly the stride its own amplitude implies, so the
        // feet cannot slide however the amplitude is tuned. Guarded because a zero amplitude has
        // no stride to divide by - at that point the legs are not moving and neither should the
        // phase.
        double perCycle = cycleBlocks(legAmplitudeDeg) * AnimParams.WALK_CYCLE_SCALE.get();
        if (perCycle > 1.0E-6D && distanceBlocks > 0.0D) {
            this.phase = (this.phase + distanceBlocks / perCycle * WALK_LENGTH_TICKS)
                    % WALK_LENGTH_TICKS;
        }
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
        this.lastDt = dt;
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
