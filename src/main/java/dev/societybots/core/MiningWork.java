package dev.societybots.core;

import java.util.*;
import static dev.societybots.core.Routes.Pos;

/** Owner-driven mining loop. No transaction or permission bypass exists in this layer. */
public final class MiningWork {
    public enum State { IDLE, TO_MINE, MINING, TO_HUB, UNLOAD, PAUSED, COMPLETE, BLOCKED, STOPPED }
    public enum Result { WAIT, DONE, BLOCKED }
    public enum Origin { VERIFIED_NATURAL, PLACED, UNKNOWN }
    public enum Material { STONE, DEEPSLATE, DIAMOND_ORE, COPPER_ORE, OTHER }
    public record Area(Pos min, Pos max) {
        public Area {
            Objects.requireNonNull(min); Objects.requireNonNull(max);
            long x = (long) max.x()-min.x()+1, y = (long) max.y()-min.y()+1, z = (long) max.z()-min.z()+1;
            if (x < 1 || y < 1 || z < 1 || x > 128 || y > 128 || z > 128 || x*y*z > 262_144)
                throw new IllegalArgumentException("mine bounds");
        }
        public boolean includes(Pos p) {
            return p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
        }
    }
    public record Job(String dimension, Area mine, Pos mineEntry, Pos hub, Pos furnace, Pos chest, int maxBreaks) {
        public Job {
            if (dimension == null || dimension.isBlank() || mine == null || mineEntry == null || !mine.includes(mineEntry)
                    || hub == null || furnace == null || chest == null || furnace.equals(chest)
                    || maxBreaks < 1 || maxBreaks > 4096) throw new IllegalArgumentException("job settings");
        }
    }
    public record Target(Pos pos, Material material, Origin origin, long revision) {
        public Target { Objects.requireNonNull(pos); Objects.requireNonNull(material); Objects.requireNonNull(origin); }
    }
    public interface Port {
        String dimension();
        boolean isLoaded(Pos pos);
        boolean mayBreak(Pos pos);
        boolean mayUse(Pos pos);
        boolean shouldReturnToHub(); // cargo or low supplies; adapter must count only actual inventory
        Optional<Target> chooseNext(Area bounds); // bounded search; never infer natural origin from material
        Optional<Target> observe(Pos pos);
        Result travelTo(Pos destination); // observe rail/boat edges, do not teleport or trigger elevated commands
        /** Check token immediately before normal break; never mutate on WAIT/BLOCKED. */
        Result mine(Target token);
        /** Only work cargo; vanilla furnace recipes/fuel; if more work remains, replenish or block. No drops, copies or bypass. */
        Result deliver(Pos furnace, Pos chest, boolean completeAfterUnload);
        /**  Cancel remaining movement, mining progress and vehicle control. No uncommitted actions may survive. */
        void halt();
    }
    private final UUID owner;
    private final Port port;
    private Job job;
    private State state = State.IDLE, resumeAt = State.IDLE;
    private int spent;
    private boolean finishAtHub;
    private String reason = "";

    public MiningWork(UUID owner, Port port) {
        this.owner = Objects.requireNonNull(owner);
        this.port = Objects.requireNonNull(port);
    }
    public boolean start(UUID sender, Job value) {
        if (!owner.equals(sender) || !(state == State.IDLE || state == State.STOPPED || state == State.COMPLETE)) return false;
        Objects.requireNonNull(value);
        if (!value.dimension().equals(port.dimension())) return false;
        job = value; spent = 0; finishAtHub = false;
        reason = ""; state = State.TO_MINE; return true;
    }
    public boolean pause(UUID sender) {
        if (!owner.equals(sender) || !running()) return false;
        port.halt(); resumeAt = state; state = State.PAUSED; return true;
    }
    public boolean resume(UUID sender) {
        if (!owner.equals(sender) || !(state == State.PAUSED || state == State.BLOCKED)) return false;
        if (!job.dimension().equals(port.dimension())) return false;
        reason = ""; state = resumeAt; return true;
    }
    public boolean stop(UUID sender) {
        if (!owner.equals(sender) || state == State.IDLE || state == State.STOPPED) return false;
        port.halt(); reason = ""; state = State.STOPPED; return true;
    }
    public State state() { return state; }
    public String reason() { return reason; }
    public int breaks() { return spent; }
    private boolean running() {
        return switch(state) { case TO_MINE, MINING, TO_HUB, UNLOAD -> true; default -> false; };
    }
    private void block(String why) {
        port.halt(); resumeAt = state; state = State.BLOCKED; reason = why;
    }
    public void tick() {
        if (!running()) return;
        if (!job.dimension().equals(port.dimension())) { block("dimension"); return; }
        switch (state) {
            case TO_MINE -> travel(job.mineEntry(), State.MINING);
            case TO_HUB -> travel(job.hub(), State.UNLOAD);
            case UNLOAD -> {
                if (!port.isLoaded(job.furnace()) || !port.isLoaded(job.chest())
                        || !port.mayUse(job.furnace()) || !port.mayUse(job.chest())) { block("hub_access"); return; }
                var delivered = port.deliver(job.furnace(), job.chest(), finishAtHub);
                if (delivered == Result.BLOCKED) block("storage_or_furnace");
                if (delivered == Result.DONE) state = finishAtHub ? State.COMPLETE : State.TO_MINE;
            }
            case MINING -> {
                if (spent >= job.maxBreaks() || port.shouldReturnToHub()) {
                    finishAtHub = spent >= job.maxBreaks(); state = State.TO_HUB; return;
                }
                var selected = port.chooseNext(job.mine());
                if (selected.isEmpty()) { finishAtHub = true; state = State.TO_HUB; return; }
                var token = selected.orElseThrow();
                var pos = token.pos();
                if (!job.mine().includes(pos) || !port.isLoaded(pos)
                        || token.origin() != Origin.VERIFIED_NATURAL
                        || token.material() == Material.OTHER || !port.mayBreak(pos)
                        || port.observe(pos).filter(token::equals).isEmpty()) {
                    block("unsafe_target"); return;
                }
                var result = port.mine(token);
                if (result == Result.BLOCKED) block("mine_unreachable");
                if (result == Result.DONE) spent++;
            }
            default -> throw new IllegalStateException("unreachable");
        }
    }
    private void travel(Pos pos, State next) {
        var result = port.travelTo(pos);
        if (result == Result.DONE) state = next;
        if (result == Result.BLOCKED) block("route_unavailable");
    }
}
