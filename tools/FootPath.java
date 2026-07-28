import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import software.bernie.geckolib.core.animation.EasingType;
import software.bernie.geckolib.core.keyframe.AnimationPoint;
import software.bernie.geckolib.core.keyframe.Keyframe;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline: how far does the sole actually travel over one walk cycle?
 *
 * The distance-driven phase assumes one cycle moves the sole 2 x (2 x 12 x sin A) px. That is
 * exact for a pure triangle/sine sweep between -A and +A. The walk clip is neither: its keyframes
 * are -18 / -9 / 0 / +12 / +18 / 0 / -18 at uneven times, run through catmullrom, which overshoots.
 * Integrating the real curve settles it without the game.
 *
 * Keyframes are built exactly as JsonAxisConvention emits them: Constant(toRadians(+deg)) with
 * per-axis catmullrom easingArgs (p0 = previous frame's start, p3 = next frame's end, wrapped).
 */
public final class FootPath {

    static final double[] TIMES = {0, 3, 6.5, 10, 13, 19.5, 26};      // ticks
    static final double[] VALUES = {-18, -9, 0, 12, 18, 0, -18};      // degrees, Part 4.0 convention

    public static void main(String[] args) {
        double ampScale = args.length > 0 ? Double.parseDouble(args[0]) : 1.0;
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
            IValue p0 = frames.get(Math.floorMod(i - 1, n)).startValue();
            IValue p3 = frames.get(Math.floorMod(i + 1, n)).endValue();
            out.add(new Keyframe<>(k.length(), k.startValue(), k.endValue(), k.easingType(),
                    List.of(p0, p3)));
        }

        int steps = 26000;
        double path = 0, prev = Double.NaN, minDeg = 1e9, maxDeg = -1e9;
        for (int s = 0; s <= steps; s++) {
            double t = 26.0 * s / steps;
            double deg = Math.toDegrees(value(out, t));
            minDeg = Math.min(minDeg, deg);
            maxDeg = Math.max(maxDeg, deg);
            double sole = 12.0 * Math.sin(Math.toRadians(deg)) / 16.0;
            if (!Double.isNaN(prev)) path += Math.abs(sole - prev);
            prev = sole;
        }
        double amp = 18.0 * ampScale;
        double assumed = 2.0 * (2.0 * 12.0 * Math.sin(Math.toRadians(amp)) / 16.0);
        System.out.printf("amp_scale %.2f  (json peak %.2f deg)%n", ampScale, amp);
        System.out.printf("  실제 각도 범위      : %+8.4f .. %+8.4f  (폭 %.4f)%n",
                minDeg, maxDeg, maxDeg - minDeg);
        System.out.printf("  실제 발바닥 경로 길이 : %.6f 블록%n", path);
        System.out.printf("  전제 값 2*(2*12*sinA)/16 : %.6f 블록%n", assumed);
        System.out.printf("  실제 / 전제          : %.4f%n%n", path / assumed);
    }

    static IValue rad(double deg) { return new Constant(Math.toRadians(deg)); }

    /** ClipSampler's transcription, single axis. */
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
