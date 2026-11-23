package dev.societybots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Small read-only sampler. A result is evidence, never an ownership or permission grant. */
public final class StructureScan {
    private StructureScan() {}
    public record Cell(int x, int y, int z) {
        public Cell move(int dx, int dy, int dz) { return new Cell(x+dx,y+dy,z+dz); }
    }
    public enum Part { OPEN, PLATFORM, RAIL, OTHER, HOPPER, FURNACE, CONTAINER, POWERED_RAIL, BUTTON, WATER, DOCK, ICE, GUARD }
    public enum Kind { FURNACE_FEED, CONTAINER_FEED, POSSIBLE_STATION, RAIL_BOARDING_CANDIDATE, POSSIBLE_DOCK, POSSIBLE_ICE_ENTRY, ICE_BOARDING_CANDIDATE }
    public record Block(Part part, Cell output) {}
    public record Match(Cell cell, Kind kind) {}
    public interface View {
        /** null denotes unavailable data, not air. Never load a chunk here. */
        Block read(Cell cell);
    }
    public static List<Match> near(View view, Cell center) {
        Objects.requireNonNull(view);
        var found = new ArrayList<Match>();
        for (int radius=0; radius<=4 && found.size()<12; radius++) {
            for(int y=-2;y<=2 && found.size()<12;y++) {
                for(int x=-radius;x<=radius && found.size()<12;x++) {
                    for(int z=-radius;z<=radius && found.size()<12;z++) {
                        if(Math.max(Math.abs(x),Math.abs(z))!=radius) continue;
                        Cell pos=center.move(x,y,z);
                        Block b=view.read(pos);
                        if(b==null) continue;
                        Kind match=switch(b.part()) {
                            case FURNACE -> connected(view,pos,Part.HOPPER)?Kind.FURNACE_FEED:null;
                            case CONTAINER -> connected(view,pos,Part.HOPPER)?Kind.CONTAINER_FEED:null;
                            case POWERED_RAIL -> hasControl(view,pos)?(railBoarding(view,pos)?Kind.RAIL_BOARDING_CANDIDATE:Kind.POSSIBLE_STATION):null;
                            case DOCK -> dock(view,pos)?Kind.POSSIBLE_DOCK:null;
                            case ICE -> iceEntry(view,pos);
                            default -> null;
                        };
                        if(match!=null) found.add(new Match(pos,match));
                    }
                }
            }
        }
        return List.copyOf(found);
    }
    private static final int[][] HORIZONTAL={{1,0},{-1,0},{0,1},{0,-1}};
    private static boolean connected(View v, Cell to, Part source) {
        if (feeds(v,to.move(0,1,0),to,source)) return true;
        for(var d:HORIZONTAL) if(feeds(v,to.move(d[0],0,d[1]),to,source)) return true;
        return false;
    }
    private static boolean feeds(View v, Cell from, Cell to, Part source) {
        var b=v.read(from);
        return b!=null && b.part()==source && to.equals(b.output());
    }
    private static boolean adjacent(View v, Cell c, Part p) {
        for(var d:HORIZONTAL) if(is(v,c.move(d[0],0,d[1]),p)) return true;
        return false;
    }
    private static boolean hasControl(View v, Cell c) {
        return adjacent(v,c,Part.BUTTON) || adjacent(v,c.move(0,1,0),Part.BUTTON);
    }
    private static boolean platform(View v,Cell c) {
        return (is(v,c,Part.PLATFORM)||is(v,c,Part.DOCK)) && is(v,c.move(0,1,0),Part.OPEN)
                && is(v,c.move(0,2,0),Part.OPEN);
    }
    private static boolean railBoarding(View v,Cell c) {
        for(var d:HORIZONTAL) {
            var r=c.move(d[0],0,d[1]);
            if(!is(v,r,Part.RAIL)&&!is(v,r,Part.POWERED_RAIL)) continue;
            for(int side:new int[]{-1,1}) {
                int a=-d[1]*side,b=d[0]*side;
                if(platform(v,c.move(a,0,b))&&platform(v,r.move(a,0,b))) return true;
            }
        }
        return false;
    }
    private static boolean dock(View v,Cell c) {
        if(!platform(v,c)) return false;
        for(var d:HORIZONTAL) {
            var water=c.move(d[0],0,d[1]);
            if(!is(v,water,Part.WATER)||!is(v,water.move(0,1,0),Part.OPEN)) continue;
            for(int side:new int[]{-1,1}) {
                int a=-d[1]*side,b=d[0]*side;
                if(is(v,c.move(a,0,b),Part.DOCK)&&platform(v,c.move(a,0,b))
                        &&is(v,water.move(a,0,b),Part.WATER)) return true;
            }
        }
        return false;
    }
    private static Kind iceEntry(View v, Cell c) {
        for(var d:HORIZONTAL) {
            if(is(v,c.move(d[0],0,d[1]),Part.ICE) &&
               is(v,c.move(d[1],1,d[0]),Part.GUARD) &&
               is(v,c.move(-d[1],1,-d[0]),Part.GUARD) &&
               is(v,c.move(-d[0],0,-d[1]),Part.DOCK)) {
                var pad=c.move(-d[0],0,-d[1]);
                if(platform(v,pad) && is(v,c.move(0,1,0),Part.OPEN)
                        && (platform(v,pad.move(-d[1],0,d[0]))||platform(v,pad.move(d[1],0,-d[0]))))
                    return Kind.ICE_BOARDING_CANDIDATE;
                return Kind.POSSIBLE_ICE_ENTRY;
            }
        }
        return null;
    }
    private static boolean is(View v, Cell c, Part p) {
        var b=v.read(c);
        return b!=null && b.part()==p;
    }
}
