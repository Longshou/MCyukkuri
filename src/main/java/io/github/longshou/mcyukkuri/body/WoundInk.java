package io.github.longshou.mcyukkuri.body;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/** Authored wound ink clipped to the outward skin and hair triangles. */
public final class WoundInk {
    public static final String TEXTURE_DIR="textures/wounds/hair/";

    public static String material(BodyWound wound){
        return wound.kind()==WoundKind.SCAR?"scar":material(wound.shape(),wound.severity());
    }

    /** Festering shows on mechanical injuries only; scars and burns have no infected art. */
    public static boolean infectable(BodyWound wound){return wound.kind().injury()&&!wound.shape().thermal();}

    public static String material(BodyWound wound,boolean infected){
        return infected&&infectable(wound)?material(wound)+"-infected":material(wound);
    }

    public static String material(WoundShape shape,int severity){
        return shape.id()+"-"+severity;
    }

    public static class Plane {
        public final Vector3f anchor,n,u,v,center;
        public final float radiusU,radiusV,scale,vertical;
        private final float[][] box;

        public Plane(WoundSurface mesh,BodyWound wound,Vector3f fitScale,Vector3f fitOffset,
                     float profileU,float profileV,float[] coreRadius){
            anchor=mesh.anchor(wound);n=mesh.normal(wound.triangle());u=WoundSurface.tangent(n);v=new Vector3f(n).cross(u);
            center=new Vector3f(0,4.5f,.5f).mul(fitScale).add(fitOffset);
            float size=decalSize(wound);
            float openingScale=wound.shape()==WoundShape.BLUNT?.76f:1;
            vertical=WoundProfiles.verticalScale(mesh.assetId,n);
            scale=new Vector3f(n).mul(fitScale).length();
            radiusU=coreRadius==null?profileU*size*new Vector3f(u).mul(fitScale).length():coreRadius[0]/openingScale;
            radiusV=coreRadius==null?profileV*size*new Vector3f(v).mul(fitScale).length()*vertical:coreRadius[1]/openingScale;
            box=new float[][]{{-radiusU,-radiusV},{radiusU,-radiusV},{radiusU,radiusV},{-radiusU,radiusV}};
        }

        private static float project(Vector3f point,Vector3f origin,Vector3f axis){
            return axis.dot(point.x-origin.x,point.y-origin.y,point.z-origin.z);
        }
        public float x(Vector3f point){return project(point,anchor,u);}
        public float y(Vector3f point){return project(point,anchor,v);}
        public float u(Vector3f point){return .5f+x(point)/(2*radiusU);}
        public float v(Vector3f point){return .5f-y(point)/(2*radiusV);}
        public boolean front(Vector3f point){return project(point,center,n)>.15f*scale;}
        public boolean facing(Vector3f a,Vector3f b,Vector3f c){
            var normal=new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            return !(normal.lengthSquared()<1e-12||normal.normalize().dot(n)<.3f);
        }
        public boolean bounds(Vector3f a,Vector3f b,Vector3f c,float extra,boolean coreTunnel){
            float ax=x(a),bx=x(b),cx=x(c),ay=y(a),by=y(b),cy=y(c);
            return Math.min(ax,Math.min(bx,cx))<=radiusU*extra&&Math.max(ax,Math.max(bx,cx))>=-radiusU*extra
                    &&Math.min(ay,Math.min(by,cy))<=radiusV*extra&&Math.max(ay,Math.max(by,cy))>=-radiusV*extra
                    &&(coreTunnel||nearTriangle(a,b,c));
        }
        private boolean nearTriangle(Vector3f a,Vector3f b,Vector3f c){
            if(!(front(a)||front(b)||front(c)))return false;
            float za=project(a,anchor,n),zb=project(b,anchor,n),zc=project(c,anchor,n);
            return Math.min(za,Math.min(zb,zc))<2.5f*scale&&Math.max(za,Math.max(zb,zc))>-2.5f*scale;
        }
        public List<Bary[]> ink(List<Piece> polygon){
            for(int i=0;i<4;i++)polygon=clip(polygon,box[i],box[(i+1)%4],true);
            return fan(polygon);
        }
    }

    public static float decalSize(BodyWound wound){
        return wound.kind()==WoundKind.SCAR?Math.min(wound.size(),1):wound.size();
    }

    public static void lift(Vector3f a,Vector3f b,Vector3f c,Vector3f normal,boolean offset,float firstU,float inset){
        float lift=(offset&&firstU!=.5f?.006f:0)+inset;
        if(lift!=0){a.fma(lift,normal);b.fma(lift,normal);c.fma(lift,normal);}
    }

    public record Bary(float a,float b,float c) {
        Bary mix(Bary other,float t){return new Bary(a+(other.a-a)*t,b+(other.b-b)*t,c+(other.c-c)*t);}
        public Vector3f point(float[] source,int first){
            return point(source,first,new Vector3f());
        }
        public Vector3f point(float[] source,int first,Vector3f out){
            int offset=first*8;
            return out.set(coordinate(source,offset),coordinate(source,offset+1),coordinate(source,offset+2));
        }
        private float coordinate(float[] source,int offset){
            return org.joml.Math.fma(c,source[offset+16],org.joml.Math.fma(b,source[offset+8],source[offset]*a));
        }
    }
    public record Piece(float x,float y,Bary bary) {
        Piece mix(Piece other,float t){return new Piece(x+(other.x-x)*t,y+(other.y-y)*t,bary.mix(other.bary,t));}
    }
    public static final Bary A=new Bary(1,0,0),B=new Bary(0,1,0),C=new Bary(0,0,1);

    private static float side(float[] a,float[] b,Piece p){return (b[0]-a[0])*(p.y-a[1])-(b[1]-a[1])*(p.x-a[0]);}
    public static List<Piece> clip(List<Piece> source,float[] a,float[] b,boolean inside){
        var result=new ArrayList<Piece>();if(source.isEmpty())return result;
        var previous=source.getLast();float before=side(a,b,previous);boolean prevIn=inside?before>=0:before<=0;
        for(var current:source){float next=side(a,b,current);boolean currentIn=inside?next>=0:next<=0;
            if(prevIn!=currentIn)result.add(previous.mix(current,Math.clamp(before/(before-next),0,1)));
            if(currentIn)result.add(current);previous=current;before=next;prevIn=currentIn;
        }return result;
    }
    public static List<Bary[]> fan(List<Piece> polygon){var result=new ArrayList<Bary[]>();for(int j=1;j+1<polygon.size();j++){
        var a=polygon.getFirst();var b=polygon.get(j);var c=polygon.get(j+1);
        if(Math.abs((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x))>1e-9)result.add(new Bary[]{a.bary,b.bary,c.bary});
    }return result;}

    private WoundInk() {}
}
