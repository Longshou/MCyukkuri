package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.joml.Vector3f;

/** Project expression panels onto the actual posed belly triangles, including their creases. */
final class PregnancyFaces {
    static final class Surface {
        private static final float FIT_EPSILON=.0001f,CELL_SIZE=4f;
        private static final int RETAINED_CELL_LIMIT=2048,MAX_CELLS=4096;
        private final ArrayList<Triangle> triangles=new ArrayList<>();
        private final Map<Long,Bucket> cells=new HashMap<>();
        private float[] levels=new float[64],bands=new float[16];
        private int triangleCount,levelCount,generation;
        private boolean finished,indexed;

        /** Reuse the model-owned containers for the current pose. */
        Surface begin(){
            triangleCount=0;levelCount=0;finished=false;indexed=true;
            if(cells.size()>RETAINED_CELL_LIMIT)cells.clear();
            if(++generation==0){cells.clear();generation=1;}
            return this;
        }

        void add(Vector3f[] points){
            float ax=points[1].x-points[0].x,ay=points[1].y-points[0].y;
            float bx=points[2].x-points[0].x,by=points[2].y-points[0].y;
            if(ax*by-ay*bx>=-.00001f)return;
            Triangle triangle=triangleCount<triangles.size()?triangles.get(triangleCount):new Triangle();
            if(triangleCount==triangles.size())triangles.add(triangle);
            triangle.set(points,ax,ay,bx,by);int index=triangleCount++;
            for(int i=0;i<3;i++)addLevel(points[i].y);
            if(indexed)for(int x=triangle.minCellX;x<=triangle.maxCellX&&indexed;x++)for(int y=triangle.minCellY;y<=triangle.maxCellY;y++){
                if(!addToCell(x,y,index)){indexed=false;break;}
            }
        }

        /** Sort the posed body seams once before all expression triangles use them. */
        void finish(){
            if(finished)return;
            Arrays.sort(levels,0,levelCount);
            int unique=0;
            for(int i=0;i<levelCount;i++)if(unique==0||Float.compare(levels[i],levels[unique-1])!=0)levels[unique++]=levels[i];
            levelCount=unique;finished=true;
        }

        int bands(float low,float high){
            finish();
            int first=firstGreater(low),last=firstAtLeast(high),count=last-first+2;
            if(bands.length<count)bands=Arrays.copyOf(bands,Integer.highestOneBit(count-1)<<1);
            bands[0]=low;
            if(last>first)System.arraycopy(levels,first,bands,1,last-first);
            bands[count-1]=high;
            return count;
        }

        float band(int index){return bands[index];}

        void fit(Vector3f point){
            float front=Float.POSITIVE_INFINITY;
            if(indexed){
                Bucket bucket=cells.get(cellKey(cell(point.x),cell(point.y)));
                if(bucket!=null&&bucket.generation==generation)for(int i=0;i<bucket.count;i++)front=fit(point,triangles.get(bucket.indices[i]),front);
            }else for(int i=0;i<triangleCount;i++)front=fit(point,triangles.get(i),front);
            if(Float.isFinite(front))point.z=Math.min(point.z,front-.035f);
        }

        private float fit(Vector3f point,Triangle triangle,float front){
            if(Math.abs(triangle.det)>=1e-8f){
                float x=point.x-triangle.x,y=point.y-triangle.y;
                float u=(x*triangle.by-y*triangle.bx)/triangle.det,v=(triangle.ax*y-triangle.ay*x)/triangle.det;
                if(u>=-FIT_EPSILON&&v>=-FIT_EPSILON&&u+v<=1.0001f)
                    return Math.min(front,triangle.z+u*triangle.az+v*triangle.bz);
            }
            return front;
        }

        private void addLevel(float level){
            if(levelCount==levels.length)levels=Arrays.copyOf(levels,levels.length*2);
            levels[levelCount++]=level;
        }

        private int firstGreater(float value){
            int low=0,high=levelCount;
            while(low<high){int middle=(low+high)>>>1;if(Float.compare(levels[middle],value)<=0)low=middle+1;else high=middle;}
            return low;
        }

        private int firstAtLeast(float value){
            int low=0,high=levelCount;
            while(low<high){int middle=(low+high)>>>1;if(Float.compare(levels[middle],value)<0)low=middle+1;else high=middle;}
            return low;
        }

        private boolean addToCell(int x,int y,int triangle){
            long key=cellKey(x,y);Bucket bucket=cells.get(key);
            if(bucket==null&&cells.size()>=MAX_CELLS)return false;
            if(bucket==null){bucket=new Bucket();cells.put(key,bucket);}
            if(bucket.generation!=generation){bucket.generation=generation;bucket.count=0;}
            bucket.add(triangle);return true;
        }

        private static int cell(float value){return (int)Math.floor(value/CELL_SIZE);}
        private static long cellKey(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}

        private static final class Bucket {
            private int[] indices=new int[8];
            private int count,generation;
            void add(int index){
                if(count==indices.length)indices=Arrays.copyOf(indices,indices.length*2);
                indices[count++]=index;
            }
        }

        private static final class Triangle {
            private float x,y,z,ax,ay,bx,by,az,bz,det;
            private int minCellX,maxCellX,minCellY,maxCellY;
            void set(Vector3f[] points,float ax,float ay,float bx,float by){
                x=points[0].x;y=points[0].y;z=points[0].z;
                this.ax=ax;this.ay=ay;this.bx=bx;this.by=by;
                az=points[1].z-z;bz=points[2].z-z;det=ax*by-ay*bx;
                float minX=Math.min(x,Math.min(points[1].x,points[2].x));
                float maxX=Math.max(x,Math.max(points[1].x,points[2].x));
                float minY=Math.min(y,Math.min(points[1].y,points[2].y));
                float maxY=Math.max(y,Math.max(points[1].y,points[2].y));
                float marginX=2*FIT_EPSILON*(Math.abs(ax)+Math.abs(bx));
                float marginY=2*FIT_EPSILON*(Math.abs(ay)+Math.abs(by));
                minCellX=cell(minX-marginX);maxCellX=cell(maxX+marginX);
                minCellY=cell(minY-marginY);maxCellY=cell(maxY+marginY);
            }
        }
    }

