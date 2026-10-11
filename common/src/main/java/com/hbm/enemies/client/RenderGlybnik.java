package com.hbm.enemies.client;

import com.hbm.client.model.Meshes;
import com.hbm.client.render.WorldRenderPipeline;
import com.hbm.enemies.Enemies;
import com.hbm.enemies.glyphid.EntityGlyphidGlybnik;
import com.hbm.enemies.glyphid.GlybnikPose;
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

/** Plays the Glybnik's clips from {@link GlybnikPose}; every part gets its own matrix. */
public class RenderGlybnik extends EntityRenderer<EntityGlyphidGlybnik, RenderGlybnik.State> {

    static final HFRWavefrontObject MESH = Meshes.load(Enemies.id("models/obj/glyphid_glybnik.obj"));
    static final RenderType TYPE = WorldRenderPipeline.oneSidedCutout(Enemies.id("textures/entity/glyphid_glybnik.png"));
    private static final String[] SIDES = {"L", "R"};
    private static final int[] HULL;
    private static final int[][] MANDIBLE = new int[2][];
    /** [side][0 upper, 1 knee+shin, 2 ankle+blade, 3 joint+claw] part ids, matching GlybnikPose.Frame#legs. */
    private static final int[][][] LEG = new int[2][4][];
    private static final int[] PODS;
    /** Pod base in model space (x, y, z) and its Blender height for the pump wave. */
    private static final double[][] POD_BASE;
    private static final double[] POD_BASE_Z;

    static {
        List<Integer> hull = new ArrayList<>(), pods = new ArrayList<>();
        for (String name : MESH.getGroupNames()) {
            if (name.startsWith("Pod")) pods.add(MESH.partId(name));
            else if (!name.startsWith("Leg") && !name.startsWith("Mandible")) hull.add(MESH.partId(name));
        }
        HULL = hull.stream().mapToInt(Integer::intValue).toArray();
        PODS = pods.stream().mapToInt(Integer::intValue).toArray();
        POD_BASE = new double[PODS.length][];
        POD_BASE_Z = new double[PODS.length];
        for (int n = 0; n < PODS.length; n++) {
            float[] b = MESH.boundsOfParts(PODS[n]);   // minX, minY, minZ, maxX, maxY, maxZ
            POD_BASE[n] = new double[]{(b[0] + b[3]) / 2, b[1], (b[2] + b[5]) / 2};
            POD_BASE_Z[n] = b[1];
        }
        for (int side = 0; side < 2; side++) {
            String s = SIDES[side];
            MANDIBLE[side] = MESH.partIds("Mandible" + s, "Mandible" + s + "Hook");
            int[] upper = MESH.partIds("Leg" + s + "Upper"), shin = MESH.partIds("Leg" + s + "Knee", "Leg" + s + "Shin"),
                    blade = MESH.partIds("Leg" + s + "Ankle", "Leg" + s + "Blade"), claw = MESH.partIds("Leg" + s + "ClawJoint", "Leg" + s + "Claw");
            LEG[side] = new int[][]{upper, shin, blade, claw};
        }
    }

    public RenderGlybnik(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 1.5f;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(EntityGlyphidGlybnik entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.bodyYaw = Mth.rotLerp(partialTicks, entity.yBodyRotO, entity.yBodyRot);
        state.clip = entity.state();
        state.clipAge = entity.tickCount - entity.clientStateStart + partialTicks;
        // walkAnimation runs at about 4 units per block
        state.walkPhase = entity.walkAnimation.position(partialTicks) / 4.0 / GlybnikPose.CYCLE_DISTANCE;
        state.walkAmp = Math.min(1.0, entity.walkAnimation.speed(partialTicks) * 3.0);
        state.missing = entity.missingLegs();
        state.legLost = entity.tickCount - entity.clientLegLost + partialTicks;
        state.subtype = entity.subtype();
        for (int side = 0; side < 2; side++) System.arraycopy(entity.footGround[side], 0, state.ground[side], 0, 4);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        int light = state.lightCoords;
        GlybnikPose.Frame f = GlybnikPose.compute(state.clip, state.clipAge, state.walkPhase, state.walkAmp,
                state.missing, state.legLost, POD_BASE_Z, state.ground);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-state.bodyYaw));
        part(pose, collector, light, f.body, HULL);
        for (int n = 0; n < PODS.length; n++) {
            double[] b = POD_BASE[n];
            Matrix4d pod = GlybnikPose.toModel(f.body).translate(b[0], b[1], b[2])
                    .scale(f.podSide[n], f.podUp[n], f.podSide[n]).translate(-b[0], -b[1], -b[2]);
            partModel(pose, collector, light, pod, PODS[n]);
        }
        for (int side = 0; side < 2; side++) {
            part(pose, collector, light, f.mandible[side], MANDIBLE[side]);
            for (int i = 0; i < 4; i++) {
                Matrix4d[] leg = f.legs[side][i];
                part(pose, collector, light, leg[0], LEG[side][0]);
                if (f.stump[side][i]) continue;
                for (int p = 1; p < 4; p++) part(pose, collector, light, leg[p], LEG[side][p]);
            }
        }
        pose.popPose();
        super.submit(state, pose, collector, camera);
    }

    private static void part(PoseStack pose, SubmitNodeCollector collector, int light, Matrix4d blender, int... ids) {
        partModel(pose, collector, light, GlybnikPose.toModel(blender), ids);
    }

    static void partModel(PoseStack pose, SubmitNodeCollector collector, int light, Matrix4d model, int... ids) {
        pose.pushPose();
        pose.mulPose(new Matrix4f(model));
        collector.submitCustomGeometry(pose, TYPE, (p, buf) -> {
            for (int id : ids) MESH.renderPart(p, buf, light, -1, id);
        });
        pose.popPose();
    }

    public static final class State extends EntityRenderState {
        float bodyYaw;
        EntityGlyphidGlybnik.State clip = EntityGlyphidGlybnik.State.WALK;
        double clipAge, walkPhase, walkAmp, legLost;
        int missing;
        byte subtype;
        final double[][] ground = new double[2][4];
    }
}
