package kfc.udp.client.anticheat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 클라이언트 무결성 — 접속한 게스트의 모드 목록과 이 모드 jar 해시를 방장이 받아 확인한다.
 * <p>
 * 한계를 분명히: 조작된 클라는 거짓 목록을 보낼 수 있다. 그래도 "평범한 패브릭에 핵 모드를 같이 넣은" 대부분의 경우
 * (Meteor·Wurst 등)는 여기서 바로 걸리고, 목록을 숨기려면 클라를 직접 고쳐야 해서 문턱이 높아진다. 진짜 판정은
 * 행동 검사(이동·전투·월드)가 하고, 이건 보조 수단이다.
 */
public final class Integrity {

    private Integrity() {}

    public enum Severity { CHEAT, XRAY, ADVANTAGE }

    /** 알려진 핵 클라이언트·모드 id(소문자). 이름 일부만 같아도 잡히는 것은 {@link #SUSPICIOUS_PARTS}. */
    static final Set<String> CHEAT_IDS = Set.of(
            "meteor-client", "meteorclient", "wurst", "aristois", "inertia", "liquidbounce", "bleachhack", "aoba",
            "thunderhack", "coffeeclient", "boze", "rusherhack", "kamiblue", "salhack", "phobos", "konas",
            "3arthh4ck", "venomhack", "trouserstreak", "jexclient", "nodus", "huzuni", "wolfram");
    /** 이름이 흔해서 다른 정상 모드와 겹칠 수 있는 핵 클라 이름 — 강퇴 없이 알림만. */
    static final Set<String> MAYBE_CHEAT_IDS = Set.of("future", "impact", "sigma", "novoline", "vape", "prestige",
            "ares", "alien", "orion", "electron", "argon", "krypton", "rise", "tenacity", "blackout", "catalyst");
    static final Set<String> XRAY_IDS = Set.of("xray", "advanced-xray", "advancedxray", "xraymod", "x-ray", "xray-ultimate",
            "betterxray", "simplexray", "xrayfabric", "cavexray");
    static final Set<String> ADVANTAGE_IDS = Set.of("freecam", "freecammod", "minimap-radar", "entityradar", "tweakeroo",
            "fullbright", "baritone", "autoclicker", "autofish", "inventory-profiles-next");
    static final String[] SUSPICIOUS_PARTS = {"hack", "cheat", "killaura", "xray", "x-ray", "nofall", "autoclick"};

    public record Finding(Severity severity, String modId) {}

    /** 모드 목록 판정. {@code allowAdvantage}이면 미니맵·프리캠 같은 "유리한" 모드는 알림만. */
    public static List<Finding> evaluate(List<String> modIds) {
        List<Finding> out = new ArrayList<>();
        for (String raw : modIds) {
            if (raw == null) continue;
            String id = raw.toLowerCase(Locale.ROOT).trim();
            if (id.isEmpty() || id.length() > 64) continue;
            if (CHEAT_IDS.contains(id)) out.add(new Finding(Severity.CHEAT, id));
            else if (XRAY_IDS.contains(id)) out.add(new Finding(Severity.XRAY, id));
            else if (ADVANTAGE_IDS.contains(id) || MAYBE_CHEAT_IDS.contains(id)) out.add(new Finding(Severity.ADVANTAGE, id));
            else {
                for (String part : SUSPICIOUS_PARTS) {
                    if (id.contains(part)) { out.add(new Finding(Severity.ADVANTAGE, id)); break; }
                }
            }
        }
        return out;
    }
}
