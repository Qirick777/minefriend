package com.wardengirl.anim;

/**
 * Catmull–Rom interpolation, computed here because GeckoLib 4.8.4's is broken.
 *
 * <h2>What is wrong with the library's</h2>
 *
 * {@code EasingType$CatmullRomEasing.apply} takes two branches. With fewer than two
 * {@code easingArgs} — which is what {@code "lerp_mode": "catmullrom"} produces, since the json
 * loader only fills {@code easingArgs} from an explicit {@code easingArgs} array — it calls
 *
 * <pre>
 *   Interpolations.lerp(easedT, start, end)      // CatmullRomEasing
 *   Interpolations.lerp(start, end, easedT)      // every other easing's default apply
 * </pre>
 *
 * and {@code Interpolations.lerp(a, b, x) = a + (b - a) * x}, so the arguments are in the wrong
 * order. The helper it eases with is degenerate too: expanding {@code EasingType.catmullRom(n)}
 * cancels every time term and leaves {@code n + 2}. Measured on a −18 → +18 segment: the output
 * runs from −341 to −315 and only reaches +18 on the final sample.
 *
 * <p>The other branch, {@code getPointOnSpline(t, p0, p1, p2, p3)}, is correct — but
 * {@code BakedAnimationsAdapter} hands one {@code easingArgs} list to all three axes of a
 * keyframe, so per-axis control points cannot be expressed and a constant axis would bend.
 *
 * <h2>Uniform-parameter spline, matching Blockbench</h2>
 *
 * The classic uniform form is used, the same one Blockbench previews with, so that what the
 * modeller sees and what the game draws agree:
 *
 * <pre>
 *   p(t) = 0.5 · ( 2p1 + (p2−p0)t + (2p0−5p1+4p2−p3)t² + (3p1−p0−3p2+p3)t³ )
 * </pre>
 *
 * "Uniform" means the four control points are treated as equally spaced regardless of the real
 * keyframe times. That is a deliberate match to Blockbench rather than an oversight; a
 * centripetal or chordal parameterisation would be better behaved on uneven spacing but would
 * disagree with the preview.
 */
public final class CatmullRom {

    private CatmullRom() {
    }

    /**
     * @param t 0..1 across the segment {@code p1 → p2}
     */
    public static double point(double t, double p0, double p1, double p2, double p3) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5D * (2.0D * p1
                + (p2 - p0) * t
                + (2.0D * p0 - 5.0D * p1 + 4.0D * p2 - p3) * t2
                + (3.0D * p1 - p0 - 3.0D * p2 + p3) * t3);
    }

    /**
     * Interpolates a whole keyframe track at {@code time}.
     *
     * <h2>Loop seam</h2>
     *
     * A spline needs a point on each side of the segment. At the ends of a looping cycle those
     * neighbours are on the <em>other</em> end of the track, so the index wraps. Without that the
     * curve would flatten into the loop point from both sides and the walk would hitch once per
     * step — visible exactly where a walk cycle is least forgiving.
     *
     * <p>The wrap skips the duplicated end knot. A 26-tick cycle carries a keyframe at both 0 and
     * 26 holding the same value; treating them as two distinct neighbours would give the spline a
     * zero-length segment to reason about and flatten the tangent at the seam anyway.
     *
     * @param times  keyframe times, ascending; first and last are the loop endpoints
     * @param values keyframe values, same length as {@code times}
     * @param time   time to sample, in the same unit as {@code times}
     * @param loop   whether to take neighbours from the opposite end
     */
    public static double sample(double[] times, double[] values, double time, boolean loop) {
        int n = times.length;
        if (n == 0) {
            return 0.0D;
        }
        if (n == 1) {
            return values[0];
        }
        double first = times[0];
        double last = times[n - 1];
        if (loop && last > first) {
            double span = last - first;
            time = first + ((time - first) % span + span) % span;
        } else if (time <= first) {
            return values[0];
        } else if (time >= last) {
            return values[n - 1];
        }

        int i = 0;
        while (i < n - 2 && time > times[i + 1]) {
            i++;
        }
        double segStart = times[i];
        double segEnd = times[i + 1];
        double t = segEnd > segStart ? (time - segStart) / (segEnd - segStart) : 0.0D;

        double p0 = values[neighbour(i - 1, n, loop)];
        double p1 = values[i];
        double p2 = values[i + 1];
        double p3 = values[neighbour(i + 2, n, loop)];
        return point(t, p0, p1, p2, p3);
    }

    /**
     * Index of a neighbour, wrapped for a looping track and clamped otherwise.
     *
     * <p>The wrap is over {@code n - 1} entries, not {@code n}: index {@code n-1} duplicates index
     * {@code 0} at the seam, so stepping off the end lands on {@code 1} rather than on the
     * duplicate.
     */
    private static int neighbour(int index, int n, boolean loop) {
        if (!loop) {
            return Math.max(0, Math.min(n - 1, index));
        }
        int period = n - 1;
        int wrapped = ((index % period) + period) % period;
        return wrapped;
    }
}
