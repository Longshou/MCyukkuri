package io.github.longshou.mcyukkuri.body;

import java.util.List;
import org.joml.Vector3f;

/** Rest-surface attachments used by the game and the pose player. */
public record SurfaceMark(WoundSurface.Hit a,WoundSurface.Hit b,WoundSurface.Hit c,int material,float offset,float[] uv) {
    public static final int LEAK_WET_MATERIAL=45;
    public static final String LEAK_WET_TEXTURE="textures/props/body_wounds/leak_wet.png";

    public static void soleTexture(WoundSurface mesh,int material,List<SurfaceMark> marks){
        var bounds=mesh.bounds();float cx=(bounds[0]+bounds[3])*.5f,cz=(bounds[2]+bounds[5])*.5f;
        float halfWidth=Math.max(.001f,(bounds[3]-bounds[0])*.5f),halfDepth=Math.max(.001f,(bounds[5]-bounds[2])*.5f),height=Math.max(.001f,bounds[4]-bounds[1]);
        for(int i=0;i<mesh.skinCount;i+=3){
            float[] uv=new float[6];float minU=1,maxU=0,minY=Float.MAX_VALUE;
            for(int j=0;j<3;j++){var p=mesh.rest(i+j);float y=(p.y-bounds[1])/height*9.6f;minY=Math.min(minY,y);
                float u=(float)((Math.atan2((p.x-cx)/halfWidth,-(p.z-cz)/halfDepth)+Math.PI)/(2*Math.PI));
                uv[j*2]=u;uv[j*2+1]=Math.clamp((2-y)/2.5f,0,1);minU=Math.min(minU,u);maxU=Math.max(maxU,u);}
            if(minY>2)continue;
            if(maxU-minU>.5f)for(int j=0;j<3;j++)if(uv[j*2]<.5f)uv[j*2]+=1;
            marks.add(new SurfaceMark(new WoundSurface.Hit(i,0,0,0),new WoundSurface.Hit(i,1,0,0),new WoundSurface.Hit(i,0,1,0),material,.027f,uv));
        }
    }
    public static void supineWetness(WoundSurface mesh,List<SurfaceMark> marks){
        var bounds=mesh.bounds();
        for(int i=0;i<mesh.skinCount;i+=3){
            float[] uv=new float[6];float wettest=0;
            for(int j=0;j<3;j++){
                var p=mesh.rest(i+j);
                float down=(p.z-bounds[2])/(bounds[5]-bounds[2]);
                float fromSole=(p.y-bounds[1])/(bounds[4]-bounds[1]);
                uv[j*2]=(p.x-bounds[0])/(bounds[3]-bounds[0]);
                // The texture is transparent above .56 and below .82. After the quarter-turn, +Z is down.
                uv[j*2+1]=Math.max(0,.54f+.27f*down-.55f*fromSole);
                wettest=Math.max(wettest,uv[j*2+1]);
            }
            if(wettest<.56f)continue;
            marks.add(new SurfaceMark(new WoundSurface.Hit(i,0,0,0),new WoundSurface.Hit(i,1,0,0),
                    new WoundSurface.Hit(i,0,1,0),LEAK_WET_MATERIAL,.027f,uv));
        }
    }

    /** Shared normals across coincident skin corners, preserving hard edges. */
    public record NormalKey(int x,int y,int z,int nx,int ny,int nz) {
        public static NormalKey of(Vector3f point,Vector3f normal){
            return new NormalKey(Math.round(point.x*100000),Math.round(point.y*100000),Math.round(point.z*100000),
                    Math.round(normal.x*1000),Math.round(normal.y*1000),Math.round(normal.z*1000));
        }
    }

    public static void smoothNormals(Vector3f[] posed,int[] triangles,int[] slots,Vector3f[] normals,Vector3f normal,Vector3f edge){
        for(var n:normals)n.zero();
        for(int i:triangles){var n=normal.set(posed[i+1]).sub(posed[i]).cross(edge.set(posed[i+2]).sub(posed[i]));
            for(int j=0;j<3;j++){int slot=slots[i+j];if(slot>=0)normals[slot].add(n);}}
        for(var n:normals){if(n.lengthSquared()>1e-12)n.normalize();else n.set(0,0,-1);}
    }

    public static void point(Vector3f[] posed,WoundSurface.Hit h,float offset,Vector3f normal,Vector3f out){
        out.set(posed[h.triangle()]).mul(1-h.u()-h.v()).fma(h.u(),posed[h.triangle()+1]).fma(h.v(),posed[h.triangle()+2]).fma(offset,normal);
    }
    public static void pointNormal(Vector3f[] normals,int[] slots,WoundSurface.Hit h,Vector3f out){
        out.set(normals[slots[h.triangle()]]).mul(1-h.u()-h.v()).fma(h.u(),normals[slots[h.triangle()+1]])
                .fma(h.v(),normals[slots[h.triangle()+2]]).normalize();
    }
}
