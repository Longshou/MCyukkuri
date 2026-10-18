package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.headwear.HatDamageGeometry;
import java.io.IOException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Turns the approved accessory damage recipe into square face cells.
 *
 * <p>Each two-triangle box face becomes a regular grid of square-ish cells.  Front/back/top/bottom
 * faces use that grid; thin x-facing side faces stay whole.  A removed cell gets a close-colour
 * inner cap (green stays green, red stays red, black becomes a lighter grey-lavender, pale cloth
 * becomes a darker version of itself) and its neighbour gets a same-hue rim.  This helper is
 * shared by the worn hat (YukkuriModel) and detached accessory items (PropModels).
 */
final class HatDamageFaces {
    static final ResourceLocation INNER_TEXTURE = MCYukkuri.id("textures/props/hat_damage_inner.png");
    private static final byte[] NO_CODES = new byte[0];
    private static final int[] NO_COLORS = new int[0];

    /** {@code corners == null} means draw the original triangle pair; otherwise draw the grid. */
    record Face(int originalStart, int originalCount, int[] corners, int n, int m,
                byte[] codes, int[] cap, int[] rim, boolean flip) {
        boolean original() { return corners == null; }
    }

    /** Small RGBA sampler copied out of a NativeImage so the render thread never touches the image. */
    record Sampler(int width, int height, int[] pixels) {
        int[] sample(float u, float v) {
            if (width <= 0 || height <= 0) return new int[]{200, 200, 200};
            int x = Math.max(0, Math.min(width - 1, (int) (Math.max(0f, Math.min(.9999f, u)) * width)));
            int y = Math.max(0, Math.min(height - 1, (int) (Math.max(0f, Math.min(.9999f, v)) * height)));
            int p = pixels[y * width + x];
            if (((p >>> 24) & 255) < 20) return new int[]{200, 200, 200};
            return new int[]{p & 255, (p >>> 8) & 255, (p >>> 16) & 255};
        }
    }

    static Sampler load(ResourceManager resources, ResourceLocation texture) throws IOException {
        try (var stream = resources.getResourceOrThrow(texture).open(); var image = NativeImage.read(stream)) {
            int w = image.getWidth(), h = image.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pixels[y * w + x] = image.getPixelRGBA(x, y);
            return new Sampler(w, h, pixels);
        }
    }

    static Face[] build(float[] v,int start,int count,String part,float[] bounds,int level,Sampler sampler){
        var layouts=HatDamageGeometry.layout(v,start,count,part,bounds,level,HatDamageMask.pattern());
        var faces=new Face[layouts.length];
        for(int k=0;k<layouts.length;k++){
            var layout=layouts[k];
            if(layout.original()){faces[k]=new Face(layout.originalStart(),layout.originalCount(),null,0,0,NO_CODES,NO_COLORS,NO_COLORS,false);continue;}
            var corners=layout.corners();int n=layout.n(),m=layout.m();var cap=new int[n*m];var rim=new int[n*m];
            float[] uv0=uv(v,corners[0]),uv1=uv(v,corners[1]),uv2=uv(v,corners[2]),uv3=uv(v,corners[3]);
            for(int i=0;i<n;i++)for(int j=0;j<m;j++){
                float u=(i+.5f)/n,w=(j+.5f)/m;
                float su=bilerp(uv0[0],uv1[0],uv2[0],uv3[0],u,w),sv=bilerp(uv0[1],uv1[1],uv2[1],uv3[1],u,w);
                int[] color=sampler==null?new int[]{200,200,200}:sampler.sample(su,sv);var colors=capRim(color[0],color[1],color[2]);
                cap[i*m+j]=colors[0];rim[i*m+j]=colors[1];
            }
            faces[k]=new Face(-1,0,corners,n,m,layout.codes(),cap,rim,layout.flip());
        }
        return faces;
    }

    /** {packed cap, packed rim} using the local cloth colour. */
    private static int[] capRim(float r, float g, float b) {
        float luma = (r + g + b) / 3f;
        float cr, cg, cb, rr, rg, rb;
        if (luma < 70f) {
            cr = r * 2f; cg = g * 2f; cb = b * 2f;
            rr = r * 1.2f; rg = g * 1.2f; rb = b * 1.2f;
        } else if (luma < 170f) {
            cr = r * 1.25f; cg = g * 1.25f; cb = b * 1.25f;
            rr = r * .62f; rg = g * .62f; rb = b * .62f;
        } else {
            float mean = (r + g + b) / 3f;
            float tr = mean + (r - mean) * 2.2f, tg = mean + (g - mean) * 2.2f, tb = mean + (b - mean) * 2.2f;
            cr = tr * .78f; cg = tg * .78f; cb = tb * .78f;
            rr = tr * .55f; rg = tg * .55f; rb = tb * .55f;
        }
        return new int[]{pack(cr, cg, cb), pack(rr, rg, rb)};
    }

    private static int pack(float r, float g, float b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(float value) { return Math.max(0, Math.min(255, Math.round(value))); }
    private static float[] uv(float[] v, int index) { return new float[]{v[index * 8 + 3], v[index * 8 + 4]}; }
    private static float bilerp(float a, float b, float c, float d, float u, float v) {
        return a + (b - a) * u + (d - a) * v + (a - b + c - d) * u * v;
    }

    private HatDamageFaces() {}
}
