import io.github.longshou.mcyukkuri.family.*;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
public class DetachedGeometryProbe {
    public static void main(String[] args) {
        int checks=0;double minimum=Double.POSITIVE_INFINITY;
        for(int n=1;n<=5;n++)for(int v=85;v<=115;v++)for(var parent:YukkuriVariant.values()){
            float variation=v/100f;
            var oldBottle=DetachedStemGeometry.BOTTLE_MOUTH.subtract(FamilyGeometry.arc(n,1,1,variation,parent).getFirst());
            if(oldBottle.distanceTo(DetachedStemGeometry.offset(n,variation,parent,true))>1e-10)throw new AssertionError("bottle moved");
            for(int i=0;i<n;i++)for(var child:YukkuriVariant.values())for(float progress:new float[]{.15f,.5f,1}){
                double bottom=DetachedStemGeometry.childBase(n,i,progress,variation,parent,child,false).y;
                minimum=Math.min(minimum,bottom-.04);
                if(bottom-.04<.009999)throw new AssertionError(n+" "+variation+" "+parent+" "+child+" "+bottom);
                checks++;
            }
        }
        float variation=1.01972258f;
        double old=FamilyGeometry.childBase(2,1,1,1,variation,YukkuriVariant.REIMU,YukkuriVariant.REIMU).y
                +DetachedStemGeometry.GROUND_ROOT.y-FamilyGeometry.arc(2,1,1,variation,YukkuriVariant.REIMU).getFirst().y;
        double current=DetachedStemGeometry.childBase(2,1,1,variation,YukkuriVariant.REIMU,YukkuriVariant.REIMU,false).y;
        System.out.println("{\"checks\":"+checks+",\"minimumInflatedBottom\":"+minimum+",\"reproductionOldBottom\":"+old+",\"reproductionNewBottom\":"+current+",\"bottleUnchanged\":true}");
    }
}
