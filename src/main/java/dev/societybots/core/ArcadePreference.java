package dev.societybots.core;

import java.util.Objects;

/** Used for optional in-game leisure. This does not authorize an action or calculate a charge. */
public final class ArcadePreference {
    private ArcadePreference() {}

    public record Conditions(boolean enabled, boolean autonomous, boolean ownerApproved,
                             boolean reachableTable, boolean urgentTask, int availablePoints,
                             int protectedPoints, int remainingBudget, int sessionCost, long tick,
                             long nextAllowedTick) {}

    public static boolean consider(ResidentTraits traits, Conditions c, int roll) {
        Objects.requireNonNull(traits);
        Objects.requireNonNull(c);
        if (roll < 0 || roll >= 100) throw new IllegalArgumentException("roll");
        if (!c.enabled() || !c.autonomous() || !c.ownerApproved() || !c.reachableTable() || c.urgentTask()
                || c.availablePoints() < 0 || c.protectedPoints() < 0 || c.remainingBudget() <= 0
                || c.sessionCost() <= 0 || c.tick() < 0 || c.nextAllowedTick() < 0 || c.tick() < c.nextAllowedTick()) {
            return false;
        }
        return c.sessionCost() <= c.remainingBudget()
                && c.sessionCost() <= c.availablePoints() - c.protectedPoints()
                && roll < traits.greed();
    }
}
