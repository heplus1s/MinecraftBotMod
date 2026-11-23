package dev.societybots.core;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-side, single-use proof of physical presence. Never construct observations from model or command input. */
public final class CounterVisit {
    public enum Service { BANK, SHOP, MARKET, LAND, ARCADE, ARCADE_TABLE }
    public record Task(Service service,String action,String reference) {
        public Task {
            Objects.requireNonNull(service);
            if(action==null||action.isBlank()||action.length()>64||reference==null||reference.isBlank()||reference.length()>512)
                throw new IllegalArgumentException("invalid counter task");
        }
    }
    public record Point(String dimension,double x,double y,double z) {
        public Point {
            if(dimension==null||dimension.isBlank()||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))
                throw new IllegalArgumentException("invalid point");
        }
        boolean within(Point other,double reach) {
            return dimension.equals(other.dimension)&&Math.hypot(Math.hypot(x-other.x,y-other.y),z-other.z)<=reach;
        }
    }
    public record Deployment(UUID instance,long revision,Service service,Point anchor,double reach) {
        public Deployment {
            Objects.requireNonNull(instance); Objects.requireNonNull(service); Objects.requireNonNull(anchor);
            if(revision<0||!Double.isFinite(reach)||reach<=0||reach>6) throw new IllegalArgumentException("deployment");
        }
    }
    public record Observation(Point actorPosition,Deployment liveDeployment,boolean online,boolean ordinaryAccess,boolean unobstructed,ResidentAuthority.Stamp authority) {}
    @FunctionalInterface public interface WorldView {
        /** Re-resolve the service's own registered deployment in the resident's loaded world, not a named label. */
        Observation inspect(UUID bot, UUID deployment, Task task);
    }
    public static final int MAX_AGE_TICKS=40;
    public static final class Proof {
        private final CounterVisit issuer;
        private final UUID bot;
        private final Deployment deployment;
        private final Task task;
        private final long issuedAt;
        private final ResidentAuthority.Stamp authority;
        private boolean spent;
        private Proof(CounterVisit issuer, UUID bot, Deployment deployment, Task task,long issuedAt,ResidentAuthority.Stamp authority) {
            this.issuer=issuer; this.bot=bot; this.deployment=deployment; this.task=task; this.issuedAt=issuedAt; this.authority=authority;
        }
    }
    private final WorldView world;
    public CounterVisit(WorldView world) { this.world=Objects.requireNonNull(world); }
    /** Checks presence at the actual deployment on the server; opening a menu is not required. */
    public Optional<Proof> atDeployment(UUID bot,UUID deployment,Task task,long tick) {
        if(tick<0||bot==null||deployment==null||task==null) return Optional.empty();
        var seen=world.inspect(bot,deployment,task);
        return present(seen,deployment,task)?Optional.of(new Proof(this,bot,seen.liveDeployment,task,tick,seen.authority)):Optional.empty();
    }
    public boolean consume(Proof proof,UUID bot,Task task,long tick) {
        if(proof==null||proof.issuer!=this||!proof.bot.equals(bot)||!proof.task.equals(task)) return false;
        synchronized(proof) {
            if(proof.spent) return false;
            // An attempt by the intended actor on the intended order closes this ticket,
            // even when the venue, authority or quote is no longer usable.
            proof.spent=true;
        }
        if(tick<proof.issuedAt||tick-proof.issuedAt>MAX_AGE_TICKS) return false;
        var seen=world.inspect(bot,proof.deployment.instance(),task);
        return present(seen,proof.deployment.instance(),task) && proof.deployment.equals(seen.liveDeployment)
                && proof.authority.equals(seen.authority);
    }
    private static boolean present(Observation seen,UUID deployment,Task task) {
        if(seen==null||!seen.online||!seen.ordinaryAccess||!seen.unobstructed||seen.actorPosition==null||seen.liveDeployment==null||seen.authority==null) return false;
        var live=seen.liveDeployment;
        return live.instance.equals(deployment)&&live.service==task.service&&seen.actorPosition.within(live.anchor,live.reach);
    }
}
