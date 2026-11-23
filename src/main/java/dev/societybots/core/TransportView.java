package dev.societybots.core;

import java.util.*;
import static dev.societybots.core.Routes.*;

/** Discovers local rail/boat/walking connections from physical observations, not waypoint markers. */
public final class TransportView implements View {
    public enum Waterway { NONE, GUARDED_ICE, WATER }
    public record Cell(boolean walkable, Set<Pos> railEnds, boolean railPlatform,
                       Waterway waterway, boolean boatClear, boolean safeDock) {
        public Cell {
            railEnds = Set.copyOf(railEnds);
            Objects.requireNonNull(waterway);
            if (railEnds.size()>2) throw new IllegalArgumentException("current rail shape has at most two ends");
        }
    }
    public interface World {
        boolean isLoaded(Pos pos);
        Cell at(Pos pos);
        boolean allows(Node node);
    }
    private final World world;
    public TransportView(World world) { this.world = Objects.requireNonNull(world); }
    public boolean loaded(Pos pos) { return world.isLoaded(pos); }
    public boolean permitted(Node node) { return world.allows(node); }
    public List<Arc> observedEdges(Node from) {
        Pos p = from.pos();
        if (!loaded(p) || !permitted(from)) return List.of();
        Cell here = world.at(p);
        var output = new ArrayList<Arc>();
        if (from.mode()==Mode.WALK && !here.walkable()) return List.of();
        if (from.mode()==Mode.RAIL && here.railEnds().isEmpty()) return List.of();
        if (from.mode()==Mode.BOAT && !navigable(here)) return List.of();
        if (from.mode()!=Mode.RAIL) {
            for (int[] dir : DIRS) {
                if (from.mode()==Mode.BOAT) {
                    Pos q = offset(p,dir,0);
                    if (loaded(q) && navigable(world.at(q))) add(output,q,Mode.BOAT,3);
                } else {
                    for (int dy=-1;dy<=1;dy++) {
                        Pos q = offset(p,dir,dy);
                        if (loaded(q) && world.at(q).walkable()) add(output,q,Mode.WALK,9);
                    }
                }
            }
        }
        if (from.mode()==Mode.RAIL) {
            for (Pos q:here.railEnds()) {
                if (neighbors(p,q) && loaded(q) && world.at(q).railEnds().contains(p))
                    add(output,q,Mode.RAIL,2);
            }
        }
        if (here.walkable() && here.railPlatform() && !here.railEnds().isEmpty()) {
            if (from.mode()==Mode.WALK) add(output,p,Mode.RAIL,18);
            if (from.mode()==Mode.RAIL) add(output,p,Mode.WALK,8);
        }
        if (here.walkable() && here.safeDock() && navigable(here)) {
            if (from.mode()==Mode.WALK) add(output,p,Mode.BOAT,18);
            if (from.mode()==Mode.BOAT) add(output,p,Mode.WALK,8);
        }
        return List.copyOf(output);
    }
    private void add(List<Arc> list, Pos p, Mode m, int ticks) {
        Node node = new Node(p,m);
        if (permitted(node)) list.add(new Arc(node,ticks));
    }
    private static boolean neighbors(Pos p,Pos q) {
        return Math.abs((long)p.x()-q.x())+Math.abs((long)p.z()-q.z())==1 && Math.abs((long)p.y()-q.y())<=1;
    }
    private static boolean navigable(Cell c) { return c.boatClear() && c.waterway()!=Waterway.NONE; }
    private static final int[][] DIRS={{1,0},{-1,0},{0,1},{0,-1}};
    private static Pos offset(Pos p,int[] dir,int dy) { return new Pos(p.x()+dir[0],p.y()+dy,p.z()+dir[1]); }
}
