package io.github.longshou.mcyukkuri.client;

import static io.github.longshou.mcyukkuri.client.YukkuriModel.floats;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.ModelFingerprint;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesAssets;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

/** Resource loading and authored JSON corrections; publication stays on the model facade. */
final class YukkuriModelLoader {
    static void reload(ResourceManager manager) {
        Map<String, YukkuriModel> loaded = new HashMap<>();
        JsonObject motionRevisions;
        try (var stream = manager.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/motion_corrections.json")).open()) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            // Format 2 stores the puff as offsets; an older override pack keeps the base clips instead.
            if (root.get("format").getAsInt() == 2) motionRevisions = root.getAsJsonObject("models");
            else { motionRevisions = new JsonObject(); LoggerFactory.getLogger(YukkuriModel.class).warn("Motion corrections ignored: unsupported format {}", root.get("format")); }
        } catch (IOException error) { throw new UncheckedIOException("Cannot load motion corrections", error); }
        JsonObject placementFits;
        try (var stream = manager.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/placement_fit.json")).open()) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("format").getAsInt() == 1) placementFits = root.getAsJsonObject("models");
            else { placementFits = new JsonObject(); LoggerFactory.getLogger(YukkuriModel.class).warn("Placement fit ignored: unsupported format {}", root.get("format")); }
        } catch (IOException error) { throw new UncheckedIOException("Cannot load placement fit", error); }
        JsonObject houseEcology;
        try (var reader = manager.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/house_ecology.json")).openAsReader()) {
            var data=JsonParser.parseReader(reader).getAsJsonObject();
            if(data.get("format").getAsInt()!=1)throw new IllegalArgumentException("Unsupported house ecology motions");
            houseEcology=data.getAsJsonObject("models");
        } catch(IOException error) { throw new UncheckedIOException("Cannot load house ecology motions",error); }
        JsonObject toiletContact = ToiletPresentation.reload(manager);
        var assetIds = new ArrayList<String>();
        for (YukkuriVariant variant : YukkuriVariant.values()) assetIds.add(variant.assetId());
        assetIds.addAll(SubspeciesAssets.MODELS);
        for (String id : assetIds) {
            try (var stream = manager.getResourceOrThrow(MCYukkuri.id("yukkuri/" + id + ".json")).open()) {
                byte[] bytes=stream.readAllBytes();
                var hash=ModelFingerprint.of(bytes);
                JsonObject data = JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
                // Seat headwear and round the hair first; motion revisions were authored on the fitted mesh.
                JsonObject fit = placementFits.getAsJsonObject(id);
                if (fit != null) applyFit(id,data,fit,hash);
                JsonObject revision = motionRevisions.getAsJsonObject(id);
                if (revision != null) applyRevision(id,data,revision);
                JsonObject houseRevision=houseEcology.getAsJsonObject(id);
                if(houseRevision!=null)applyRevision(id,data,houseRevision);
                JsonObject toiletRevision=toiletContact.getAsJsonObject(id);
                if(toiletRevision!=null)applyRevision(id,data,toiletRevision);
                // Hash still identifies unchanged base geometry for wounds and badges.
                var model=new YukkuriModel(data,hash);
                try{model.hatSampler(HatDamageFaces.load(manager,MCYukkuri.id("textures/entity/"+id+"/neutral.png")));}
                catch(Exception ignored){model.hatSampler(null);}
                loaded.put(id, model);
            } catch (IOException error) {
                throw new UncheckedIOException("Cannot load Yukkuri model " + id, error);
            }
        }
        try (var stream = manager.getResourceOrThrow(MCYukkuri.id("yukkuri/badge_mounts.json")).open()) {
            var data=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            var mounts=data.getAsJsonObject("variants");
            Map<String,JsonObject> props=new HashMap<>();
            for(var entry:data.getAsJsonObject("detached").entrySet()) {
                try(var prop=manager.getResourceOrThrow(MCYukkuri.id("yukkuri_props/"+entry.getKey()+".json")).open()) {
                    var fingerprint=ModelFingerprint.of(prop.readAllBytes());
                    if(fingerprint.matches(entry.getValue().getAsJsonObject().get("model_sha256").getAsString()))props.put(entry.getKey(),entry.getValue().getAsJsonObject());
                }
            }
            YukkuriModel.install(null,Map.copyOf(props));
            for(var entry:loaded.entrySet()) {
                var mount=mounts.getAsJsonObject(entry.getKey()); var model=entry.getValue();
                if(mount==null)continue;
                if(!model.installBadgeMount(mount))LoggerFactory.getLogger(YukkuriModel.class).warn("Badge attachment disabled for incompatible accessory geometry: {}",entry.getKey());
            }
        } catch(IOException error) { throw new UncheckedIOException("Cannot load badge mounts",error); }
        LivingPresentation.reload(manager);
        WoundRenderer.clear();
        YukkuriModel.install(Map.copyOf(loaded),null);
    }

