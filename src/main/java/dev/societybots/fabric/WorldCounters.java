package dev.societybots.fabric;

import dev.societybots.core.CounterVisit;
import dev.societybots.core.ArcadeVenue;
import java.util.UUID;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.RaycastContext;

/** Observes existing world service points without opening a menu or submitting a trade. */
final class WorldCounters {
    private WorldCounters() {}
    private static final double REACH=4;
    static boolean nearby(ServerPlayerEntity actor,CounterVisit.Service service) {
        if(!eligible(actor)) return false;
        var tag=tag(service);
        if(tag.isEmpty() || !FabricLoader.getInstance().isModLoaded(tag)) return false;
        var world=actor.getServerWorld();
        var nearby=world.getEntitiesByClass(InteractionEntity.class,actor.getBoundingBox().expand(REACH),
                e->e.isAlive()&&e.getCommandTags().contains(markerTag(tag)));
        if(nearby.size()>32) return false;
        // Use the same observation as the future submission path without issuing a payment proof.
        var probe=new CounterVisit.Task(service,"probe","read-only");
        return nearby.stream().sorted(Comparator.comparingDouble(actor::squaredDistanceTo))
                .anyMatch(e->observe(actor,e.getUuid(),probe)!=null);
    }
    private static boolean eligible(ServerPlayerEntity actor) {
        return SocietyBotsMod.isRegisteredOrdinary(actor);
    }
    private static String tag(CounterVisit.Service service) {
        return switch(service) {
            case BANK -> "bankmod";
            case SHOP -> "shopmod";
            case MARKET -> "marketmod";
            case ARCADE, ARCADE_TABLE -> "arcadehall";
            default -> "";
        };
    }
    private static String markerTag(String mod) {
        return mod.equals("arcadehall")?"arcadehall_table":mod+"_outlet";
    }
    /** Exact world observation, not a second nearest-site lookup. Ordinary access is determined by the service. */
    private static CounterVisit.Observation observe(ServerPlayerEntity actor,UUID deployment,CounterVisit.Task task) {
        var stamp=SocietyBotsMod.ordinaryStamp(actor);
        if(stamp==null) return null;
        var mod=tag(task.service());
        if(mod.isEmpty() || !FabricLoader.getInstance().isModLoaded(mod)) return null;
        var world=actor.getServerWorld();
        if(!(world.getEntity(deployment) instanceof InteractionEntity marker)
                || !marker.isAlive() || !marker.getCommandTags().contains(markerTag(mod))
                || !reachable(actor,marker) || !registered(actor.getServer(),marker,task.service())) return null;
        var dim=world.getRegistryKey().getValue().toString();
        var eye=actor.getEyePos();
        var center=marker.getBoundingBox().getCenter();
        var anchor=new CounterVisit.Point(dim,center.x,center.y,center.z);
        // The entity UUID changes on replacement; coordinates and live registry metadata are rechecked on every observation.
        var site=new CounterVisit.Deployment(marker.getUuid(),0,task.service(),anchor,REACH);
        return new CounterVisit.Observation(new CounterVisit.Point(dim,eye.x,eye.y,eye.z),site,true,false,true,stamp);
    }
    private static boolean reachable(ServerPlayerEntity actor,InteractionEntity marker) {
        var world=actor.getServerWorld();
        if(marker.isRemoved()||marker.getWorld()!=world) return false;
        var target=marker.getBoundingBox().getCenter();
        var eye=actor.getEyePos();
        var base=BlockPos.ofFloored(marker.getX(),marker.getY()-0.125,marker.getZ());
        if(eye.squaredDistanceTo(target)>REACH*REACH || !world.getWorldBorder().contains(base)) return false;
        var chunk=world.getChunkManager().getWorldChunk(base.getX()>>4,base.getZ()>>4);
        if(chunk==null || chunk.getBlockState(base).isAir()) return false;
        // A four-block segment can only intersect chunks inside the envelope of its endpoints.
        for(int x=(int)Math.floor(Math.min(eye.x,target.x))>>4;x<=((int)Math.floor(Math.max(eye.x,target.x))>>4);x++)
            for(int z=(int)Math.floor(Math.min(eye.z,target.z))>>4;z<=((int)Math.floor(Math.max(eye.z,target.z))>>4);z++)
                if(world.getChunkManager().getWorldChunk(x,z)==null) return false;
        return world.raycast(new RaycastContext(eye,target,RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,actor)).getType()==HitResult.Type.MISS;
    }
    private static boolean registered(MinecraftServer server,InteractionEntity marker,CounterVisit.Service service) {
        try {
            return switch(service) {
                case BANK -> {
                    var runtime=Class.forName("dev.jdguo.bank.persistence.BankRuntime");
                    var books=runtime.getMethod("books").invoke(null);
                    var row=books.getClass().getMethod("outletByMarker",java.util.UUID.class).invoke(books,marker.getUuid());
                    yield matches(marker,row,"world");
                }
                case SHOP -> {
                    var name=key(marker,"shopmod_outlet_name_");
                    if(name.isEmpty()) yield false;
                    var type=Class.forName("dev.jdguo.shop.outlet.ShopOutletState");
                    var state=type.getMethod("get",MinecraftServer.class).invoke(null,server);
                    yield matches(marker,type.getMethod("find",String.class).invoke(state,name.orElseThrow()),"dimension");
                }
                case MARKET -> {
                    var name=key(marker,"marketmod_outlet_name:");
                    if(name.isEmpty()) yield false;
                    var type=Class.forName("dev.jdguo.market.outlet.MarketOutlets");
                    var state=(PersistentState)type.getMethod("get",MinecraftServer.class).invoke(null,server);
                    // Serialize a read-only in-memory view, never save it.
                    var rows=state.writeNbt(new NbtCompound()).getList("outlets",10);
                    if(rows.size()>128) yield false;
                    boolean match=false;
                    for(int i=0;i<rows.size();i++) {
                        var row=rows.getCompound(i);
                        if(name.orElseThrow().equals(row.getString("name")) && sameSite(marker,row.getString("world"),
                                row.getInt("x"),row.getInt("y"),row.getInt("z"))) { match=true;break; }
                    }
                    yield match;
                }
                case ARCADE, ARCADE_TABLE -> arcade(server,marker,service);
                default -> false;
            };
        } catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable) { return false; }
    }
    private static boolean arcade(MinecraftServer server,InteractionEntity marker,CounterVisit.Service service)
            throws ReflectiveOperationException {
        var markerId=key(marker,"arcadehall_id_").map(UUID::fromString).orElse(null);
        if(markerId==null) return false;
        // Optional, versioned-by-schema observation only. No call to play/settlement/private actions.
        var getter=Class.forName("dev.jdguo.arcade.ArcadeTables").getDeclaredMethod("get",MinecraftServer.class);
        if(!getter.trySetAccessible()) return false;
        var rows=((PersistentState)getter.invoke(null,server)).writeNbt(new NbtCompound()).getList("tables",10);
        if(rows.size()>256) return false;
        ArcadeVenue found=null;
        for(int i=0;i<rows.size();i++){
            var row=rows.getCompound(i);
            if(!row.containsUuid("id") || !markerId.equals(row.getUuid("id"))) continue;
            if(found!=null || !row.contains("base",4)) return false;
            var base=BlockPos.fromLong(row.getLong("base"));
            found=new ArcadeVenue(markerId,row.getString("dimension"),base.getX(),base.getY(),base.getZ(),
                    row.getString("game"),row.getString("skin"));
        }
        var base=BlockPos.ofFloored(marker.getX(),marker.getY()-0.125,marker.getZ());
        String dim=marker.getWorld().getRegistryKey().getValue().toString();
        return found!=null && found.matches(service,dim,base.getX(),base.getY(),base.getZ(),marker.getCommandTags())
                && sameSite(marker,dim,found.x(),found.y(),found.z());
    }
    private static Optional<String> key(InteractionEntity marker,String prefix) {
        var tags=marker.getCommandTags().stream().filter(t->t.startsWith(prefix)).limit(2).toList();
        return tags.size()==1?Optional.of(tags.get(0).substring(prefix.length())):Optional.empty();
    }
    private static boolean matches(InteractionEntity marker,Object row,String dimension) throws ReflectiveOperationException {
        if(row==null) return false;
        var type=row.getClass();
        return sameSite(marker,(String)type.getMethod(dimension).invoke(row),(int)type.getMethod("x").invoke(row),
                (int)type.getMethod("y").invoke(row),(int)type.getMethod("z").invoke(row));
    }
    private static boolean sameSite(InteractionEntity e,String dim,int x,int y,int z) {
        return Objects.equals(e.getWorld().getRegistryKey().getValue().toString(),dim)
            && Math.abs(e.getX()-x-0.5)<0.05 && Math.abs(e.getY()-y-1.0)<0.05 && Math.abs(e.getZ()-z-0.5)<0.05;
    }
}
