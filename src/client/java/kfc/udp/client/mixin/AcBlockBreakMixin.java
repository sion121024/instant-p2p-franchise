package kfc.udp.client.mixin;

import kfc.udp.client.anticheat.World;
import kfc.udp.client.signaling.AntiCheatHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
*///?} else {
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
//?}

/**
 * 안티치트 — 채굴 시작/중단/완료 패킷을 엔진에 넘긴다(빠른 채굴·너커·벽 너머 채굴·엑스레이 통계).
 * 취소하면 블록은 클라에 원래대로 다시 보낸다(AntiCheatHooks.onDig).
 */
//? if >=26.1 {
/*@Mixin(ServerPlayerGameMode.class)
*///?} else {
@Mixin(ServerPlayerInteractionManager.class)
//?}
public abstract class AcBlockBreakMixin {

    //? if >=26.1 {
    /*@Shadow @Final protected ServerPlayer player;

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void kfcudp$acDig(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction face, int maxY, int sequence,
                              CallbackInfo ci) {
        int a = switch (action) {
            case START_DESTROY_BLOCK -> World.Dig.START;
            case ABORT_DESTROY_BLOCK -> World.Dig.ABORT;
            case STOP_DESTROY_BLOCK -> World.Dig.STOP;
            default -> -1;
        };
        if (a >= 0 && AntiCheatHooks.onDig(this.player, pos, a, face)) ci.cancel();
    }
    *///?} else {
    @Shadow @Final protected ServerPlayerEntity player;

    @Inject(method = "processBlockBreakingAction", at = @At("HEAD"), cancellable = true)
    private void kfcudp$acDig(BlockPos pos, PlayerActionC2SPacket.Action action, Direction face, int maxY, int sequence,
                              CallbackInfo ci) {
        int a = switch (action) {
            case START_DESTROY_BLOCK -> World.Dig.START;
            case ABORT_DESTROY_BLOCK -> World.Dig.ABORT;
            case STOP_DESTROY_BLOCK -> World.Dig.STOP;
            default -> -1;
        };
        if (a >= 0 && AntiCheatHooks.onDig(this.player, pos, a, face)) ci.cancel();
    }
    //?}
}
