package kfc.udp.client.anticheat;

import java.util.ArrayDeque;

/**
 * 전투 검사 — 리치(랙 보정), 히트박스(시선 광선), 킬오라(다중 대상·아이템 사용 중 공격·창 열고 공격·스윙 없는 공격),
 * 조준(감도 GCD·스냅·조준 고정), 오토클리커(CPS·간격 일정함), 넉백 무시는 {@link Movement}.
 */
public final class Combat {

    private Combat() {}

    /** 공격 패킷 하나에 대한 서버 쪽 사실. */
    public record Attack(int targetId, Box targetNow, double eyeX, double eyeY, double eyeYAlt, double eyeZ,
                  double range, boolean usingItem, boolean containerOpen, boolean attackerPassenger,
                  boolean targetIsPlayer) {}

    static boolean onAttack(Engine e, PlayerData p, Attack a, long nowMs, Engine.Out out) {
        if (!e.cfg.categoryOn(Check.Category.COMBAT)) return false;
        p.combatUntilMs = nowMs + 5000;
        boolean cancel = false;

        // ── 대상 후보 위치(랙 보정) ──
        long to = p.tx.working() ? Math.max(p.tx.lastSentTick(), p.serverTick) : p.serverTick;
        long from = p.tx.working() ? p.tx.confirmedTick() - 3
                : p.serverTick - (long) Math.ceil(p.tx.rttMs() / 50.0) - 4;
        Box[] boxes = e.history.candidates(a.targetId(), from, to, 0);
        if (boxes.length == 0 && a.targetNow() != null) boxes = new Box[]{a.targetNow()};
        if (boxes.length == 0) return false;

        double min = Double.MAX_VALUE;
        for (Box b : boxes) {
            min = Math.min(min, b.distanceTo(a.eyeX(), a.eyeY(), a.eyeZ()));
            min = Math.min(min, b.distanceTo(a.eyeX(), a.eyeYAlt(), a.eyeZ()));
        }
        double limit = a.range() + 0.0005;
        if (!a.attackerPassenger() && min > limit + 0.03) {
            double over = min - limit;
            if (p.check("reach").fail(nowMs, 1, Math.min(4, 1 + over * 2))) {
                e.flag(p, "reach", String.format("%.3f > %.2f (ping %.0fms)", min, a.range(), p.tx.rttMs()), out);
            }
            if (over > 0.6 && e.mitigate()) cancel = true;
        } else {
            p.check("reach").reward();
        }

        // ── 히트박스: 다음 회전이 오면 시선 광선이 상자에 닿았는지 본다 ──
        Box[] inflated = new Box[boxes.length];
        for (int i = 0; i < boxes.length; i++) inflated[i] = boxes[i].inflate(0.1 + 0.03);
        if (min > 0.6) {
            p.pendingRay = new PlayerData.PendingRay("hitbox", inflated, a.eyeX(), a.eyeY(), a.eyeYAlt(), a.eyeZ(),
                    p.yaw, p.pitch, a.range() + 3, null);
            p.pendingRayTarget = boxes[boxes.length - 1];
        }

        // ── 킬오라: 한 클라 틱에 서로 다른 방향의 대상 여럿 ──
        if (p.attackedThisClientTick && p.lastTargetThisTick != a.targetId() && p.lastTargetBox != null) {
            double[] d1 = dir(a, p.lastTargetBox), d2 = dir(a, boxes[boxes.length - 1]);
            double ang = Box.angle(d1, d2);
            if (ang > 60) {
                if (p.check("killaura").fail(nowMs, 1, 2)) e.flag(p, "killaura", String.format("multi %.0f°", ang), out);
                if (e.mitigate()) cancel = true;
            }
        }
        if (a.usingItem() && p.useTicks >= 3) {
            if (p.check("killaura").fail(nowMs, 1, 1)) e.flag(p, "killaura", "attack while using item", out);
        }
        if (a.containerOpen()) {
            if (p.check("killaura").fail(nowMs, 1, 1)) e.flag(p, "killaura", "attack with container open", out);
            if (e.mitigate()) cancel = true;
        }
        p.attackedThisClientTick = true;
        p.attacksSinceSwing++;
        p.lastTargetThisTick = a.targetId();
        p.lastTargetBox = boxes[boxes.length - 1];
        p.lastAttackMs = nowMs;
        if (e.cfg.aimStats && Math.abs(p.lastYawDelta) > 45) p.snapBeforeAttack = true;
        return cancel;
    }

