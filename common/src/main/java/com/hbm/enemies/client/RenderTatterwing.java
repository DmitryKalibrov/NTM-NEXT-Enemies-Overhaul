package com.hbm.enemies.client;

import com.hbm.client.model.Meshes;
import com.hbm.client.render.WorldRenderPipeline;
import com.hbm.enemies.Enemies;
import com.hbm.enemies.glyphid.EntityGlyphidTatterwing;
import com.hbm.enemies.glyphid.GlybnikPose;
import com.hbm.enemies.glyphid.TatterwingPose;
import com.hbm.enemies.glyphid.TatterwingRig;
import com.hbm.render.loader.HFRWavefrontObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Plays the Tatterwing's clips from {@link TatterwingPose}. Rigid parts ride one bone each; the leather of the wings
 * is skinned (every vertex blends its two bones), so it stretches and folds with the fingers.
 */
public class RenderTatterwing extends EntityRenderer<EntityGlyphidTatterwing, RenderTatterwing.State> {

    static final HFRWavefrontObject MESH = Meshes.load(Enemies.id("models/obj/glyphid_tatterwing.obj"));
    private static final Identifier TEXTURE = Enemies.id("textures/entity/glyphid_tatterwing.png");
    static final RenderType SOLID = WorldRenderPipeline.oneSidedCutout(TEXTURE);
    static final RenderType LEATHER = WorldRenderPipeline.oneSidedTranslucent(TEXTURE);
    /** Rigid mesh parts per bone. */
    private static final int[][] BONE_PARTS = new int[TatterwingRig.BONES.length][];

    static {
        List<List<Integer>> parts = new ArrayList<>();
        for (int b = 0; b < TatterwingRig.BONES.length; b++) parts.add(new ArrayList<>());
        for (int g = 0; g < TatterwingRig.GROUPS.length; g++) parts.get(TatterwingRig.GROUP_BONE[g]).add(MESH.partId(TatterwingRig.GROUPS[g]));
        for (int b = 0; b < BONE_PARTS.length; b++) BONE_PARTS[b] = parts.get(b).stream().mapToInt(Integer::intValue).toArray();
    }

    /** Skin: per vertex Blender position, two bones and the first weight; per triangle corner the vertex and uv. */
    private static float[] skinPos, skinW, cornerUv;
    private static byte[] skinBone;
    private static int[] corner;

    public RenderTatterwing(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.9f;
        if (skinPos == null) loadSkin(context.getResourceManager());
    }

    private static void loadSkin(ResourceManager resources) {
        Identifier id = Enemies.id("models/rig/glyphid_tatterwing_skin.bin");
        try (InputStream raw = resources.getResourceOrThrow(id).open(); DataInputStream in = new DataInputStream(raw)) {
            int n = in.readInt();
            float[] pos = new float[n * 3], w = new float[n];
            byte[] bones = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                pos[i * 3] = in.readFloat();
                pos[i * 3 + 1] = in.readFloat();
                pos[i * 3 + 2] = in.readFloat();
                bones[i * 2] = in.readByte();
                bones[i * 2 + 1] = in.readByte();
                w[i] = in.readFloat();
            }
            int tris = in.readInt();
            int[] c = new int[tris * 3];
            float[] uv = new float[tris * 6];
            for (int i = 0; i < tris * 3; i++) {
                c[i] = in.readInt();
                uv[i * 2] = in.readFloat();
                uv[i * 2 + 1] = in.readFloat();
            }
            skinPos = pos;
            skinW = w;
            skinBone = bones;
            corner = c;
            cornerUv = uv;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + id, e);
        }
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(EntityGlyphidTatterwing entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.bodyYaw = Mth.rotLerp(partialTicks, entity.yBodyRotO, entity.yBodyRot);
        if (entity.deathTime > 0) {
            state.clip = TatterwingPose.Clip.DEATH;
            state.clipAge = entity.deathTime + partialTicks;
        } else {
            state.clip = entity.clip();
            state.clipAge = entity.tickCount - entity.clientClipStart + partialTicks;
        }
        state.bank = Mth.lerp(partialTicks, entity.bankO, entity.bank);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        int light = state.lightCoords;
        Matrix4d[] bones = TatterwingPose.compute(state.clip, state.clipAge, state.bank);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-state.bodyYaw));
        for (int b = 0; b < bones.length; b++) {
            if (BONE_PARTS[b].length == 0) continue;
            int[] ids = BONE_PARTS[b];
            pose.pushPose();
            pose.mulPose(new Matrix4f(GlybnikPose.toModel(bones[b])));
            collector.submitCustomGeometry(pose, SOLID, (p, buf) -> {
                for (int id : ids) MESH.renderPart(p, buf, light, -1, id);
            });
            pose.popPose();
        }
        float[] skin = skin(bones);
        collector.submitCustomGeometry(pose, LEATHER, (p, buf) -> {
            Matrix4f m = p.pose();
            Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), n = new Vector3f();
            for (int t = 0; t < corner.length; t += 3) {
                m.transformPosition(at(skin, corner[t], a));
                m.transformPosition(at(skin, corner[t + 1], b));
                m.transformPosition(at(skin, corner[t + 2], c));
                new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a), n);
                if (n.lengthSquared() < 1e-12f) continue;
                n.normalize();
                for (int k : new int[]{0, 1, 2, 2}) {
                    Vector3f v = k == 0 ? a : k == 1 ? b : c;
                    int ci = t + k;
                    buf.addVertex(v.x, v.y, v.z, -1, cornerUv[ci * 2], cornerUv[ci * 2 + 1], OverlayTexture.NO_OVERLAY, light, n.x, n.y, n.z);
                }
            }
        });
        pose.popPose();
        super.submit(state, pose, collector, camera);
    }

    /** Skinned vertex positions in model space (Blender x, z, -y), 3 floats each. */
    private static float[] skin(Matrix4d[] bones) {
        int n = skinW.length;
        float[] out = new float[n * 3];
        Vector3d p = new Vector3d(), q = new Vector3d();
        for (int i = 0; i < n; i++) {
            double x = skinPos[i * 3], y = skinPos[i * 3 + 1], z = skinPos[i * 3 + 2], w = skinW[i];
            bones[skinBone[i * 2]].transformPosition(p.set(x, y, z));
            bones[skinBone[i * 2 + 1]].transformPosition(q.set(x, y, z));
            p.mul(w).fma(1 - w, q);
            out[i * 3] = (float) p.x;
            out[i * 3 + 1] = (float) p.z;
            out[i * 3 + 2] = (float) -p.y;
        }
        return out;
    }

    private static Vector3f at(float[] skin, int v, Vector3f out) {
        return out.set(skin[v * 3], skin[v * 3 + 1], skin[v * 3 + 2]);
    }

    public static final class State extends EntityRenderState {
        float bodyYaw, bank;
        TatterwingPose.Clip clip = TatterwingPose.Clip.REST;
        double clipAge;
    }
}
