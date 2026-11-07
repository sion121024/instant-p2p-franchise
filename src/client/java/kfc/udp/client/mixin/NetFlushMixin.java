package kfc.udp.client.mixin;

import io.netty.channel.ChannelHandlerContext;
//? if >=26.1 {
/*import net.minecraft.network.Connection;
*///?} else {
import net.minecraft.network.ClientConnection;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 방장 통합 서버 네트워크 최적화(가맹점 F4.0) — P2P 접속자 연결에 Netty {@code FlushConsolidationHandler} 를 끼운다.
 * <p>
 * 바닐라는 패킷마다 flush 한다(엔티티 이동·블록 갱신이 한 틱에 수백 개). P2P 접속자는 QUIC 터널이 <b>127.0.0.1 TCP</b> 로
 * 통합 서버에 붙으므로, flush 하나하나가 그대로 터널 쪽 읽기 한 번 → QUIC 스트림 쓰기 한 번(작은 패킷 여러 개)이 됐다.
 * flush 를 이벤트 루프 한 바퀴(또는 64번)마다 한 번으로 모으면 시스템 호출·QUIC 프레임 수가 크게 줄고 중계 대역폭
 * 오버헤드도 준다(Velocity·Krypton 이 쓰는 방식). 지연은 이벤트 루프 한 바퀴(마이크로초 단위)뿐이다.
 * <p>
 * 끼우는 조건: 서버가 받아들인 연결(부모 채널이 있음) + 상대가 루프백(= 이 모드의 터널) + 지금 P2P 방을 열어 둔 상태.
 * 일반 LAN·싱글플레이·다른 서버 접속에는 손대지 않는다.
 */
//? if >=26.1 {
/*@Mixin(Connection.class)
*///?} else {
@Mixin(ClientConnection.class)
//?}
public abstract class NetFlushMixin {

    @Inject(method = "channelActive", at = @At("TAIL"))
    private void kfcudp$consolidateFlushes(ChannelHandlerContext ctx, CallbackInfo ci) {
        try {
            if (ctx.channel().parent() == null || !kfc.udp.client.KfcudpClient.isRoomActive()) return;
            if (!(ctx.channel().remoteAddress() instanceof java.net.InetSocketAddress a) || !a.getAddress().isLoopbackAddress()) return;
            if (ctx.pipeline().get("instant-p2p-flush") != null) return;
            ctx.pipeline().addFirst("instant-p2p-flush", new io.netty.handler.flush.FlushConsolidationHandler(64, true));
        } catch (Throwable ignored) {
            // 최적화일 뿐 — 실패해도 연결은 그대로
        }
    }
}
