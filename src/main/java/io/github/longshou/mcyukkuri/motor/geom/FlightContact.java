package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.ToDoubleFunction;

/** First surface crossing of a flight segment, in one rigid host frame. */
public final class FlightContact {
    public record Hit(Vec point,double fraction,boolean wall) {}
    private FlightContact() {}

    public static Hit first(Vec from,Vec to,double step,double touch,ToDoubleFunction<Vec> surface) {
        var delta=to.sub(from);
        int steps=Math.max(2,(int)Math.ceil(Math.sqrt(delta.x()*delta.x()+delta.y()*delta.y()+delta.z()*delta.z())/step));
        Vec previous=from;double previousHeight=surface.applyAsDouble(from);
        for(int i=1;i<=steps;i++){
            double share=i/(double)steps;Vec point=from.add(delta.scale(share));double height=surface.applyAsDouble(point);
            boolean under=Double.isFinite(height)&&point.y()<height-touch;
            if(under&&Double.isFinite(previousHeight)&&previous.y()>=previousHeight-touch){
                double drop=previous.y()-previousHeight,fall=height-point.y();
                double inside=drop+fall<1e-9?1:Math.clamp(drop/(drop+fall),0,1),at=(i-1+inside)/steps;
                return new Hit(from.add(delta.scale(at)),at,false);
            }
            if(under&&!Double.isFinite(previousHeight))return new Hit(point,share,true);
            previous=point;previousHeight=height;
        }
        return null;
    }
}
