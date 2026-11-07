package kfc.udp.client.signaling;

/**
 * 게임 언어로 번역한 문자열(1.5~) — 채팅 컴포넌트가 아니라 예외 메시지처럼 <b>문자열</b>이 필요한 곳(QuicClient 의
 * 접속 실패 사유 등)에서 쓴다. 게임 밖(QuicProbe 하니스)에서는 번역기가 없으니 키를 그대로 돌려준다.
 */
public final class L10n {

    private L10n() {}

    /** @param key {@code instant-p2p.} 뒤 부분 */
    public static String t(String key) {
        try {
            //? if >=26.1 {
            /*return net.minecraft.client.resources.language.I18n.get("instant-p2p." + key);
            *///?} else {
            return net.minecraft.client.resource.language.I18n.translate("instant-p2p." + key);
            //?}
        } catch (Throwable t) {
            return key;
        }
    }
}