    static void render(Vector3f[] points,float[] uv,float belly,float contraction,LiveBirthRenderer.Delivery delivery,
                       PoseStack stack,VertexConsumer buffer,int light,int overlay,Surface surface){
        render(points,uv,belly,contraction,delivery,stack,buffer,light,overlay,surface,255,255,255,1,1,1);
    }
    static void render(Vector3f[] points,float[] uv,float belly,float contraction,LiveBirthRenderer.Delivery delivery,
                       PoseStack stack,VertexConsumer buffer,int light,int overlay,Surface surface,
                       int red,int green,int blue,float alphaA,float alphaB,float alphaC){
        Scratch scratch=SCRATCH.get();float low=Float.MAX_VALUE,high=-Float.MAX_VALUE;
        for(int i=0;i<3;i++){
            Vertex vertex=scratch.source[i];vertex.set(points[i],uv[i*2],uv[i*2+1]);vertex.alpha=i==0?alphaA:i==1?alphaB:alphaC;LiveBirthGeometry.deform(vertex.point,belly,contraction);
            low=Math.min(low,vertex.point.y);high=Math.max(high,vertex.point.y);
        }
        int bands=surface.bands(low,high);
        for(int band=0;band+1<bands;band++){
            int lower=clip(scratch.source,3,surface.band(band),true,scratch.lower);
            int strip=clip(scratch.lower,lower,surface.band(band+1),false,scratch.upper);
            for(int i=0;i<strip;i++)surface.fit(scratch.upper[i].point);
            for(int i=1;i+1<strip;i++)draw(scratch,scratch.upper[0],scratch.upper[i],scratch.upper[i+1],delivery,stack,buffer,light,overlay,red,green,blue);
        }
    }

    private static void draw(Scratch scratch,Vertex first,Vertex second,Vertex third,LiveBirthRenderer.Delivery delivery,
                             PoseStack stack,VertexConsumer buffer,int light,int overlay,int red,int green,int blue){
        scratch.points[0].set(first.point);scratch.points[1].set(second.point);scratch.points[2].set(third.point);
        scratch.uv[0]=first.u;scratch.uv[1]=first.v;scratch.uv[2]=second.u;scratch.uv[3]=second.v;scratch.uv[4]=third.u;scratch.uv[5]=third.v;
        if(delivery!=null&&delivery.drawMotherTriangle(stack,buffer,light,overlay,scratch.points,scratch.uv,red,green,blue,first.alpha,second.alpha,third.alpha))return;
        scratch.normal.set(scratch.points[1]).sub(scratch.points[0]).cross(scratch.edge.set(scratch.points[2]).sub(scratch.points[0]));
        if(scratch.normal.lengthSquared()<1e-12)return;scratch.normal.normalize();
        for(int i=0;i<4;i++){
            int index=Math.min(i,2);Vector3f point=scratch.points[index];
            float alpha=index==0?first.alpha:index==1?second.alpha:third.alpha;
            buffer.addVertex(stack.last().pose(),point.x/16,point.y/16,point.z/16).setColor(red,green,blue,Math.round(Math.clamp(alpha,0,1)*255))
                    .setUv(scratch.uv[index*2],scratch.uv[index*2+1]).setOverlay(overlay).setLight(light)
                    .setNormal(stack.last(),scratch.normal.x,scratch.normal.y,scratch.normal.z);
        }
    }

    private static int clip(Vertex[] input,int inputCount,float boundary,boolean above,Vertex[] output){
        if(inputCount==0)return 0;
        int count=0;Vertex previous=input[inputCount-1];float previousDistance=(previous.point.y-boundary)*(above?1:-1);
        for(int i=0;i<inputCount;i++){
            Vertex current=input[i];float currentDistance=(current.point.y-boundary)*(above?1:-1);
            if((previousDistance>=0)!=(currentDistance>=0))output[count++].mix(previous,current,previousDistance/(previousDistance-currentDistance));
            if(currentDistance>=0)output[count++].set(current);
            previous=current;previousDistance=currentDistance;
        }
        return count;
    }

    private static final ThreadLocal<Scratch> SCRATCH=ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        private final Vertex[] source=vertices(),lower=vertices(),upper=vertices();
        private final Vector3f[] points={new Vector3f(),new Vector3f(),new Vector3f()};
        private final float[] uv=new float[6];
        private final Vector3f normal=new Vector3f(),edge=new Vector3f();
        private static Vertex[] vertices(){
            Vertex[] result=new Vertex[6];for(int i=0;i<result.length;i++)result[i]=new Vertex();return result;
        }
    }

    private static final class Vertex {
        private final Vector3f point=new Vector3f();
        private float u,v,alpha=1;
        void set(Vector3f source,float u,float v){point.set(source);this.u=u;this.v=v;alpha=1;}
        void set(Vertex source){set(source.point,source.u,source.v);alpha=source.alpha;}
        void mix(Vertex first,Vertex second,float amount){
            point.set(first.point).lerp(second.point,amount);
            u=first.u+(second.u-first.u)*amount;v=first.v+(second.v-first.v)*amount;
            alpha=first.alpha+(second.alpha-first.alpha)*amount;
        }
    }

    private PregnancyFaces(){}
}
