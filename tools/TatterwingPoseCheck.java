import com.hbm.enemies.glyphid.TatterwingPose;
import com.hbm.enemies.glyphid.TatterwingRig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * Checks the Java Tatterwing animation against the Blender preview: tools/tatterwing_pose_ref.txt holds every bone
 * matrix at sample ticks of each clip (written by tools/butterfly_anim.py). Run: tools/check_tatterwing.sh after a build.
 */
public final class TatterwingPoseCheck {

    public static void main(String[] args) throws Exception {
        double worst = 0;
        String last = "";
        Matrix4d[] frame = null;
        for (String line : Files.readAllLines(Path.of("tools/tatterwing_pose_ref.txt"))) {
            String[] c = line.split(" ");
            String key = c[0] + c[1];
            if (!key.equals(last)) {
                frame = TatterwingPose.compute(TatterwingPose.Clip.valueOf(c[0].toUpperCase()), Integer.parseInt(c[1]), 1);
                last = key;
            }
            double[] r = new double[16];
            for (int i = 0; i < 16; i++) r[i] = Double.parseDouble(c[3 + i]);
            // row-major from Blender -> JOML (column-major setter takes m00, m01.. as columns)
            Matrix4d ref = new Matrix4d(r[0], r[4], r[8], r[12], r[1], r[5], r[9], r[13], r[2], r[6], r[10], r[14], r[3], r[7], r[11], r[15]);
            Matrix4d got = frame[Arrays.asList(TatterwingRig.BONES).indexOf(c[2])];
            double err = 0;
            for (Vector3d p : new Vector3d[]{new Vector3d(0, 0, 0), new Vector3d(2, 0, 0), new Vector3d(0, 2, 0), new Vector3d(0, 0, 2)}) {
                err = Math.max(err, ref.transformPosition(new Vector3d(p)).distance(got.transformPosition(new Vector3d(p))));
            }
            if (err > 0.005) System.out.printf("%s t=%s %s err=%.4f%n", c[0], c[1], c[2], err);
            worst = Math.max(worst, err);
        }
        if (worst > 0.01) throw new AssertionError("port differs from the Blender preview by " + worst);
        System.out.println("OK, worst " + worst);
    }
}
