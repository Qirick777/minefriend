import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import software.bernie.geckolib.core.animation.EasingType;
import software.bernie.geckolib.core.keyframe.AnimationPoint;
import software.bernie.geckolib.core.keyframe.Keyframe;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline: how much of the sole's path survives being sampled once per game tick?
 *
 * The distance-driven phase advances ONCE PER TICK — walkDistanceThisTickForAnimation() returns 0
 * on every frame that is not the first of a new tick. So the drawn leg angle is a staircase: it
 * holds still for a whole tick and then jumps. If the cycle is short in ticks, that staircase is a
 * coarse polygon inscribed in the real curve, and an inscribed polygon is always SHORTER than the
 * curve it approximates. The foot then travels less than the geometry says it should — which is
 * exactly the direction of the 0.6979 shortfall, and unlike the curve shape it is not a 2% effect.
 *
 * This prints, for each ticks-per-cycle T, the fraction of the true path length the staircase
 * retains — averaged over the starting phase offset, because a mob does not get to choose where in
 * the cycle its ticks land.
 *
 * Shares FootPath's keyframe construction; run it the same way.
 */
public final class FootStair {

    static final double[] TIMES = {0, 3, 6.5, 10, 13, 19.5, 26};
    static final double[] VALUES = {-18, -9, 0, 12, 18, 0, -18};

    public static void main(String[] args) {
        double ampScale = args.length > 0 ? Double.parseDouble(args[0]) : 1.0;
        List<Keyframe<IValue>> out = build(ampScale);

        // Reference: the continuous path, 26000 steps. Same figure FootPath prints.
        double truePath = 0, prev = Double.NaN;
        for (int s = 0; s <= 26000; s++) {
            double sole = sole(out, 26.0 * s / 26000);
            if (!Double.isNaN(prev)) truePath += Math.abs(sole - prev);
            prev = sole;
        }
        System.out.printf("amp_scale %.2f  연속 경로 %.6f 블록%n%n", ampScale, truePath);
        System.out.printf("%-10s %-14s %-14s %s%n",
                "틱/사이클", "계단 경로", "유지 비율", "(시작 위상 100개 평균, 사이클 40회)");

        double[] tpc = {2, 3, 3.5, 4, 5, 6, 8, 10, 13, 16, 20, 26, 40};
        for (double t : tpc) {
            double stepTicks = 26.0 / t;      // clip ticks advanced per game tick
            double sum = 0;
            int offsets = 100;
            for (int o = 0; o < offsets; o++) {
                double phase = 26.0 * o / offsets;
                double p = Double.NaN, path = 0;
                int samples = (int) Math.round(t * 40);
                for (int s = 0; s <= samples; s++) {
                    double v = sole(out, (phase + s * stepTicks) % 26.0);
                    if (!Double.isNaN(p)) path += Math.abs(v - p);
                    p = v;
                }
                sum += path / 40.0;           // per cycle
            }
            double mean = sum / offsets;
            System.out.printf("%-10.2f %-14.6f %-14.4f%n", t, mean, mean / truePath);
        }
    }

    static List<Keyframe<IValue>> build(double ampScale) {
        int n = TIMES.length - 1;
        List<Keyframe<IValue>> frames = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            frames.add(new Keyframe<>(TIMES[i + 1] - TIMES[i],
                    rad(VALUES[i] * ampScale), rad(VALUES[i + 1] * ampScale),
                    EasingType.CATMULLROM, List.of()));
        }
        List<Keyframe<IValue>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Keyframe<IValue> k = frames.get(i);
            out.add(new Keyframe<>(k.length(), k.startValue(), k.endValue(), k.easingType(),
                    List.of(frames.get(Math.floorMod(i - 1, n)).startValue(),
                            frames.get(Math.floorMod(i + 1, n)).endValue())));
        }
        return out;
    }

    static double sole(List<Keyframe<IValue>> f, double t) {
        return 12.0 * Math.sin(value(f, t)) / 16.0;
    }

    static IValue rad(double deg) { return new Constant(Math.toRadians(deg)); }

    static double value(List<Keyframe<IValue>> frames, double age) {
        double total = 0;
        for (Keyframe<IValue> f : frames) {
            total += f.length();
            if (total > age) {
                return EasingType.lerpWithOverride(new AnimationPoint(f,
                        age - (total - f.length()), f.length(),
                        f.startValue().get(), f.endValue().get()), null);
            }
        }
        Keyframe<IValue> f = frames.get(frames.size() - 1);
        return EasingType.lerpWithOverride(new AnimationPoint(f, age, f.length(),
                f.startValue().get(), f.endValue().get()), null);
    }
}
