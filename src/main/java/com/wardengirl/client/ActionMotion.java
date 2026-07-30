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
     * 재생 시간축 배율. 1.0 이 저자가 그린 속도다.
     *
     * <p><b>1.0 이 아닌 값을 넘기는 곳은 {@code attack} 클립 한 곳뿐이며</b> 그 값은
     * {@code AnimRegistry.ATTACK_SPEED} 코드 상수다 — 런타임에 바꿀 수단이 없다. 4인자
     * {@link #syncTo} 를 쓰는 소닉·피격·킁킁·임시 클립은 전부 1.0 이므로 영향이 없다.
     */
    private double timeScale = 1.0D;

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
        return syncTo(seq, clip, lengthTicks, now, 1.0D);
    }

    /**
     * 시간축 배율을 지정하는 판. {@code lengthTicks} 는 <b>저자가 그린 클립 길이 그대로</b>
     * 넘긴다 — 배율은 {@link #age} 가 시간을 읽는 속도만 바꾸고 클립의 길이 정의는 바꾸지 않는다.
     */
    public boolean syncTo(int seq, String clip, double lengthTicks, double now, double timeScale) {
        if (seq == this.lastSeq) {
            return false;
        }
        this.lastSeq = seq;
        this.clip = clip;
        this.lengthTicks = lengthTicks;
        this.startTime = now;
        this.timeScale = Double.isFinite(timeScale) && timeScale > 0.0D ? timeScale : 1.0D;
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
     *
     * <p><b>돌려주는 값은 클립 시간이다</b> — 흐른 실제 틱에 {@link #timeScale} 을 곱한다. 이
     * 한 줄이 배속의 전부이므로 샘플러와 페이드 엔벨로프가 <b>같은</b> 시간축을 쓴다. 그래서
     * 배속을 올리면 클립도 페이드도 같은 비율로 빨라지고, 클립 시간이 저자가 그린 길이에 닿는
     * 순간 그대로 끝난다 — 비루프 클립이 처음으로 되감기지 않고, 종료 후 자세 복귀는 기존
     * {@code attack_fade_out} 엔벨로프가 그대로 처리한다({@code attack} 클립은 18틱 키가 전부 0
     * 이므로 복귀 자세도 원래와 같다).
     */
    public double age(double now) {
        if (Double.isNaN(this.startTime)) {
            return -1.0D;
        }
        double age = (now - this.startTime) * this.timeScale;
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
