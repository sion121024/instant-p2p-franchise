package kfc.udp.client.mixin;

import kfc.udp.client.anticheat.Engine;
import kfc.udp.client.signaling.AntiCheatHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
*///?} else {
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdatePlayerAbilitiesC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
//?}

/**
 * 안티치트 — 게스트가 보낸 게임 패킷을 바닐라가 처리하기 직전에 엔진에 넘긴다.
 * <p>
 * 바닐라 핸들러는 처음에 네트워크 스레드에서 불렸다가 서버 스레드로 다시 넘겨 한 번 더 불린다 — 우리는 서버 스레드
 * 쪽에서만 본다({@link AntiCheatHooks#onServerThread}). 방장 본인은 훅 안에서 거른다.
 */
//? if >=26.1 {
/*@Mixin(ServerGamePacketListenerImpl.class)
*///?} else {
@Mixin(ServerPlayNetworkHandler.class)
//?}
public abstract class AcServerPlayMixin {

    /** 노폴 교정 — 이번 이동 패킷의 "땅에 있음"을 서버 판정으로 바꿔 끼울 값(null이면 그대로). */
    @Unique private Boolean kfcudp$groundOverride;

    //? if >=26.1 {
    /*@Shadow public ServerPlayer player;
    @Shadow private Vec3 awaitingPositionFromClient;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void kfcudp$acMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!AntiCheatHooks.onServerThread()) return; // 네트워크 스레드 쪽 첫 호출은 건드리지 않는다(서버 스레드가 쓰는 값)
        this.kfcudp$groundOverride = null;
        Engine.Out o = AntiCheatHooks.onMove(this.player, packet, this.awaitingPositionFromClient != null);
        if (o == null) return;
        if (o.setback != null) {
            AntiCheatHooks.setback(this.player, o.setback);
            ci.cancel();
            return;
        }
        this.kfcudp$groundOverride = o.groundOverride;
    }

    @Redirect(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;isOnGround()Z"))
    private boolean kfcudp$acGround(ServerboundMovePlayerPacket packet) {
        Boolean o = this.kfcudp$groundOverride;
        return o != null ? o : packet.isOnGround();
    }

    @Inject(method = "handleClientTickEnd", at = @At("HEAD"))
    private void kfcudp$acTickEnd(ServerboundClientTickEndPacket packet, CallbackInfo ci) {
        if (AntiCheatHooks.onServerThread()) AntiCheatHooks.onClientTickEnd(this.player);
    }

    @Inject(method = "handlePlayerCommand", at = @At("HEAD"))
    private void kfcudp$acCommand(ServerboundPlayerCommandPacket packet, CallbackInfo ci) {
        if (!AntiCheatHooks.onServerThread()) return;
        if (packet.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) AntiCheatHooks.onSprint(this.player, true);
    }

    @Inject(method = "handlePlayerAbilities", at = @At("HEAD"))
    private void kfcudp$acAbilities(ServerboundPlayerAbilitiesPacket packet, CallbackInfo ci) {
        if (AntiCheatHooks.onServerThread()) AntiCheatHooks.onAbilities(this.player, packet.isFlying());
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"))
    private void kfcudp$acClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (AntiCheatHooks.onServerThread()) AntiCheatHooks.onContainerClick(this.player);
    }
    *///?} else {
    @Shadow public ServerPlayerEntity player;
    @Shadow private Vec3d requestedTeleportPos;

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void kfcudp$acMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (!AntiCheatHooks.onServerThread()) return; // 네트워크 스레드 쪽 첫 호출은 건드리지 않는다(서버 스레드가 쓰는 값)
        this.kfcudp$groundOverride = null;
        Engine.Out o = AntiCheatHooks.onMove(this.player, packet, this.requestedTeleportPos != null);
        if (o == null) return;
        if (o.setback != null) {
            AntiCheatHooks.setback(this.player, o.setback);
            ci.cancel();
            return;
        }
        this.kfcudp$groundOverride = o.groundOverride;
    }

    @Redirect(method = "onPlayerMove", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/packet/c2s/play/PlayerMoveC2SPacket;isOnGround()Z"))
    private boolean kfcudp$acGround(PlayerMoveC2SPacket packet) {
        Boolean o = this.kfcudp$groundOverride;
        return o != null ? o : packet.isOnGround();
    }

    @Inject(method = "onClientCommand", at = @At("HEAD"))
    private void kfcudp$acCommand(ClientCommandC2SPacket packet, CallbackInfo ci) {
        if (!AntiCheatHooks.onServerThread()) return;
        if (packet.getMode() == ClientCommandC2SPacket.Mode.START_SPRINTING) AntiCheatHooks.onSprint(this.player, true);
    }

    @Inject(method = "onUpdatePlayerAbilities", at = @At("HEAD"))
    private void kfcudp$acAbilities(UpdatePlayerAbilitiesC2SPacket packet, CallbackInfo ci) {
        if (AntiCheatHooks.onServerThread()) AntiCheatHooks.onAbilities(this.player, packet.isFlying());
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"))
    private void kfcudp$acClick(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (AntiCheatHooks.onServerThread()) AntiCheatHooks.onContainerClick(this.player);
    }
    //?}

    // ── 스윙·틱 끝 — 버전마다 패킷이 갈린다(26.3: 스윙이 Punch로 바뀜, 1.21.2 이전: 틱 끝 패킷 없음) ──

    //? if >=26.3 {
    /*@Inject(method = "handlePunch", at = @At("HEAD"))
    private void kfcudp$acSwing(net.minecraft.network.protocol.game.ServerboundPunchPacket packet, CallbackInfo ci) {
        if (AntiCheatHooks.onServerThread()) AntiCheatHooks.onSwing(this.player);
    }
    *///?}
    //? if >=26.1 <26.3 {
    /*@Inject(method = "handleAnimate", at = @At("HEAD"))
    private void kfcudp$acSwing(net.minecraft.network.protocol.game.ServerboundSwingPacket packet, CallbackInfo ci) {
        if (AntiCheatHooks.onServerThread()) AntiCheatHooks.onSwing(this.player);
    }
    *///?}
    //? if <26.1 {
    @Inject(method = "onHandSwing", at = @At("HEAD"))
    private void kfcudp$acSwing(net.minecraft.network.packet.c2s.play.HandSwingC2SPacket packet, CallbackInfo ci) {
        if (AntiCheatHooks.onServerThread()) AntiCheatHooks.onSwing(this.player);
    }
    //?}
    //? if >=1.21.2 <26.1 {
    @Inject(method = "onClientTickEnd", at = @At("HEAD"))
    private void kfcudp$acTickEnd(net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket packet, CallbackInfo ci) {
        if (AntiCheatHooks.onServerThread()) AntiCheatHooks.onClientTickEnd(this.player);
    }
    //?}
}
