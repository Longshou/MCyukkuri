package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.client.YukkuriModel;
import io.github.longshou.mcyukkuri.client.YukkuriRenderer;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;

/** Real client observation and framebuffer capture, isolated from production code. */
@EventBusSubscriber(modid = P0Mod.ID, value = Dist.CLIENT)
public final class P0Client {
    private static int ticks;
    private static boolean configured;
    private static boolean joined;
    private static JsonObject captureRequest;
    private static int captureUntil;
    private static int nextCapture;
    private static int captureEvery;
    private static int captured;
    private static final List<Object> captureStates = new ArrayList<>();
    private static long previousFrame;
    private static long frameTotal;
    private static int frameCount;
    private static long longestFrame;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!P0Control.enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if(captureRequest!=null && captureRequest.has("followYukkuri") && mc.level!=null && mc.player!=null){
            for(var entity:mc.level.entitiesForRendering())if(entity instanceof YukkuriEntity){
                mc.player.setPos(entity.getX()+2.4,entity.getY(),entity.getZ()-4);
                mc.player.setYRot(25);mc.player.setXRot(23);break;
            }
        }
        if (!configured) {
            configured = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.framerateLimit().set(60);
            mc.options.enableVsync().set(false);
            mc.options.renderDistance().set(4);
            mc.options.fov().set(50);
            mc.options.hideGui = true;
            GLFW.glfwHideWindow(mc.getWindow().getWindow());
            P0Control.write("started", Map.of("client",mc.getUser().getName()));
        }
        if (mc.level != null && mc.player != null && !joined) {
            joined = true;
            P0Control.write("joined", snapshot());
        }
        if (ticks % 4 != 0 || captureRequest != null) return;
        JsonObject request = P0Control.poll();
        if (request == null) return;
        try {
            switch (request.get("op").getAsString()) {
                case "integration-snapshot" -> {
                    var rows=new ArrayList<Object>();
                    for(var entity:mc.level.entitiesForRendering())if(entity instanceof YukkuriEntity y)rows.add(IntegrationAcceptance.network(y));
                    P0Control.reply(request,true,rows);
                }
                case "integration-menu" -> {
                    var result=new java.util.LinkedHashMap<String,Object>();
                    result.put("type",mc.player.containerMenu.getClass().getSimpleName());
                    result.put("containerId",mc.player.containerMenu.containerId);
                    if(mc.player.containerMenu instanceof io.github.longshou.mcyukkuri.human.HumanMenu menu) {
                        var values=new ArrayList<Integer>();for(int i=0;i<54;i++)values.add(menu.value(i));result.put("values",values);
                    }
                    P0Control.reply(request,true,result);
                }
                case "integration-menu-button" -> {
                    if(!(mc.player.containerMenu instanceof io.github.longshou.mcyukkuri.human.HumanMenu))throw new IllegalStateException("Human menu is not open");
                    mc.gameMode.handleInventoryButtonClick(mc.player.containerMenu.containerId,request.get("button").getAsInt());
                    P0Control.reply(request,true,"Actual inventory button packet sent");
                }
                case "integration-close-menu" -> {mc.player.closeContainer();P0Control.reply(request,true,"Closed actual player container");}
                case "screen-info" -> P0Control.reply(request,true,Map.of("screen",mc.screen==null?"none":mc.screen.getClass().getSimpleName(),"buttons",mc.screen==null?List.of():mc.screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button).map(c->((net.minecraft.client.gui.components.Button)c).getMessage().getString()).toList()));
                case "screen-button" -> {
                    if(!(mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen))throw new IllegalStateException("Only isolated preview backup warning is supported");
                    var label=request.get("label").getAsString();
                    var button=mc.screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals(label)).findFirst().orElseThrow();
                    ((net.minecraft.client.gui.components.Button)button).onPress();P0Control.reply(request,true,label);
                }
                case "snapshot" -> P0Control.reply(request, mc.level != null, snapshot());
                case "preview-backup" -> {
                    if(!mc.getUser().getName().equals("P0Preview")||mc.screen==null||!mc.screen.getClass().getSimpleName().equals("BackupConfirmScreen"))
                        throw new IllegalStateException("Only the disposable preview backup prompt is supported");
                    var buttons=mc.screen.children().stream().filter(child->child instanceof net.minecraft.client.gui.components.Button)
                            .map(child->(net.minecraft.client.gui.components.Button)child).toList();
                    if(request.has("button")){int index=request.get("button").getAsInt();buttons.get(index).onPress();P0Control.reply(request,true,"Preview backup choice applied");}
                    else P0Control.reply(request,true,buttons.stream().map(button->button.getMessage().getString()).toList());
                }
                case "show" -> {
                    mc.setScreen(null);mc.options.hideGui=false;
                    GLFW.glfwShowWindow(mc.getWindow().getWindow());GLFW.glfwRestoreWindow(mc.getWindow().getWindow());GLFW.glfwFocusWindow(mc.getWindow().getWindow());
                    P0Control.reply(request,true,"Interactive preview window shown");
                }
                case "assets" -> P0Control.reply(request, true, assets());
                case "waddle-assets" -> P0Control.reply(request,true,waddleAssets());
                case "wound-assets" -> {
                    List<Object> checks=new ArrayList<>();
                    for(var entity:mc.level.entitiesForRendering())if(entity instanceof YukkuriEntity e&&!e.visibleWounds().isEmpty()){
                        var model=YukkuriModel.get(e.getVariant().assetId());Probe a=new Probe(),b=new Probe();
                        if(!model.woundGeometryCompatible())throw new IllegalStateException("Wound geometry fingerprint mismatch: "+e.getVariant());
                        model.renderLiving("idle",0,new PoseStack(),a,15728880,0,true,"chew",2,0,e,type->a);
                        model.renderLiving("idle",0,new PoseStack(),b,15728880,0,true,"chew",6,0,e,type->b);
                        if(a.hash==b.hash)throw new IllegalStateException("Wounded mouth no longer chews: "+e.getVariant());
                        checks.add(Map.of("variant",e.getVariant().assetId(),"geometryCompatible",true,"chewing",true,"vertices",a.vertices));
                    }P0Control.write("wound-client-assets",checks);P0Control.reply(request,true,checks);
                }
                case "preview" -> {
                    var server=mc.getSingleplayerServer();
                    if(server==null)throw new IllegalStateException("No integrated preview server");
                    server.execute(()->P0Server.preview(server,request));
                }
                case "capture", "record" -> {
                    if (mc.level == null || mc.player == null) throw new IllegalStateException("Client has no world");
                    if(request.has("careAction")){
                        if(!(mc.player.containerMenu instanceof io.github.longshou.mcyukkuri.interaction.CareMenu))throw new IllegalStateException("Open a real care menu first");
                        var action=io.github.longshou.mcyukkuri.interaction.CareAction.valueOf(request.get("careAction").getAsString());
                        mc.gameMode.handleInventoryButtonClick(mc.player.containerMenu.containerId,action.ordinal());
                    }
                    captureRequest=request; captured=0; captureStates.clear();
                    captureEvery=request.has("every")?request.get("every").getAsInt():4;
                    if(captureEvery<1)throw new IllegalArgumentException("Invalid capture interval");
                    captureUntil=ticks+(request.has("ticks")?request.get("ticks").getAsInt():1);
                    nextCapture=ticks;
                    if(!request.has("keepScreen") || !request.get("keepScreen").getAsBoolean()) mc.setScreen(null);
                    if(request.has("hideGui")) mc.options.hideGui=request.get("hideGui").getAsBoolean();
                    if(request.has("speech")) {
                        var server=mc.getSingleplayerServer();
                        if(server==null)throw new IllegalStateException("Speech capture requires integrated preview");
                        String speech=request.get("speech").getAsString();
                        var area=mc.player.getBoundingBox().inflate(16);
                        server.execute(()->server.overworld().getEntitiesOfClass(YukkuriEntity.class, area)
                                .forEach(e->{
                                    if(request.has("young")) {
                                        var family=e.family().save();boolean young=request.get("young").getAsBoolean();
                                        family.putString("Stage",young?"BABY":"ADULT");family.putFloat("Scale",young?.35f:1f);
                                        e.family().load(family);
                                    }
                                    if(request.has("invisible"))e.setInvisible(request.get("invisible").getAsBoolean());
                                    e.speak(speech);
                                }));
                        nextCapture=ticks+8;
                    }
                    frameCount=0; frameTotal=0; longestFrame=0; previousFrame=0;
                    if(request.has("action")) {
                        String action=request.get("action").getAsString();
                        if(YukkuriAction.byId(action).isEmpty())throw new IllegalArgumentException("Unknown recording action");
                        var server=mc.getSingleplayerServer();
                        if(server==null)throw new IllegalStateException("Action recording requires the isolated integrated preview");
                        server.execute(()->server.overworld().getEntitiesOfClass(YukkuriEntity.class,
                                new net.minecraft.world.phys.AABB(-16,60,-16,48,85,48),e->e.getTags().contains("p0_display"))
                                .forEach(e->e.playAction(action)));
                    }
                }
                case "reload" -> {
                    YukkuriModel old=YukkuriModel.get("reimu");
                    mc.reloadResourcePacks().whenComplete((value,error)->mc.execute(()-> {
                        boolean changed=error==null && old!=YukkuriModel.get("reimu");
                        P0Control.reply(request,changed,error==null?Map.of("modelReplaced",changed,"state",snapshot()):error.toString());
                    }));
                }
                case "interact" -> {
                    var entity=mc.level.getEntity(request.get("target").getAsInt());
                    if(entity==null)throw new IllegalStateException("Target absent on client");
                    var result=mc.gameMode.interact(mc.player,entity,InteractionHand.MAIN_HAND);
                    P0Control.reply(request,true,Map.of("predictedResult",result.toString(),"target",entity.getUUID()));
                }
                case "use-item" -> {
                    P0Control.reply(request,true,mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND).toString());
                }
                case "block-interact" -> {
                    var pos=new net.minecraft.core.BlockPos(request.get("x").getAsInt(),request.get("y").getAsInt(),request.get("z").getAsInt());
                    var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false);
                    P0Control.reply(request,true,mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit).toString());
                }
                case "sneak" -> {
                    boolean down=request.get("down").getAsBoolean();mc.options.keyShift.setDown(down);mc.player.setShiftKeyDown(down);
                    P0Control.reply(request,true,down);
                }
                case "care-check" -> {
                    var care=(io.github.longshou.mcyukkuri.life.CareBlockEntity)mc.level.getBlockEntity(new net.minecraft.core.BlockPos(94,65,8));
                    boolean expectedEmpty=request.has("empty")&&request.get("empty").getAsBoolean();
                    P0Control.reply(request,care.displayFood().isEmpty()==expectedEmpty,Map.of("displayFood",care.displayFood().toString()));
                }
                case "book-page" -> {
                    var book=(net.minecraft.client.gui.screens.inventory.BookViewScreen)mc.screen;
                    book.setPage(request.get("page").getAsInt());
                    var content=mc.player.getMainHandItem().get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
                    var heights=content.pages().stream().map(page->mc.font.split(page.get(false),114).size()*9).toList();
                    P0Control.reply(request,heights.stream().allMatch(height->height<=128),Map.of("pageHeights",heights));
                }
                case "close-screen" -> { mc.setScreen(null);P0Control.reply(request,true,"closed"); }
                case "respawn" -> {mc.player.respawn();mc.setScreen(null);P0Control.reply(request,true,"Respawn requested");}
                case "house-check" -> {
                    var nest=(io.github.longshou.mcyukkuri.house.HouseNestBlockEntity)mc.level.getBlockEntity(new net.minecraft.core.BlockPos(178,65,8));
                    P0Control.reply(request,nest!=null,nest==null?"absent":Map.of("materials",nest.materials(),"food",nest.food(),"dirt",nest.dirt(),"eggs",nest.eggs(),"progress",nest.progress(),"id",nest.identity()));
                }
                case "stop" -> { P0Control.reply(request,true,"Stopping owned P0 client");mc.stop(); }
                default -> throw new IllegalArgumentException("Unknown P0 client operation");
            }
        } catch(Throwable error) { P0Control.reply(request,false,error.toString()); captureRequest=null; }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if(!P0Control.enabled() || captureRequest==null)return;
        long now=System.nanoTime();
        if(previousFrame>0) { long elapsed=now-previousFrame;frameTotal+=elapsed;longestFrame=Math.max(longestFrame,elapsed);frameCount++; }
        previousFrame=now;
        if(ticks<nextCapture)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            String name=captureRequest.has("name")?captureRequest.get("name").getAsString():"frame";
            if(!name.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Invalid capture name");
            Path directory=P0Control.DIRECTORY.resolve("screenshots");Files.createDirectories(directory);
            Path target=directory.resolve(name+String.format("-%04d.png",captured));
            try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(target); }
            captureStates.add(Map.of("file",target.toString(),"clientTick",ticks,"state",snapshot()));
            captured++;nextCapture=ticks+captureEvery;
            if(ticks>=captureUntil || captureRequest.get("op").getAsString().equals("capture")) {
                Object result=Map.of("captures",captureStates,"meanFrameMs",frameCount==0?0:frameTotal/1_000_000.0/frameCount,
                        "maxFrameMs",longestFrame/1_000_000.0,"frames",frameCount);
                P0Control.write("capture-"+name,result);
                P0Control.reply(captureRequest,true,Map.of("count",captured,"manifest", "capture-"+name+".json"));
                captureRequest=null;
            }
        } catch(Throwable error) { P0Control.reply(captureRequest,false,error.toString());captureRequest=null; }
    }

    private static Object snapshot() {
        Minecraft mc=Minecraft.getInstance();
        List<JsonObject> entities=new ArrayList<>();
        if(mc.level!=null)for(var entity:mc.level.entitiesForRendering())if(entity instanceof YukkuriEntity y) {
            JsonObject state=new JsonObject();
            state.addProperty("uuid",y.getUUID().toString());state.addProperty("networkId",y.getId());
            state.addProperty("variant",y.getVariant().assetId());state.addProperty("action",y.getAnimationId());
            state.addProperty("start",y.getAnimationStartTick());state.addProperty("expression",y.getExpressionId());
            state.addProperty("x",y.getX());state.addProperty("y",y.getY());state.addProperty("z",y.getZ());
            state.addProperty("renderer",mc.getEntityRenderDispatcher().getRenderer(y).getClass().getSimpleName());
            state.addProperty("bodyYaw",y.yBodyRot);state.addProperty("previousBodyYaw",y.yBodyRotO);state.addProperty("yaw",y.getYRot());
            state.addProperty("nuzzlePartner",y.nuzzlePartnerId());
            state.addProperty("behavior",y.getBehaviorId());state.addProperty("behaviorStart",y.getBehaviorStartTick());
            state.addProperty("crawling",y.isCrawling());state.addProperty("crawlStart",y.getCrawlStartTick());
            state.addProperty("accessory",y.hasVisibleAccessory());state.addProperty("mouthCount",y.getMouthItem().getCount());
            state.addProperty("dialogue",y.getDialogueKey());
            state.addProperty("speechStart",y.speechStart());state.addProperty("speechEnd",y.speechEnd());state.addProperty("speechSequence",y.speechSequence());
            state.addProperty("scale",y.growthScale());state.addProperty("stage",y.lifeStage());state.addProperty("broodCount",y.broodCount());
            state.addProperty("broodBorn",y.broodBornMask());state.addProperty("broodProgress",y.broodProgress());state.addProperty("broodVariation",y.broodVariation());
            state.addProperty("wound",y.visibleWound());state.addProperty("bandaged",y.visibleBandage());state.addProperty("carrying",y.getCarriedItem().toString());
            state.add("wounds",P0Control.JSON.toJsonTree(y.visibleWounds()));state.addProperty("filling",y.visibleFilling());
            state.addProperty("contact",y.contactPose());state.addProperty("contactTurn",y.contactTurn());state.addProperty("hairLoss",y.visibleHairLoss());
            entities.add(state);
        }
        return Map.of("client",mc.getUser().getName(),"world",mc.level!=null,"tick",mc.level==null?0:mc.level.getGameTime(),
                "screen",mc.screen==null?"none":mc.screen.getClass().getSimpleName(),"entities",entities,"dialogueAudio",dialogueAudio(),"ecologyAudio",ecologyAudio());
    }

    private static Object ecologyAudio() {
        try {
            var field=io.github.longshou.mcyukkuri.client.EcologyAudio.class.getDeclaredField("ACTIVE");field.setAccessible(true);
            var instances=(java.util.Map<?,?>)field.get(null);int playing=0;
            for(Object instance:instances.values())if(Minecraft.getInstance().getSoundManager().isActive((net.minecraft.client.resources.sounds.SoundInstance)instance))playing++;
            return Map.of("instances",instances.size(),"playing",playing);
        }catch(ReflectiveOperationException error){return Map.of("error",error.toString());}
    }

    private static Object dialogueAudio() {
        try {
            var field=io.github.longshou.mcyukkuri.client.DialogueAudio.class.getDeclaredField("ACTIVE");field.setAccessible(true);
            var instances=(java.util.Map<?,?>)field.get(null);
            int playing=0;
            for(Object instance:instances.values())if(Minecraft.getInstance().getSoundManager().isActive((net.minecraft.client.resources.sounds.SoundInstance)instance))playing++;
            return Map.of("instances",instances.size(),"playing",playing);
        }catch(ReflectiveOperationException error){return Map.of("error",error.toString());}
    }

    private static Object assets() throws Exception {
        Minecraft mc=Minecraft.getInstance();List<Object> result=new ArrayList<>();
        for(YukkuriVariant variant:YukkuriVariant.values()) {
            YukkuriModel model=YukkuriModel.get(variant.assetId());
            for(YukkuriAction action:YukkuriAction.values()) {
                int count=0;
                for(float part:new float[]{0,.25f,.5f,.75f,1,1.5f}) {
                    Probe vertices=new Probe();
                    model.render(action.id(),(float)action.duration()*part,new PoseStack(),vertices,15728880,0);
                    if(vertices.vertices==0)throw new IllegalStateException("Empty action "+variant+"/"+action);
                    count+=vertices.vertices;
                }
                result.add(Map.of("variant",variant.assetId(),"action",action.id(),"samples",6,"vertices",count));
            }
            // Reusing a model across actions/entities must restore sparse and hidden defaults.
            Probe before=new Probe();model.render("idle",0,new PoseStack(),before,15728880,0);
            model.render("puff",3,new PoseStack(),new Probe(),15728880,0);
            model.render("anatomy_mamu_extend",1,new PoseStack(),new Probe(),15728880,0);
            Probe after=new Probe();model.render("idle",0,new PoseStack(),after,15728880,0);
            if(before.vertices!=after.vertices || before.hash!=after.hash)throw new IllegalStateException("Pose leaks into idle: "+variant);
            Probe bare=new Probe();model.render("idle",0,new PoseStack(),bare,15728880,0,false,"idle",0);
            if(bare.vertices<=0 || bare.vertices>=before.vertices)throw new IllegalStateException("Accessory visibility failed: "+variant);
            Probe chewA=new Probe(),chewB=new Probe();
            model.render("idle",0,new PoseStack(),chewA,15728880,0,true,"chew",2);
            model.render("idle",0,new PoseStack(),chewB,15728880,0,true,"chew",6);
            if(chewA.hash==chewB.hash)throw new IllegalStateException("Chewing has no mouth motion: "+variant);
            Probe restored=new Probe();model.render("idle",0,new PoseStack(),restored,15728880,0);
            if(restored.hash!=before.hash)throw new IllegalStateException("Living pose leaks into idle: "+variant);
            // Also decode five behavior atlases per registered variant in Minecraft.
            for(String face:List.of("neutral","happy","surprise","sleep","angry")) {
                try(var stream=mc.getResourceManager().getResourceOrThrow(MCYukkuri.id("textures/entity/"+variant.assetId()+"/"+face+".png")).open();
                    var image=com.mojang.blaze3d.platform.NativeImage.read(stream)) {
                    int expected=variant.isPredator()?256:128;
                    if(image.getWidth()!=expected || image.getHeight()!=expected)throw new IllegalStateException("Invalid atlas: "+variant);
                }
            }
            if(!I18n.exists("entity.mcyukkuri."+variant.assetId()))throw new IllegalStateException("Missing entity translation");
        }
        var subspeciesAtlases=new ArrayList<Object>();
        for(String asset:io.github.longshou.mcyukkuri.subspecies.SubspeciesAssets.MODELS) {
            for(String face:List.of("neutral","happy","surprise","sleep","angry","confused","cry","drool",
                    "sleepy","teary","yawn","closed_smile","chewing","chewing_closed","satisfied","open_smile")) {
                var location=MCYukkuri.id("textures/entity/"+asset+"/"+face+".png");
                try(var stream=mc.getResourceManager().getResourceOrThrow(location).open();
                    var image=com.mojang.blaze3d.platform.NativeImage.read(stream)) {
                    int expected=asset.equals("marisa_tsumuri")?256:128;
                    if(image.getWidth()!=expected||image.getHeight()!=expected)throw new IllegalStateException("Invalid subspecies atlas: "+location);
                    subspeciesAtlases.add(Map.of("asset",asset,"expression",face,"width",image.getWidth(),"height",image.getHeight()));
                }
                mc.getTextureManager().getTexture(location);
            }
            Probe probe=new Probe();YukkuriModel.get(asset).render("idle",0,new PoseStack(),probe,15728880,0);
            if(probe.vertices<=0)throw new IllegalStateException("Empty subspecies model: "+asset);
        }
        P0Control.write("client-subspecies-atlases",subspeciesAtlases);
        P0Control.write("client-assets",result);
        return Map.of("clips",result.size(),"samples",result.size()*6,"idleRestoration",true,"mandatoryAtlases",YukkuriVariant.values().length*5,"accessoryVisibility",true,"chewingMotion",true,"subspeciesAtlases",subspeciesAtlases.size());
    }

    private static Object waddleAssets() {
        var results=new ArrayList<Object>();
        for(var variant:YukkuriVariant.values()) {
            var model=YukkuriModel.get(variant.assetId());
            var idle=new Probe();model.render("idle",0,new PoseStack(),idle,15728880,0);
            var a=new Probe();var b=new Probe();
            model.render("waddle",.3f,new PoseStack(),a,15728880,0);
            model.render("waddle",.9f,new PoseStack(),b,15728880,0);
            if(a.hash==b.hash)throw new IllegalStateException("Walking has no visible motion: "+variant);
            if(variant.isHouse()) {
                model.setCarrying(true);
                model.render("waddle",.3f,new PoseStack(),a=new Probe(),15728880,0,true,"house_carry",6);
                var gripA=model.gripPoint();
                model.render("waddle",.9f,new PoseStack(),b=new Probe(),15728880,0,true,"house_carry",18);
                var gripB=model.gripPoint();
                if(a.hash==b.hash||gripA==null||gripB==null||!gripA.isFinite()||!gripB.isFinite())
                    throw new IllegalStateException("Carrying lost walking or grip: "+variant);
                model.setCarrying(false);
            }
            var restored=new Probe();model.render("idle",0,new PoseStack(),restored,15728880,0);
            if(restored.hash!=idle.hash)throw new IllegalStateException("Walking leaks into idle: "+variant);
            results.add(Map.of("variant",variant.assetId(),"walking",true,"idleRestored",true,"carryChecked",variant.isHouse()));
        }
        P0Control.write("waddle-client-checks",results);return results;
    }

    private static final class Probe implements VertexConsumer {
        int vertices;long hash=1;
        private void finite(float... values) { for(float value:values)if(!Float.isFinite(value))throw new IllegalStateException("Nonfinite vertex"); }
        public VertexConsumer addVertex(float x,float y,float z) {
            finite(x,y,z);vertices++;hash=hash*31+Float.floatToIntBits(x);hash=hash*31+Float.floatToIntBits(y);hash=hash*31+Float.floatToIntBits(z);return this;
        }
        public VertexConsumer setColor(int r,int g,int b,int a) { return this; }
        public VertexConsumer setUv(float u,float v) { finite(u,v);return this; }
        public VertexConsumer setUv1(int u,int v) { return this; }
        public VertexConsumer setUv2(int u,int v) { return this; }
        public VertexConsumer setNormal(float x,float y,float z) { finite(x,y,z);return this; }
    }

    private P0Client() {}
}
