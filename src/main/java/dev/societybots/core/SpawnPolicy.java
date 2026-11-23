package dev.societybots.core;

import java.util.Objects;
import java.util.Optional;

/** First join only. A spawn choice never authorizes any block changes or tasks. */
public final class SpawnPolicy {
    private SpawnPolicy() {}

    public record Position(String dimension,int x,int y,int z) {
        public Position {
            if(dimension==null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("spawn dimension id");
        }
    }
    public record Safety(boolean loaded,boolean withinWorld,boolean fullSafeFloor,
                         boolean twoClearBlocks,boolean hazardous,boolean ordinaryEntry,
                         boolean exitWithoutBreakingOrBuilding) {
        public boolean safe() {
            return loaded && withinWorld && fullSafeFloor && twoClearBlocks && !hazardous
                    && ordinaryEntry && exitWithoutBreakingOrBuilding;
        }
    }
    @FunctionalInterface public interface World {
        /** Inspection must not load chunks, mutate the world, or use the administrator's access. */
        Safety inspect(Position at);
    }
    public static Optional<Position> firstJoin(Optional<Position> adminSelected,
                                               Optional<Position> serverSpawn,World world) {
        Objects.requireNonNull(adminSelected);
        Objects.requireNonNull(serverSpawn);
        Objects.requireNonNull(world);
        // Invalid explicit selection never falls back to a different administrative area.
        return adminSelected.or(()->serverSpawn).filter(p->{
            var safety=world.inspect(p);
            return safety!=null&&safety.safe();
        });
    }
}
