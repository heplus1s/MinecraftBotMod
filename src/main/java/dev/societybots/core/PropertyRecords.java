package dev.societybots.core;

import java.util.*;
import dev.societybots.core.Routes.Pos;

/** Server-side provenance. The adapter calls these methods on confirmed mutations, never based on model text. */
public final class PropertyRecords {
    public record BlockKey(String dimension, Pos pos) {
        public BlockKey { Objects.requireNonNull(dimension); Objects.requireNonNull(pos); }
    }
    public record Building(UUID builder, String blockState, long revision) {
        public Building {
            Objects.requireNonNull(builder); Objects.requireNonNull(blockState);
            if (revision < 0) throw new IllegalArgumentException("block revision");
        }
    }
    public record Cargo(UUID beneficialOwner, Optional<UUID> contract, int count) {
        public Cargo {
            Objects.requireNonNull(beneficialOwner); Objects.requireNonNull(contract);
            if (count < 1) throw new IllegalArgumentException("count");
        }
        public boolean belongsTo(UUID bot) { return contract.isEmpty() && beneficialOwner.equals(bot); }
    }
    private final Map<BlockKey,Building> placements = new HashMap<>();
    /** Any human/system mutation, including indistinguishable replacement, must invalidate the previous stamp. */
    public void changed(BlockKey block) { placements.remove(Objects.requireNonNull(block)); }
    public void botPlaced(BlockKey block, Building placement) { placements.put(Objects.requireNonNull(block),Objects.requireNonNull(placement)); }
    public boolean knownBotBlock(UUID bot, BlockKey block, String currentState, long currentRevision) {
        var evidence = placements.get(block);
        return evidence!=null && evidence.builder().equals(bot) && evidence.blockState().equals(currentState)
                && evidence.revision()==currentRevision;
    }
}
