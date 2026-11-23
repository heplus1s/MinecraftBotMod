package dev.societybots.fabric;

import dev.societybots.core.StructureScan;
import dev.societybots.core.StructureScan.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.*;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.world.ServerWorld;

final class LoadedStructures {
    private LoadedStructures() {}
    static List<Match> near(ServerWorld world, BlockPos origin) {
        var center=new Cell(origin.getX(),origin.getY(),origin.getZ());
        return scan(world,center);
    }
    private static List<Match> scan(ServerWorld world, Cell center) {
        Map<Cell,StructureScan.Block> cache = new HashMap<>();
        return StructureScan.near(cell->{
            if(!cache.containsKey(cell)) cache.put(cell,read(world,cell));
            return cache.get(cell);
        },center);
    }
    private static StructureScan.Block read(ServerWorld world, Cell c) {
        if(world.isOutOfHeightLimit(c.y())) return null;
        var chunk=world.getChunkManager().getWorldChunk(c.x()>>4,c.z()>>4);
        if(chunk==null) return null;
        var state=chunk.getBlockState(new BlockPos(c.x(),c.y(),c.z()));
        var block=state.getBlock();
        if(block instanceof HopperBlock) {
            var facing=state.get(HopperBlock.FACING);
            return new StructureScan.Block(Part.HOPPER,c.move(facing.getOffsetX(),facing.getOffsetY(),facing.getOffsetZ()));
        }
        var part=Part.OTHER;
        if(block instanceof AbstractFurnaceBlock) part=Part.FURNACE;
        else if(block instanceof ChestBlock || block instanceof BarrelBlock) part=Part.CONTAINER;
        else if(state.isOf(Blocks.POWERED_RAIL)) part=Part.POWERED_RAIL;
        else if(block instanceof AbstractRailBlock) part=Part.RAIL;
        else if(state.isAir()) part=Part.OPEN;
        else if(block instanceof SlabBlock || block instanceof StairsBlock) part=Part.PLATFORM;
        else if(state.isIn(BlockTags.BUTTONS)) part=Part.BUTTON;
        else if(state.isOf(Blocks.WATER)) part=Part.WATER;
        else if(state.isIn(BlockTags.PLANKS)) part=Part.DOCK;
        else if(state.isOf(Blocks.ICE)||state.isOf(Blocks.PACKED_ICE)||state.isOf(Blocks.BLUE_ICE)) part=Part.ICE;
        else if(block instanceof FenceBlock || block instanceof WallBlock) part=Part.GUARD;
        return new StructureScan.Block(part,null);
    }
}
