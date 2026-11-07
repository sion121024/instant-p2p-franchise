package kfc.udp.client.mixin;

//? if >=26.1 {
/*import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
*///?} else {
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.network.MultiplayerServerListPinger;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 서버 목록에 저장된 {@code quic.<코드>} 항목은 핑하지 않는다(1.5~).
 * <p>
 * 예전엔 앞의 {@code quic.} 만 떼고 나머지(초대 코드)를 주소로 핑했다 — 그 코드는 호스트 이름이 아니라서 매번 DNS 에
 * 초대 코드를 그대로 물어보는 꼴이었고(코드가 DNS 서버·공유기 로그에 남는다), 응답도 올 리 없었다. 그래서 핑 자체를
 * 건너뛴다. (KCP {@code kcp.} 직접 접속 주소는 F3.4 에서 뺐다 — QuicBridge 클래스 주석.)
 */
//? if >=26.1 {
/*@Mixin(ServerStatusPinger.class)
*///?} else {
@Mixin(MultiplayerServerListPinger.class)
//?}
public class ServerAddressMixin {

    //? if >=26.1 {
    /*@Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void kfcudp$handleSpecialPing(ServerData entry, Runnable saver, Runnable pingCallback,
                                          net.minecraft.server.network.EventLoopGroupHolder backend, CallbackInfo ci) {
        if (entry != null && entry.ip != null && entry.ip.startsWith("quic.")) ci.cancel();
    }
    *///?}
    //? if >=1.21.11 <26.1 {
    /*@Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void kfcudp$handleSpecialPing(ServerInfo entry, Runnable saver, Runnable pingCallback,
                                          net.minecraft.network.NetworkingBackend backend, CallbackInfo ci) {
        if (entry != null && entry.address != null && entry.address.startsWith("quic.")) ci.cancel();
    }
    *///?}
    //? if <1.21.11 {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void kfcudp$handleSpecialPing(ServerInfo entry, Runnable saver, Runnable pingCallback, CallbackInfo ci) {
        if (entry != null && entry.address != null && entry.address.startsWith("quic.")) ci.cancel();
    }
    //?}
}
