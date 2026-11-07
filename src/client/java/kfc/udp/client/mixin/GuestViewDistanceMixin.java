package kfc.udp.client.mixin;

//? if >=26.1 {
/*import net.minecraft.client.server.IntegratedServer;
*///?} else {
import net.minecraft.server.integrated.IntegratedServer;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 바닐라 LAN 문제 고침(가맹점 F4.0) — 통합 서버의 시야 거리가 <b>방장의 렌더 거리</b>에 묶여 있어서, 방장이 프레임 때문에
 * 렌더 거리를 낮추면(예: 6) 접속자도 전부 6칸만 받았다. P2P 방을 열어 둔 동안에는 서버 시야 거리를 최소
 * {@link #GUEST_MIN_VIEW} 칸으로 둔다. 각 플레이어는 여전히 자기 렌더 거리까지만 받으므로(바닐라 ChunkMap 이
 * 플레이어 요청값과 서버 값 중 작은 쪽을 쓴다) 방장 화면·방장 쪽 청크 전송은 그대로다.
 * <p>
 * IntegratedServer.tickServer 의 {@code Math.max(2, renderDistance)}(첫 번째 Math.max) 결과만 바꾼다 — 비교와 설정에 같은
 * 값이 쓰여서 매 틱 다시 설정되지 않는다. 시뮬레이션 거리(두 번째 Math.max)는 건드리지 않는다.
 */
@Mixin(IntegratedServer.class)
public abstract class GuestViewDistanceMixin {

    private static final int GUEST_MIN_VIEW = 12;

    //? if >=26.1 {
    /*@Redirect(method = "tickServer", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I", ordinal = 0))
    *///?} else {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I", ordinal = 0))
    //?}
    private int kfcudp$guestViewDistance(int a, int b) {
        int v = Math.max(a, b);
        if (!kfc.udp.client.KfcudpClient.isRoomActive()) return v;
        return Math.min(32, Math.max(v, GUEST_MIN_VIEW));
    }
}
