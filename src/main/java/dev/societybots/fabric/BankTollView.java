package dev.societybots.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.societybots.core.TransitTolls;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;

/** Optional and read-only. Never read legacy files: after migration they are not authoritative. */
final class BankTollView {
    private BankTollView() {}
    static Optional<List<TransitTolls.Zone>> read() {
        if(!FabricLoader.getInstance().isModLoaded("bankmod") || !FabricLoader.getInstance().isModLoaded("tollmod")) return Optional.empty();
        try {
            // Bank's snapshot API requires the server thread; invoked only by an OP command, never per tick.
            var api=Class.forName("dev.jdguo.bank.api.TollSettlement");
            return decode((String)api.getMethod("loadState").invoke(null));
        } catch(ReflectiveOperationException|RuntimeException|LinkageError bad) { return Optional.empty(); }
    }
    static Optional<List<TransitTolls.Zone>> decode(String json) {
        if(json==null||json.length()>1024*1024) return Optional.empty();
        try {
            var envelope=JsonParser.parseString(json).getAsJsonObject();
            if(!envelope.get("success").getAsBoolean()||!envelope.has("revision")) return Optional.empty();
            var payload=envelope.get("payload");
            if(payload==null||payload.isJsonNull()) return Optional.empty();
            var state=JsonParser.parseString(payload.getAsString()).getAsJsonObject();
            if(state.get("schema").getAsInt()!=1) return Optional.empty();
            var points=state.getAsJsonObject("points");
            if(points.size()>128) return Optional.empty();
            var list=new ArrayList<TransitTolls.Zone>();
            var ids=new java.util.HashSet<String>();
            for(var entry:points.entrySet()) {
                var p=entry.getValue().getAsJsonObject();
                var id=java.util.UUID.fromString(p.get("id").getAsString()).toString();
                if(!ids.add(id)) return Optional.empty();
                list.add(new TransitTolls.Zone(id,p.get("dimension").getAsString(),num(p,"x"),num(p,"y"),num(p,"z"),
                    num(p,"radius"),has(p,"halfZ")?num(p,"halfZ"):null,
                    has(p,"below")?num(p,"below"):numOr(p,"height",4),
                    has(p,"above")?num(p,"above"):numOr(p,"height",4)));
            }
            return Optional.of(List.copyOf(list));
        } catch(RuntimeException bad) { return Optional.empty(); }
    }
    private static boolean has(JsonObject p,String name) { return p.has(name)&&!p.get(name).isJsonNull(); }
    private static double num(JsonObject p,String name) { return p.get(name).getAsDouble(); }
    private static double numOr(JsonObject p,String name,double d) { return has(p,name)?num(p,name):d; }
}
