package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullProfile;
import java.util.ArrayList;

/** Closed mesh of the exported bottom, dry top and rim. Plane clipping works at every attitude, including vertical. */
final class HullShell {
    final double[] vertices;
    final int[] faces,cells,rim;
    final double rimDepth;
    final Buoyancy.Load cloth;

    HullShell(HullProfile profile) {
        var data=profile.data();var angles=data.getAsJsonArray("azimuths");var outer=data.getAsJsonArray("outer");
        var source=new GeometryJson(profile.hull(),data);
        source.check(outer.size()==angles.size(),"outer","ray count mismatch");
        var points=new ArrayList<Vec>();var triangles=new ArrayList<Integer>();var columns=new ArrayList<Integer>();
        double unit=profile.measuredUnit(),keel=data.getAsJsonObject("keel").get("y").getAsDouble();
        int[][] rays=new int[outer.size()][];double[][] radii=new double[outer.size()][];rim=new int[outer.size()];
        double weight=0;var material=Buoyancy.Load.ZERO;
        for(int i=0;i<rays.length;i++) {
            var row=outer.get(i).getAsJsonArray();double angle=Math.toRadians(angles.get(i).getAsDouble());
            source.check(row.size()>1,"outer","expected radial samples");
            rays[i]=new int[row.size()];radii[i]=new double[row.size()];
            for(int j=0;j<row.size();j++) {
                var v=row.get(j).getAsJsonArray();source.check(v.size()==3,"outer","expected radius, bottom, top");
                double r=v.get(0).getAsDouble()/unit,b=(v.get(1).getAsDouble()-keel)/unit,t=(v.get(2).getAsDouble()-keel)/unit;
                source.check(Double.isFinite(r)&&Double.isFinite(b)&&Double.isFinite(t)&&t>=b,"outer","invalid shell column");
                source.check(j==0?r==0:r>=radii[i][j-1],"outer","radii must start at zero and not decrease");
                rays[i][j]=j==0&&i>0?0:points.size();radii[i][j]=r;
                if(j==0&&i>0)continue;
                points.add(new Vec(r*Math.cos(angle),b,r*Math.sin(angle)));points.add(new Vec(r*Math.cos(angle),t,r*Math.sin(angle)));
            }
        }
        for(int i=0;i<rays.length;i++) {
            int k=(i+1)%rays.length,a=0,b=0;
            while(a+1<rays[i].length||b+1<rays[k].length) {
                boolean advance=b+1==rays[k].length||a+1<rays[i].length&&radii[i][a+1]/radii[i][radii[i].length-1]<radii[k][b+1]/radii[k][radii[k].length-1];
                int x=rays[i][a],y=advance?rays[i][++a]:rays[k][++b],z=advance?rays[k][b]:rays[k][b-1];
                if(x==y||y==z||z==x)continue;
                face(columns,x,y,z);face(triangles,x,y,z);face(triangles,z+1,y+1,x+1);
                var p=points.get(x);var q=points.get(y);var r=points.get(z);
                double area=Math.abs((q.x()-p.x())*(r.z()-p.z())-(q.z()-p.z())*(r.x()-p.x()))/2;
                material=material.add(area,p.add(q).add(r).scale(1.0/3));weight+=area;
            }
            int x=rays[i][a],y=rays[k][b];rim[i]=x+1;face(triangles,y,x,x+1);face(triangles,y,x+1,y+1);
        }
        double mass=data.getAsJsonObject("volume").get("hull_mass").getAsDouble()/(unit*unit);
        cloth=new Buoyancy.Load(mass,material.moment().scale(mass/weight),material.rollInertia()*mass/weight,material.pitchInertia()*mass/weight);
        rimDepth=profile.rimY();vertices=new double[points.size()*3];
        for(int i=0;i<points.size();i++){var p=points.get(i);vertices[3*i]=p.x();vertices[3*i+1]=p.y();vertices[3*i+2]=p.z();}
        faces=triangles.stream().mapToInt(Integer::intValue).toArray();cells=columns.stream().mapToInt(Integer::intValue).toArray();
    }
    private static void face(ArrayList<Integer> faces,int a,int b,int c){faces.add(a);faces.add(b);faces.add(c);}

