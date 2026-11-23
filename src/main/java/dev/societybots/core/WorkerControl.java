package dev.societybots.core;

import java.util.*;
import java.util.function.Predicate;

/** Commands and managed routines. Access is checked against the authenticated command actor, not the bot. */
public final class WorkerControl {
    public enum Kind { PLAYER, MANAGED }
    private final UUID playerOwner; // null for managed bots
    private final Kind kind;
    private final UUID driver = UUID.randomUUID(); // internal control capability, not the in-game identity
    private final MiningWork mining;
    private final Predicate<UUID> isAdministrator;
    private List<MiningWork.Job> allowedJobs = List.of();
    private boolean autonomous, retired;
    private int cursor;
    private long cooldown = 200, due = 0;

    private WorkerControl(Kind kind, UUID owner, MiningWork.Port port, Predicate<UUID> isAdministrator) {
        this.kind = Objects.requireNonNull(kind);
        this.playerOwner = owner;
        this.mining = new MiningWork(driver,port);
        this.isAdministrator = Objects.requireNonNull(isAdministrator);
    }
    public static WorkerControl player(UUID playerOwner, MiningWork.Port port) {
        return new WorkerControl(Kind.PLAYER, Objects.requireNonNull(playerOwner), port, x -> false);
    }
    public static WorkerControl managed(MiningWork.Port port, Predicate<UUID> currentAdministratorCheck) {
        return new WorkerControl(Kind.MANAGED, null, port, currentAdministratorCheck);
    }
    public Kind kind() { return kind; }
    public Optional<UUID> owner() { return Optional.ofNullable(playerOwner); }
    public boolean canControl(UUID actor) {
        return !retired && actor != null && (kind == Kind.PLAYER ? playerOwner.equals(actor) : isAdministrator.test(actor));
    }
    public boolean configureManaged(UUID actor, List<MiningWork.Job> allowlist, long restTicks) {
        if (kind != Kind.MANAGED || !canControl(actor) || autonomous || !canStart()) return false;
        if (allowlist == null || allowlist.isEmpty() || allowlist.size()>8 || restTicks<20 || restTicks>72_000)
            return false;
        allowedJobs = List.copyOf(allowlist); cooldown = restTicks; due = 0; cursor = 0; return true;
    }
    public boolean enableManaged(UUID actor) {
        if (kind != Kind.MANAGED || !canControl(actor) || allowedJobs.isEmpty()) return false;
        autonomous = true; return true;
    }
    public boolean order(UUID actor, MiningWork.Job job) {
        if (!canControl(actor) || (kind == Kind.MANAGED && !allowedJobs.contains(job))) return false;
        return mining.start(driver,job);
    }
    public boolean pause(UUID actor) { return canControl(actor) && mining.pause(driver); }
    public boolean resume(UUID actor) { return canControl(actor) && mining.resume(driver); }
    public boolean stop(UUID actor) {
        if (!canControl(actor)) return false;
        boolean old = autonomous;
        autonomous = false;
        return mining.stop(driver) || old;
    }
    public MiningWork.State state() { return mining.state(); }
    public String reason() { return mining.reason(); }
    public boolean autonomous() { return autonomous; }
    public boolean active() { return !retired; }
    /** Called only by the trusted server orchestrator after persisting a change of control. */
    void retire() {
        autonomous=false; retired=true; mining.stop(driver);
    }
    private boolean canStart() {
        return switch(mining.state()) {
            case IDLE, STOPPED, COMPLETE -> true;
            default -> false;
        };
    }
    /** Server-thread only, using a nonnegative monotonic tick counter. */
    public void tick(long now) {
        if (retired) return;
        if (now < 0) throw new IllegalArgumentException("negative tick");
        if (autonomous && now >= due && canStart()) {
            // Round robin over explicitly configured tasks; never generate coordinates or budgets.
            for (int i=0; i<allowedJobs.size(); i++) {
                var job=allowedJobs.get(cursor);
                cursor=(cursor+1)%allowedJobs.size();
                if (mining.start(driver,job)) break;
            }
            due=now>Long.MAX_VALUE-cooldown ? Long.MAX_VALUE : now+cooldown;
        }
        var before=mining.state();
        mining.tick();
        if (before!=MiningWork.State.COMPLETE && mining.state()==MiningWork.State.COMPLETE) {
            due=now>Long.MAX_VALUE-cooldown ? Long.MAX_VALUE : now+cooldown;
        }
    }
}
