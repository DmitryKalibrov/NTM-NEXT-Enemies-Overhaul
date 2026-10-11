package com.hbm.enemies.client;

import com.hbm.client.model.Meshes;
import com.hbm.client.render.WorldRenderPipeline;
import com.hbm.enemies.Enemies;
import com.hbm.enemies.glyphid.EntityGlyphidNeedler;
import com.hbm.enemies.glyphid.GlybnikPose;
import com.hbm.enemies.glyphid.NeedlerPose;
import com.hbm.enemies.glyphid.NeedlerRig;
import com.hbm.render.loader.HFRWavefrontObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4d;
import org.joml.Matrix4f;

/** Plays the Needler's clips from {@link NeedlerPose}; every part gets its own matrix. */
public class RenderNeedler extends EntityRenderer<EntityGlyphidNeedler, RenderNeedler.State> {

    static final HFRWavefrontObject MESH = Meshes.load(Enemies.id("models/obj/glyphid_needler.obj"));
    static final RenderType SOLID = WorldRenderPipeline.oneSidedCutout(Enemies.id("textures/entity/glyphid_needler.png"));
    static final RenderType GLASS = WorldRenderPipeline.oneSidedTranslucent(Enemies.id("textures/entity/glyphid_needler.png"));
    private static final String[] SIDES = {"L", "R"};
    static final String[] LEG_PARTS = {"Femur", "Knee", "Tibia", "Ankle", "Tarsus"}, ARM_PARTS = {"Upper", "Elbow", "Fore", "Wrist", "Sting"};
    /** Which IK matrix (0 femur, 1 tibia, 2 tarsus) each of the five limb parts rides on. */
    static final int[] LIMB_BONE = {0, 0, 1, 2, 2};
    static final int[] BODY, SHELL, WOMB;
    static final int[] PORTS = ids(NeedlerRig.PORTS);
    static final int[] PLEATS = ids(NeedlerRig.PLEATS);
    /** [side][slot][part]. */
    static final int[][][] LIMB = new int[2][4][5];
    /** [side][0 outer, 1 inner]. */
    static final int[][][] MANDIBLE = new int[2][2][];

    static {
        List<Integer> body = new ArrayList<>(), shell = new ArrayList<>(), womb = new ArrayList<>();
        for (String n : MESH.getGroupNames()) {
            int id = MESH.partId(n);
            if (n.equals("Carapace") || n.startsWith("GillBase")) shell.add(id);
            else if (n.startsWith("Womb")) womb.add(id);
            else if (n.startsWith(("Gill")) || n.startsWith("Port") || n.startsWith("Mandible") || n.equals("Needle")) continue;
            else if ((n.startsWith("Leg") && !n.endsWith("Coxa")) || (n.startsWith("Arm") && !n.endsWith("Shoulder"))) continue;
            else body.add(id);
        }
        BODY = body.stream().mapToInt(Integer::intValue).toArray();
        SHELL = shell.stream().mapToInt(Integer::intValue).toArray();
        WOMB = womb.stream().mapToInt(Integer::intValue).toArray();
        for (int side = 0; side < 2; side++) {
            String s = SIDES[side];
            for (int i = 0; i < 4; i++) {
                for (int p = 0; p < 5; p++) {
                    LIMB[side][i][p] = MESH.partId(i == 0 ? "Arm" + s + ARM_PARTS[p] : "Leg" + s + (i - 1) + LEG_PARTS[p]);
                }
            }
            MANDIBLE[side][0] = MESH.partIds("Mandible" + s + "0", "Mandible" + s + "1", "Mandible" + s + "2");
            MANDIBLE[side][1] = MESH.partIds("Mandible" + s + "In0", "Mandible" + s + "In1", "Mandible" + s + "In2");
        }
    }