    private static double[] dir(Attack a, Box b) {
        return new double[]{b.centerX() - a.eyeX(), b.centerY() - a.eyeY(), b.centerZ() - a.eyeZ()};
    }

    static void onSwing(Engine e, PlayerData p, long nowMs, Engine.Out out) {
        p.swungThisClientTick = true;
        p.attacksSinceSwing = 0;
        if (!e.cfg.categoryOn(Check.Category.COMBAT)) return;
        if (p.breakStartServerTick >= 0) return; // 채굴 중 연속 스윙은 클릭이 아니다
        if (nowMs > p.combatUntilMs) return;
        ArrayDeque<Long> q = p.swingTimesMs;
        q.addLast(nowMs);
        while (!q.isEmpty() && nowMs - q.peekFirst() > 3000) q.pollFirst();
        int n = q.size();
        if (n >= 66) { // 3초에 66번 = 22 CPS 유지
            if (p.check("autoclicker").fail(nowMs, 1, 1)) e.flag(p, "autoclicker", String.format("%.1f cps", n / 3.0), out);
        }
        // 간격 일정함: 9 CPS 넘게 치는데 간격 표준편차가 너무 작다 — 사람 손은 떨린다.
        if (n >= 26) {
            Long[] t = q.toArray(new Long[0]);
            int k = Math.min(25, t.length - 1);
            double sum = 0, sq = 0;
            for (int i = t.length - k; i < t.length; i++) {
                double d = t[i] - t[i - 1];
                sum += d; sq += d * d;
            }
            double mean = sum / k, sd = Math.sqrt(Math.max(0, sq / k - mean * mean));
            if (mean < 110 && sd < 6) {
                if (p.check("autoclicker").fail(nowMs, 0.5, 1))
                    e.flag(p, "autoclicker", String.format("consistent %.0f±%.1fms", mean, sd), out);
            }
        }
    }

    /** 클라 틱 끝 — 이번 클라 틱 안의 공격·스윙 짝을 본다(공격 뒤에 스윙이 같은 틱에 와야 정상). */
    static void onClientTickEnd(Engine e, PlayerData p, long nowMs, Engine.Out out) {
        if (e.cfg.categoryOn(Check.Category.COMBAT) && p.attackedThisClientTick && !p.swungThisClientTick
                && p.attacksSinceSwing > 0) {
            if (p.check("killaura").fail(nowMs, 0.5, 1)) e.flag(p, "killaura", "no swing", out);
        }
        p.attackedThisClientTick = false;
        p.swungThisClientTick = false;
        p.lastTargetThisTick = Integer.MIN_VALUE;
    }

    static void onRotation(Engine e, PlayerData p, float yaw, float pitch, long nowMs) {
        if (p.rotKnown) {
            p.lastYawDelta = wrap(yaw - p.yaw);
            p.lastPitchDelta = pitch - p.pitch;
        }
        p.prevYaw = p.yaw; p.prevPitch = p.pitch;
        p.yaw = yaw; p.pitch = pitch;
        p.rotKnown = true;
        resolvePending(e, p, nowMs, null);
        if (e.cfg.aimStats && e.cfg.categoryOn(Check.Category.COMBAT)) aimStats(e, p, nowMs);
    }

