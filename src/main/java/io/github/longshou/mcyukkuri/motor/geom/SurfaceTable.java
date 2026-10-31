package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayList;
import java.util.List;

/** Immutable spatial index of upward cloth triangles; probes allocate nothing and preserve sharp steps. */
public final class SurfaceTable {
    private static final double CELL=.5;
    private final Triangle[][] cells;
    private final List<Triangle> triangles;
    private final double radius;
    private final int side;
    public SurfaceTable(GeometryJson source,String field,double unit,double keel) {
        var values=source.field(field);
        source.check(values.isJsonArray()&&!values.getAsJsonArray().isEmpty(),field,"expected triangles");
        triangles=new ArrayList<>();double extent=0;int index=0;
        for(var value:values.getAsJsonArray()){
            String path=field+"["+index+++"]";
            source.check(value.isJsonArray()&&value.getAsJsonArray().size()==3,path,"expected three vertices");
            double[] t=new double[9];int n=0;
            for(var vertex:value.getAsJsonArray()){
                source.check(vertex.isJsonArray()&&vertex.getAsJsonArray().size()==3,path,"expected xyz");
                for(var v:vertex.getAsJsonArray()){
                    source.check(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isNumber()&&Double.isFinite(v.getAsDouble()),path,"expected finite coordinates");
                    t[n]=(v.getAsDouble()-(n%3==1?keel:0))/unit;
                    if(n%3!=1)extent=Math.max(extent,Math.abs(t[n]));n++;
                }
            }
            var triangle=new Triangle(t);
            source.check(Math.abs(triangle.det)>1e-12,path,"vertical/degenerate support face");triangles.add(triangle);
        }
        radius=extent+CELL;side=(int)Math.ceil(2*radius/CELL)+1;
        var bins=new ArrayList<ArrayList<Triangle>>(side*side);
        for(int i=0;i<side*side;i++)bins.add(new ArrayList<>());
        for(var triangle:triangles){
            var t=triangle.t;
            int x0=cell(Math.min(t[0],Math.min(t[3],t[6]))),x1=cell(Math.max(t[0],Math.max(t[3],t[6])));
            int z0=cell(Math.min(t[2],Math.min(t[5],t[8]))),z1=cell(Math.max(t[2],Math.max(t[5],t[8])));
            for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)bins.get(x*side+z).add(triangle);
        }
        cells=bins.stream().map(bin->bin.toArray(Triangle[]::new)).toArray(Triangle[][]::new);
    }
    private int cell(double x){return (int)Math.floor((x+radius)/CELL);}
    /** Exact edge on a bearing, computed once per profile; no inward epsilon or rounded radial sample. */
    public Vec rim(double angle){
        double dx=Math.cos(angle),dz=Math.sin(angle),reach=Double.NEGATIVE_INFINITY,y=Double.NaN;
        for(var triangle:triangles)for(int i=0;i<3;i++){
            var v=triangle.t;int a=i*3,b=(i+1)%3*3;
            double ex=v[b]-v[a],ez=v[b+2]-v[a+2],cross=ex*dz-ez*dx;
            if(Math.abs(cross)<1e-12)continue;
            double t=(v[a+2]*dx-v[a]*dz)/cross;
            if(t<0||t>1)continue;
            double r=(v[a]+t*ex)*dx+(v[a+2]+t*ez)*dz;
            if(r>reach){reach=r;y=v[a+1]+t*(v[b+1]-v[a+1]);}
        }
        return new Vec(dx*reach,y,dz*reach);
    }
    public double height(double x,double z){
        int ix=cell(x),iz=cell(z);
        if(ix<0||iz<0||ix>=side||iz>=side)return Double.NaN;
        double y=Double.NEGATIVE_INFINITY;
        for(var t:cells[ix*side+iz])y=Math.max(y,t.height(x,z));
        return y==Double.NEGATIVE_INFINITY?Double.NaN:y;
    }
    private static final class Triangle {
        final double[] t;final double det;
        Triangle(double[] t){this.t=t;det=(t[5]-t[8])*(t[0]-t[6])+(t[6]-t[3])*(t[2]-t[8]);}
        double height(double x,double z){
            double a=((t[5]-t[8])*(x-t[6])+(t[6]-t[3])*(z-t[8]))/det,b=((t[8]-t[2])*(x-t[6])+(t[0]-t[6])*(z-t[8]))/det;
            return a>=-1e-4&&b>=-1e-4&&a+b<=1.0001?a*t[1]+b*t[4]+(1-a-b)*t[7]:Double.NEGATIVE_INFINITY;
        }
    }
}
