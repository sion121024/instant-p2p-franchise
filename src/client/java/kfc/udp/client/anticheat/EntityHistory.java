package kfc.udp.client.anticheat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 엔티티 위치 기록 — 리치·히트박스 검사의 랙 보정용. 서버 틱마다 게스트 근처 엔티티의 히트박스를 남긴다.
 * <p>
 * 공격한 클라는 대상의 "과거" 위치를 보고 때린다(핑만큼 늦게 받고, 받은 뒤에도 3틱에 걸쳐 부드럽게 옮겨 그린다).
 * 그래서 서버의 현재 위치로 거리를 재면 핑 높은 사람이 억울하게 걸린다. 트랜잭션으로 "클라가 확실히 받은 틱"을
 * 알고, 그보다 3틱 전 ~ 가장 최근 보낸 틱 사이의 모든 위치(와 그 사이 보간 위치)까지의 최소 거리를 쓴다.
 */
public final class EntityHistory {

    static final int CAPACITY = 64; // 3.2초 — 핑 3초까지

    private static final class Ring {
        final long[] ticks = new long[CAPACITY];
        final Box[] boxes = new Box[CAPACITY];
        int head; // 다음에 쓸 칸
        int size;
        long lastTouched;

        void put(long tick, Box b) {
            ticks[head] = tick;
            boxes[head] = b;
            head = (head + 1) % CAPACITY;
            size = Math.min(size + 1, CAPACITY);
        }
    }

    private final Map<Integer, Ring> rings = new HashMap<>();

    public synchronized void record(int entityId, long tick, Box box) {
        Ring r = rings.computeIfAbsent(entityId, k -> new Ring());
        r.put(tick, box);
        r.lastTouched = tick;
    }

    /** 오래 기록이 없는 엔티티(멀어졌거나 사라짐)를 지운다. */
    public synchronized void prune(long nowTick) {
        Iterator<Map.Entry<Integer, Ring>> it = rings.entrySet().iterator();
        while (it.hasNext()) {
            if (nowTick - it.next().getValue().lastTouched > CAPACITY) it.remove();
        }
    }

    public synchronized void clear() {
        rings.clear();
    }

    /**
     * 틱 [fromTick, toTick] 사이 대상 히트박스들 — 연속한 두 틱 사이는 보간 지점도 넣는다(거리 함수가 그 사이에서
     * 더 작아질 수 있다). 기록이 없으면 빈 배열.
     */
    public synchronized Box[] candidates(int entityId, long fromTick, long toTick, double inflate) {
        Ring r = rings.get(entityId);
        if (r == null || r.size == 0) return new Box[0];
        java.util.List<Box> out = new java.util.ArrayList<>();
        Box prev = null;
        // 오래된 것부터 순서대로
        for (int i = 0; i < r.size; i++) {
            int idx = (r.head - r.size + i + CAPACITY) % CAPACITY;
            long t = r.ticks[idx];
            if (t < fromTick - 1 || t > toTick) { prev = (t < fromTick - 1) ? r.boxes[idx] : prev; continue; }
            Box b = r.boxes[idx].inflate(inflate);
            if (prev != null) {
                Box p = prev.inflate(inflate);
                for (int s = 1; s < 4; s++) out.add(p.lerp(b, s / 4.0));
            }
            out.add(b);
            prev = r.boxes[idx];
        }
        if (out.isEmpty()) {
            // 범위 안 기록이 없으면(아주 높은 핑) 가장 최근 것이라도.
            int last = (r.head - 1 + CAPACITY) % CAPACITY;
            out.add(r.boxes[last].inflate(inflate));
        }
        return out.toArray(new Box[0]);
    }

    public synchronized Box latest(int entityId) {
        Ring r = rings.get(entityId);
        if (r == null || r.size == 0) return null;
        return r.boxes[(r.head - 1 + CAPACITY) % CAPACITY];
    }
}
