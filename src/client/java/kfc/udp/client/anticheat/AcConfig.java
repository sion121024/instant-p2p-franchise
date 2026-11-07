package kfc.udp.client.anticheat;

/**
 * 엔진이 읽는 설정 스냅샷 — {@code AntiCheatSettings}(저장되는 설정)를 훅이 이걸로 바꿔 넣는다.
 * 엔진은 마크·Gson 의존이 없어야 해서 따로 둔다.
 */
public final class AcConfig {

    public enum Action {
        /** 알림·기록만. */
        ALERT,
        /** 이동 핵은 되돌리고(셋백) 공격·설치는 취소, 강퇴는 안 함. */
        MITIGATE,
        /** 되돌리기 + 높은 점수면 강퇴. */
        KICK
    }

    public boolean enabled = true;
    public boolean movement = true, combat = true, world = true, packets = true, integrity = true;
    /** 문턱 배율 — 1보다 크면 느슨(더 쌓여야 조치), 작으면 엄격. */
    public double thresholdScale = 1.0;
    public Action action = Action.KICK;
    /** 조준(에임) 통계 검사 — 오탐 위험이 가장 커서 따로 끌 수 있게. 기본은 알림만. */
    public boolean aimStats = true;

    public AcConfig copy() {
        AcConfig c = new AcConfig();
        c.enabled = enabled; c.movement = movement; c.combat = combat; c.world = world; c.packets = packets;
        c.integrity = integrity; c.thresholdScale = thresholdScale; c.action = action; c.aimStats = aimStats;
        return c;
    }

    boolean categoryOn(Check.Category c) {
        if (!enabled) return false;
        return switch (c) {
            case MOVEMENT -> movement;
            case COMBAT -> combat;
            case WORLD -> world;
            case PACKET -> packets;
            case INTEGRITY -> integrity;
        };
    }
}
