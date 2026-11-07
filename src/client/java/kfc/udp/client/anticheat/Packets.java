package kfc.udp.client.anticheat;

import java.util.ArrayDeque;

/**
 * 잘못된 패킷(BadPackets) — 정상 클라는 절대 보낼 수 없는 값·순서. 하나만 와도 확실한 증거라 버퍼가 없다.
 */
final class Packets {

    private Packets() {}

    static void checkPitch(Engine e, PlayerData p, float pitch, long nowMs) {
        if (!e.cfg.categoryOn(Check.Category.PACKET)) return;
        if (Math.abs(pitch) > 90.01f) {
            Engine.Out o = e.scratchOut();
            if (p.check("badpackets").failNow(nowMs, 5)) e.flag(p, "badpackets", String.format("pitch %.1f", pitch), o);
            e.flushScratch(p, o);
        }
    }

    /** 능력 패킷 — 클라가 "날고 있다"고 보내는데 날 수 있는 권한(mayfly)이 없다 = 비행 핵이 능력 플래그를 켬. */
    static void onAbilities(Engine e, PlayerData p, boolean flying, boolean mayFly, long nowMs, Engine.Out out) {
        if (!e.cfg.categoryOn(Check.Category.PACKET)) return;
        if (flying && !mayFly) {
            if (p.check("badpackets").failNow(nowMs, 3)) e.flag(p, "badpackets", "fly ability without permission", out);
        }
    }

    /** 질주 시작 — 배고픔 6 이하·웅크린 채로는 바닐라가 질주를 시작하지 않는다. */
    static void onSprint(Engine e, PlayerData p, boolean start, float food, boolean shiftKey, boolean swimming,
                         boolean creativeOrFly, long nowMs, Engine.Out out) {
        if (!e.cfg.categoryOn(Check.Category.PACKET) || !start || creativeOrFly) return;
        if (food <= 5f) { // 바닐라 기준은 6 이하 — 서버·클라 배고픔이 경계에서 어긋날 수 있어 1칸 여유
            if (p.check("badpackets").failNow(nowMs, 1)) e.flag(p, "badpackets", "sprint while starving", out);
        } else if (shiftKey && !swimming && p.shiftHeldTicks >= 3) {
            if (p.check("badpackets").failNow(nowMs, 0.5)) e.flag(p, "badpackets", "sprint while sneaking", out);
        }
    }

    static void onSelfInteract(Engine e, PlayerData p, long nowMs, Engine.Out out) {
        if (!e.cfg.categoryOn(Check.Category.PACKET)) return;
        if (p.check("badpackets").failNow(nowMs, 5)) e.flag(p, "badpackets", "interact self", out);
    }

    static void onSlotChange(Engine e, PlayerData p, boolean sameSlot, long nowMs, Engine.Out out) {
        if (!e.cfg.categoryOn(Check.Category.PACKET)) return;
        if (sameSlot) {
            // 바닐라는 같은 칸으로 바꾸는 패킷을 보내지 않는다(오토 툴·킬오라 아이템 바꿔치기 흔적).
            if (++p.sameSlotChanges > 3) {
                p.sameSlotChanges = 0;
                if (p.check("badpackets").failNow(nowMs, 1)) e.flag(p, "badpackets", "duplicate slot change", out);
            }
        }
        if (++p.slotChangesThisTick > 6) {
            if (p.check("badpackets").failNow(nowMs, 1)) e.flag(p, "badpackets", p.slotChangesThisTick + " slot changes/tick", out);
        }
    }

    /** 모든 수신 패킷 — 초당 개수 폭주(패킷 플러드·크래셔). */
    static boolean onAnyPacket(Engine e, PlayerData p, long nowMs, Engine.Out out) {
        ArrayDeque<Long> q = p.packetTimesMs;
        q.addLast(nowMs);
        while (!q.isEmpty() && nowMs - q.peekFirst() > 1000) q.pollFirst();
        if (q.size() > 1200 && e.cfg.categoryOn(Check.Category.PACKET)) {
            q.clear();
            if (p.check("badpackets").failNow(nowMs, 4)) e.flag(p, "badpackets", "packet flood", out);
            return true;
        }
        return false;
    }

    static void onClientTickEnd(PlayerData p) {
        p.slotChangesThisTick = 0;
    }
}
