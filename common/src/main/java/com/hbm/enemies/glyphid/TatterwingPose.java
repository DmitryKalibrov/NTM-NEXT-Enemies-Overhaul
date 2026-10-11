package com.hbm.enemies.glyphid;

import static com.hbm.enemies.glyphid.GlybnikPose.rotAbout;
import static com.hbm.enemies.glyphid.NeedlerPose.bump;
import static com.hbm.enemies.glyphid.NeedlerPose.smooth;

import java.util.Arrays;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * The Tatterwing's animation maths, ported 1:1 from tools/butterfly_anim.py. Blender space (x right, -y forward,
 * z up, blocks); one matrix per bone of {@link TatterwingRig#BONES}. Wings: flap (degrees up), sweep (back), fold
 * (fingers bunched back) and curl (tips bent down); legs by IK onto body-frame targets, or the ground when landed.
 */
public final class TatterwingPose {

    public enum Clip {
        HOVER(0), GLIDE(0), DIVE(0), STING(26), CLIMB(0), LAND(30), REST(0), TAKEOFF(26), DEATH(40);

        /** Ticks for a one-shot clip, 0 for clips that loop or hold. */
        public final int length;

        Clip(int length) {
            this.length = length;
        }
    }

    static final Vector3d BODY_PIVOT = new Vector3d(0, -0.3, 1.4), ABD_A = new Vector3d(0, 0.0, 1.45),
            ABD_B = new Vector3d(0, 0.72, 1.07), NECK = new Vector3d(0, -0.9, 1.22);
    static final double FOLD_FORE = 94, FOLD_HIND = 102;
    public static final int STING_HIT = 2, STING_PULL = 14;
    static final int BODY = bone("body"), ABD_A_B = bone("abdA"), ABD_B_B = bone("abdB"), HEAD = bone("head");
    /** Per wing (rig order): arm bone, finger a / b bones. */
    static final int[] ARM = new int[4];
    static final int[][] FA = new int[4][], FB = new int[4][];
    /** Legs [i][side]: rest joints (hip, knee, ankle, foot) and bones (femur, tibia, tarsus). */
    static final Vector3d[][][] LEG = new Vector3d[3][2][];
    static final int[][][] LEG_BONE = new int[3][2][];

    static {
        for (int w = 0; w < 4; w++) {
            TatterwingRig.Wing g = TatterwingRig.WINGS[w];
            ARM[w] = bone(g.name() + "Arm");
            FA[w] = new int[g.tips().length];
            FB[w] = new int[g.tips().length];
            for (int k = 0; k < g.tips().length; k++) {
                FA[w][k] = bone(g.name() + "F" + k + "a");
                FB[w][k] = bone(g.name() + "F" + k + "b");
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int side = 0; side < 2; side++) {
                int s = side == 0 ? -1 : 1;
                Vector3d[] j = new Vector3d[4];
                for (int n = 0; n < 4; n++) {
                    double[] p = TatterwingRig.LEGS[i][n];
                    j[n] = new Vector3d(s * p[0], p[1], p[2]);
                }
                LEG[i][side] = j;
                String n = "Leg" + (side == 0 ? "L" : "R") + i;
                LEG_BONE[i][side] = new int[]{bone(n + "F"), bone(n + "T"), bone(n + "S")};
            }
        }
    }

    static int bone(String name) {
        int i = Arrays.asList(TatterwingRig.BONES).indexOf(name);
        if (i < 0) throw new IllegalStateException("no bone " + name);
        return i;
    }

    static double mix(double a, double b, double w) {
        return a + (b - a) * w;
    }

    private static Vector3d v(double[] p) {
        return new Vector3d(p[0], p[1], p[2]);
    }

    /** Fast downstroke (p 0..0.4), slow upstroke with the fingers half folded and the tips trailing: {flap, sweep, fold, curl}. */
    static double[] flapCycle(double p, double up, double down) {
        double flap = p < 0.4 ? mix(up, down, smooth(0, 0.4, p)) : mix(down, up, smooth(0.4, 1.0, p));
        return new double[]{flap, 8 * Math.sin(2 * Math.PI * p), 0.3 * bump(0.4, 1.0, p), 25 * bump(0.4, 1.0, p)};
    }

    private static double frac(double x) {
        return ((x % 1) + 1) % 1;
    }

    private static void wingPose(Matrix4d[] m, int w, double[] p) {
        TatterwingRig.Wing g = TatterwingRig.WINGS[w];
        int s = g.side();
        Vector3d sh = v(g.shoulder()), kn = v(g.knee());
        double flap = p[0], sweep = p[1], fold = p[2], curl = p[3];
        Matrix4d r = rotAbout(sh.x, sh.y, sh.z, 'Z', s * sweep).mul(rotAbout(sh.x, sh.y, sh.z, 'Y', -s * flap));
        m[ARM[w]] = r;
        double to = g.name().contains("Fore") ? FOLD_FORE : FOLD_HIND;
        int n = g.tips().length;
        for (int k = 0; k < n; k++) {
            Vector3d mid = v(g.mids()[k]), tip = v(g.tips()[k]);
            double ang = Math.toDegrees(Math.atan2(tip.y - kn.y, s * (tip.x - kn.x)));
            Matrix4d fa = new Matrix4d(r).mul(rotAbout(kn.x, kn.y, kn.z, 'Z', s * (to - ang) * fold * (0.8 + 0.2 * k / Math.max(1, n - 1))));
            Vector3d axis = new Vector3d(0, 0, 1).cross(new Vector3d(tip).sub(mid)).normalize();
            m[FA[w][k]] = fa;
            m[FB[w][k]] = new Matrix4d(fa).translate(mid).rotate(Math.toRadians(curl * (0.7 + 0.3 * k / 3.0)), axis)
                    .translate(-mid.x, -mid.y, -mid.z);
        }
    }

    /**
     * @param t    ticks since the clip started (loops just keep counting)
     * @param bank -1..1 which way the glide turns (+ = right wing down)
     */
    public static Matrix4d[] compute(Clip clip, double t, double bank) {
        double pitch = 0, roll = 0, yaw = 0, dz = 0, cA = 0, cB = 0, nod = 0, drop = 0, stand = 0;
        double[][] wings = new double[4][];
        Vector3d[][] feet = new Vector3d[3][2];
        boolean grounded = false;
        double[] fore = null, hind = null;
        // dangle(sway, tuck)
        double sway = 0, tuck = 0;
        switch (clip) {
            case HOVER -> {
                double p = t / 14;
                fore = flapCycle(frac(p), 40, -35);
                hind = flapCycle(frac(p - 0.15), 30, -30);
                dz = 0.08 * Math.sin(2 * Math.PI * (p + 0.25));
                pitch = -12 + 3 * Math.sin(2 * Math.PI * p);
                cA = -8 + 4 * Math.sin(2 * Math.PI * p * 0.5);
                cB = -10 + 5 * Math.sin(2 * Math.PI * p * 0.5 + 1);
                sway = t * 0.3;
            }
            case GLIDE -> {
                double f = 6 + 6 * Math.sin(2 * Math.PI * t / 20);
                fore = new double[]{f, -4, 0, 6};
                hind = new double[]{f * 0.8, 2, 0, 8};
                roll = bank * (22 + 2 * Math.sin(t * 0.2));
                yaw = 3 * Math.sin(2 * Math.PI * t / 40);
                pitch = -4;
                cA = -6;
                cB = -12 + 3 * Math.sin(t * 0.25);
                sway = t * 0.15;
                tuck = 0.4;
            }
            case DIVE -> {
                double k = smooth(0, 8, t), trem = 1.5 * Math.sin(t * 2.3);
                fore = new double[]{mix(20, 28, k) + trem, mix(0, 34, k), mix(0, 0.42, k), mix(0, 12, k)};
                hind = new double[]{mix(15, 22, k) - trem, mix(0, 40, k), mix(0, 0.5, k), mix(0, 15, k)};
                pitch = mix(-12, 62, k);
                cA = mix(-8, -32, k);
                cB = mix(-10, -48, k);
                nod = mix(0, -20, k);
                sway = t * 0.3;
                tuck = k;
            }
            case STING -> {
                double ram = smooth(0, 3, t) * (1 - smooth(12, 17, t)), out = smooth(12, 17, t);
                double shake = 4 * Math.sin(t * 3.1) * smooth(3, 4, t) * (1 - smooth(11, 13, t));
                double[] beat = t > 16 ? flapCycle(frac(Math.max(0, t - 16) / 9), 55, -45) : new double[]{60 * out, 0, 0, 0};
                double[] diving = {28, 34, 0.42, 12};
                fore = new double[4];
                hind = new double[4];
                for (int i = 0; i < 4; i++) {
                    fore[i] = mix(diving[i], beat[i], out);
                    hind[i] = fore[i] * 0.85;
                }
                pitch = mix(mix(62, 40, smooth(0, 3, t)), -20, out) + shake;
                cA = mix(-32, -38, ram) * (1 - out) + -8 * out;
                cB = mix(-48, -72, ram) * (1 - out) + -12 * out;
                nod = -20 * (1 - out) + 8 * ram;
                dz = -0.1 * ram;
                sway = t * 0.5;
                tuck = 0.3 * (1 - out);
            }
            case CLIMB -> {
                double p = t / 10;
                fore = flapCycle(frac(p), 55, -48);
                hind = flapCycle(frac(p - 0.15), 45, -40);
                pitch = -28 + 4 * Math.sin(2 * Math.PI * p);
                dz = 0.1 * Math.sin(2 * Math.PI * (p + 0.25));
                cA = -4;
                cB = -6;
                sway = t * 0.4;
                tuck = 0.5;
            }
            case LAND, REST, TAKEOFF -> {
                double k;
                double[] beat;
                if (clip == Clip.LAND) {
                    k = smooth(8, 24, t);
                    stand = smooth(0, 10, t);
                    beat = t < 10 ? flapCycle(frac(t / 12), 30, -20) : new double[]{30, 0, 0, 0};
                } else if (clip == Clip.REST) {
                    k = 1;
                    stand = 1;
                    beat = new double[]{30, 0, 0, 0};
                } else {
                    k = 1 - smooth(0, 10, t);
                    stand = 1 - smooth(12, 20, t);
                    beat = t > 9 ? flapCycle(frac(Math.max(0, t - 9) / 9), 55, -45) : new double[]{30, 0, 0, 0};
                }
                double[] folded = {36, 14, 0.9, -12};
                double breath = clip == Clip.REST ? Math.sin(t * 0.3) : 0;
                double[] wf = new double[4];
                for (int i = 0; i < 4; i++) wf[i] = mix(beat[i], folded[i], k);
                fore = new double[]{wf[0] + breath, wf[1], wf[2], wf[3]};
                hind = new double[]{wf[0] - 6 + breath, wf[1] + 8, wf[2], wf[3]};
                pitch = mix(-12, 2, stand) + (clip == Clip.TAKEOFF ? -15 * bump(10, 26, t) : 0);
                cA = mix(-8, -4, k) + 1.5 * Math.sin(t * 0.21);
                cB = mix(-10, -6, k);
                sway = t * 0.3;
                grounded = true;
            }
            case DEATH -> {
                double fall = smooth(4, 18, t), crumple = smooth(0, 14, t);
                double twitch = Math.exp(-Math.max(0, t - 18) / 10) * Math.sin(t * 1.9);
                double[] ri = {0.0, 0.15, 0.3, 0.1};      // each wing crumples its own way (ForeL, ForeR, HindL, HindR)
                for (int w = 0; w < 4; w++) {
                    double low = TatterwingRig.WINGS[w].name().contains("Hind") ? -55 : 5;
                    wings[w] = new double[]{mix(30, low + 20 * ri[w], fall) + 5 * twitch, mix(0, 20 + 25 * ri[w], crumple),
                            mix(0, 0.7 - ri[w], crumple), mix(0, 30, crumple)};
                }
                roll = 65 * smooth(10, 26, t) + 3 * twitch;
                pitch = mix(-12, 10, fall);
                cA = -10 - 25 * crumple;
                cB = -15 - 35 * crumple;
                nod = -15 * fall;
                drop = fall;
                sway = t * 0.5;
                tuck = crumple;
            }
        }
        if (fore != null) {
            for (int w = 0; w < 4; w++) wings[w] = TatterwingRig.WINGS[w].name().contains("Fore") ? fore : hind;
        }
        for (int i = 0; i < 3; i++) {
            for (int side = 0; side < 2; side++) {
                Vector3d f = LEG[i][side][3];
                feet[i][side] = new Vector3d(f).lerp(new Vector3d(f.x * 0.6, f.y + 0.25, f.z + 0.3), tuck)
                        .add(0, 0.04 * Math.sin(sway + i), 0.03 * Math.sin(sway * 1.3 + i));
            }
        }
        if (clip == Clip.STING) {
            double ram = smooth(0, 3, t) * (1 - smooth(12, 17, t));
            for (int side = 0; side < 2; side++) feet[0][side].lerp(new Vector3d((side == 0 ? -1 : 1) * 0.15, -1.25, 0.55), ram);
        }

        Vector3d bp = BODY_PIVOT;
        Matrix4d body = new Matrix4d().translation(0, 0, dz).mul(rotAbout(bp.x, bp.y, bp.z, 'Z', yaw))
                .mul(rotAbout(bp.x, bp.y, bp.z, 'X', pitch)).mul(rotAbout(bp.x, bp.y, bp.z, 'Y', roll));
        if (drop > 0) {
            double low = Double.MAX_VALUE;
            Vector3d q = new Vector3d();
            for (double[] b : TatterwingRig.BELLY) low = Math.min(low, body.transformPosition(q.set(b[0], b[1], b[2])).z);
            body.mulLocal(new Matrix4d().translation(0, 0, -low * drop));
        }
        Matrix4d[] m = new Matrix4d[TatterwingRig.BONES.length];
        m[BODY] = new Matrix4d();
        m[ABD_A_B] = rotAbout(ABD_A.x, ABD_A.y, ABD_A.z, 'X', cA);
        m[ABD_B_B] = new Matrix4d(m[ABD_A_B]).mul(rotAbout(ABD_B.x, ABD_B.y, ABD_B.z, 'X', cB));
        m[HEAD] = rotAbout(NECK.x, NECK.y, NECK.z, 'X', nod);
        for (int w = 0; w < 4; w++) wingPose(m, w, wings[w]);
        Matrix4d inv = new Matrix4d(body).invert();
        for (int i = 0; i < 3; i++) {
            for (int side = 0; side < 2; side++) {
                Vector3d tgt = feet[i][side];
                if (grounded) {
                    Vector3d f = LEG[i][side][3];
                    tgt = new Vector3d(tgt).lerp(inv.transformPosition(new Vector3d(f.x * 1.8, f.y, 0)), stand);
                }
                Matrix4d[] b = NeedlerPose.ik(LEG[i][side], tgt);
                for (int n = 0; n < 3; n++) m[LEG_BONE[i][side][n]] = b[n];
            }
        }
        for (int i = 0; i < m.length; i++) m[i] = new Matrix4d(body).mul(m[i]);
        return m;
    }

    private TatterwingPose() {}
}