    static void applyFit(String id,JsonObject data,JsonObject fit,ModelFingerprint hash) {
        if (!hash.matches(fit.get("base_sha256").getAsString())) { LoggerFactory.getLogger(YukkuriModel.class).warn("Placement fit skipped for changed base geometry: {}",id); return; }
        JsonArray vertices=data.getAsJsonArray("vertices"),weights=data.getAsJsonArray("weights"),parts=data.getAsJsonArray("parts"),bones=data.getAsJsonArray("bones");
        for (var raw:fit.getAsJsonArray("parts")) {
            JsonObject entry=raw.getAsJsonObject();int index=entry.get("part_index").getAsInt();
            if (index<0||index>=parts.size()) throw new IllegalArgumentException("Invalid placement fit part");
            JsonObject part=parts.get(index).getAsJsonObject();
            if (!part.get("name").getAsString().equals(entry.get("name").getAsString())) throw new IllegalArgumentException("Placement fit part mismatch");
            float[] source=floats(entry.getAsJsonArray("vertices"));JsonArray binding=entry.getAsJsonArray("weights"),indices=entry.getAsJsonArray("indices");
            int count=indices.size();boolean inplace=entry.get("inplace").getAsBoolean();
            if (count%3!=0||source.length%5!=0||binding.size()*5!=source.length*3||(inplace&&count!=part.get("count").getAsInt()))
                throw new IllegalArgumentException("Invalid placement fit mesh");
            int start=inplace?part.get("start").getAsInt():vertices.size()/8;
            int[] corner=new int[3];var a=new Vector3f();var b=new Vector3f();
            for (int t=0;t<count;t+=3) {
                for (int j=0;j<3;j++) { corner[j]=indices.get(t+j).getAsInt(); if (corner[j]<0||corner[j]*5+5>source.length) throw new IllegalArgumentException("Invalid placement fit index"); }
                a.set(source[corner[1]*5]-source[corner[0]*5],source[corner[1]*5+1]-source[corner[0]*5+1],source[corner[1]*5+2]-source[corner[0]*5+2]);
                b.set(source[corner[2]*5]-source[corner[0]*5],source[corner[2]*5+1]-source[corner[0]*5+1],source[corner[2]*5+2]-source[corner[0]*5+2]);
                a.cross(b);if (a.lengthSquared()>1e-24f) a.normalize(); else a.set(0,0,-1);
                for (int j=0;j<3;j++) {
                    int k=corner[j],target=start+t+j;float[] row={source[k*5],source[k*5+1],source[k*5+2],source[k*5+3],source[k*5+4],a.x,a.y,a.z};
                    JsonArray bind=new JsonArray();bind.add(binding.get(k*3));bind.add(binding.get(k*3+1));bind.add(binding.get(k*3+2));
                    if (inplace) { for (int c=0;c<8;c++) vertices.set(target*8+c,new JsonPrimitive(row[c])); weights.set(target,bind); }
                    else { for (float value:row) vertices.add(value); weights.add(bind); }
                }
            }
            if (!inplace) { part.addProperty("start",start); part.addProperty("count",count); }
        }
        for (var raw:fit.getAsJsonArray("bone_pivots")) {
            JsonObject moved=raw.getAsJsonObject();int index=moved.get("index").getAsInt();
            if (index<0||index>=bones.size()||!bones.get(index).getAsJsonObject().get("name").getAsString().equals(moved.get("name").getAsString()))
                throw new IllegalArgumentException("Placement fit bone mismatch");
            bones.get(index).getAsJsonObject().add("pivot",moved.getAsJsonArray("pivot").deepCopy());
        }
    }

