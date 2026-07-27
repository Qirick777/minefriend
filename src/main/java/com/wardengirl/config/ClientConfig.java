package com.wardengirl.config;

import com.wardengirl.WardenGirlMod;
import com.wardengirl.anim.AnimParams;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code config/wardengirl-client.toml}. Design doc Part 3.6.
 *
 * <p>The spec is generated from {@link AnimParams} rather than written out by hand, so a parameter
 * cannot exist in one place and be missing from the other. Adding a parameter to AnimParams is all
 * it takes to get a TOML entry, a command key, and a preset level.
 *
 * <p>Values are clamped to ×0.01 … ×10 of the default (or a symmetric band around zero for
 * parameters whose default is 0), which is wide enough to contain the ×4 MAX preset with room to
 * explore past it, and narrow enough that a typo is rejected rather than silently producing a
 * model folded inside out.
 */
public final class ClientConfig {

    private ClientConfig() {
    }

    public static final ForgeConfigSpec SPEC;
    private static final Map<String, ForgeConfigSpec.DoubleValue> VALUES = new LinkedHashMap<>();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment(
                "WardenGirl 애니메이션 파라미터 (1차 튜닝용).",
                "",
                "여기 있는 값은 '크기'다. 동작의 '모양'(수식)은",
                "assets/wardengirl/animations/warden_girl.animation.json 에 있고 F3+T 로 즉시 반영된다.",
                "이 파일을 고친 뒤에는 /wardengirl param reload 로 적용한다.",
                "",
                "즉시 바꿔보려면 /wardengirl param set <key> <value>,",
                "크기 감을 잡으려면 /wardengirl param preset <min|low|default|high|max>.");

        String section = null;
        for (AnimParams.Param p : AnimParams.all().values()) {
            if (!p.task.equals(section)) {
                if (section != null) {
                    builder.pop();
                }
                section = p.task;
                builder.push(section.toLowerCase(java.util.Locale.ROOT));
            }
            double def = p.defaultValue;
            double lo = def == 0 ? -10.0D : Math.min(def * 0.01D, def * 10.0D);
            double hi = def == 0 ? 10.0D : Math.max(def * 0.01D, def * 10.0D);
            VALUES.put(p.key, builder
                    .comment(p.description + "  [" + p.unit + ", 기본 " + def + ", " + p.task + "]")
                    .defineInRange(p.key, def, lo, hi));
        }
        if (section != null) {
            builder.pop();
        }
        SPEC = builder.build();
    }

    /**
     * Copies TOML values into {@link AnimParams}.
     *
     * @return key -> {before, after} for every value that actually changed
     */
    public static Map<String, double[]> load() {
        Map<String, double[]> changed = new LinkedHashMap<>();
        if (!SPEC.isLoaded()) {
            return changed;
        }
        for (Map.Entry<String, ForgeConfigSpec.DoubleValue> e : VALUES.entrySet()) {
            AnimParams.Param p = AnimParams.get(e.getKey());
            if (p == null) {
                continue;
            }
            double before = p.get();
            double after = e.getValue().get();
            if (before != after) {
                p.set(after);
                changed.put(p.key, new double[]{before, after});
            }
        }
        if (!changed.isEmpty()) {
            WardenGirlMod.LOGGER.info("[wardengirl] config -> AnimParams, {} 개 값 변경", changed.size());
        }
        return changed;
    }
}
