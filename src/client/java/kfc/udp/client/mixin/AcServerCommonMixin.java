package kfc.udp.client.mixin;

import kfc.udp.client.signaling.AntiCheatHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
*///?} else {
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
//?}

/**
 * 안티치트 — 게스트에게 나가는 넉백·폭발·텔레포트 패킷을 엔진에 알리고(이동 예측 후보), 우리 트랜잭션 핑의 퐁을 받는다.
 */
//? if >=26.1 {
/*@Mixin(ServerCommonPacketListenerImpl.class)
*///?} else {
@Mixin(ServerCommonNetworkHandler.class)
//?}
public abstract class AcServerCommonMixin {

    //? if >=26.1 {
    /*// 한 인자짜리 send — 넉백·텔레포트는 모두 이 길로 나간다(두 인자짜리는 버전마다 콜백 타입이 달라 피한다).
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void kfcudp$acSend(Packet<?> packet, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl gl && gl.player != null) AntiCheatHooks.onSend(gl.player, packet);
    }

    @Inject(method = "handlePong", at = @At("HEAD"))
    private void kfcudp$acPong(ServerboundPongPacket packet, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl gl && gl.player != null) AntiCheatHooks.onPong(gl.player, packet.getId());
    }
    *///?} else {
    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void kfcudp$acSend(Packet<?> packet, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayNetworkHandler gl && gl.player != null) AntiCheatHooks.onSend(gl.player, packet);
    }

    @Inject(method = "onPong", at = @At("HEAD"))
    private void kfcudp$acPong(CommonPongC2SPacket packet, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayNetworkHandler gl && gl.player != null) AntiCheatHooks.onPong(gl.player, packet.getParameter());
    }
    //?}
}
