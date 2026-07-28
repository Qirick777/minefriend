import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import com.google.gson.*;
import software.bernie.geckolib.core.animation.EasingType;
import software.bernie.geckolib.core.keyframe.AnimationPoint;
import software.bernie.geckolib.core.keyframe.Keyframe;
import java.nio.file.*;
import java.util.*;

/**
 * Offline: how many times does the head actually change direction during idle_sniff?
 *
 * Reads the real json, rebuilds keyframes exactly as JsonAxisConvention emits them
 * (xRot x-1, yRot x-1, zRot x+1, per-axis catmullrom easingArgs), samples with the real
 * EasingType.CATMULLROM, and counts direction reversals.
 *
 * "까딱" is a reversal the eye can see, so a reversal only counts when the swing on either
 * side of it exceeds VISIBLE degrees. A 0.05 degree wiggle is a reversal in the data and
 * nothing at all on screen.
 */
public final class HeadCount {

    static final double VISIBLE = 1.0;   // 도. 이보다 작은 스윙은 화면에서 안 보인다

    public static void main(String[] args) throws Exception {
        String path = "src/main/resources/assets/wardengirl/animations/warden_girl.animation.json";
        JsonObject root = JsonParser.parseString(Files.readString(Paths.get(path))).getAsJsonObject();
        JsonObject clip = root.getAsJsonObject("animations").getAsJsonObject("idle_sniff");
        double len = clip.get("animation_length").getAsDouble() * 20.0;
        JsonObject bones = clip.getAsJsonObject("bones");

        System.out.printf("idle_sniff  길이 %.1f틱%n%n", len);
        Map<String, double[][]> series = new LinkedHashMap<>();
        int steps = 3800;
        for (String bone : new String[]{"body", "head"}) {
            if (!bones.has(bone) || !bones.getAsJsonObject(bone).has("rotation")) continue;
            JsonObject rot = bones.getAsJsonObject(bone).getAsJsonObject("rotation");
            for (int axis = 0; axis < 3; axis++) {
                List<Keyframe<IValue>> kf = build(rot, axis);
                if (kf.isEmpty()) continue;
                double[] t = new double[steps + 1], v = new double[steps + 1];
                for (int s = 0; s <= steps; s++) {
                    t[s] = len * s / steps;
                    v[s] = Math.toDegrees(value(kf, t[s]));
                }
                String key = bone + "." + "xyz".charAt(axis) + "Rot";
                series.put(key, new double[][]{t, v});
                report(key, t, v);
            }
        }
        // 머리 방향 벡터: R_body * R_head * (0,0,1).  본 규약대로 Rz*Ry*Rx.
        double[] bt = series.containsKey("body.xRot") ? series.get("body.xRot")[0] : null;
        double[] pitch = new double[steps + 1], yaw = new double[steps + 1];
        for (int s = 0; s <= steps; s++) {
            double[] d = {0, 0, 1};
            d = rot(d, get(series, "head.xRot", s), get(series, "head.yRot", s), get(series, "head.zRot", s));
            d = rot(d, get(series, "body.xRot", s), get(series, "body.yRot", s), get(series, "body.zRot", s));
            pitch[s] = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d[1]))));
            yaw[s] = Math.toDegrees(Math.atan2(d[0], d[2]));
        }
        System.out.println();
        report("합성 머리방향 pitch(위아래)", bt, pitch);
        report("합성 머리방향 yaw(좌우)", bt, yaw);
    }

    static double get(Map<String, double[][]> m, String k, int i) {
        double[][] a = m.get(k);
        return a == null ? 0 : a[1][i];
    }

    /** Rz*Ry*Rx, BoneTrace 의 규약과 같다. */
    static double[] rot(double[] v, double xd, double yd, double zd) {
        double c, s;
        c = Math.cos(Math.toRadians(xd)); s = Math.sin(Math.toRadians(xd));
        v = new double[]{v[0], v[1] * c + v[2] * s, -v[1] * s + v[2] * c};
        c = Math.cos(Math.toRadians(yd)); s = Math.sin(Math.toRadians(yd));
        v = new double[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
        c = Math.cos(Math.toRadians(zd)); s = Math.sin(Math.toRadians(zd));
        return new double[]{v[0] * c + v[1] * s, -v[0] * s + v[1] * c, v[2]};
    }

    static void report(String name, double[] t, double[] v) {
        List<Integer> ext = new ArrayList<>();
        for (int i = 1; i < v.length - 1; i++) {
            if ((v[i] - v[i - 1]) == 0) continue;
            boolean up = v[i] > v[i - 1], nextUp = v[i + 1] > v[i];
            if (up != nextUp) ext.add(i);
        }
        // 보이는 반전만: 양옆 스윙이 VISIBLE 이상
        List<Integer> vis = new ArrayList<>();
        for (int idx : ext) {
            double a = swing(v, idx, -1), b = swing(v, idx, +1);
            if (Math.min(a, b) >= VISIBLE) vis.add(idx);
        }
        StringBuilder sb = new StringBuilder();
        for (int idx : vis) sb.append(String.format(Locale.ROOT, " %.1f틱=%.2f°", t[idx], v[idx]));
        System.out.printf("%-26s 반전 %2d회 (보이는 것 %2d회, %.1f° 이상)%s%n",
                name, ext.size(), vis.size(), VISIBLE, sb);
    }

    static double swing(double[] v, int idx, int dir) {
        double best = 0;
        for (int i = idx; i >= 0 && i < v.length; i += dir) {
            best = Math.max(best, Math.abs(v[i] - v[idx]));
            if (i > 0 && i < v.length - 1) {
                boolean up = v[i] > v[i - 1], nextUp = v[i + 1] > v[i];
                if (i != idx && up != nextUp) break;
            }
        }
        return best;
    }

    static List<Keyframe<IValue>> build(JsonObject rot, int axis) {
        List<Double> times = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : rot.entrySet()) {
            times.add(Double.parseDouble(e.getKey()) * 20.0);
            JsonArray a = e.getValue().getAsJsonObject().getAsJsonArray("vector");
            double raw = a.get(axis).getAsDouble();
            vals.add(axis == 2 ? raw : -raw);      // JsonAxisConvention: x,y 는 -1
        }
        boolean allZero = true;
        for (double d : vals) if (d != 0) { allZero = false; break; }
        if (allZero) return List.of();
        int n = times.size() - 1;
        List<Keyframe<IValue>> raw = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            raw.add(new Keyframe<>(times.get(i + 1) - times.get(i),
                    new Constant(Math.toRadians(vals.get(i))),
                    new Constant(Math.toRadians(vals.get(i + 1))),
                    EasingType.CATMULLROM, List.of()));
        }
        List<Keyframe<IValue>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Keyframe<IValue> k = raw.get(i);
            out.add(new Keyframe<>(k.length(), k.startValue(), k.endValue(), k.easingType(),
                    List.of(raw.get(Math.floorMod(i - 1, n)).startValue(),
                            raw.get(Math.floorMod(i + 1, n)).endValue())));
        }
        return out;
    }

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
