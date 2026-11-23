package dev.societybots.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class TransitTolls {
    private TransitTolls() {}
    public enum Type { STATION_CANDIDATE, TOLL_ONLY }
    public record Zone(String id,String dimension,double x,double y,double z,double halfX,Double halfZ,double below,double above) {
        public Zone {
            Objects.requireNonNull(id); Objects.requireNonNull(dimension);
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||!extent(halfX)||halfX<1||halfZ!=null&&(!extent(halfZ)||halfZ<1)
                ||!extent(below)||!extent(above)||below+above<=0) throw new IllegalArgumentException("zone geometry");
        }
        public boolean contains(double a,double b,double c) {
            if(b<y-below||b>y+above) return false;
            return halfZ==null?Math.hypot(a-x,c-z)<=halfX:Math.abs(a-x)<=halfX&&Math.abs(c-z)<=halfZ;
        }
        public boolean intersectsScan(StructureScan.Cell pos) {
            double dx=Math.max(0,Math.abs(x-(pos.x()+0.5))-4.5);
            double dz=Math.max(0,Math.abs(z-(pos.z()+0.5))-4.5);
            if(pos.y()+3<y-below||pos.y()-2>y+above) return false;
            return halfZ==null?Math.hypot(dx,dz)<=halfX:dx<=halfX && dz<=halfZ;
        }
        private static boolean extent(double n) { return Double.isFinite(n)&&n>=0&&n<=128; }
    }
    public record Finding(Zone zone,Type type,Optional<StructureScan.Match> evidence) {}
    public static List<Finding> classify(String dimension,StructureScan.Cell origin,List<Zone> zones,List<StructureScan.Match> evidence) {
        return zones.stream().filter(z->z.dimension().equals(dimension)&&z.intersectsScan(origin))
            .map(zone->{
                var station=evidence.stream().filter(TransitTolls::boardingEvidence)
                    .filter(m->zone.contains(m.cell().x()+0.5,m.cell().y(),m.cell().z()+0.5)).findFirst();
                return new Finding(zone,station.isPresent()?Type.STATION_CANDIDATE:Type.TOLL_ONLY,station);
            }).toList();
    }
    private static boolean boardingEvidence(StructureScan.Match m) {
        return m.kind()==StructureScan.Kind.RAIL_BOARDING_CANDIDATE || m.kind()==StructureScan.Kind.POSSIBLE_DOCK
            || m.kind()==StructureScan.Kind.ICE_BOARDING_CANDIDATE;
    }
}
