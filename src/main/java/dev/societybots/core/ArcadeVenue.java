package dev.societybots.core;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure validation of a loaded marker against the arcade's server-owned deployment record. */
public record ArcadeVenue(UUID id, String dimension, int x, int y, int z, String game, String skin) {
    private static final Set<String> TABLES=Set.of("blackjack","baccarat","roulette","reels","holdem");
    public ArcadeVenue {
        Objects.requireNonNull(id);
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(game);
        Objects.requireNonNull(skin);
        if(dimension.isBlank() || !(game.equals("exchange") || TABLES.contains(game)) || skin.isBlank())
            throw new IllegalArgumentException("invalid arcade deployment");
    }
    public CounterVisit.Service service() {
        return game.equals("exchange")?CounterVisit.Service.ARCADE:CounterVisit.Service.ARCADE_TABLE;
    }
    public boolean matches(CounterVisit.Service required,String world,int bx,int by,int bz,Set<String> tags) {
        return required==service() && dimension.equals(world) && x==bx && y==by && z==bz
                && tags.contains("arcadehall_table")
                && tags.contains("arcadehall_id_"+id) && unique(tags,"arcadehall_id_")
                && tags.contains("arcadehall_skin_"+skin) && unique(tags,"arcadehall_skin_");
    }
    private static boolean unique(Set<String> tags,String prefix) {
        return tags.stream().filter(tag->tag.startsWith(prefix)).limit(2).count()==1;
    }
}
