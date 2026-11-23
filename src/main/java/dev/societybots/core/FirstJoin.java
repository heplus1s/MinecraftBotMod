package dev.societybots.core;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** First-join orchestration. Only a trusted server-thread adapter may register or activate a session. */
public final class FirstJoin {
    private FirstJoin() {}

    public enum Result { ACTIVE, NOT_ALLOWED, UNSAFE, FAILED, RECONCILE }
    public record Identity(UUID id,String name) {
        public Identity {
            Objects.requireNonNull(id);
            if(name==null||!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("game profile");
        }
    }
    public record Started(Identity identity,UUID session,SpawnPolicy.Position position,
                          boolean fake,boolean survival,boolean flying,boolean operator,boolean elevated) {
        public Started {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(session);
            Objects.requireNonNull(position);
        }
        private boolean matches(Identity expected,SpawnPolicy.Position point) {
            return expected.equals(identity) && point.equals(position)
                    && fake && survival && !flying && !operator && !elevated;
        }
    }
    public interface Port {
        /**
         * Check both profile name and UUID. Deny collisions, ordinary foreign player data, banned or unauthorized
         * profiles, and any unfinished prior join. An explicitly reserved Bot profile is required.
         */
        boolean canJoin(Identity bot);
        /** Read without terrain mutation or loading chunks, as the prospective ordinary resident. */
        SpawnPolicy.Safety inspect(Identity bot,SpawnPolicy.Position point);
        /** Do not enroll the result yet. Pass explicit Survival and no flying to the concrete factory. */
        Optional<Started> createSurvival(Identity bot,SpawnPolicy.Position point);
        /** Safely publish only if exactly this session still exists with the confirmed position and ordinary state. */
        boolean activateIfCurrent(Started started);
        /** Clean up only the exact unactivated fake-player session created by this attempt. Not a name-based kick. */
        boolean discardUnactivated(Started started);
    }
    public static Result start(Identity expected,Optional<SpawnPolicy.Position> chosen,
                               Optional<SpawnPolicy.Position> serverSpawn,Port port) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(port);
        if(!port.canJoin(expected)) return Result.NOT_ALLOWED;
        var point=SpawnPolicy.firstJoin(chosen,serverSpawn,p->port.inspect(expected,p));
        if(point.isEmpty()) return Result.UNSAFE;
        // No privileged caller identity, arbitrary callback, or administrative command crosses this boundary.
        // Once factory execution begins, failure can be ambiguous. Do not automatically retry or kick an unrelated player.
        try {
            var result=port.createSurvival(expected,point.orElseThrow());
            if(result.isEmpty()) return Result.RECONCILE;
            var started=result.orElseThrow();
            if(!started.fake() || !started.identity().equals(expected)) return Result.RECONCILE;
            if(started.matches(expected,point.orElseThrow())) {
                var safety=port.inspect(expected,point.orElseThrow());
                if(safety!=null && safety.safe() && port.activateIfCurrent(started)) return Result.ACTIVE;
            }
            return port.discardUnactivated(started)?Result.FAILED:Result.RECONCILE;
        } catch(RuntimeException uncertain) {
            return Result.RECONCILE;
        }
    }
}