    /** 공격·설치 당시 대기 중인 시선 판정 — 클라는 이번 틱 중간의 회전으로 쳤으므로 직전~다음 회전 사이를 다 본다. */
    static void resolvePending(Engine e, PlayerData p, long nowMs, Engine.Out out) {
        PlayerData.PendingRay r = p.pendingRay;
        if (r == null) return;
        p.pendingRay = null;
        if (Math.abs(wrap(p.yaw - r.prevYaw())) > 90) return; // 아주 빠른 휙 돌리기는 보간이 부정확 — 판정 안 함
        double bestAngle = Double.MAX_VALUE;
        boolean hit = false;
        for (int s = 0; s <= 8 && !hit; s++) {
            double t = s / 8.0;
            float yy = (float) (r.prevYaw() + wrap(p.yaw - r.prevYaw()) * t);
            float pp = (float) (r.prevPitch() + (p.pitch - r.prevPitch()) * t);
            double[] d = Box.look(yy, pp);
            for (double ey : new double[]{r.eyeY(), r.eyeY2()}) {
                for (Box b : r.boxes()) {
                    if (b.rayHit(r.eyeX(), ey, r.eyeZ(), d[0], d[1], d[2], r.maxDist()) >= 0) { hit = true; break; }
                }
                if (hit) break;
            }
            if (r.kind().equals("hitbox") && p.pendingRayTarget != null) {
                Box c = p.pendingRayTarget;
                double[] toC = {c.centerX() - r.eyeX(), c.centerY() - r.eyeY(), c.centerZ() - r.eyeZ()};
                bestAngle = Math.min(bestAngle, Box.angle(d, toC));
            }
        }
        Engine.Out o = out != null ? out : e.scratchOut();
        if (r.kind().equals("hitbox")) {
            if (!hit) {
                if (p.check("hitbox").fail(nowMs, 1, 1.5)) e.flag(p, "hitbox", "look ray missed target", o);
            } else {
                p.check("hitbox").reward();
                if (e.cfg.aimStats && bestAngle < 90) aimLock(e, p, bestAngle, nowMs, o);
            }
        } else if (r.kind().equals("place")) {
            if (!hit) {
                if (p.check("scaffold").fail(nowMs, 1, 1)) e.flag(p, "scaffold", "place outside view" + (r.detail() == null ? "" : " " + r.detail()), o);
            } else {
                p.check("scaffold").reward();
            }
        }
        if (o != out) e.flushScratch(p, o);
    }

    private static void aimLock(Engine e, PlayerData p, double err, long nowMs, Engine.Out out) {
        ArrayDeque<Double> q = p.aimErrors;
        q.addLast(err);
        while (q.size() > 25) q.pollFirst();
        if (q.size() < 20) return;
        double s = 0, sq = 0;
        for (double v : q) { s += v; sq += v * v; }
        double mean = s / q.size(), sd = Math.sqrt(Math.max(0, sq / q.size() - mean * mean));
        if (mean < 0.6 && sd < 0.35) {
            if (p.check("aim").fail(nowMs, 2, 1)) e.flag(p, "aim", String.format("lock %.2f±%.2f°", mean, sd), out);
            q.clear();
        }
    }

    /**
     * 감도(GCD) 검사 — 마우스 회전은 감도에서 나온 최소 단위의 정수배다. 에임봇·킬오라가 계산해서 돌린 회전은 그
     * 단위가 없다. 시네마틱 카메라·확대 모드·컨트롤러는 단위를 깨므로 오탐이 날 수 있어 "알림만"(강퇴 안 함).
     */
    private static void aimStats(Engine e, PlayerData p, long nowMs) {
        if (nowMs > p.combatUntilMs) return;
        double dp = Math.abs(p.lastPitchDelta);
        if (dp < 0.1 || dp > 25 || Math.abs(p.pitch) > 85) return;
        long cur = (long) (dp * (1L << 24));
        if (p.lastPitchSample > 0) {
            long g = gcd(cur, p.lastPitchSample);
            p.gcdSamples++;
            if (g < 131072L) p.gcdBadSamples++;
            if (p.gcdSamples >= 60) {
                double ratio = p.gcdBadSamples / (double) p.gcdSamples;
                if (ratio > 0.85) {
                    Engine.Out o = e.scratchOut();
                    if (p.check("aim").fail(nowMs, 3, 1)) e.flag(p, "aim", String.format("sensitivity gcd %.0f%%", ratio * 100), o);
                    e.flushScratch(p, o);
                }
                p.gcdSamples = 0;
                p.gcdBadSamples = 0;
            }
        }
        p.lastPitchSample = cur;
        if (p.snapBeforeAttack && Math.abs(p.lastYawDelta) < 1.0) {
            Engine.Out o = e.scratchOut();
            if (p.check("aim").fail(nowMs, 1, 0.5)) e.flag(p, "aim", "snap", o);
            e.flushScratch(p, o);
        }
        p.snapBeforeAttack = false;
    }

    static long gcd(long a, long b) {
        while (b > 16384) { long t = a % b; a = b; b = t; }
        return a;
    }

    static float wrap(float d) {
        d %= 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
