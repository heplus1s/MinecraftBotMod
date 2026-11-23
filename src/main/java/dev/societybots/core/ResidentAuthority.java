package dev.societybots.core;

import java.util.Objects;
import java.util.UUID;

/** No resident action uses an administrative, simulated owner or detached session identity. */
public final class ResidentAuthority {
    private ResidentAuthority() {}

    /** Changes on reconnect, respawn, or an observed loss of survival eligibility. */
    public record Stamp(UUID session, long revision) {
        public Stamp {
            Objects.requireNonNull(session);
            if(revision<0) throw new IllegalArgumentException("negative authority revision");
        }
    }

    public record Live(boolean registered, boolean fakePlayer, boolean sameSession, boolean alive,
                       boolean survival, boolean operator, boolean elevated) {
        public boolean ordinaryActor() {
            return registered && fakePlayer && sameSession && alive
                    && survival && !operator && !elevated;
        }
    }
}