    static int[] ids(String[] names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) out[i] = MESH.partId(names[i]);
        return out;
    }

    public RenderNeedler(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 1.6f;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(EntityGlyphidNeedler entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.bodyYaw = Mth.rotLerp(partialTicks, entity.yBodyRotO, entity.yBodyRot);
        state.clip = entity.state();
        state.clipAge = entity.tickCount - entity.clientStateStart + partialTicks;
        if (state.clip == EntityGlyphidNeedler.State.LEAP && state.clipAge > 21) {
            // the flight lasts as long as physics says: hold the airborne pose, land with the body
            state.clipAge = entity.clientLanded < 0 ? 21 : 22 + entity.tickCount - entity.clientLanded + partialTicks;
        }
        state.missing = entity.missingLimbs();
        state.running = entity.has(EntityGlyphidNeedler.CHARGING);
        // walkAnimation runs at about 4 units per block
        state.walkPhase = entity.walkAnimation.position(partialTicks) / 4.0 / NeedlerPose.cycleDistance(state.running, state.missing != 0);
        state.walkAmp = Math.min(1.0, entity.walkAnimation.speed(partialTicks) * 3.0);
        state.legLost = entity.tickCount - entity.clientLegLost + partialTicks;
        state.fan = Mth.lerp(partialTicks, entity.fanOpenO, entity.fanOpen);
        state.time = entity.tickCount + partialTicks;
        state.deathT = entity.deathTime > 0 ? entity.deathTime + partialTicks : -1;
        state.shell = !entity.has(EntityGlyphidNeedler.SHELL_LOST);
        state.womb = !entity.has(EntityGlyphidNeedler.WOMB_LOST);
        for (int side = 0; side < 2; side++) System.arraycopy(entity.footGround[side], 0, state.ground[side], 0, 4);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        int light = state.lightCoords;
        NeedlerPose.Frame f = NeedlerPose.compute(state.clip, state.clipAge, state.walkPhase, state.walkAmp, state.running,
                state.missing, state.legLost, state.fan, state.time, state.deathT, state.ground);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-state.bodyYaw));
        part(pose, collector, SOLID, light, f.body, BODY);
        for (int side = 0; side < 2; side++) {
            for (int m = 0; m < 2; m++) part(pose, collector, SOLID, light, f.mandible[side][m], MANDIBLE[side][m]);
            for (int i = 0; i < 4; i++) {
                Matrix4d[] bones = f.limbs[side][i];
                for (int p = 0; p < 5; p++) {
                    if (f.stump[side][i] && p > 0) break;
                    part(pose, collector, SOLID, light, bones[LIMB_BONE[p]], LIMB[side][i][p]);
                }
            }
        }
        if (state.shell) {
            part(pose, collector, SOLID, light, f.body, SHELL);
            for (int n = 0; n < PORTS.length; n++) {
                part(pose, collector, SOLID, light, new Matrix4d(f.body).mul(NeedlerPose.port(n, f.ports[n])), PORTS[n]);
            }
            for (int n = 0; n < PLEATS.length; n++) {
                part(pose, collector, GLASS, light, new Matrix4d(f.body).mul(NeedlerPose.pleat(n, f.fan, state.time)), PLEATS[n]);
            }
        }
        if (state.womb) part(pose, collector, GLASS, light, f.body, WOMB);
        pose.popPose();
        super.submit(state, pose, collector, camera);
    }

    static void part(PoseStack pose, SubmitNodeCollector collector, RenderType type, int light, Matrix4d blender, int... ids) {
        pose.pushPose();
        pose.mulPose(new Matrix4f(GlybnikPose.toModel(blender)));
        collector.submitCustomGeometry(pose, type, (p, buf) -> {
            for (int id : ids) MESH.renderPart(p, buf, light, -1, id);
        });
        pose.popPose();
    }

    public static final class State extends EntityRenderState {
        float bodyYaw;
        EntityGlyphidNeedler.State clip = EntityGlyphidNeedler.State.WALK;
        double clipAge, walkPhase, walkAmp, legLost, fan, time, deathT;
        int missing;
        boolean running, shell = true, womb = true;
        final double[][] ground = new double[2][4];
    }
}
