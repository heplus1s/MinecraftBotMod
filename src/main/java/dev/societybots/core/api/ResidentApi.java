package dev.societybots.core.api;

import java.util.*;
import dev.societybots.core.CounterVisit;

/**
 * Integration boundary, v2. No Minecraft internals or administrator credentials.
 * Modules register names before server start; only the server may execute a permitted action.
 */
public final class ResidentApi {
    public static final int VERSION = 2;
    private ResidentApi() {}
    public enum Render { NORMAL, LOW_DETAIL, HIDDEN }
    public enum Activity { ACTIVE, IDLE, OFFLINE }
    public record Identity(UUID id, String label, Optional<UUID> controller) {
        public Identity {
            Objects.requireNonNull(id); Objects.requireNonNull(label); Objects.requireNonNull(controller);
            if (label.isBlank() || label.length()>48 || controller.filter(id::equals).isPresent())
                throw new IllegalArgumentException("resident identity");
        }
    }
    /** Render preferences are visual only; they do not control server physics. */
    public record Limits(Render render, int maxTrackedChunks, int maxPlansPerSecond) {
        public Limits {
            Objects.requireNonNull(render);
            if (maxTrackedChunks<0 || maxTrackedChunks>4096 || maxPlansPerSecond<0 || maxPlansPerSecond>20)
                throw new IllegalArgumentException("resident limits");
        }
        public boolean allowsPlanning(Activity state, int trackedChunks, int plannedThisSecond) {
            return state==Activity.ACTIVE && trackedChunks>=0 && trackedChunks<=maxTrackedChunks
                    && plannedThisSecond>=0 && plannedThisSecond<maxPlansPerSecond;
        }
    }
    public record Context(Identity resident, long revision) {
        public Context { Objects.requireNonNull(resident); if(revision<0) throw new IllegalArgumentException("revision"); }
    }
    public record Intent(String action, String targetRef, long observationRevision) {
        public Intent {
            Objects.requireNonNull(action); Objects.requireNonNull(targetRef);
            if (!action.matches("[a-z0-9_]{1,32}:[a-z0-9_]{1,48}") || targetRef.isBlank()
                    || targetRef.length()>160 || observationRevision<0) throw new IllegalArgumentException("intent");
        }
    }
    public enum Result { APPLIED, REJECTED, PENDING }
    public interface Action {
        /** Read-only, fail-closed permission and resource check using only the resident's identity. */
        boolean mayExecute(Context context, Intent intent);
        /** May be called only on the game server thread. Pending must reconcile before another submission. */
        Result execute(Context context, Intent intent);
    }
    private static final Map<String,Set<CounterVisit.Service>> REQUIRED_VENUE = Map.of(
            "bank", Set.of(CounterVisit.Service.BANK),
            "shop", Set.of(CounterVisit.Service.SHOP),
            "market", Set.of(CounterVisit.Service.MARKET),
            "land", Set.of(CounterVisit.Service.LAND),
            "arcade", Set.of(CounterVisit.Service.ARCADE,CounterVisit.Service.ARCADE_TABLE),
            "arcadehall", Set.of(CounterVisit.Service.ARCADE,CounterVisit.Service.ARCADE_TABLE));
    private static String canonical(String namespace) {
        return namespace.endsWith("mod")?namespace.substring(0,namespace.length()-3):namespace;
    }
    private record CounterAction(CounterVisit.Service service,CounterVisit visits,Action action) {}
    @FunctionalInterface public interface ActorGate {
        /** The live ordinary resident is present and was neither substituted nor elevated. */
        boolean ordinary(Identity resident);
    }
    public static final class Registry {
        private final ActorGate residents;
        public Registry(ActorGate residents) { this.residents=Objects.requireNonNull(residents); }
        private final Map<String,Action> actions = new LinkedHashMap<>();
        private final Map<String,CounterAction> counters=new LinkedHashMap<>();
        private boolean sealed;
        public void register(String namespace, String name, Action provider) {
            if (sealed) throw new IllegalStateException("registry already initialized");
            String id = namespace + ":" + name;
            new Intent(id,"registration",0);
            if(REQUIRED_VENUE.containsKey(canonical(namespace)))
                throw new IllegalArgumentException("service requires registerAtCounter: "+id);
            if (counters.containsKey(id)||actions.putIfAbsent(id,Objects.requireNonNull(provider))!=null)
                throw new IllegalArgumentException("duplicate action: " + id);
        }
        public void registerAtCounter(String namespace,String name,CounterVisit.Service service,CounterVisit visits,Action provider) {
            if(sealed) throw new IllegalStateException("registry already initialized");
            String id=namespace+":"+name;
            new Intent(id,"registration",0);
            var required = REQUIRED_VENUE.get(canonical(namespace));
            if(required!=null&&!required.contains(service))
                throw new IllegalArgumentException("wrong venue for "+id);
            if(actions.containsKey(id)||counters.putIfAbsent(id,new CounterAction(Objects.requireNonNull(service),
                    Objects.requireNonNull(visits),Objects.requireNonNull(provider)))!=null)
                throw new IllegalArgumentException("duplicate action: "+id);
        }
        public Set<String> allowedActions() {
            var ids=new HashSet<>(actions.keySet());ids.addAll(counters.keySet());
            return Set.copyOf(ids);
        }
        public Result dispatchAtCounter(Context current, Intent intent, CounterVisit.Proof proof, long serverTick) {
            if(!sealed||current.revision()!=intent.observationRevision()||!residents.ordinary(current.resident())) return Result.REJECTED;
            var entry=counters.get(intent.action());
            if(entry==null||!entry.action().mayExecute(current,intent)) return Result.REJECTED;
            var request=new CounterVisit.Task(entry.service(),intent.action(),intent.targetRef());
            return !entry.visits().consume(proof,current.resident().id(),request,serverTick)
                ?Result.REJECTED:Objects.requireNonNull(entry.action().execute(current,intent));
        }
        public void seal() { sealed=true; }
        public Result dispatch(Context current, Intent intent) {
            if (!sealed || current.revision()!=intent.observationRevision() || !residents.ordinary(current.resident())) return Result.REJECTED;
            var action=actions.get(intent.action());
            return action==null || !action.mayExecute(current,intent)
                    ? Result.REJECTED : Objects.requireNonNull(action.execute(current,intent));
        }
    }
}
