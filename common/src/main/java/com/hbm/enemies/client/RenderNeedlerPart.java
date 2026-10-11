package com.hbm.enemies.client;

import com.hbm.enemies.glyphid.EntityNeedlerPart;
import com.hbm.enemies.glyphid.NeedlerPose;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.Arrays;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** A piece knocked off a Needler, in its rest pose (the radar folded), tumbling about its centre. */
public class RenderNeedlerPart extends EntityRenderer<EntityNeedlerPart, RenderNeedlerPart.State> {

    public RenderNeedlerPart(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.5f;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(EntityNeedlerPart entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.slot = entity.slot();
        state.yaw = entity.getYRot();
        state.spin = Mth.lerp(partialTicks, entity.spinO, entity.spin);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        boolean shell = state.slot == EntityNeedlerPart.SHELL;
        int side = (state.slot / 4) & 1, i = state.slot % 4, s = side == 0 ? -1 : 1;
        Vector3d c = shell ? new Vector3d(NeedlerPose.SHIELD_C) : NeedlerPose.lowerCentre(side, i);
        // Blender space: spin about the piece's centre, centre it on the entity (raised a little off the ground)
        Matrix4d m = new Matrix4d().translation(0, 0, 0.25)
                .rotateX(Math.toRadians(shell ? -state.spin : state.spin * 0.4)).rotateY(Math.toRadians(shell ? 0 : s * state.spin))
                .translate(-c.x, -c.y, -c.z);
        int light = state.lightCoords;
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-state.yaw));
        if (shell) {
            RenderNeedler.part(pose, collector, RenderNeedler.SOLID, light, m, RenderNeedler.SHELL);
            for (int n = 0; n < RenderNeedler.PORTS.length; n++) RenderNeedler.part(pose, collector, RenderNeedler.SOLID, light, m, RenderNeedler.PORTS[n]);
            for (int n = 0; n < RenderNeedler.PLEATS.length; n++) {
                RenderNeedler.part(pose, collector, RenderNeedler.GLASS, light, new Matrix4d(m).mul(NeedlerPose.pleat(n, 0, 0)), RenderNeedler.PLEATS[n]);
            }
        } else {
            RenderNeedler.part(pose, collector, RenderNeedler.SOLID, light, m, Arrays.copyOfRange(RenderNeedler.LIMB[side][i], 1, 5));
        }
        pose.popPose();
        super.submit(state, pose, collector, camera);
    }

    public static final class State extends EntityRenderState {
        int slot;
        float yaw, spin;
    }
}
