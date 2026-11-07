package kfc.udp.client.anticheat;

import java.util.ArrayDeque;

/**
 * 월드 검사 — 블록 상호작용 거리, 보이지 않는 면 클릭(스캐폴드), 벽 너머 상호작용, 허공 설치, 시선 밖 설치,
 * 빠른 설치, 빠른 채굴(채굴 진행도 재계산), 너커(한 틱 여러 블록), 엑스레이 통계(희귀 광석 비율), 인벤토리(창 열고
 * 이동·클릭 속도).
 */
public final class World {

    private World() {}

    /** 면 번호는 마크 Direction 순서: 0=아래 1=위 2=북(-z) 3=남(+z) 4=서(-x) 5=동(+x). */
    static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;

    public record Place(int bx, int by, int bz, int face, double hitX, double hitY, double hitZ,
                 double eyeX, double eyeY, double eyeYAlt, double eyeZ, double range,
                 boolean clickedAir, boolean obstructed, boolean creative) {}

    public record Dig(int action, int bx, int by, int bz, int face, Box blockBox, double progressPerTick,
               double eyeX, double eyeY, double eyeYAlt, double eyeZ, double range,
               boolean obstructed, boolean creative, boolean valuableOre, boolean stoneLike) {
        public static final int START = 0, ABORT = 1, STOP = 2;
    }

    /** @return true면 설치를 취소해야 한다. */
    static boolean onPlace(Engine e, PlayerData p, Place pl, long nowMs, Engine.Out out) {
        if (!e.cfg.categoryOn(Check.Category.WORLD)) return false;
        boolean cancel = false;
        double tol = 0.03;
        // 거리 — 클라는 눈에서 range 안의 면만 집는다.
        double dist = Math.min(dist(pl.eyeX(), pl.eyeY(), pl.eyeZ(), pl.hitX(), pl.hitY(), pl.hitZ()),
                dist(pl.eyeX(), pl.eyeYAlt(), pl.eyeZ(), pl.hitX(), pl.hitY(), pl.hitZ()));
        if (dist > pl.range() + tol) {
            if (p.check("interact").fail(nowMs, 1, 1 + (dist - pl.range()))) e.flag(p, "interact", String.format("place reach %.2f", dist), out);
            if (e.mitigate()) cancel = true;
        }
        if (pl.clickedAir()) {
            if (p.check("interact").failNow(nowMs, 2)) e.flag(p, "interact", "air place", out);
            if (e.mitigate()) cancel = true;
        }
        // 보이지 않는 면 — 클릭 지점이 놓인 평면 기준이라 반 블록·계단도 정확하다.
        if (!faceVisible(pl.face(), pl.eyeX(), Math.max(pl.eyeY(), pl.eyeYAlt()), Math.min(pl.eyeY(), pl.eyeYAlt()), pl.eyeZ(),
                pl.hitX(), pl.hitY(), pl.hitZ(), tol)) {
            if (p.check("scaffold").fail(nowMs, 1, 1.5)) e.flag(p, "scaffold", "invisible face " + faceName(pl.face()), out);
            if (e.mitigate()) cancel = true;
        }
        if (pl.obstructed()) {
            if (p.check("interact").fail(nowMs, 1, 1)) e.flag(p, "interact", "place through wall", out);
            if (e.mitigate()) cancel = true;
        }
        // 빠른 설치
        p.placesThisClientTick++;
        ArrayDeque<Long> q = p.placeTimesMs;
        q.addLast(nowMs);
        while (!q.isEmpty() && nowMs - q.peekFirst() > 1000) q.pollFirst();
        if (!pl.creative() && (p.placesThisClientTick > 4 || q.size() > 24)) {
            if (p.check("scaffold").fail(nowMs, 1, 1)) e.flag(p, "scaffold", String.format("fast place %d/s", q.size()), out);
            if (e.mitigate()) cancel = true;
        }
        // 시선 밖 설치 — 다음 회전이 오면 판정
        if (!cancel && p.rotKnown) {
            Box hb = new Box(pl.hitX() - 0.2, pl.hitY() - 0.2, pl.hitZ() - 0.2, pl.hitX() + 0.2, pl.hitY() + 0.2, pl.hitZ() + 0.2);
            p.pendingRay = new PlayerData.PendingRay("place", new Box[]{hb}, pl.eyeX(), pl.eyeY(), pl.eyeYAlt(), pl.eyeZ(),
                    p.yaw, p.pitch, pl.range() + 2, faceName(pl.face()));
        }
        return cancel;
    }

