import com.hbm.enemies.glyphid.EntityGlyphidNeedler.State;
import com.hbm.enemies.glyphid.NeedlerPose;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * Checks the Java Needler animation against the Blender preview: tools/needler_pose_ref.json holds knee and foot
 * positions in the body frame (and the body pose for clips without root motion) read from the keyed preview.
 * Run: tools/check_needler.sh (after a build).
 */
public final class NeedlerPoseCheck {

    public static void main(String[] args) throws Exception {
        String json = Files.readString(Path.of("tools/needler_pose_ref.json"));
        double worst = 0;
        for (String row : json.substring(2, json.length() - 2).split("], \\[")) {
            String[] c = row.split(", ");
            String clip = c[0].replace("\"", "");
            int t = Integer.parseInt(c[1]), side = Integer.parseInt(c[2]), i = Integer.parseInt(c[3]);
            double[] v = new double[6];
            for (int k = 0; k < 6; k++) v[k] = Double.parseDouble(c[4 + k]);
            NeedlerPose.Frame f = frame(clip, t);
            double err;
            if (side < 0) {
                if (clip.equals("walk") || clip.equals("run")) continue;   // the preview walks forward (root motion)
                Matrix4d ref = new Matrix4d().translation(v[0], v[1], v[2]).rotateZ(v[5]).rotateY(v[4]).rotateX(v[3]);
                err = 0;
                for (Vector3d p : new Vector3d[]{new Vector3d(0, 0, 0), new Vector3d(1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1)}) {
                    err = Math.max(err, ref.transformPosition(new Vector3d(p)).distance(f.body.transformPosition(new Vector3d(p))));
                }
            } else {
                Matrix4d inv = new Matrix4d(f.body).invert();
                Vector3d knee = inv.transformPosition(f.limbs[side][i][0].transformPosition(rest(side, i, 1)));
                Vector3d foot = inv.transformPosition(f.limbs[side][i][2].transformPosition(rest(side, i, 3)));
                err = Math.max(knee.distance(v[0], v[1], v[2]), foot.distance(v[3], v[4], v[5]));
            }
            if (err > 0.005) System.out.printf("%s t=%d side=%d slot=%d err=%.4f%n", clip, t, side, i, err);
            worst = Math.max(worst, err);
        }
        if (worst > 0.01) throw new AssertionError("port differs from the Blender preview by " + worst);
        System.out.println("OK, worst " + worst);
    }

    private static Vector3d rest(int side, int i, int joint) {
        try {
            var f = NeedlerPose.class.getDeclaredField("REST");
            f.setAccessible(true);
            return new Vector3d(((Vector3d[][][]) f.get(null))[side][i][joint]);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static NeedlerPose.Frame frame(String clip, int t) {
        return switch (clip) {
            case "walk" -> NeedlerPose.compute(State.WALK, 0, t / 40.0, 1, false, 0, 1000, 0, t, -1, null);
            case "run" -> NeedlerPose.compute(State.WALK, 0, t / 14.0, 1, true, 0, 1000, 0, t, -1, null);
            case "leap" -> NeedlerPose.compute(State.LEAP, t, 0, 0, false, 0, 1000, 0, t, -1, null);
            case "bite" -> NeedlerPose.compute(State.BITE, t, 0, 0, false, 0, 1000, 0, t, -1, null);
            case "aim" -> NeedlerPose.compute(State.AIM, t, 0, 0, false, 0, 1000, 0, t, -1, null);
            case "shoot" -> NeedlerPose.compute(State.SHOOT, t, 0, 0, false, 0, 1000, 0, t, -1, null);
            case "death" -> NeedlerPose.compute(State.WALK, 0, 0, 0, false, 0, 1000, 0, t, t, null);
            default -> throw new IllegalArgumentException(clip);
        };
    }
}
