package com.hbm.enemies.glyphid;

import static com.hbm.enemies.glyphid.GlybnikPose.rotAbout;

import com.hbm.enemies.glyphid.EntityGlyphidNeedler.State;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * The Needler's animation maths, ported 1:1 from tools/needler_anim.py (which keyframes the Blender preview).
 * Blender space (x right, -y forward, z up, blocks); {@link GlybnikPose#toModel} turns results into model space.
 * In game the entity itself moves, so there is no root motion: foot targets live in the ground frame (the entity
 * frame before the body pose) and the IK turns them into the body frame, so planted feet stay put.
 */
public final class NeedlerPose {

    /** Limb slots per side, front to back: 0 = stinger arm, 1..3 = legs. Rest joints of the right side: pivot, knee, ankle, foot. */
    static final double RZ = 0.68;
    static final double[][][] LEG_REST = {
            {{0.38, 0.7, RZ}, {1.1, 0.95, 1.75}, {2.0, 0.55, 0.0}},
            {{0.38, 1.2, RZ}, {1.15, 1.6, 1.75}, {2.1, 1.3, 0.0}},
            {{0.3, 1.85, RZ - 0.05}, {1.1, 2.35, 1.65}, {2.0, 2.2, 0.0}}};
    static final double[][] ARM_REST = {{0.42, -0.45, 0.58}, {1.55, -1.18, 1.45}, {1.6, -1.88, 0.55}, {1.62, -2.27, 0.0}};
    /** Gaits: period (ticks), duty, stride, step height. */
    static final double[] WALK = {40, 0.75, 0.6, 0.25}, RUN = {14, 0.5, 0.7, 0.3};
    static final double LIMP_STRIDE = 0.7;
    static final double[] BODY_PIVOT = {0, 0.5, 0.9};
    static final double LEAN = Math.toRadians(20);
    static final Vector3d SU = new Vector3d(1, 0, 0), SV = new Vector3d(0, Math.sin(LEAN), Math.cos(LEAN)),
            SN = new Vector3d(0, -Math.cos(LEAN), Math.sin(LEAN));
    public static final Vector3d SHIELD_C = new Vector3d(0, -0.35, 0.95).fma(0.95, SV);
    static final double FAN_CLOSED_R = 80, FAN_CLOSED_L = 100, FAN_CLOSED_STEP = 4;
    static final int[] SHOTS = {8, 18, 28};
    static final int LEAP_LAUNCH = 10, LEAP_STAB = 24;
    static final int[] BITES = {11, 18};
    public static final int DEATH_LENGTH = 70;
    static final double[][] MANDIBLE_PIVOT = {{0.3, -0.86, 0.6}, {0.09, -1.0, 0.52}};   // outer, inner (right side)

    /** [side][slot] = {pivot, knee, ankle, foot}. */
    static final Vector3d[][][] REST = new Vector3d[2][4][];

    static {
        for (int side = 0; side < 2; side++) {
            int s = side == 0 ? -1 : 1;
            for (int i = 0; i < 4; i++) {
                if (i == 0) {
                    Vector3d[] a = new Vector3d[4];
                    for (int j = 0; j < 4; j++) a[j] = mirror(ARM_REST[j], s);
                    a[0].add(s * 0.08, 0, 0);
                    REST[side][i] = a;
                } else {
                    double[][] r = LEG_REST[i - 1];
                    Vector3d h = mirror(r[0], s), k = mirror(r[1], s), f = mirror(r[2], s);
                    Vector3d ankle = new Vector3d(k).lerp(f, 0.74).add(s * 0.04, 0, 0.1);
                    REST[side][i] = new Vector3d[]{h.add(s * 0.08, 0, 0), k, ankle, f};
                }
            }
        }
    }

    private static Vector3d mirror(double[] p, int s) {
        return new Vector3d(s * p[0], p[1], p[2]);
    }

    /** Standing foot of a limb in the body frame. */
    public static Vector3d home(int side, int i) {
        return new Vector3d(REST[side][i][3]);
    }

    /** Bit of a limb in the missing mask. */
    public static boolean missing(int mask, int side, int i) {
        return (mask & (1 << (side * 4 + i))) != 0;
    }

    /** Body-frame centre of the part of a limb that flies off (between knee and ankle). */
    public static Vector3d lowerCentre(int side, int i) {
        return new Vector3d(REST[side][i][1]).lerp(REST[side][i][2], 0.5);
    }

    public static final class Frame {
        public final Matrix4d body = new Matrix4d();
        /** [side][slot][0 femur (+knee), 1 tibia, 2 tarsus (+ankle)] relative to the world-less entity frame. */
        public final Matrix4d[][][] limbs = new Matrix4d[2][4][3];
        public final boolean[][] stump = new boolean[2][4];
        /** [side][0 outer, 1 inner]. */
        public final Matrix4d[][] mandible = new Matrix4d[2][2];
        public final double[] ports = new double[NeedlerRig.PORTS.length];
        public double fan;
    }

    static double smooth(double a, double b, double t) {
        double x = Math.min(1, Math.max(0, (t - a) / (b - a)));
        return x * x * (3 - 2 * x);
    }

    static double bump(double a, double b, double t) {
        return Math.sin(Math.PI * Math.min(1, Math.max(0, (t - a) / (b - a))));
    }

    private static Matrix3d basis(Vector3d d0, Vector3d n0) {
        Vector3d d = new Vector3d(d0).normalize();
        Vector3d n = new Vector3d(n0).fma(-n0.dot(d), d).normalize();
        Vector3d c = new Vector3d(d).cross(n);
        return new Matrix3d(d.x, d.y, d.z, n.x, n.y, n.z, c.x, c.y, c.z);
    }

    private static Matrix4d about(Vector3d p, Matrix3d r) {
        return new Matrix4d().translation(p).mul(new Matrix4d(r)).translate(-p.x, -p.y, -p.z);
    }

    /** Body-frame foot target -> {femur, tibia, tarsus}; the knee keeps its rest pole, the tarsus its slope. */
    static Matrix4d[] ik(Vector3d[] rest, Vector3d target) {
        Vector3d H = rest[0], K = rest[1], A = rest[2], F = rest[3];
        double yaw = Math.atan2(target.y - H.y, target.x - H.x) - Math.atan2(F.y - H.y, F.x - H.x);
        Matrix4d rz = rotAbout(H.x, H.y, H.z, 'Z', Math.toDegrees(yaw));
        Matrix3d r3 = new Matrix3d().rotationZ(yaw);
        Vector3d tars = r3.transform(new Vector3d(F).sub(A));
        Vector3d a1 = new Vector3d(target).sub(tars);
        double l1 = K.distance(H), l2 = A.distance(K);
        Vector3d v = new Vector3d(a1).sub(H);
        double d = Math.max(Math.abs(l1 - l2) + 1e-4, Math.min(v.length(), l1 + l2 - 1e-4));
        Vector3d dh = new Vector3d(v).normalize();
        a1.set(H).fma(d, dh);
        Vector3d pole = r3.transform(new Vector3d(K).sub(H));
        Vector3d e = new Vector3d(pole).fma(-pole.dot(dh), dh).normalize();
        double a = (l1 * l1 - l2 * l2 + d * d) / (2 * d);
        Vector3d k1 = new Vector3d(H).fma(a, dh).fma(Math.sqrt(Math.max(0, l1 * l1 - a * a)), e);
        Vector3d n0 = new Vector3d(K).sub(H).cross(new Vector3d(A).sub(K));
        Vector3d n1 = new Vector3d(k1).sub(H).cross(new Vector3d(a1).sub(k1));
        Matrix3d rf = basis(new Vector3d(k1).sub(H), n1).mul(basis(new Vector3d(K).sub(H), n0).transpose());
        Matrix3d rt = basis(new Vector3d(a1).sub(k1), n1).mul(basis(new Vector3d(A).sub(K), n0).transpose());
        Matrix4d femur = about(H, rf);
        Matrix4d tibia = new Matrix4d().translation(k1).mul(new Matrix4d(rt)).translate(-K.x, -K.y, -K.z);
        Matrix4d tarsus = new Matrix4d().translation(a1).mul(new Matrix4d(r3)).translate(-A.x, -A.y, -A.z);
        return new Matrix4d[]{femur, tibia, tarsus};
    }

    /** Foot offset over one cycle {dy, dz}; forward is -y. g = {T, duty, stride, height}. */
    static double[] stepOffset(double phase, double[] g, double stride) {
        double duty = g[1];
        if (phase < duty) return new double[]{-stride / 2 + stride * phase / duty, 0};
        double k = (phase - duty) / (1 - duty);
        return new double[]{stride / 2 - stride * (k * k * (3 - 2 * k)), g[3] * Math.sin(Math.PI * k)};
    }

    /** Body distance per gait cycle (blocks): what the walk phase is measured in. */
    public static double cycleDistance(boolean running, boolean limping) {
        double[] g = running ? RUN : WALK;
        return g[2] * (limping && !running ? LIMP_STRIDE : 1) / g[1];
    }

    static double offset(int side, int i, boolean running) {
        return running ? ((i % 2) * 0.5 + (side == 1 ? 0.5 : 0)) % 1.0 : ((3 - i) * 0.25 + (side == 1 ? 0.5 : 0)) % 1.0;
    }

    /** Pleat transform relative to the body: folds toward the top of its side like a hand fan, trembles. */
    public static Matrix4d pleat(int index, double open, double time) {
        String n = NeedlerRig.PLEATS[index];
        boolean left = n.charAt(4) == 'L';
        int k = Integer.parseInt(n.substring(5, 7));
        double closed = left ? FAN_CLOSED_L + FAN_CLOSED_STEP * k : FAN_CLOSED_R - FAN_CLOSED_STEP * k;
        double ang = (closed - NeedlerRig.PLEAT_ANGLE[index]) * (1 - open);
        ang += (0.4 + 1.2 * open) * Math.sin(time * 2.7 + k * 1.9 + (left ? 1.3 : 0.0));
        // four depth layers 0.07 apart: bunched pleats never cut each other, roots stay in the base ring
        Vector3d stack = new Vector3d(SN).mul(-0.14 * (k / 2 % 2));
        return new Matrix4d().translation(stack).translate(SHIELD_C)
                .rotate(Math.toRadians(ang), SN.x, SN.y, SN.z).translate(-SHIELD_C.x, -SHIELD_C.y, -SHIELD_C.z);
    }

    /** Port tube transform relative to the body: stretched along the shield normal from its base. */
    public static Matrix4d port(int index, double k) {
        double[] b = NeedlerRig.PORT_BASE[index];
        Matrix3d s = new Matrix3d().identity();
        s.m00 += (k - 1) * SN.x * SN.x; s.m01 += (k - 1) * SN.x * SN.y; s.m02 += (k - 1) * SN.x * SN.z;
        s.m10 += (k - 1) * SN.y * SN.x; s.m11 += (k - 1) * SN.y * SN.y; s.m12 += (k - 1) * SN.y * SN.z;
        s.m20 += (k - 1) * SN.z * SN.x; s.m21 += (k - 1) * SN.z * SN.y; s.m22 += (k - 1) * SN.z * SN.z;
        return about(new Vector3d(b[0], b[1], b[2]), s);
    }

    /**
     * @param state     clip
     * @param t         ticks since the clip started (with partial tick)
     * @param walkPhase walk cycle position in cycles (distance / {@link #cycleDistance})
     * @param walkAmp   0..1, how much the walk cycle shows (0 standing)
     * @param running   charging gait (tetrapod) instead of the calm wave
     * @param mask      torn-off limbs (bit side * 4 + slot)
     * @param legLost   ticks since the last limb came off (large if long ago)
     * @param fan       radar fan opening 0..1
     * @param time      entity age in ticks, for the tremble
     * @param deathT    ticks since death, or negative while alive
     * @param ground    [side][slot] terrain under each foot relative to the feet (blocks), or null for flat
     */
    public static Frame compute(State state, double t, double walkPhase, double walkAmp, boolean running, int mask,
                                double legLost, double fan, double time, double deathT, double[][] ground) {
        Frame f = new Frame();
        f.fan = fan;
        java.util.Arrays.fill(f.ports, 1);
        double fwd = 0, dz = 0, pitch = 0, roll = 0, yaw = 0, mandO = 0, mandI = 0, drop = 0;
        Vector3d[][] targets = new Vector3d[2][4];
        Vector3d[][] bodyTargets = new Vector3d[2][4];
        double[][] blend = new double[2][4];
        for (int side = 0; side < 2; side++) for (int i = 0; i < 4; i++) targets[side][i] = home(side, i);
        boolean limping = mask != 0;

        if (deathT >= 0) {
            // legs buckle, the body drops onto its belly and tips over, limbs curl in and twitch out
            double tt = deathT;
            drop = smooth(4, 16, tt);
            double tip = smooth(14, 28, tt), twitch = Math.exp(-Math.max(0, tt - 20) / 14) * Math.sin(tt * 1.7);
            roll = 10 * tip + 2 * twitch;
            pitch = 6 * drop;
            mandO = 40 * bump(0, 12, tt) + 8 * twitch;
            mandI = 46 * bump(0, 12, tt) + 10 * twitch;
            f.fan = Math.max(fan, 0.35 * bump(0, 10, tt));
            for (int side = 0; side < 2; side++) {
                for (int i = 0; i < 4; i++) {
                    double curl = smooth(10 + 2 * i, 30 + 2 * i, tt);
                    if (curl == 0) continue;
                    Vector3d h = home(side, i);
                    bodyTargets[side][i] = new Vector3d(h).lerp(new Vector3d(h.x * 0.88, h.y + 0.3, 0.05), curl)
                            .add(0, 0, 0.12 * twitch * ((i + side) % 2 == 1 ? 1 : -1));
                }
            }
        } else switch (state) {
            case WALK -> {
                double[] g = running ? RUN : WALK;
                double stride = g[2] * (limping && !running ? LIMP_STRIDE : 1);
                double lifted0 = 0, lifted1 = 0;
                for (int side = 0; side < 2; side++) {
                    for (int i = 0; i < 4; i++) {
                        if (missing(mask, side, i)) continue;
                        double[] o = stepOffset(((walkPhase + offset(side, i, running)) % 1.0 + 1.0) % 1.0, g, stride);
                        targets[side][i].add(0, o[0] * walkAmp, o[1] * walkAmp);
                        if (side == 0) lifted0 += o[1] / g[3] * walkAmp;
                        else lifted1 += o[1] / g[3] * walkAmp;
                    }
                }
                double wave = Math.sin(4 * Math.PI * walkPhase) * walkAmp;
                if (running) {
                    dz = (0.06 * Math.abs(Math.sin(2 * Math.PI * walkPhase)) - 0.08) * walkAmp;
                    pitch = (4 + 2 * Math.sin(4 * Math.PI * walkPhase)) * walkAmp;
                    roll = 2.5 * (lifted1 - lifted0);
                    mandO = 10 * walkAmp;
                    mandI = 14 * walkAmp;
                } else if (limping) {   // sags and lists to the maimed side
                    int lostL = Integer.bitCount(mask & 0x0F), lostR = Integer.bitCount(mask & 0xF0);
                    roll = 1.2 * (lostR - lostL) + 1.5 * lifted0 - 1.0 * lifted1 + 0.8 * wave;
                    dz = -0.12 - 0.04 * (lifted0 + lifted1);
                    pitch = 1.5 + wave;
                } else {
                    roll = 1.5 * (lifted1 - lifted0);
                    dz = -0.03 * (lifted0 + lifted1) + 0.015 * wave;
                    pitch = 0.8 * wave;
                    mandO = 4 * Math.pow(Math.sin(time * 0.3), 2);
                    mandI = 6 * Math.pow(Math.sin(time * 0.45), 2);
                }
            }
            case LEAP -> {
                // crouch, launch (the entity flies by physics), legs tuck back, arms swing up then stab on landing
                double crouch = smooth(0, 10, t) * (1 - smooth(10, 13, t));
                double land = t >= 23 ? Math.exp(-(t - 24) / 5) * smooth(23, 25, t) : 0;
                dz = -0.3 * crouch - 0.28 * land;
                pitch = -8 * crouch - 10 * bump(10, 18, t) + 12 * bump(18, 28, t);
                mandO = 30 * smooth(4, 12, t) * (1 - smooth(26, 34, t));
                mandI = 36 * smooth(6, 14, t) * (1 - smooth(26, 34, t));
                double tuck = bump(10, 26, t);
                for (int side = 0; side < 2; side++) {
                    for (int i = 0; i < 4; i++) {
                        Vector3d h = home(side, i);
                        if (i == 0) {
                            double up = smooth(2, 10, t) * (1 - smooth(18, 23, t));
                            double stab = smooth(20, 24, t) * (1 - smooth(34, 46, t));
                            targets[side][i].add(0, -0.45 * stab, 0);
                            if (t < 26) {
                                bodyTargets[side][i] = new Vector3d(h).add(0, 0.3 * up - 0.5 * stab, 1.1 * up + 0.35 * tuck * (1 - up));
                                blend[side][i] = smooth(21, 25, t);
                            }
                        } else if (t >= 11 && t < 26) {
                            bodyTargets[side][i] = new Vector3d(h).lerp(new Vector3d(h.x * 0.92, h.y + 0.45, h.z + 0.12), tuck);
                            blend[side][i] = smooth(21, 25, t);
                        }
                    }
                }
            }
            case BITE -> {
                double lunge = smooth(4, 9, t) * (1 - smooth(22, 30, t));
                pitch = 9 * lunge;
                fwd = 0.25 * lunge;
                dz = -0.1 * lunge;
                double snaps = bump(9, 14, t) + bump(16, 21, t);
                double open = smooth(0, 6, t) * (1 - smooth(24, 30, t));
                mandO = 38 * open - 34 * snaps;
                mandI = 44 * open - 40 * snaps;
                double hug = bump(6, 24, t);
                for (int side = 0; side < 2; side++) targets[side][0].add(-(side == 0 ? -1 : 1) * 0.5 * hug, -0.2 * hug, 0.45 * hug);
            }
            case AIM -> {
                yaw = 4 * Math.sin(2 * Math.PI * Math.max(0, t - 24) / 24) * smooth(22, 28, t);
                roll = 1.2 * Math.sin(t * 0.25);
                dz = -0.06 * smooth(0, 10, t);
                pitch = -3 * smooth(0, 10, t);
            }
            case SHOOT -> {
                double rec = 0;
                for (int s0 : SHOTS) if (t >= s0) rec += Math.exp(-(t - s0) / 3);
                pitch = -3 - 4 * rec;
                fwd = -0.06 * rec;
                dz = -0.06 + 0.02 * rec;
                for (int j = 0; j < f.ports.length; j++) {
                    int s0 = SHOTS[j % 3];
                    f.ports[j] = 1 + 0.7 * Math.exp(-Math.pow((t - s0) / 1.4, 2)) - 0.25 * Math.exp(-Math.pow((t - s0 + 3) / 1.5, 2));
                }
            }
        }
        if (ground != null && deathT < 0 && state != State.LEAP) {
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
            double rows = REST[0][3][3].y - REST[0][0][3].y, width = REST[1][2][3].x - REST[0][2][3].x;
            pitch += GlybnikPose.clamp(Math.toDegrees(Math.atan((back - front) / 4 / rows)), 15);
            roll += GlybnikPose.clamp(Math.toDegrees(Math.atan((left - right) / 4 / width)), 15);
            dz += sum / 8 * 0.5;
        }
        if (legLost < 50 && deathT < 0) {   // recoil after losing a limb
            double rec = Math.exp(-legLost / 12);
            roll -= 8 * rec * Math.sin(legLost * 0.45 + 1.0);
            dz -= 0.1 * rec;
            mandO += 30 * rec;
            mandI += 36 * rec;
        }

        double px = BODY_PIVOT[0], py = BODY_PIVOT[1], pz = BODY_PIVOT[2];
        f.body.translation(0, -fwd, dz).mul(rotAbout(px, py, pz, 'Z', yaw))
                .mul(rotAbout(px, py, pz, 'X', pitch)).mul(rotAbout(px, py, pz, 'Y', roll));
        if (drop > 0) {   // rest the belly on the ground
            double low = Double.MAX_VALUE;
            Vector3d v = new Vector3d();
            for (double[] b : NeedlerRig.BELLY) low = Math.min(low, f.body.transformPosition(v.set(b[0], b[1], b[2])).z);
            f.body.mulLocal(new Matrix4d().translation(0, 0, -low * drop));
        }
        Matrix4d inv = new Matrix4d(f.body).invert();
        for (int side = 0; side < 2; side++) {
            int s = side == 0 ? -1 : 1;
            for (int m = 0; m < 2; m++) {
                double[] p = MANDIBLE_PIVOT[m];
                f.mandible[side][m] = new Matrix4d(f.body).mul(rotAbout(s * p[0], p[1], p[2], 'Z', s * (m == 0 ? mandO : mandI)));
            }
            for (int i = 0; i < 4; i++) {
                Vector3d[] rest = REST[side][i];
                if (missing(mask, side, i)) {
                    // torn off at the knee: the femur stump hangs and jerks, the rest is gone
                    double jerk = Math.pow(Math.sin(time * 0.9 + i), 8);
                    Vector3d H = rest[0];
                    Matrix4d stump = rotAbout(H.x, H.y, H.z, 'Y', -s * (35 - 15 * jerk)).mul(rotAbout(H.x, H.y, H.z, 'Z', s * 10 * jerk));
                    f.limbs[side][i][0] = new Matrix4d(f.body).mul(stump);
                    f.stump[side][i] = true;
                    continue;
                }
                Vector3d tgt = inv.transformPosition(new Vector3d(targets[side][i]));
                if (bodyTargets[side][i] != null) tgt = new Vector3d(bodyTargets[side][i]).lerp(tgt, blend[side][i]);
                Matrix4d[] m = ik(rest, tgt);
                for (int p = 0; p < 3; p++) f.limbs[side][i][p] = new Matrix4d(f.body).mul(m[p]);
            }
        }
        return f;
    }

    private NeedlerPose() {}
}
