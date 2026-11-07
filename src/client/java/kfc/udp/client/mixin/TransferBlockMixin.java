package kfc.udp.client.mixin;

import kfc.udp.client.KfcudpClient;
import kfc.udp.client.quic.QuicBridge;
//? if >=26.1 {
/*import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
*///?} else {
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P2P 방에 접속자로 있는 동안 방장이 보내는 서버 이동(transfer) 패킷을 막는다(1.5~) — 방장이 접속자를 자기 서버로
 * 보내 버리면 접속자의 실제 IP 가 그 서버에 그대로 드러난다(중계 강제로 IP 를 숨긴 의미가 없어진다).
 */
//? if >=26.1 {
/*@Mixin(ClientCommonPacketListenerImpl.class)
*///?} else {
@Mixin(ClientCommonNetworkHandler.class)
//?}
public abstract class TransferBlockMixin {

    //? if >=26.1 {
    /*@Inject(method = "handleTransfer", at = @At("HEAD"), cancellable = true)
    private void kfcudp$blockTransfer(ClientboundTransferPacket packet, CallbackInfo ci) {
        if (QuicBridge.getActiveConnectionUsesRelay() != null) {
            ci.cancel();
            KfcudpClient.onTransferBlocked();
        }
    }
    *///?} else {
    @Inject(method = "onServerTransfer", at = @At("HEAD"), cancellable = true)
    private void kfcudp$blockTransfer(ServerTransferS2CPacket packet, CallbackInfo ci) {
        if (QuicBridge.getActiveConnectionUsesRelay() != null) {
            ci.cancel();
            KfcudpClient.onTransferBlocked();
        }
    }
    //?}
}
