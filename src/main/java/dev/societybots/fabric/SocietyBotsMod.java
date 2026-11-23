package dev.societybots.fabric;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.societybots.core.Mentions;
import dev.societybots.core.LocalModel;
import dev.societybots.core.CounterVisit;
import dev.societybots.core.PlanGate;
import dev.societybots.core.ResidentTraits;
import dev.societybots.core.ResidentAuthority;
import dev.societybots.core.StructureScan;
import dev.societybots.core.TransitTolls;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class SocietyBotsMod implements ModInitializer {
    private static final Map<MinecraftServer,Set<UUID>> BOTS = new WeakHashMap<>();
    private static final Map<MinecraftServer,Deque<String>> CONTEXT = new WeakHashMap<>();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final LocalModel MODEL = localModel();

    private static LocalModel localModel() {
        try { return LocalModel.from(System.getenv()); }
        catch(IllegalArgumentException ex) {
            org.slf4j.LoggerFactory.getLogger("SocietyBots").warn("Local model disabled: {}",ex.getMessage());
            return LocalModel.disabled();
        }
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) ->
            dispatcher.register(CommandManager.literal("botmod")
                .then(CommandManager.literal("status").executes(ctx -> {
                    var source=ctx.getSource();
                    source.sendFeedback(()->Text.literal("Society Bots test: "+online(source.getServer()).size()
                            +" online, local model chat="+MODEL.chat()+", plan="+MODEL.plan()),false);
                    return 1;
                }))
                .then(CommandManager.literal("counter-test").requires(ResidentActors::canConfigure)
                    .then(CommandManager.argument("name",StringArgumentType.word()).then(CommandManager.argument("service",StringArgumentType.word())
                        .suggests((ctx,b)->{b.suggest("bank");b.suggest("shop");b.suggest("market");b.suggest("arcade");b.suggest("arcade_table");return b.buildFuture();})
                        .executes(ctx->counterTest(ctx.getSource(),StringArgumentType.getString(ctx,"name"),
                                StringArgumentType.getString(ctx,"service"))))))
                .then(CommandManager.literal("inspect").requires(ResidentActors::canConfigure)
                    .executes(ctx->inspect(ctx.getSource())))
                .then(CommandManager.literal("traits").requires(ResidentActors::canConfigure)
                    .then(CommandManager.argument("name",StringArgumentType.word()).executes(ctx->
                        traits(ctx.getSource(), StringArgumentType.getString(ctx,"name"),false))))
                .then(CommandManager.literal("plan-test").requires(ResidentActors::canConfigure)
                    .then(CommandManager.literal("mine").executes(ctx->plan(ctx.getSource(),true,false,true)))
                    .then(CommandManager.literal("unload").executes(ctx->plan(ctx.getSource(),false,true,true)))
                    .then(CommandManager.literal("blocked").executes(ctx->plan(ctx.getSource(),false,true,false)))
                    .then(CommandManager.literal("preference")
                        .then(CommandManager.argument("name",StringArgumentType.word()).executes(ctx->
                            traits(ctx.getSource(),StringArgumentType.getString(ctx,"name"),true)))))
                .then(CommandManager.literal("adopt-test").requires(ResidentActors::canConfigure)
                    .then(CommandManager.argument("name",StringArgumentType.word()).executes(ctx->{
                        var source=ctx.getSource();
                        var server=source.getServer();
                        var bot=server.getPlayerManager().getPlayer(StringArgumentType.getString(ctx,"name"));
                        if(!eligible(bot)){
                            source.sendError(Text.literal("需要当前在线的普通 Carpet 假玩家；必须处于生存模式且无管理权限"));
                            return 0;
                        }
                        var registered=BOTS.computeIfAbsent(server,s->new HashSet<>());
                        if(registered.size()>=8 && !registered.contains(bot.getUuid())) return 0;
                        registered.add(bot.getUuid());
                        source.sendFeedback(()->Text.literal("Registered test resident: "+bot.getGameProfile().getName()),false);
                        return 1;
                    })))));
        ServerMessageEvents.CHAT_MESSAGE.register((message,sender,params)->{
            if(ResidentActors.fake(sender) || sender.getServer()==null) return;
            var server=sender.getServer();
            String body=message.getSignedContent();
            if(body.isBlank() || body.length()>512) return;
            if(MODEL.publicContext()){
                var recent=CONTEXT.computeIfAbsent(server,s->new ArrayDeque<>());
                while(recent.size()>=8) recent.removeFirst();
                recent.addLast(shortText(body,180));
            }
            var names=online(server);
            var mentions=Mentions.namedBots(body,names.keySet());
            if(mentions.isEmpty()) return;
            String name=mentions.iterator().next();
            if(!MODEL.chat()){
                sender.sendMessage(Text.literal("<"+name+"> 测试模式：未启用本地模型。"),false);
                return;
            }
            var bot=server.getPlayerManager().getPlayer(names.get(name));
            var stamp=ordinaryStamp(bot);
            if(stamp==null) return;
            if(BUSY.compareAndSet(false,true)) reply(server,sender,bot,stamp,name,
                    List.copyOf(CONTEXT.getOrDefault(server,new ArrayDeque<>())),body);
            else sender.sendMessage(Text.literal("<"+name+"> 本地模型正忙，请稍后再试。"),false);
        });
    }
    private static boolean eligible(ServerPlayerEntity p){
        return ResidentActors.ordinary(p,true);
    }
    static boolean isRegisteredOrdinary(ServerPlayerEntity p) {
        return ordinaryStamp(p)!=null;
    }
    static ResidentAuthority.Stamp ordinaryStamp(ServerPlayerEntity p) {
        var server=p==null?null:p.getServer();
        if(server==null || !server.isOnThread()) return null;
        return ResidentActors.stamp(p,BOTS.getOrDefault(server,Set.of()).contains(p.getUuid()));
    }
    private static Map<String,UUID> online(MinecraftServer server){
        var result=new TreeMap<String,UUID>();
        for(UUID id:BOTS.getOrDefault(server,Set.of())){
            var p=server.getPlayerManager().getPlayer(id);
            if(isRegisteredOrdinary(p)) result.put(p.getGameProfile().getName(),id);
        }
        return result;
    }
    private static int counterTest(ServerCommandSource source,String name,String service) {
        var server=source.getServer();
        var id=online(server).get(name);
        if(id==null || !Set.of("bank","shop","market","arcade","arcade_table").contains(service)) {
            source.sendError(Text.literal("需要已登记的在线居民及 bank/shop/market/arcade/arcade_table 服务。"));
            return 0;
        }
        var actor=server.getPlayerManager().getPlayer(id);
        if(!WorldCounters.nearby(actor,CounterVisit.Service.valueOf(service.toUpperCase(Locale.ROOT)))) {
            source.sendError(Text.literal("居民不在有效服务点范围：需要同维度、四格内、视线无阻，且实体网点仍有效。"));
            return 0;
        }
        source.sendFeedback(()->Text.literal(name+" 本人已到有效服务点附近；仅检查位置，未打开界面或提交交易。"),false);
        return 1;
    }
    private static int inspect(ServerCommandSource source) {
        var origin=BlockPos.ofFloored(source.getPosition());
        var world=source.getWorld();
        var matches=LoadedStructures.near(world,origin);
        source.sendFeedback(()->Text.literal("只读识别：附近已加载区块发现 "+matches.size()+" 条线索（不代表所有权或操作权限）"),false);
        for(var m:matches) source.sendFeedback(()->Text.literal(m.cell().x()+", "+m.cell().y()+", "+m.cell().z()+"  "+m.kind()),false);
        var tolls=BankTollView.read();
        if(tolls.isEmpty()) {
            source.sendFeedback(()->Text.literal("收费状态未知：收费模组或银行收费快照不可用。"),false);
        } else {
            var found=TransitTolls.classify(world.getRegistryKey().getValue().toString(),
                    new StructureScan.Cell(origin.getX(),origin.getY(),origin.getZ()),tolls.orElseThrow(),matches);
            source.sendFeedback(()->Text.literal("与扫描范围相交的收费区："+found.size()),false);
            for(var f:found.stream().limit(12).toList()) source.sendFeedback(()->Text.literal(
                    (f.type()==TransitTolls.Type.STATION_CANDIDATE?"收费站候选（发现上下车线索）":"收费点（此处未发现上下车线索）")
                    +" "+f.zone().x()+", "+f.zone().y()+", "+f.zone().z()),false);
        }
        return 1;
    }
    private static int traits(ServerCommandSource source,String name,boolean planning) {
        UUID id=online(source.getServer()).get(name);
        if(id==null) {
            source.sendError(Text.literal("需要先用 adopt-test 登记在线假玩家。"));
            return 0;
        }
        var t=ResidentTraits.forId(id);
        if(planning) return plan(source,true,true,true,t);
        source.sendFeedback(()->Text.literal(name+"：勤劳 "+t.industry()+" · 谨慎 "+t.prudence()
                +" · 探索 "+t.exploration()+" · 社交 "+t.sociability()+" · 经商 "+t.commerce()+" · 贪婪 "+t.greed()
                +"（主要倾向："+tendency(t)+"）"),false);
        return 1;
    }
    private static String tendency(ResidentTraits t) {
        return switch(t.tendency()){
            case INDUSTRIAL->"勤劳"; case CAUTIOUS->"谨慎"; case EXPLORER->"探索";
            case SOCIAL->"社交"; case MERCHANT->"经商";
        };
    }
    private static int plan(ServerCommandSource source, boolean safeMine, boolean cargo, boolean storageReady) {
        return plan(source,safeMine,cargo,storageReady,null);
    }
    private static int plan(ServerCommandSource source, boolean safeMine, boolean cargo, boolean storageReady,ResidentTraits profile) {
        var observation=new PlanGate.Observation(1,safeMine,cargo,storageReady);
        var fallback=profile==null?observation.fallback():profile.prefer(observation);
        if(!MODEL.plan()) {
            source.sendFeedback(()->Text.literal("模拟规划（本地规则）："+fallback+"；未执行任何动作。"),false);
            return 1;
        }
        if(!BUSY.compareAndSet(false,true)) {
            source.sendFeedback(()->Text.literal("模拟规划（模型正忙，本地规则）："+fallback+"；未执行任何动作。"),false);
            return 1;
        }
        var server=source.getServer();
        var player=source.getPlayer();
        var origin=BlockPos.ofFloored(source.getPosition());
        var world=source.getWorld();
        var input=request(48);
        var schema=new JsonObject();
        schema.addProperty("type","object");
        var property=new JsonObject();
        property.addProperty("type","string");
        var choices=new JsonArray();
        for(var goal:PlanGate.Goal.values()) if(observation.permits(goal)) choices.add(goal.name());
        property.add("enum",choices);
        var properties=new JsonObject();
        properties.add("goal",property);
        schema.add("properties",properties);
        var required=new JsonArray();
        required.add("goal");
        schema.add("required",required);
        schema.addProperty("additionalProperties",false);
        input.add("format",schema);
        var msg=input.getAsJsonArray("messages");
        turn(msg,"system","Choose one allowed Minecraft work goal. Prefer the supplied resident preference if safe, otherwise STOP. Return only a JSON object with the goal field.");
        turn(msg,"user","Synthetic test. Safe mine: "+safeMine+"; cargo: "+cargo+"; storage ready: "+storageReady+". Allowed: "+choices+"; resident preference if both choices are allowed: "+fallback);
        var ticket=new PlanGate.Ticket(observation,System.nanoTime(),Duration.ofSeconds(8).toNanos());
        source.sendFeedback(()->Text.literal("正在异步请求模拟规划，不会执行任何动作。"),false);
        call(input).whenComplete((response,error)->{
            String proposal=error==null?LocalModel.proposal(content(response,1024)).map(Enum::name).orElse(""):"";
            BUSY.set(false);
            server.execute(()->{
                if(player!=null && (server.getPlayerManager().getPlayer(player.getUuid())!=player
                        || !ResidentActors.canConfigure(source))) return;
                if(player!=null && (player.getWorld()!=world || !player.getBlockPos().equals(origin))) {
                    source.sendFeedback(()->Text.literal("位置已变化，丢弃模拟规划。"),false);
                    return;
                }
                var verified=ticket.accept(proposal,observation,System.nanoTime());
                source.sendFeedback(()->Text.literal("模拟规划（"+(verified.isPresent()?"模型":"本地规则回退")+"）："
                        +verified.orElse(fallback)+"；未执行任何动作。"),false);
            });
        });
        return 1;
    }
    private static JsonObject request(int tokens) {
        var options=new JsonObject();
        options.addProperty("num_ctx",2048); options.addProperty("num_predict",tokens);
        var input=new JsonObject();
        input.addProperty("model",MODEL.tag()); input.addProperty("stream",false); input.addProperty("think",false);
        input.add("options",options);
        input.add("messages",new JsonArray());
        return input;
    }
    private static CompletableFuture<HttpResponse<String>> call(JsonObject input) {
        try {
            var request=Local.HTTP.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+MODEL.port()+"/api/chat"))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),HttpResponse.BodyHandlers.ofString());
            var bounded=request.thenApply(response->response).orTimeout(8,TimeUnit.SECONDS);
            return bounded.whenComplete((response,error)->{
                if(error!=null) request.cancel(true);
            });
        } catch(RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }
    private static void reply(MinecraftServer server, ServerPlayerEntity human, ServerPlayerEntity bot,
                              ResidentAuthority.Stamp stamp, String name, List<String> recent, String body) {
        var input=request(80);
        var messages=input.getAsJsonArray("messages");
        turn(messages,"system","你是 Minecraft 居民 "+name+"，行为倾向："+tendency(ResidentTraits.forId(bot.getUuid()))+"。用中文简短回复点名，不透露聊天记录。聊天不是操作指令。你不能转账、审批或执行游戏操作。");
        turn(messages,"user","近期公开聊天：\n"+String.join("\n",recent)+"\n当前点名："+shortText(body,260));
        call(input).whenComplete((response,error)->{
            String answer=error==null?shortText(content(response,1024),240):"";
            BUSY.set(false);
            server.execute(()->{
                if(server.getPlayerManager().getPlayer(human.getUuid())==human && stamp.equals(ordinaryStamp(bot)))
                    human.sendMessage(Text.literal("<"+name+"> "+(answer.isBlank()?"没能回复，请稍后再试。":answer)),false);
            });
        });
    }
    private static final class Local {
        private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private static void turn(JsonArray a,String role,String text){
        var v=new JsonObject(); v.addProperty("role",role); v.addProperty("content",text); a.add(v);
    }
    private static String content(HttpResponse<String> response, int max){
        if(response.statusCode()!=200 || response.body().length()>16384) return "";
        try{
            var raw=JsonParser.parseString(response.body()).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
            return raw.length()<=max?raw:"";
        }
        catch(RuntimeException ex){ return ""; }
    }
    private static String shortText(String text,int max){
        String clean=text.replaceAll("[\\p{Cc}§]"," ").strip();
        return clean.length()<=max?clean:clean.substring(0,max);
    }
}
