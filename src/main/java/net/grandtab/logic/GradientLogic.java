package net.grandtab.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Colour maths for rank gradients (pure; no Minecraft classes, tested by TabLogicSelfTest).
 * Colours are 0xRRGGBB ints, which Minecraft 1.16 text accepts directly, so every character can
 * have its own colour on an unmodified client.
 *
 * <p>Animations are frames. The server advances a frame counter on a fixed interval and only
 * resends text whose frame actually changed. Chat lines can't change after they are sent, so
 * chat shows the frame current at the moment of sending.
 */
public final class GradientLogic {
    private GradientLogic() {
    }

    public enum Animation { STATIC, WAVE, PULSE, RAINBOW, SHINE }

    public static final int MIN_SPEED = 1;
    public static final int MAX_SPEED = 10;

    public static Animation parseAnimation(String text) {
        try {
            return Animation.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** Parses #RRGGBB / RRGGBB / a Minecraft colour name. Throws IllegalArgumentException. */
    public static int parseHex(String text) {
        String s = text == null ? "" : text.trim();
        if (s.matches("[0-9a-fA-F]{6}")) {
            s = "#" + s;
        }
        return TabLogic.parseColor(s);
    }

    /** Linear interpolation across the colour stops, one colour per character. */
    public static int[] gradient(List<Integer> stops, int length) {
        int[] out = new int[Math.max(0, length)];
        if (length == 0 || stops.isEmpty()) {
            return out;
        }
        if (stops.size() == 1 || length == 1) {
            Arrays.fill(out, stops.get(0));
            return out;
        }
        for (int i = 0; i < length; i++) {
            double t = (double) i / (length - 1) * (stops.size() - 1);
            int seg = Math.min(stops.size() - 2, (int) Math.floor(t));
            out[i] = lerp(stops.get(seg), stops.get(seg + 1), t - seg);
        }
        return out;
    }

    public static int lerp(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Factor below 1 darkens towards black, above 1 lightens towards white. */
    public static int brightness(int rgb, double factor) {
        if (factor >= 1.0) {
            return lerp(rgb, 0xFFFFFF, Math.min(1.0, factor - 1.0));
        }
        return lerp(0x000000, rgb, Math.max(0.0, factor));
    }

    public static int hsv(double hue, double saturation, double value) {
        double h = (hue % 1.0 + 1.0) % 1.0 * 6.0;
        int sector = (int) Math.floor(h);
        double f = h - sector;
        double p = value * (1 - saturation);
        double q = value * (1 - saturation * f);
        double t = value * (1 - saturation * (1 - f));
        double r;
        double g;
        double b;
        switch (sector) {
            case 0: r = value; g = t; b = p; break;
            case 1: r = q; g = value; b = p; break;
            case 2: r = p; g = value; b = t; break;
            case 3: r = p; g = q; b = value; break;
            case 4: r = t; g = p; b = value; break;
            default: r = value; g = p; b = q; break;
        }
        return ((int) Math.round(r * 255) << 16) | ((int) Math.round(g * 255) << 8) | (int) Math.round(b * 255);
    }

    /** Animation phase (0..1) at a frame number: speed 1 is slow, 10 is fast. */
    public static double phase(long frame, int speed) {
        int s = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        double p = frame * s * 0.0125;
        return p - Math.floor(p);
    }

    /** Number of distinct frames before an animation repeats (so frames can be cached). */
    public static boolean animated(Animation animation) {
        return animation != null && animation != Animation.STATIC;
    }

    /** Per-character colours of a text of {@code length} characters at {@code phase}. */
    public static int[] colors(List<Integer> stops, Animation animation, int length, double phase) {
        List<Integer> base = stops.isEmpty() ? Arrays.asList(0xFFFFFF, 0xFFFFFF) : stops;
        int[] out;
        switch (animation == null ? Animation.STATIC : animation) {
            case RAINBOW:
                out = new int[length];
                for (int i = 0; i < length; i++) {
                    out[i] = hsv(phase + (double) i / Math.max(8, length), 0.75, 1.0);
                }
                return out;
            case WAVE: {
                // The gradient (with its first colour repeated at the end) slides along the text.
                List<Integer> cycle = new ArrayList<>(base);
                cycle.add(cycle.get(0));
                int resolution = 96;
                int[] ring = gradient(cycle, resolution + 1);
                out = new int[length];
                for (int i = 0; i < length; i++) {
                    double pos = ((double) i / Math.max(6, length) - phase) % 1.0;
                    if (pos < 0) {
                        pos += 1.0;
                    }
                    out[i] = ring[(int) Math.round(pos * resolution)];
                }
                return out;
            }
            case PULSE: {
                double factor = 0.65 + 0.5 * (0.5 + 0.5 * Math.sin(phase * 2 * Math.PI));
                out = gradient(base, length);
                for (int i = 0; i < length; i++) {
                    out[i] = brightness(out[i], factor);
                }
                return out;
            }
            case SHINE: {
                // A white highlight sweeps across the gradient.
                out = gradient(base, length);
                double centre = phase * (length + 6) - 3;
                for (int i = 0; i < length; i++) {
                    double d = Math.abs(i - centre);
                    if (d < 2.5) {
                        out[i] = lerp(out[i], 0xFFFFFF, 0.75 * (1 - d / 2.5));
                    }
                }
                return out;
            }
            default:
                return gradient(base, length);
        }
    }
}
