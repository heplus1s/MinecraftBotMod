package dev.societybots.core.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Draft-only planning boundary. There is deliberately no placeBlock or commit implementation. */
public final class BuildingPlans {
    private BuildingPlans() {}
    public enum Need { SHELTER, OWNER_REQUEST }
    public record Cell(int x,int y,int z) {}
    public record Block(Cell offset,String blockId) {
        public Block {
            Objects.requireNonNull(offset);
            if(offset.x()<-7||offset.x()>7||offset.z()<-7||offset.z()>7||offset.y()<0||offset.y()>7
                    ||blockId==null||!blockId.matches("[a-z0-9_]+:[a-z0-9_]+")) throw new IllegalArgumentException("blueprint cell");
        }
    }
    public record Design(UUID designer, Optional<UUID> controller, Need need,List<Block> cells) {
        public Design {
            Objects.requireNonNull(designer); Objects.requireNonNull(controller); Objects.requireNonNull(need);
            cells=List.copyOf(cells);
            if(cells.isEmpty()||cells.size()>512||controller.filter(designer::equals).isPresent()
                    || need==Need.OWNER_REQUEST && controller.isEmpty()) throw new IllegalArgumentException("blueprint identity or size");
            var used=new HashSet<Cell>();
            for(var b:cells) if(!used.add(b.offset())) throw new IllegalArgumentException("duplicate position");
        }
        public boolean supports(Set<String> allowedPalette) {
            return cells.stream().allMatch(b->allowedPalette.contains(b.blockId()));
        }
    }
    public interface SiteReview {
        /** Read-only: future integration reviews material, ownership/owner approval, clearance and chunk bounds. */
        boolean mayPropose(ResidentApi.Identity architect,Design blueprint,String siteRef);
    }
}