    /** Reusable workspace, owned by one thread; the renderer consumes its triangles without constructing a polygon. */
    static final class Slice {
        final HullShell shell;
        final double[] xyz,px=new double[4],py=new double[4],pz=new double[4];
        double unit,roll=Double.NaN,pitch,draft,low,high,volume,mx,my,mz,area,ax,az,xx,zz,xz;
        Slice(HullShell shell) {
            this.shell=shell;xyz=new double[shell.vertices.length];
        }
        void pose(double unit,double roll,double pitch) {
            if(this.unit==unit&&this.roll==roll&&this.pitch==pitch)return;
            this.unit=unit;this.roll=roll;this.pitch=pitch;
            double r=Math.toRadians(roll),p=Math.toRadians(pitch),sr=Math.sin(r),cr=Math.cos(r),sp=Math.sin(p),cp=Math.cos(p);
            low=Double.POSITIVE_INFINITY;high=Double.NEGATIVE_INFINITY;
            for(int i=0;i<xyz.length;i+=3) {
                double a=shell.vertices[i]*unit,b=shell.vertices[i+1]*unit,c=shell.vertices[i+2]*unit,y=b*cp-c*sp;
                xyz[i]=a*cr-y*sr;xyz[i+1]=a*sr+y*cr;xyz[i+2]=b*sp+c*cp;
                low=Math.min(low,xyz[i+1]);high=Math.max(high,xyz[i+1]);
            }
        }
        void cut(double draft) {
            this.draft=draft;volume=mx=my=mz=area=ax=az=xx=zz=xz=0;
            for(int f=0;f<shell.faces.length;f+=3) {
                int n=0;double lx=0,lz=0,ex=0,ez=0;boolean crossing=false;
                for(int j=0;j<3;j++) {
                    int a=shell.faces[f+j],b=shell.faces[f+(j+1)%3],ia=3*a,ib=3*b;
                    double ay=xyz[ia+1]-draft,by=xyz[ib+1]-draft;
                    if(ay<0){px[n]=xyz[ia];py[n]=ay;pz[n++]=xyz[ia+2];}
                    if((ay<0)!=(by<0)) {
                        double t=ay/(ay-by),cx=xyz[ia]+t*(xyz[ib]-xyz[ia]),cz=xyz[ia+2]+t*(xyz[ib+2]-xyz[ia+2]);
                        px[n]=cx;py[n]=0;pz[n++]=cz;
                        crossing=true;
                        if(ay<0){lx=cx;lz=cz;}else{ex=cx;ez=cz;}
                    }
                }
                for(int i=1;i+1<n;i++) {
                    double v=(px[0]*(py[i]*pz[i+1]-pz[i]*py[i+1])-py[0]*(px[i]*pz[i+1]-pz[i]*px[i+1])+pz[0]*(px[i]*py[i+1]-py[i]*px[i+1]))/6;
                    volume+=v;mx+=v*(px[0]+px[i]+px[i+1])/4;my+=v*(py[0]+py[i]+py[i+1])/4;mz+=v*(pz[0]+pz[i]+pz[i+1])/4;
                }
                if(crossing) {
                    double a=lx,b=lz,c=ex,d=ez,cross=a*d-c*b;
                    area+=cross/2;ax+=cross*(a+c)/6;az+=cross*(b+d)/6;
                    xx+=cross*(a*a+a*c+c*c)/12;zz+=cross*(b*b+b*d+d*d)/12;xz+=cross*(2*a*b+a*d+c*b+2*c*d)/24;
                }
            }
            my+=volume*draft;
        }
    }
}
