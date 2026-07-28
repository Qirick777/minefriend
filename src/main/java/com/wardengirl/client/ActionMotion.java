package com.wardengirl.client;

import com.wardengirl.anim.AnimParams;

/**
 * C3 playback position and fade envelope, for one entity. Design doc Part 4.2 / 4.8.
 *
 * <h2>Single shot, no loop</h2>
 *
 * An action is a one-off: 공격 18틱, 소닉붐 60틱, 대시 3구간. It starts, it ends, and while it is
 * not playing it contributes exactly nothing. That is different from C1 (never stops) and from C2
 * (loops while a condition holds), and it is why this is a plain elapsed-tick counter rather than
 * a modulo.
 *
 * <h2>Ticks, not frames</h2>
 *
 * The playhead is {@code (tickCount + partialTick) − startTime}. Counting rendered frames instead
 * would make the clip's real duration depend on framerate — the same defect that made a
 * {@code trace 240} cover 94 ticks on one run and 2730 on another. The tick is the clock; the
 * partial tick is only what makes it smooth between them.
 *
 * <h2>The envelope</h2>
 *
 * <pre>
 *   in    : age / fadeIn                 rising  0 -> 1 over fade_in ticks
 *   out   : (length − age) / fadeOut     falling 1 -> 0 over the last fade_out ticks
 *   weight: min(in, out), clamped to [0,1]
 * </pre>
 *
 * <p><b>The contribution is scaled, not blended toward a pose.</b> C3 is an additive layer on top
 * of whatever C1 and C2 already produced, so "fading in" means the added value grows from zero —
 * there is no second pose to interpolate against. That also makes the fade free of the transition
 * defect C2 has: no snapshot is taken, so nothing can be polluted by it.
 *
 * <p>A clip shorter than {@code fadeIn + fadeOut} never reaches full weight. That is deliberate and
 * not clamped away: the alternative is silently stretching the clip, and a peak that is quietly
 * 0.6× the authored value is exactly the kind of thing that gets tuned around instead of fixed.
 */
public final class ActionMotion {

    /** Entity tick (plus partial) at which the current clip started, or NaN when idle. */
    private double startTime = Double.NaN;
    private String clip;
    private double lengthTicks;
    /** The sequence number this playback was started from; see {@link #syncTo}. */
    private int lastSeq = 0;

    /**
     * Starts {@code clip} if {@code seq} differs from the last one started.
     *
     * <p>A counter rather than a boolean because an action has to be re-triggerable while the
     * previous one is still playing — the second attack in a chain does not wait for the first to
     * finish. A boolean would need an off frame in between, and at 5 frames per tick that off frame
     * is visible.
     *
     * @return true if this call started a new playback
     */
    public boolean syncTo(int seq, String clip, double lengthTicks, double now) {
        if (seq == this.lastSeq) {
            return false;
        }
        this.lastSeq = seq;
        this.clip = clip;
        this.lengthTicks = lengthTicks;
        this.startTime = now;
        return true;
    }

    /** Stops immediately, with no fade. Used by {@code /wardengirl action stop}. */
    public void stop() {
        this.startTime = Double.NaN;
        this.clip = null;
    }

    public boolean isPlaying() {
        return !Double.isNaN(this.startTime);
    }

    public String clip() {
        return this.clip;
    }

    /**
     * Playhead in ticks, or {@code -1} when nothing is playing.
     *
     * <p>Ends the playback as a side effect once the age passes the clip length. Doing it here
     * rather than on a tick handler keeps the "is it over" test on the same clock as the value
     * that is about to be drawn — a separate handler could end it one frame late and leave a
     * single frame of full-weight pose after the fade had already reached zero.
     */
    public double age(double now) {
        if (Double.isNaN(this.startTime)) {
            return -1.0D;
        }
        double age = now - this.startTime;
        if (age < 0.0D || age > this.lengthTicks) {
            stop();
            return -1.0D;
        }
        return age;
    }

    /** Fade envelope at {@code age}, in [0,1]. */
    public double weight(double age) {
        if (age < 0.0D) {
            return 0.0D;
        }
        return envelope(age, this.lengthTicks, this.clip);
    }

    /**
     * Split out so the verifier can compute the expected envelope from the parameters alone,
     * without going through a playback object. A check that calls the same object it is checking
     * proves only that the object is consistent with itself.
     */
    public static double envelope(double age, double lengthTicks) {
        return envelope(age, lengthTicks, null);
    }

    /**
     * Per-clip fade parameters.
     *
     * <p>4.11's hurt flinch needs its own pair, and specifically {@code hurt_fade_in = 0}: an
     * impact that ramps in is not an impact. Selecting by clip name rather than adding a second
     * playback class keeps one C3 slot and one exclusive lock — the priority question is T6's.
     */
    public static double envelope(double age, double lengthTicks, String clip) {
        // 4.12 는 클립 자체가 0 에서 시작해 0 으로 끝나므로 엔벨로프가 할 일이 없다. 페이드를
        // 걸면 저자가 그린 예비동작과 정착을 한 번 더 깎을 뿐이다.
        if (com.wardengirl.anim.AnimRegistry.IDLE_SNIFF.equals(clip)) {
            return age < 0.0D || age > lengthTicks ? 0.0D : 1.0D;
        }
        boolean hurt = com.wardengirl.anim.AnimRegistry.IDLE_HURT.equals(clip);
        double fadeIn = hurt ? AnimParams.HURT_FADE_IN.get() : AnimParams.ATTACK_FADE_IN.get();
        double fadeOut = hurt ? AnimParams.HURT_FADE_OUT.get() : AnimParams.ATTACK_FADE_OUT.get();
        double rising = fadeIn <= 0.0D ? 1.0D : age / fadeIn;
        double falling = fadeOut <= 0.0D ? 1.0D : (lengthTicks - age) / fadeOut;
        return Math.max(0.0D, Math.min(1.0D, Math.min(rising, falling)));
    }
}
