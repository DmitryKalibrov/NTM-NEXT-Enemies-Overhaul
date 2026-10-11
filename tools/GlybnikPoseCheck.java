import com.hbm.enemies.glyphid.EntityGlyphidGlybnik.State;
import com.hbm.enemies.glyphid.GlybnikPose;
import org.joml.Vector3d;

/**
 * Checks the Java port of the Glybnik animation against claw positions read from the Blender preview
 * (tools/glybnik_anim.py). Run: tools/check_pose.sh (after a build).
 */
public final class GlybnikPoseCheck {

    public static void main(String[] args) {
        // clip, tick, side (0 = L, 1 = R), slot, expected Blender-space claw tip
        Object[][] cases = {
                {State.BASH, 14, 1, 2, 2.6642, 0.8209, -0.0001}, {State.BASH, 14, 0, 0, -2.7429, -1.1751, 0.2811},
                {State.SPORES, 20, 1, 2, 2.6642, 0.8209, -0.0001}, {State.SPORES, 20, 0, 0, -2.7429, -1.1126, -0.0002},
                {State.HIDE, 30, 1, 2, 0.7439, 0.3422, 0.2639}, {State.HIDE, 30, 0, 0, -0.7397, -0.3558, 0.2639},
                {State.BASH, 30, 1, 2, 2.6642, 0.8209, -0.0001}, {State.BASH, 30, 0, 0, -2.7429, -1.2689, -0.0002},
        };
        double worst = 0;
        for (Object[] c : cases) {
            GlybnikPose.Frame f = GlybnikPose.compute((State) c[0], (int) c[1], 0, 0, 0, 1000, new double[0], null);
            int side = (int) c[2], s = side == 0 ? -1 : 1;
            Vector3d tip = f.legs[side][(int) c[3]][3].transformPosition(new Vector3d(s * 50 / 16.0, 0, 11 / 16.0));
            double err = tip.distance((double) c[4], (double) c[5], (double) c[6]);
            worst = Math.max(worst, err);
            System.out.printf("%s t=%d side=%d slot=%d err=%.4f%n", c[0], c[1], side, c[3], err);
        }
        if (worst > 0.01) throw new AssertionError("port differs from the Blender preview by " + worst);
        // terrain: ground 0.4 lower under the front pair, the front claws must reach down to it
        double[][] ground = {{-0.4, 0, 0, 0}, {-0.4, 0, 0, 0}};
        GlybnikPose.Frame f = GlybnikPose.compute(State.WALK, 0, 0, 0, 0, 1000, new double[0], ground);
        for (int side = 0; side < 2; side++) {
            int s = side == 0 ? -1 : 1;
            double z = f.legs[side][0][3].transformPosition(new Vector3d(s * 50 / 16.0, 0, 11 / 16.0)).z;
            System.out.printf("terrain: front claw side=%d z=%.3f (want -0.4)%n", side, z);
            if (Math.abs(z + 0.4) > 0.03) throw new AssertionError("front claw misses the lower ground: " + z);
        }
        System.out.println("OK, worst " + worst);
    }
}
