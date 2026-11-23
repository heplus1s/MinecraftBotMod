package dev.societybots.core;

import java.util.*;

/** Bounded incremental search. The adapter must read only already-loaded cells. */
public final class Routes {
    public enum Mode { WALK, RAIL, BOAT }
    public record Pos(int x, int y, int z) {}
    public record Node(Pos pos, Mode mode) {
        public Node { Objects.requireNonNull(pos); Objects.requireNonNull(mode); }
    }
    public record Arc(Node to, int ticks) {
        public Arc { Objects.requireNonNull(to); if (ticks < 1 || ticks > 1000) throw new IllegalArgumentException("cost"); }
    }
    public interface View {
        boolean loaded(Pos pos);
        boolean permitted(Node node);
        /** Read-only observation of neighboring cells, reciprocal rails, bounded boat corridors or safe transfers. */
        List<Arc> observedEdges(Node from);
    }
    public enum State { SEARCHING, FOUND, NO_KNOWN_ROUTE, BUDGET_EXHAUSTED }
    private record Pending(Node node, long cost, long serial) {}
    private final View view;
    private final Pos destination;
    private final int limit;
    private final Map<Node,Long> distances = new HashMap<>();
    private final Map<Node,Node> previous = new HashMap<>();
    private final PriorityQueue<Pending> open = new PriorityQueue<>(Comparator
            .comparingLong(Pending::cost).thenComparingLong(Pending::serial));
    private int allocated = 1;
    private long serial = 0;
    private List<Node> path = List.of();
    private State state = State.SEARCHING;

    public Routes(View view, Node start, Pos destination, int totalOfferLimit) {
        this.view = Objects.requireNonNull(view);
        this.destination = Objects.requireNonNull(destination);
        if (start.mode() != Mode.WALK || totalOfferLimit < 1 || totalOfferLimit > 50_000)
            throw new IllegalArgumentException("start or limit");
        this.limit = totalOfferLimit;
        distances.put(start, 0L);
        open.add(new Pending(start, 0, serial++));
    }

    public State step(int polls) {
        if (polls < 1 || polls > 256) throw new IllegalArgumentException("polls");
        for (int i = 0; i < polls && state == State.SEARCHING; i++) {
            var here = open.poll();
            if (here == null) return state = State.NO_KNOWN_ROUTE;
            if (here.cost != distances.getOrDefault(here.node, Long.MAX_VALUE)
                    || !view.loaded(here.node.pos()) || !view.permitted(here.node)) continue;
            if (here.node.mode() == Mode.WALK && here.node.pos().equals(destination)) {
                var result = new ArrayList<Node>();
                for (Node p = here.node; p != null; p = previous.get(p)) result.add(p);
                Collections.reverse(result);
                path = List.copyOf(result);
                return state = State.FOUND;
            }
            var edges = view.observedEdges(here.node);
            if (edges.size() > 32) throw new IllegalStateException("adapter exceeded edge budget");
            for (var edge : edges) {
                var to = edge.to();
                if (!local(here.node.pos(), to.pos()) || !view.loaded(to.pos()) || !view.permitted(to)) continue;
                long candidate = here.cost + edge.ticks();
                if (candidate >= distances.getOrDefault(to, Long.MAX_VALUE)) continue;
                if (allocated >= limit) return state = State.BUDGET_EXHAUSTED;
                allocated++;
                distances.put(to, candidate);
                previous.put(to, here.node);
                open.add(new Pending(to, candidate, serial++));
            }
        }
        return state;
    }

    private static boolean local(Pos a, Pos b) {
        return Math.abs((long)a.x()-b.x()) + Math.abs((long)a.z()-b.z()) <= 1
                && Math.abs((long)a.y()-b.y()) <= 1;
    }
    public List<Node> path() { return path; }
}
