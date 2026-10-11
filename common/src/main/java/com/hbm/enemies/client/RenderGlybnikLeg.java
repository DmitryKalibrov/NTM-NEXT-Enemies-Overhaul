package com.hbm.enemies.client;

import com.hbm.enemies.glyphid.EntityGlybnikLeg;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4d;

/** A torn-off Glybnik leg: the knee-down parts in their rest pose, tumbling about the blade. */
public class RenderGlybnikLeg extends EntityRenderer<EntityGlybnikLeg, RenderGlybnikLeg.State> {

    private static final int[][] PARTS = new int[2][];

    static {
        for (int side = 0; side < 2; side++) {
            String s = side == 0 ? "L" : "R";
            PARTS[side] = RenderGlybnik.MESH.partIds("Leg" + s + "Knee", "Leg" + s + "Shin", "Leg" + s + "Ankle",
                    "Leg" + s + "Blade", "Leg" + s + "ClawJoint", "Leg" + s + "Claw");
        }
    }

    public RenderGlybnikLeg(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.4f;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(EntityGlybnikLeg entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.side = entity.slot() >= 4 ? 1 : 0;
        state.yaw = entity.getYRot();
        state.spin = Mth.lerp(partialTicks, entity.spinO, entity.spin);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        int s = state.side == 0 ? -1 : 1;
        // model space: the leg runs along ±x from the knee (21/16) to the claw tip (50/16) at height 11/16
        double cx = s * 33 / 16.0, cy = 11 / 16.0;
        Matrix4d m = new Matrix4d().translation(0, 0.25, 0)
                .rotateZ(Math.toRadians(s * state.spin)).rotateX(Math.toRadians(state.spin * 0.4))
                .translate(-cx, -cy, 0);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-state.yaw));
        RenderGlybnik.partModel(pose, collector, state.lightCoords, m, PARTS[state.side]);
        pose.popPose();
        super.submit(state, pose, collector, camera);
    }

    public static final class State extends EntityRenderState {
        int side;
        float yaw, spin;
    }
}
