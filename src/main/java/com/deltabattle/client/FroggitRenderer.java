package com.deltabattle.client;

import com.deltabattle.DeltaBattle;
import com.deltabattle.entity.FroggitEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Лёгкий рендер: один билборд-квад (4 вершины) со squash&stretch по вертикальной скорости, интерполяция по partialTick. */
public class FroggitRenderer extends EntityRenderer<FroggitEntity> {
    private static final ResourceLocation TEX = new ResourceLocation(DeltaBattle.MODID, "textures/entity/froggit.png");

    public FroggitRenderer(EntityRendererProvider.Context ctx) { super(ctx); shadowRadius = 0.35f; }

    @Override public ResourceLocation getTextureLocation(FroggitEntity e) { return TEX; }

    @Override
    public void render(FroggitEntity e, float yaw, float pt, PoseStack ps, MultiBufferSource buf, int light) {
        float vy = (float) e.getDeltaMovement().y;
        float stretch = Mth.clamp(vy * 0.9f, -0.25f, 0.3f);            // тянется вверх в прыжке, сплющивается при приземлении
        float sy = 1f + stretch, sx = 1f - stretch * 0.6f;
        float sink = e.isInBattle() ? (float) Math.sin((e.tickCount + pt) * 0.25f) * 0.03f : 0f;
        ps.pushPose();
        ps.translate(0, sink, 0);
        ps.mulPose(this.entityRenderDispatcher.cameraOrientation());   // всегда лицом к камере
        ps.scale(sx, sy, 1f);
        PoseStack.Pose pose = ps.last();
        Matrix4f m = pose.pose(); Matrix3f n = pose.normal();
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(TEX));
        float hw = 0.5f, h = 1.0f;
        v(vc, m, n, -hw, 0, 0, 1, light); v(vc, m, n, hw, 0, 1, 1, light); v(vc, m, n, hw, h, 1, 0, light); v(vc, m, n, -hw, h, 0, 0, light);
        ps.popPose();
        super.render(e, yaw, pt, ps, buf, light);
    }

    private static void v(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y, float u, float v, int light) {
        vc.vertex(m, x, y, 0).color(255, 255, 255, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, 1, 0).endVertex();
    }
}