    /** @return true면 이 채굴 동작을 무시(취소)해야 한다. */
    static boolean onDig(Engine e, PlayerData p, Dig d, long nowMs, Engine.Out out) {
        if (!e.cfg.categoryOn(Check.Category.WORLD)) return false;
        boolean cancel = false;
        long key = ((long) d.bx() & 0x3FFFFFF) << 38 | ((long) d.bz() & 0x3FFFFFF) << 12 | ((long) d.by() & 0xFFF);
        if (d.action() == Dig.START) {
            double dist = Math.min(d.blockBox().distanceTo(d.eyeX(), d.eyeY(), d.eyeZ()), d.blockBox().distanceTo(d.eyeX(), d.eyeYAlt(), d.eyeZ()));
            if (dist > d.range() + 0.03) {
                if (p.check("interact").fail(nowMs, 1, 1 + (dist - d.range()))) e.flag(p, "interact", String.format("break reach %.2f", dist), out);
                if (e.mitigate()) cancel = true;
            }
            if (!faceVisibleBox(d.face(), d, 0.03)) {
                if (p.check("interact").fail(nowMs, 1, 1)) e.flag(p, "interact", "break invisible face " + faceName(d.face()), out);
            }
            if (d.obstructed()) {
                if (p.check("interact").fail(nowMs, 1, 1)) e.flag(p, "interact", "break through wall", out);
                if (e.mitigate()) cancel = true;
            }
            boolean instant = d.creative() || d.progressPerTick() >= 1.0;
            if (instant) {
                if (++p.instantBreaksThisClientTick > 2 && !d.creative()) {
                    if (p.check("nuker").failNow(nowMs, 1)) e.flag(p, "nuker", p.instantBreaksThisClientTick + " instant breaks/tick", out);
                    if (e.mitigate()) cancel = true;
                }
                if (!cancel) countMined(e, p, d, nowMs, out);
            } else {
                if (++p.breakStartsThisTick > 3) {
                    if (p.check("nuker").failNow(nowMs, 1)) e.flag(p, "nuker", p.breakStartsThisTick + " break starts/tick", out);
                    if (e.mitigate()) cancel = true;
                }
                p.breakPosKey = key;
                p.breakStartClientTick = p.clientTicks;
                p.breakStartServerTick = p.serverTick;
                p.breakProgressPerTick = d.progressPerTick();
            }
        } else if (d.action() == Dig.ABORT) {
            p.breakStartServerTick = -1;
            p.breakPosKey = Long.MIN_VALUE;
        } else if (d.action() == Dig.STOP) {
            if (p.breakPosKey == key && p.breakStartServerTick >= 0 && !d.creative()) {
                long elapsed = p.tickEndSeen ? p.clientTicks - p.breakStartClientTick : p.serverTick - p.breakStartServerTick;
                double ppt = Math.max(p.breakProgressPerTick, d.progressPerTick());
                double progress = (elapsed + 1) * ppt;
                double need = p.tickEndSeen ? 0.95 : 0.7; // 클라 틱을 셀 수 있으면 엄격하게, 서버 틱이면 바닐라와 같은 0.7
                if (progress < need && ppt > 0) {
                    if (p.check("fastbreak").fail(nowMs, 1, Math.min(4, 1 + (need - progress) * 4)))
                        e.flag(p, "fastbreak", String.format("progress %.0f%% in %d ticks", progress * 100, elapsed), out);
                    if (e.mitigate()) cancel = true;
                } else {
                    p.check("fastbreak").reward();
                }
            }
            if (!cancel) countMined(e, p, d, nowMs, out);
            p.breakStartServerTick = -1;
            p.breakPosKey = Long.MIN_VALUE;
        }
        return cancel;
    }

