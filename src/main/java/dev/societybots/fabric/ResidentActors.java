package dev.societybots.fabric;

import dev.societybots.core.ResidentAuthority;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import net.minecraft.world.GameMode;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/** Configuration and execution are separate. A command source never supplies a Bot's permissions. */
final class ResidentActors {
    private ResidentActors() {}
    private static final Map<ServerPlayerEntity,ResidentAuthority.Stamp> STAMPS=new WeakHashMap<>();

    static ResidentAuthority.Stamp stamp(ServerPlayerEntity player,boolean enrolled) {
        var server=player==null?null:player.getServer();
        if(server==null || !server.isOnThread()) return null;
        if(!ordinarySnapshot(player,enrolled)) {
            STAMPS.remove(player);
            return null;
        }
        return STAMPS.computeIfAbsent(player,p->new ResidentAuthority.Stamp(UUID.randomUUID(),0));
    }

    static boolean fake(ServerPlayerEntity player) {
        return player!=null && player.getClass().getName().equals("carpet.patches.EntityPlayerMPFake");
    }
    static boolean ordinary(ServerPlayerEntity player,boolean enrolled) {
        return stamp(player,enrolled)!=null;
    }

    private static boolean ordinarySnapshot(ServerPlayerEntity player,boolean enrolled) {
        var server=player==null?null:player.getServer();
        if(server==null || !server.isOnThread()) return false;
        var players=server.getPlayerManager();
        return new ResidentAuthority.Live(enrolled,fake(player),players.getPlayer(player.getUuid())==player,
                player.isAlive(),player.interactionManager.getGameMode()==GameMode.SURVIVAL,players.isOperator(player.getGameProfile()),
                player.hasPermissionLevel(1)).ordinaryActor();
    }
    static boolean canConfigure(ServerCommandSource source) {
        // Even accidentally opped FakePlayers cannot enter the human control plane.
        return !fake(source.getPlayer()) && source.hasPermissionLevel(2);
    }
}
