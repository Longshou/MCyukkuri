package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.pose.Splice;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.ModelFingerprint;
import io.github.longshou.mcyukkuri.body.ModelPlacementFit;
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
        JsonObject faceLibrary = FaceLibrary.reload(manager);
        HairWoundAssets.reload(manager);
        RemainsAssets.reload(manager);RemainsRenderer.reload();
        var assetIds = new ArrayList<String>();
        for (YukkuriVariant variant : YukkuriVariant.values()) assetIds.add(variant.assetId());
        assetIds.addAll(SubspeciesAssets.MODELS);
        for (String id : assetIds) {
            try (var stream = manager.getResourceOrThrow(MCYukkuri.id("yukkuri/" + id + ".json")).open()) {
                byte[] bytes=stream.readAllBytes();
                var hash=ModelFingerprint.of(bytes);
                var chain=new StringBuilder(hash.lf());
                JsonObject data = JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
                // Seat headwear and round the hair first; motion revisions were authored on the fitted mesh.
                JsonObject fit = placementFits.getAsJsonObject(id);
                if (fit != null && applyFit(id,data,fit,hash)) chain.append('\n').append("placement_fit").append('\n').append(fit);
                JsonObject revision = motionRevisions.getAsJsonObject(id);
                if (revision != null && applyRevision(id,data,revision)) chain.append('\n').append("motion_corrections").append('\n').append(revision);
                JsonObject houseRevision=houseEcology.getAsJsonObject(id);
                if(houseRevision!=null&&applyRevision(id,data,houseRevision))chain.append('\n').append("house_ecology").append('\n').append(houseRevision);
                JsonObject toiletRevision=toiletContact.getAsJsonObject(id);
                if(toiletRevision!=null&&applyRevision(id,data,toiletRevision))chain.append('\n').append("toilet_contact").append('\n').append(toiletRevision);
                var faceRevision=faceLibrary.getAsJsonObject(id);
                if(applyRevision(id,data,faceRevision))chain.append('\n').append("face_library").append('\n').append(faceRevision);
                // Hash still identifies unchanged base geometry for wounds and badges.
                var model=new YukkuriModel(data,hash);
                model.poseFingerprint(ModelFingerprint.of(chain.toString().getBytes(StandardCharsets.UTF_8)).lf());
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

    static boolean applyFit(String id,JsonObject data,JsonObject fit,ModelFingerprint hash) {
        boolean applied=ModelPlacementFit.apply(id,data,fit,hash);
        if (!applied)
            LoggerFactory.getLogger(YukkuriModel.class).warn("Placement fit skipped for changed base geometry: {}",id);
        return applied;
    }

    static boolean applyRevision(String id,JsonObject data,JsonObject revision) {
        boolean applied=Splice.revision(data,revision);
        if(!applied)
            LoggerFactory.getLogger(YukkuriModel.class).warn("Motion corrections skipped for incompatible skeleton: {}",id);
        return applied;
    }

    private YukkuriModelLoader(){}
}