    /**
     * 엑스레이 통계 — 희귀 광석(다이아·고대 잔해·에메랄드) 대 돌 비율. 정상 동굴/가지 채굴은 돌 100개당 희귀 광석이
     * 1개 안팎인데 엑스레이는 곧장 광석으로 파고든다. 증거가 약하므로 방장 알림만 하고 강퇴하지 않는다.
     */
    private static void countMined(Engine e, PlayerData p, Dig d, long nowMs, Engine.Out out) {
        if (d.stoneLike()) p.stoneMined++;
        if (d.valuableOre()) {
            p.oresTotal++;
            if (p.oresTotal >= 8) {
                double rate = p.oresTotal / (double) Math.max(1, p.stoneMined);
                if (rate > 0.04 && nowMs - p.xrayAlertMs > 600_000) {
                    p.xrayAlertMs = nowMs;
                    if (p.check("xray").failNow(nowMs, 1))
                        e.flag(p, "xray", String.format("%d rare ores / %d stone", p.oresTotal, p.stoneMined), out);
                }
            }
        }
    }

    static void onContainerClick(Engine e, PlayerData p, long nowMs, Engine.Out out) {
        if (!e.cfg.categoryOn(Check.Category.WORLD)) return;
        ArrayDeque<Long> q = p.clickTimesMs;
        q.addLast(nowMs);
        while (!q.isEmpty() && nowMs - q.peekFirst() > 1000) q.pollFirst();
        if (q.size() > 28) {
            if (p.check("inventory").fail(nowMs, 1, 1)) e.flag(p, "inventory", q.size() + " clicks/s", out);
        }
        // 창이 열리면 바닐라는 이동 키를 전부 뗀다 — 계속 걷는 키가 눌린 채 클릭하면 인벤 이동 핵.
        if (p.inputMovingTicks >= 4) {
            if (p.check("inventory").fail(nowMs, 1, 1)) e.flag(p, "inventory", "click while moving", out);
        }
    }

    static void onClientTickEnd(PlayerData p) {
        p.placesThisClientTick = 0;
        p.instantBreaksThisClientTick = 0;
        p.breakStartsThisTick = 0; // 랙 뒤 몰려 온 여러 클라 틱의 채굴을 한 틱으로 세지 않게(클라 틱 기준)
    }

    static void onServerTick(PlayerData p) {
        if (!p.tickEndSeen) p.breakStartsThisTick = 0; // 틱 끝 패킷이 없는 구버전만 서버 틱 기준
    }

    private static boolean faceVisible(int face, double ex, double eyMax, double eyMin, double ez,
                                       double hx, double hy, double hz, double tol) {
        return switch (face) {
            case UP -> eyMax >= hy - tol;
            case DOWN -> eyMin <= hy + tol;
            case NORTH -> ez <= hz + tol;
            case SOUTH -> ez >= hz - tol;
            case WEST -> ex <= hx + tol;
            case EAST -> ex >= hx - tol;
            default -> true;
        };
    }

    private static boolean faceVisibleBox(int face, Dig d, double tol) {
        Box b = d.blockBox();
        double eyMax = Math.max(d.eyeY(), d.eyeYAlt()), eyMin = Math.min(d.eyeY(), d.eyeYAlt());
        return switch (face) {
            case UP -> eyMax >= b.maxY() - tol;
            case DOWN -> eyMin <= b.minY() + tol;
            case NORTH -> d.eyeZ() <= b.minZ() + tol;
            case SOUTH -> d.eyeZ() >= b.maxZ() - tol;
            case WEST -> d.eyeX() <= b.minX() + tol;
            case EAST -> d.eyeX() >= b.maxX() - tol;
            default -> true;
        };
    }

    static String faceName(int f) {
        return switch (f) { case 0 -> "down"; case 1 -> "up"; case 2 -> "north"; case 3 -> "south"; case 4 -> "west"; case 5 -> "east"; default -> "?"; };
    }

    private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double x = ax - bx, y = ay - by, z = az - bz;
        return Math.sqrt(x * x + y * y + z * z);
    }
}
