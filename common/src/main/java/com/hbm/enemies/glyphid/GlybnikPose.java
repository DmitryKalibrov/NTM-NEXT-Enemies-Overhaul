package com.hbm.enemies.glyphid;

import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * The Glybnik's animation maths, ported 1:1 from tools/glybnik_anim.py (which keyframes the Blender
 * preview). Works in Blender space (x right, -y forward, z up, metres = blocks) like the script;
 * {@link #toModel} turns a result into model space (OBJ: y up, +z forward).
 * Legs are placed by IK onto claw targets in the body frame, so moving the body never moves a foot.
 */
public final class GlybnikPose {

    static final double P = 1 / 16.0;
    static final double HIP_Z = 11 * P;
    static final double J_HIP = 10 * P, J_KNEE = 21 * P, J_ANKLE = 31 * P, J_CLAW = 42 * P;
    static final double L1 = 11 * P, L2 = 10 * P;
    static final double L3 = Math.hypot(11 + 8 * Math.cos(Math.toRadians(20)), 8 * Math.sin(Math.toRadians(20))) * P;
    static final double DELTA = Math.atan2(-8 * Math.sin(Math.toRadians(20)), 11 + 8 * Math.cos(Math.toRadians(20)));
    /** Per slot (front to back): hip y (1/16, Blender), yaw, lift, knee, ankle of the standing pose. */
    static final double[][] STANCE = {{-5, -25, 0, 30, -19.7}, {-1, -8, 20, 30, 25.7}, {5, 14, 20, 30, 25.7}, {11, 40, 10, 30, 2.1}};
    static final double[] YAW_MIN = {-45, -13, -45, -45};
    static final double[] HOME_SHIFT = {3, 3, 0, -4};
    static final double[] FOLD = {-15, 110, 110, 60};
    static final double DUTY = 0.75, STRIDE = 12 * P, STEP_H = 6 * P;
    /** Body distance per walk cycle (blocks at scale 1). */
    public static final double CYCLE_DISTANCE = STRIDE / DUTY;
    static final double[] BODY_PIVOT = {0, 2 * P, 20 * P};
    static final double COLLAPSE_ROLL = 30, COLLAPSE_DZ = -2.3 * P;
    static final double[] MANDIBLE_PIVOT = {8 * P, -19 * P, 8.5 * P};
    /** Hip offsets along the body (1/16, Blender y), front to back; model z is the negative. */
    public static final double[] HIP_Y = {STANCE[0][0], STANCE[1][0], STANCE[2][0], STANCE[3][0]};

    private static final Vector3d[][] HOME = new Vector3d[2][4];

    static {
        for (int side = 0; side < 2; side++) {
            int s = side == 0 ? -1 : 1;
            for (int i = 0; i < 4; i++) {
                double[] st = STANCE[i];
                Matrix4d claw = chain(s, st[0] * P, st[1], st[2], st[3], st[4], 20)[3];
                HOME[side][i] = claw.transformPosition(new Vector3d(s * 50 * P, 0, HIP_Z)).add(0, HOME_SHIFT[i] * P, 0);
            }
        }
    }

    /** Standing claw position of a leg in the body frame (Blender space). */
    public static Vector3d home(int side, int i) {
        return new Vector3d(HOME[side][i]);
    }

    /** Leg slot index: side 0 = L (Blender -x), 1 = R; bit in the missing mask = side * 4 + i. */
    public static boolean missing(int mask, int side, int i) {
        return (mask & (1 << (side * 4 + i))) != 0;
    }

    /** Result: matrices in Blender space, per part. */
    public static final class Frame {
        public final Matrix4d body = new Matrix4d();
        public final Matrix4d[] mandible = {new Matrix4d(), new Matrix4d()};
        /** [side][slot][0 upper, 1 shin, 2 blade, 3 claw]; null parts when the leg is gone below the knee. */
        public final Matrix4d[][][] legs = new Matrix4d[2][4][4];
        public final boolean[][] stump = new boolean[2][4];
        /** Per pod: vertical stretch and sideways squash about the pod's base. */
        public double[] podUp, podSide;
    }

    public static Matrix4d rotAbout(double px, double py, double pz, char axis, double deg) {
        Matrix4d m = new Matrix4d().translation(px, py, pz);
        double r = Math.toRadians(deg);
        switch (axis) {
            case 'X' -> m.rotateX(r);
            case 'Y' -> m.rotateY(r);
            default -> m.rotateZ(r);
        }
        return m.translate(-px, -py, -pz);
    }

    static double clamp(double v, double limit) {
        return Math.max(-limit, Math.min(limit, v));
    }

    static double smooth(double a, double b, double t) {
        double x = Math.min(1, Math.max(0, (t - a) / (b - a)));
        return x * x * (3 - 2 * x);
    }

    /** Forward kinematics: upper, shin, blade, claw matrices (the knuckles ride with the next part). */
    static Matrix4d[] chain(int s, double y, double yaw, double lift, double knee, double ankle, double claw) {
        Matrix4d up = new Matrix4d().translation(0, y, 0)
                .mul(rotAbout(s * J_HIP, 0, HIP_Z, 'Z', s * yaw)).mul(rotAbout(s * J_HIP, 0, HIP_Z, 'Y', -s * lift));
        Matrix4d sh = new Matrix4d(up).mul(rotAbout(s * J_KNEE, 0, HIP_Z, 'Y', s * knee));
        Matrix4d bl = new Matrix4d(sh).mul(rotAbout(s * J_ANKLE, 0, HIP_Z, 'Y', s * ankle));
        Matrix4d cl = new Matrix4d(bl).mul(rotAbout(s * J_CLAW, 0, HIP_Z, 'Y', s * claw));
        return new Matrix4d[]{up, sh, bl, cl};
    }

    /**
     * Body-frame claw target -> {yaw, knee, ankle, lift}; the knee joint stays up. The hip keeps the given
     * lift unless the target is out of reach (a step down), then the thigh swings down up to 45 degrees.
     */
    static double[] ik(int s, double y, double lift, Vector3d target) {
        double hx = target.x - s * J_HIP, hy = target.y - y;
        double yaw = Math.toDegrees(Math.atan2(hy, s * hx));
        double u = Math.hypot(hx, hy), w = target.z - HIP_Z;
        double lowest = lift - 45;
        while (lift > lowest) {
            double r = Math.toRadians(lift);
            if (Math.hypot(u - L1 * Math.cos(r), w - L1 * Math.sin(r)) < L2 + L3 - 1e-3) break;
            lift -= 2.5;
        }
        double lr = Math.toRadians(lift), ku = L1 * Math.cos(lr), kw = L1 * Math.sin(lr);
        double du = u - ku, dw = w - kw;
        double d = Math.max(Math.abs(L2 - L3) + 1e-4, Math.min(Math.hypot(du, dw), L2 + L3 - 1e-4));
        double a1 = Math.atan2(dw, du) + Math.acos(Math.max(-1, Math.min(1, (L2 * L2 + d * d - L3 * L3) / (2 * L2 * d))));
        double au = ku + L2 * Math.cos(a1), aw = kw + L2 * Math.sin(a1);
        double a2 = Math.atan2(w - aw, u - au) - DELTA;
        return new double[]{yaw, Math.toDegrees(lr - a1), Math.toDegrees(a1 - a2), lift};
    }

    /** Claw offset in the body frame over one walk cycle: {dy, dz}; forward is -y. */
    static double[] stepOffset(double phase) {
        if (phase < DUTY) return new double[]{-STRIDE / 2 + STRIDE * phase / DUTY, 0};
        double k = (phase - DUTY) / (1 - DUTY);
        return new double[]{STRIDE / 2 - STRIDE * (k * k * (3 - 2 * k)), STEP_H * Math.sin(Math.PI * k)};
    }

    static double offset(int side, int i) {
        return ((3 - i) * 0.25 + (side == 1 ? 0.5 : 0)) % 1.0;
    }

    /**
     * @param state     clip ({@link EntityGlyphidGlybnik.State})
     * @param t         ticks since the clip started (with partial tick)
     * @param walkPhase walk cycle position in cycles (distance / {@link #CYCLE_DISTANCE})
     * @param walkAmp   0..1, how much the walk cycle shows (0 standing)
     * @param mask      torn-off legs
     * @param legLost   ticks since the last leg came off (large if long ago)
     * @param podBaseZ  Blender z of every pod's base, for the pump wave order
     * @param ground    [side][slot] terrain height under each claw relative to the feet (blocks), or null for flat
     */
    public static Frame compute(EntityGlyphidGlybnik.State state, double t, double walkPhase, double walkAmp, int mask,
                                double legLost, double[] podBaseZ, double[][] ground) {
        Frame f = new Frame();
        double fwd = 0, dz = 0, pitch = 0, roll = 0, mand = 0, fold = 0;
        Vector3d[][] targets = new Vector3d[2][4];
        double[][][] fk = new double[2][4][];
        int pods = podBaseZ.length;
        f.podUp = new double[pods];
        f.podSide = new double[pods];
        java.util.Arrays.fill(f.podUp, 1);
        java.util.Arrays.fill(f.podSide, 1);
        boolean limping = mask != 0;
        for (int side = 0; side < 2; side++) for (int i = 0; i < 4; i++) targets[side][i] = new Vector3d(HOME[side][i]);

        switch (state) {
            case WALK -> {
                double stride = limping ? 0.7 : 1.0, lifted0 = 0, lifted1 = 0;
                for (int side = 0; side < 2; side++) {
                    for (int i = 0; i < 4; i++) {
                        if (missing(mask, side, i)) continue;
                        double[] o = stepOffset(((walkPhase + offset(side, i)) % 1.0 + 1.0) % 1.0);
                        targets[side][i].add(0, o[0] * stride * walkAmp, o[1] * walkAmp);
                        if (side == 0) lifted0 += o[1] / STEP_H * walkAmp;
                        else lifted1 += o[1] / STEP_H * walkAmp;
                    }
                }
                if (limping) {   // sags and lists to the maimed side
                    int lostL = Integer.bitCount(mask & 0x0F), lostR = Integer.bitCount(mask & 0xF0);
                    double list = 3 * (lostR - lostL);
                    roll = list + 4 * lifted0 - 2 * lifted1 + 1.5 * Math.sin(2 * Math.PI * walkPhase * 2) * walkAmp;
                    dz = -0.5 * P * Integer.bitCount(mask) - 0.8 * P * (lifted0 + lifted1);
                    pitch = 2 + 1.2 * Math.sin(2 * Math.PI * walkPhase * 2) * walkAmp;
                } else {
                    roll = 2.5 * (lifted1 - lifted0);
                    dz = -0.6 * P * (lifted0 + lifted1);
                    pitch = 0.8 * Math.sin(2 * Math.PI * walkPhase * 2) * walkAmp;
                }
            }
            case BASH -> {
                double w = smooth(0, 12, t) * (1 - smooth(12, 16, t)), k = smooth(12, 16, t) * (1 - smooth(20, 40, t));
                pitch = -10 * w + 12 * k;
                fwd = -2 * P * w + 5 * P * k;
                dz = 1.5 * P * w - 0.5 * P * k;
                mand = 35 * w - 10 * k;
                double step = smooth(11, 16, t) * (1 - smooth(24, 32, t));
                double arc = Math.sin(Math.PI * smooth(11, 16, t)) + Math.sin(Math.PI * smooth(24, 32, t));
                double late = smooth(14, 19, t) * (1 - smooth(27, 35, t));
                double lateArc = Math.sin(Math.PI * smooth(14, 19, t)) + Math.sin(Math.PI * smooth(27, 35, t));
                for (int side = 0; side < 2; side++) {
                    targets[side][0].add(0, 3 * P * w - 5 * P * k, 9 * P * w);   // rear up, slam down ahead
                    targets[side][1].add(0, -3 * P * step, 3 * P * arc);        // steps with the lunge
                    targets[side][3].add(0, -4 * P * late, 3 * P * lateArc);    // hind legs follow
                }
            }
            case SPORES -> {
                dz = 1.2 * P * Math.sin(2 * Math.PI * t / 10) - 1.5 * P * smooth(40, 44, t) * (1 - smooth(56, 60, t));
                pitch = 1.5 * Math.sin(2 * Math.PI * t / 10 + 1);
                dz += pumpPods(f, podBaseZ, t, new double[]{46, 54}, 40, 44, -1, -1);
            }
            case HIDE -> {
                double sink = smooth(0, 14, t) * (1 - smooth(66, 80, t));
                fold = smooth(10, 22, t) * (1 - smooth(58, 70, t));
                dz = -12 * P * sink + 0.5 * P * Math.sin(2 * Math.PI * t / 10) * sink;
                mand = -12 * sink;
                pumpPods(f, podBaseZ, t, new double[]{34, 42, 50}, 56, 60, 18, 26);
            }
            case COLLAPSED -> {
                double tc = t % EntityGlyphidGlybnik.State.COLLAPSED.length;
                double roar = 0;
                for (double r : new double[]{14, 62}) roar += smooth(r, r + 4, tc) * (1 - smooth(r + 10, r + 16, tc));
                // lie on the side that lost more legs
                int lostL = Integer.bitCount(mask & 0x0F), lostR = Integer.bitCount(mask & 0xF0);
                double sideSign = lostR >= lostL ? 1 : -1;
                roll = sideSign * (COLLAPSE_ROLL + 2.5 * roar * Math.sin(t * 2.9) + Math.sin(t * 0.21));
                pitch = -12 * roar + Math.sin(t * 0.17);
                mand = 42 * roar + 6 * Math.pow(Math.sin(t * 0.4), 2);
                dz = COLLAPSE_DZ;
                double weak = 1 - 0.5 * roar;
                for (int side = 0; side < 2; side++) {
                    for (int i = 0; i < 4; i++) {
                        double[] st = STANCE[i];
                        double k = i * 1.3 + (side == 0 ? 0 : 2.1);
                        fk[side][i] = new double[]{st[1] + 14 * weak * Math.sin(t * 0.33 + k), st[2] - 15 + 10 * weak * Math.sin(t * 0.27 + k * 1.7),
                                st[3] + 18 * weak * Math.sin(t * 0.41 + k * 0.6), st[4] + 20 * weak * Math.sin(t * 0.37 + k), 20 + 15 * Math.sin(t * 0.3 + k)};
                    }
                }
                pumpPods(f, podBaseZ, tc, new double[]{36, 44, 86, 94}, -1, -1, -1, -1);
            }
        }
        if (ground != null && state != EntityGlyphidGlybnik.State.COLLAPSED) {
            // feet go down (or up) to the terrain; the body follows half the average and tilts to it
            double front = 0, back = 0, left = 0, right = 0, sum = 0;
            for (int side = 0; side < 2; side++) {
                for (int i = 0; i < 4; i++) {
                    double g = ground[side][i];
                    targets[side][i].z += g;
                    sum += g;
                    if (i < 2) front += g; else back += g;
                    if (side == 0) left += g; else right += g;
                }
            }
            double rows = HOME[0][3].y - HOME[0][0].y, width = HOME[1][1].x - HOME[0][1].x;
            pitch += clamp(Math.toDegrees(Math.atan((back - front) / 4 / rows)), 20);
            roll += clamp(Math.toDegrees(Math.atan((left - right) / 4 / width)), 20);
            dz += sum / 8 * 0.5;
        }
        if (legLost < 50 && state != EntityGlyphidGlybnik.State.COLLAPSED) {   // recoil after losing a leg
            double rec = Math.exp(-legLost / 12);
            roll -= 7 * rec * Math.sin(legLost * 0.45 + 1.0);
            dz -= 1.5 * P * rec;
            mand += 22 * rec;
        }

        f.body.translation(0, -fwd, dz)
                .mul(rotAbout(BODY_PIVOT[0], BODY_PIVOT[1], BODY_PIVOT[2], 'X', pitch))
                .mul(rotAbout(BODY_PIVOT[0], BODY_PIVOT[1], BODY_PIVOT[2], 'Y', roll));
        Matrix4d inv = new Matrix4d(f.body).invert();
        for (int side = 0; side < 2; side++) {
            int s = side == 0 ? -1 : 1;
            f.mandible[side] = new Matrix4d(f.body).mul(rotAbout(s * MANDIBLE_PIVOT[0], MANDIBLE_PIVOT[1], MANDIBLE_PIVOT[2], 'Z', s * mand));
            for (int i = 0; i < 4; i++) {
                double[] st = STANCE[i];
                double y = st[0] * P, lift = st[2], claw = 20, yaw, knee, ankle;
                if (missing(mask, side, i)) {
                    // torn off at the knee: the thigh stump hangs and jerks now and then
                    double jerk = Math.pow(Math.sin(t * 0.9 + i), 8);
                    Matrix4d up = chain(s, y, st[1] + 10 * jerk, lift - 30 + 12 * jerk, 0, 0, 0)[0];
                    f.legs[side][i][0] = new Matrix4d(f.body).mul(up);
                    f.stump[side][i] = true;
                    continue;
                }
                if (fk[side][i] != null) {
                    double[] a = fk[side][i];
                    yaw = Math.max(a[0], YAW_MIN[i]);
                    lift = a[1];
                    knee = a[2];
                    ankle = a[3];
                    claw = a[4];
                } else {
                    double[] a = ik(s, y, lift, inv.transformPosition(new Vector3d(targets[side][i])));
                    yaw = Math.max(a[0], YAW_MIN[i]);
                    knee = a[1];
                    ankle = a[2];
                    lift = a[3];
                }
                if (fold > 0) {
                    lift += (FOLD[0] - lift) * fold;
                    knee += (FOLD[1] - knee) * fold;
                    ankle += (FOLD[2] - ankle) * fold;
                    claw += (FOLD[3] - claw) * fold;
                }
                Matrix4d[] ch = chain(s, y, yaw, lift, knee, ankle, claw);
                for (int p = 0; p < 4; p++) f.legs[side][i][p] = new Matrix4d(f.body).mul(ch[p]);
            }
        }
        return f;
    }

    /** Pods pump in a wave from the top down, then jump on each burst. Returns the body kick. */
    private static double pumpPods(Frame f, double[] baseZ, double t, double[] bursts, double stopA, double stopB, double startA, double startB) {
        double on = startA < 0 ? 1 : smooth(startA, startB, t), off = stopA < 0 ? 1 : 1 - smooth(stopA, stopB, t);
        double burst = 0, kick = 0;
        for (double tb : bursts) {
            burst += 0.9 * Math.exp(-Math.pow((t - tb) / 1.8, 2)) - 0.35 * Math.exp(-Math.pow((t - tb + 3) / 1.5, 2));
            kick += P * Math.exp(-Math.pow((t - tb) / 1.8, 2));
        }
        for (int n = 0; n < baseZ.length; n++) {
            double phase = (1 - (baseZ[n] * 16 - 20) / 35) * Math.PI;
            double pulse = Math.max(0, Math.sin(2 * Math.PI * t / 10 - phase)) * on * off;
            double up = 1 + 0.55 * pulse + burst;
            f.podUp[n] = up;
            f.podSide[n] = 1 - 0.15 * (up - 1);
        }
        return kick;
    }

    /** Blender space -> model space (OBJ export: x stays, Blender z -> model y, Blender -y -> model z). */
    public static Matrix4d toModel(Matrix4d blender) {
        return new Matrix4d(B2M).mul(blender).mul(M2B);
    }

    private static final Matrix4d B2M = new Matrix4d(1, 0, 0, 0, 0, 0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    private static final Matrix4d M2B = new Matrix4d(B2M).invert();

    private GlybnikPose() {}
}
