package dev.societybots.core;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** No commands, coordinates or permission changes can be emitted by this decision gate. */
public final class PlanGate {
    private PlanGate() {}
    public enum Goal { MINE, UNLOAD, STOP }
    public record Observation(long revision, boolean safeMine, boolean cargo, boolean storageReady,
                              Optional<ResidentAuthority.Stamp> authority) {
        public Observation {
            if(revision<0) throw new IllegalArgumentException("negative observation revision");
            Objects.requireNonNull(authority);
        }
        /** Simulated observations can suggest goals but cannot authorize execution. */
        public Observation(long revision, boolean safeMine, boolean cargo, boolean storageReady) {
            this(revision,safeMine,cargo,storageReady,Optional.empty());
        }
        public boolean permits(Goal goal) {
            return switch(goal) {
                case MINE -> safeMine && (!cargo || storageReady);
                case UNLOAD -> cargo && storageReady;
                case STOP -> true;
            };
        }
        public Goal fallback() {
            return permits(Goal.UNLOAD)?Goal.UNLOAD:permits(Goal.MINE)?Goal.MINE:Goal.STOP;
        }
    }
    public record Ticket(Observation observed, long sentAt, long budgetNanos) {
        public Ticket {
            Objects.requireNonNull(observed);
            if(budgetNanos<=0) throw new IllegalArgumentException("budget");
        }
        /** World execution must use live observations; synthetic tests are not authority. */
        public Optional<Goal> acceptForExecution(String candidate,Observation current,long now) {
            return current==null || current.authority().isEmpty()
                    ? Optional.empty() : accept(candidate,current,now);
        }
        public Optional<Goal> accept(String candidate, Observation current, long now) {
            if(!observed.equals(current) || now-sentAt<0 || now-sentAt>=budgetNanos) return Optional.empty();
            try {
                Goal goal=Goal.valueOf(Objects.requireNonNullElse(candidate,"").strip().toUpperCase(Locale.ROOT));
                return current.permits(goal)?Optional.of(goal):Optional.empty();
            } catch(IllegalArgumentException e) { return Optional.empty(); }
        }
    }
}
