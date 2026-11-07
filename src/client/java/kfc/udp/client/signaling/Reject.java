package kfc.udp.client.signaling;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서버의 접속 거절 사유(1.5~) — 웹소켓 핸드셰이크가 거절되면 서버가 {@code X-Reject: 코드},
 * {@code X-Reject-Until: 유닉스 초} 헤더를 실어 보내고, WebSocketClient 가 그걸 예외 메시지 끝에
 * {@code reject=코드 until=초} 로 붙인다. 여기서 꺼내 사람이 읽을 문구(번역 키 err.reject.*)로 바꾼다.
 */
public final class Reject {

    private static final Pattern CODE = Pattern.compile("reject=([a-z-]{1,24})");
    private static final Pattern UNTIL = Pattern.compile("until=(\\d{1,12})");

    private Reject() {}

    /** 메시지에 실린 거절 코드, 없으면 null. */
    public static String code(String message) {
        if (message == null) return null;
        Matcher m = CODE.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    /** 거절이 풀리는 시각(유닉스 초), 없으면 0. */
    public static long untilSec(String message) {
        if (message == null) return 0;
        Matcher m = UNTIL.matcher(message);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    /** 코드 → 번역 키(instant-p2p. 뒤 부분). 모르는 코드는 일반 "거절됨" 문구로. */
    public static String key(String code) {
        return switch (code) {
            case "banned", "no-host-perm", "no-public", "host-blocked", "net-full", "too-many-rooms",
                 "too-old", "unofficial", "cooldown", "java-old",
                 "need-consent-host", "need-consent-join" -> "err.reject." + code;
            default -> "err.refused";
        };
    }

    /** 실패 메시지에 거절 코드가 있으면 안내 문구(풀리는 시각 포함), 없으면 null. */
    public static String message(String failure) {
        String code = code(failure);
        if (code == null) return null;
        // 1.5.1: 서버가 "동의 기록이 없다"고 하면 로컬 기록도 지워 다음에 동의서가 다시 뜨게 한다.
        if (code.startsWith("need-consent-")) {
            kfc.udp.client.gui.PublicConsent.forget("host");
            kfc.udp.client.gui.PublicConsent.forget("join");
        }
        String text = L10n.t(key(code));
        long until = untilSec(failure);
        if (until > 0) {
            text = text + " " + L10n.t("err.reject.until").replace("%s",
                    DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochSecond(until).atZone(ZoneId.systemDefault())));
        }
        return text;
    }
}