    static void applyRevision(String id,JsonObject data,JsonObject revision) {
        JsonArray expected = revision.getAsJsonArray("bone_names"), actual = data.getAsJsonArray("bones");
        boolean compatible = expected.size() == actual.size();
        for (int i=0; compatible && i<expected.size(); i++)
            compatible = expected.get(i).getAsString().equals(actual.get(i).getAsJsonObject().get("name").getAsString());
        if (!compatible) { LoggerFactory.getLogger(YukkuriModel.class).warn("Motion corrections skipped for incompatible skeleton: {}",id); return; }
        revision.getAsJsonObject("clips").entrySet().forEach(e -> data.getAsJsonObject("clips").add(e.getKey(),e.getValue()));
        if (revision.has("expression_aliases")) data.add("expression_aliases",revision.get("expression_aliases"));
        if (revision.has("expression_patches")) {
            if (!data.has("expression_patches")) data.add("expression_patches",new JsonObject());
            revision.getAsJsonObject("expression_patches").entrySet().forEach(e -> data.getAsJsonObject("expression_patches").add(e.getKey(),e.getValue()));
        }
        if (revision.has("puff_surface")) {
            JsonObject surface=revision.getAsJsonObject("puff_surface").deepCopy();
            appendPuffPart(data,surface);data.add("puff_surface",surface);
        }
        if (revision.has("puff_attachments")) {
            JsonArray attachments=revision.getAsJsonArray("puff_attachments").deepCopy();
            for(var raw:attachments)appendPuffPart(data,raw.getAsJsonObject());
            data.add("puff_attachments",attachments);
        }
        if (revision.has("puff_offsets")) data.add("puff_offsets",revision.get("puff_offsets"));
    }

    /** Expand a shared-corner puff mesh into the vertex list; offsets are full xyz per corner. */
    private static void appendPuffPart(JsonObject data,JsonObject part) {
        JsonArray source=part.getAsJsonArray("vertices"),indices=part.getAsJsonArray("indices"),offsets=part.getAsJsonArray("offsets");
        int start=data.getAsJsonArray("vertices").size()/8,count=indices==null?source.size()/8:indices.size();
        if(source.size()%8!=0||count%3!=0||offsets==null||offsets.size()*8!=source.size()*3)throw new IllegalArgumentException("Invalid puff mesh");
        JsonArray expandedOffsets=new JsonArray();
        for(int i=0;i<count;i++){
            int index=indices==null?i:indices.get(i).getAsInt();
            if(index<0||index*8+8>source.size())throw new IllegalArgumentException("Invalid puff index");
            for(int k=0;k<8;k++)data.getAsJsonArray("vertices").add(source.get(index*8+k));
            for(int k=0;k<3;k++)expandedOffsets.add(offsets.get(index*3+k));
            JsonArray binding=new JsonArray();binding.add(part.get("bone"));binding.add(part.get("bone"));binding.add(0);data.getAsJsonArray("weights").add(binding);
        }
        part.remove("vertices");part.remove("indices");part.addProperty("start",start);part.addProperty("count",count);
        part.add("offsets",expandedOffsets);
    }

    private YukkuriModelLoader(){}
}
