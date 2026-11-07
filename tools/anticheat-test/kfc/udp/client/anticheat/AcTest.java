package kfc.udp.client.anticheat;

import java.util.*;

/**
 * 엔진 단위 테스트 — 바닐라 26.3 물리를 따로(엔진 코드를 재사용하지 않고) 구현한 시뮬레이터로 정상 플레이를 만들어
 * 오탐 0을 확인하고, 핵을 흉내 낸 궤적이 잡히는지 확인한다. 평지(y=0 바닥) 한정.
 */
public class AcTest {

    static int failures = 0;

    static void check(boolean cond, String msg) {
        if (!cond) { failures++; System.out.println("  FAIL: " + msg); }
        else System.out.println("  ok: " + msg);
    }

    // ───────── 바닐라 시뮬레이터 ─────────
    static final class Sim {
        double x, y, z, vx, vy, vz;
        boolean onGround = true, sprinting;
        float yaw;
        int noJumpDelay;
        double speedBase = 0.1;
        double groundFriction = 0.6;
        boolean sneak, using;
        // 키
        boolean fw, bk, lf, rt, jump;
        // 핵 옵션
        double hSpeedMul = 1.0, jumpPower = 0.42;
        boolean hover, spoofGround, noSlow, airStrafe;

        static double modFr(double f, double m) { return Math.max(0, Math.min(1, 1 - (1 - f) * m)); }

        /** 한 클라 틱 진행 → 보낼 이동 패킷. */
        MoveInput tick() {
            double px = x, py = y, pz = z;
            // aiStep 시작: 미세 속도 0
            if (vx * vx + vz * vz < 9.0E-6) { vx = 0; vz = 0; }
            if (Math.abs(vy) < 0.003) vy = 0;
            if (noJumpDelay > 0) noJumpDelay--;
            // 입력
            float strafe = (lf ? 1 : 0) - (rt ? 1 : 0), forward = (fw ? 1 : 0) - (bk ? 1 : 0);
            double ix = strafe, iz = forward;
            if (ix != 0 || iz != 0) {
                ix *= 0.98; iz *= 0.98;
                if (using && !noSlow) { ix *= 0.2; iz *= 0.2; }
                if (sneak) { ix *= 0.3; iz *= 0.3; }
                double len = Math.sqrt(ix * ix + iz * iz);
                double dx = ix / len, dz = iz / len;
                double ds = 1.0 / Math.max(Math.abs(dx), Math.abs(dz));
                double ml = Math.min(len * ds, 1.0);
                ix = dx * ml; iz = dz * ml;
            }
            // 점프
            if (jump && onGround && noJumpDelay == 0) {
                vy = Math.max(jumpPower, vy);
                if (sprinting) {
                    double r = Math.toRadians(yaw);
                    vx += -Math.sin(r) * 0.2; vz += Math.cos(r) * 0.2;
                }
                noJumpDelay = 10;
            } else if (!jump) noJumpDelay = 0;
            // travelInAir
            double friction = onGround ? groundFriction : 1.0;
            double speed = speedBase * (sprinting ? 1.3 : 1.0);
            double sf = onGround ? (friction > 0.6 ? speed * (0.21600002 / (friction * friction * friction)) : speed)
                    : (sprinting ? 0.025999999f : 0.02f);
            if (airStrafe && !onGround) sf = speed; // 공중 스트레이프 핵
            double len2 = ix * ix + iz * iz;
            if (len2 > 1e-7) {
                double l = Math.sqrt(len2);
                double mx = (len2 > 1 ? ix / l : ix) * sf, mz = (len2 > 1 ? iz / l : iz) * sf;
                double r = Math.toRadians(yaw);
                double sin = Math.sin(r), cos = Math.cos(r);
                vx += mx * cos - mz * sin;
                vz += mz * cos + mx * sin;
            }
            // move (바닥 y=0과 충돌)
            double mvx = vx * hSpeedMul, mvz = vz * hSpeedMul, mvy = vy;
            boolean landed = false;
            if (y + mvy < 0) { mvy = -y; landed = true; }
            x += mvx; y += mvy; z += mvz;
            onGround = landed || (y <= 1e-9 && vy <= 0);
            if (landed || (onGround && vy < 0)) vy = 0;
            if (hover && !onGround) { y = py; vy = 0.08; } // 호버 핵: 높이 유지
            // 마찰·중력
            double g = 0.08;
            vy = (vy - g) * modFr(0.98, 1);
            vx *= friction * modFr(0.91, 1);
            vz *= friction * modFr(0.91, 1);
            if (hover && !onGround) vy = 0;
            boolean claimGround = spoofGround || onGround;
            return new MoveInput(true, true, x, y, z, yaw, 0, claimGround, false);
        }

        void prep() {
            // 질주는 앞으로 갈 때만(사용 중·웅크림 아닐 때)
            sprinting = sprinting && fw && !sneak && (!using || noSlow);
        }

        MoveEnv env() {
            MoveEnv e = new MoveEnv();
            e.ticksExisted = 1000;
            e.movementSpeed = speedBase * (sprinting ? 1.3 : 1.0);
            e.sprinting = sprinting;
            e.sneakingPose = sneak;
            e.usingItem = using;
            e.hasInput = true;
            e.forward = fw; e.backward = bk; e.left = lf; e.right = rt; e.jumpKey = jump; e.shiftKey = sneak; e.sprintKey = sprinting;
            e.groundBelowNew = y <= 0.03;
            e.groundBelowPrev = y <= 0.03;
            e.blockFriction = (float) groundFriction;
            return e;
        }
    }

    static final long[] CLOCK = {1_000_000_000L};

    static Engine newEngine() {
        Engine e = new Engine();
        e.clockNs = () -> CLOCK[0];
        AcConfig c = new AcConfig();
        c.action = AcConfig.Action.ALERT;
        e.setConfig(c);
        return e;
    }

    record Run(Map<String, Integer> flags, int setbacks, int groundOverrides) {}

    /** 시뮬레이터를 n틱 돌리며 각 틱 키 조작 함수를 부른다. */
    static Run run(Sim s, int n, java.util.function.BiConsumer<Sim, Integer> keys, boolean tickEnd) {
        Engine e = newEngine();
        UUID id = UUID.randomUUID();
        e.join(id, "t", 1);
        Map<String, Integer> flags = new TreeMap<>();
        int setbacks = 0, go = 0;
        // 첫 위치
        MoveEnv env0 = s.env();
        e.onMove(id, new MoveInput(true, true, s.x, s.y, s.z, s.yaw, 0, true, false), env0);
        for (int t = 0; t < n; t++) {
            CLOCK[0] += 50_000_000L;
            keys.accept(s, t);
            s.prep();
            MoveEnv env = s.env(); // 키·상태는 이번 틱 시작 값
            MoveInput m = s.tick();
            env.groundBelowNew = s.y <= 0.03;
            Engine.Out o = e.onMove(id, m, env);
            if (o.setback != null) setbacks++;
            if (o.groundOverride != null) go++;
            if (tickEnd) e.onClientTickEnd(id);
        }
        int shown = 0;
        for (Engine.Alert a : e.drainAlerts()) {
            flags.merge(a.check(), 1, Integer::sum);
            if (shown++ < 3) System.out.println("    alert " + a.check() + " " + a.detail());
        }
        PlayerData p = e.get(id);
        for (Check c : p.checks().values()) if (c.flags() > 0) flags.merge(c.id + "#raw", c.flags(), Integer::sum);
        return new Run(flags, setbacks, go);
    }

    public static void main(String[] args) {
        Random r = new Random(1);

        System.out.println("[정상] 걷기·질주·질주점프·대각선·웅크림·먹으며 걷기·무작위 키 6000틱");
        Sim s = new Sim();
        Run legit = run(s, 6000, (sim, t) -> {
            int phase = (t / 300) % 8;
            sim.fw = phase != 6; sim.bk = phase == 6; sim.lf = phase == 2 || phase == 5; sim.rt = phase == 3;
            sim.sprinting = phase == 1 || phase == 4 || phase == 5;
            sim.jump = phase == 4 || phase == 5 || (phase == 7 && r.nextInt(10) == 0);
            sim.sneak = phase == 3;
            sim.using = phase == 2 && (t % 60) < 32;
            if (phase == 7) { sim.fw = r.nextBoolean(); sim.lf = r.nextInt(4) == 0; sim.rt = !sim.lf && r.nextInt(4) == 0; sim.sprinting = sim.fw && r.nextBoolean(); }
            sim.yaw += (float) (r.nextGaussian() * (phase == 5 ? 12 : 4));
        }, true);
        System.out.println("  flags=" + legit.flags());
        check(legit.flags().keySet().stream().noneMatch(k -> !k.endsWith("#raw")), "정상 플레이 알림 0");
        check(!legit.flags().containsKey("speed#raw") && !legit.flags().containsKey("fly#raw"), "정상 플레이 speed/fly 위반 0");

        System.out.println("[정상] 같은 시나리오, 틱 끝 패킷 없는 구버전(1.21~1.21.1)");
        Run legitOld = run(new Sim(), 3000, (sim, t) -> {
            sim.fw = true; sim.sprinting = (t / 200) % 2 == 0; sim.jump = (t / 100) % 3 == 0;
            sim.yaw += (float) (r.nextGaussian() * 5);
        }, false);
        System.out.println("  flags=" + legitOld.flags());
        check(legitOld.flags().keySet().stream().noneMatch(k -> !k.endsWith("#raw")), "구버전 정상 알림 0");

        System.out.println("[핵] 스피드 1.15배");
        Run speed = run(sim(sm -> sm.hSpeedMul = 1.15), 400, (sim, t) -> { sim.fw = true; sim.sprinting = true; sim.jump = t % 40 < 20; }, true);
        System.out.println("  flags=" + speed.flags());
        check(speed.flags().containsKey("speed"), "스피드 1.15배 적발");

        System.out.println("[핵] 스피드 1.05배(미세)");
        Run speed2 = run(sim(sm -> sm.hSpeedMul = 1.05), 600, (sim, t) -> { sim.fw = true; sim.sprinting = true; }, true);
        System.out.println("  flags=" + speed2.flags());
        check(speed2.flags().containsKey("speed"), "스피드 1.05배 적발");

        System.out.println("[핵] 공중 스트레이프");
        Run strafe = run(sim(sm -> sm.airStrafe = true), 400, (sim, t) -> { sim.fw = true; sim.lf = (t / 7) % 2 == 0; sim.sprinting = true; sim.jump = true; }, true);
        System.out.println("  flags=" + strafe.flags());
        check(strafe.flags().containsKey("speed"), "공중 스트레이프 적발");

        System.out.println("[핵] 고점프 0.6");
        Run hj = run(sim(sm -> sm.jumpPower = 0.6), 300, (sim, t) -> { sim.jump = t % 30 == 0; }, true);
        System.out.println("  flags=" + hj.flags());
        check(hj.flags().containsKey("fly"), "고점프 적발");

        System.out.println("[핵] 호버(공중 정지)");
        Sim hs = new Sim();
        Run hover = run(hs, 300, (sim, t) -> { sim.jump = t == 5; if (t == 9) sim.hover = true; }, true);
        System.out.println("  flags=" + hover.flags());
        check(hover.flags().containsKey("fly"), "호버 적발");

        System.out.println("[핵] 노폴(공중에서 땅인 척)");
        Run nf = run(sim(sm -> sm.spoofGround = true), 300, (sim, t) -> { sim.jump = t % 20 == 0; sim.fw = true; }, true);
        System.out.println("  flags=" + nf.flags() + " groundOverrides=" + nf.groundOverrides());
        check(nf.flags().containsKey("groundspoof") && nf.groundOverrides() > 0, "노폴 적발 + 지면 판정 교정");

        System.out.println("[핵] 노슬로우(먹으면서 질주 속도)");
        Run ns = run(sim(sm -> sm.noSlow = true), 400, (sim, t) -> { sim.fw = true; sim.sprinting = true; sim.using = true; }, true);
        System.out.println("  flags=" + ns.flags());
        check(ns.flags().containsKey("speed"), "노슬로우 적발");

        System.out.println("[정상] 얼음 위 질주점프");
        Run ice = run(sim(sm -> sm.groundFriction = 0.98), 1500, (sim, t) -> { sim.fw = true; sim.sprinting = t % 300 < 200; sim.jump = t % 50 < 30; sim.yaw += (float) (r.nextGaussian() * 3); }, true);
        System.out.println("  flags=" + ice.flags());
        check(ice.flags().keySet().stream().noneMatch(k -> !k.endsWith("#raw")), "얼음 위 알림 0");

        System.out.println("[정상/핵] 넉백");
        check(kbTest(false) == 0, "넉백 받는 정상 플레이 오탐 0");
        check(kbTest(true) > 0, "넉백 무시(안티 넉백) 적발");
        check(kbDelayed() == 0, "넉백 + 지연(1~6틱 핑) 오탐 0");

        System.out.println("[정상/핵] 겉날개");
        check(elytra(false) == 0, "겉날개 급강하→끌어올리기 오탐 0");
        check(elytra(true) > 0, "겉날개 수평 비행 핵 적발");

        System.out.println("[핵] 타이머 1.25배(틱 40ms)");
        check(timerTest(40) , "타이머 1.25배 적발");
        System.out.println("[정상] 타이머 50ms + 지터");
        check(!timerTestJitter(), "정상 틱 지터 오탐 없음");

        System.out.println("[전투] 리치");
        reachTests();
        System.out.println("[월드] 면·채굴");
        worldTests();
        System.out.println("[무결성]");
        check(!Integrity.evaluate(List.of("sodium", "lithium", "fabric-api", "modmenu")).stream().anyMatch(f -> f.severity() != Integrity.Severity.ADVANTAGE), "정상 모드 목록 통과");
        check(Integrity.evaluate(List.of("sodium", "meteor-client")).stream().anyMatch(f -> f.severity() == Integrity.Severity.CHEAT), "Meteor 적발");

        System.out.println(failures == 0 ? "\nALL PASS" : "\nFAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static Sim sim(java.util.function.Consumer<Sim> c) {
        Sim s = new Sim();
        c.accept(s);
        return s;
    }

    /** 넉백 시나리오 — 서버가 속도를 보내고 핑을 보내면 클라는 다음 틱 시작에 속도를 받는다. */
    static int kbTest(boolean ignore) {
        Engine e = newEngine();
        UUID id = UUID.randomUUID();
        e.join(id, "k", 1);
        Sim s = new Sim();
        e.onMove(id, new MoveInput(true, true, 0, 0, 0, 0, 0, true, false), s.env());
        Random r = new Random(7);
        long tick = 0;
        for (int t = 0; t < 2000; t++) {
            CLOCK[0] += 50_000_000L;
            e.serverTick(++tick);
            boolean hit = t % 40 == 20;
            double kx = 0, ky = 0, kz = 0;
            if (hit) {
                kx = r.nextGaussian() * 0.4; kz = r.nextGaussian() * 0.4; ky = 0.36;
                e.onVelocity(id, kx, ky, kz, false);
            }
            int pid = e.pingFor(id);
            e.onPong(id, pid);
            if (hit && !ignore) { s.vx = kx; s.vy = ky; s.vz = kz; }
            s.fw = t % 80 < 60; s.sprinting = s.fw && t % 160 < 100; s.jump = t % 33 == 0;
            s.yaw += (float) (r.nextGaussian() * 4);
            s.prep();
            MoveEnv env = s.env();
            MoveInput m = s.tick();
            env.groundBelowNew = s.y <= 0.03;
            e.onMove(id, m, env);
            e.onClientTickEnd(id);
        }
        PlayerData p = e.get(id);
        System.out.println("  kb ignore=" + ignore + " speed=" + p.check("speed").flags() + " fly=" + p.check("fly").flags() + " velocity=" + p.check("velocity").flags());
        return ignore ? p.check("velocity").flags() : p.check("speed").flags() + p.check("fly").flags() + p.check("velocity").flags();
    }

    /** 서버가 보낸 넉백·핑이 d틱 뒤에 클라에 같이 도착(핑 지연). */
    static int kbDelayed() {
        Engine e = newEngine();
        UUID id = UUID.randomUUID();
        e.join(id, "k", 1);
        Sim s = new Sim();
        e.onMove(id, new MoveInput(true, true, 0, 0, 0, 0, 0, true, false), s.env());
        Random r = new Random(11);
        long tick = 0;
        java.util.ArrayDeque<double[]> wire = new java.util.ArrayDeque<>(); // {도착틱, 핑id, kx,ky,kz, hasKb}
        for (int t = 0; t < 3000; t++) {
            CLOCK[0] += 50_000_000L;
            e.serverTick(++tick);
            int delay = 1 + (t / 500) % 6;
            boolean hit = t % 37 == 10;
            double kx = 0, ky = 0, kz = 0;
            if (hit) { kx = r.nextGaussian() * 0.4; kz = r.nextGaussian() * 0.4; ky = 0.36; e.onVelocity(id, kx, ky, kz, false); }
            int pid = e.pingFor(id);
            wire.addLast(new double[]{t + delay, pid, kx, ky, kz, hit ? 1 : 0});
            while (!wire.isEmpty() && wire.peekFirst()[0] <= t) {
                double[] w = wire.pollFirst();
                if (w[5] == 1) { s.vx = w[2]; s.vy = w[3]; s.vz = w[4]; }
                e.onPong(id, (int) w[1]);
            }
            s.fw = t % 90 < 70; s.sprinting = s.fw && t % 200 < 120; s.jump = t % 29 == 0;
            s.yaw += (float) (r.nextGaussian() * 6);
            s.prep();
            MoveEnv env = s.env();
            MoveInput m = s.tick();
            env.groundBelowNew = s.y <= 0.03;
            e.onMove(id, m, env);
            e.onClientTickEnd(id);
        }
        PlayerData p = e.get(id);
        System.out.println("  delayed kb speed=" + p.check("speed").flags() + " fly=" + p.check("fly").flags() + " velocity=" + p.check("velocity").flags());
        return p.check("speed").flags() + p.check("fly").flags() + p.check("velocity").flags();
    }

    /** 겉날개 — 바닐라 활공 공식(25.x~26.x 동일)으로 만든 궤적. hack이면 높이를 유지하며 직진. */
    static int elytra(boolean hack) {
        Engine e = newEngine();
        UUID id = UUID.randomUUID();
        e.join(id, "e", 1);
        double x = 0, y = 200, z = 0, vx = 0, vy = 0, vz = 0.8;
        MoveEnv base = new MoveEnv();
        base.ticksExisted = 1000; base.gliding = true;
        e.onMove(id, new MoveInput(true, true, x, y, z, 0, 30, false, false), base);
        for (int t = 0; t < 400; t++) {
            CLOCK[0] += 50_000_000L;
            float pitch = (float) (t < 100 ? 45 : t < 200 ? -20 : 10 * Math.sin(t / 15.0));
            float yaw = (float) (t * 0.7);
            double[] v;
            if (hack) {
                double[] l = Box.look(yaw, 0);
                v = new double[]{l[0] * 1.2, 0, l[2] * 1.2};
            } else {
                v = Movement.glide(vx, vy, vz, yaw, pitch, 0.08);
            }
            vx = v[0]; vy = v[1]; vz = v[2];
            x += vx; y += vy; z += vz;
            MoveEnv env = base.copy();
            e.onMove(id, new MoveInput(true, true, x, y, z, yaw, pitch, false, false), env);
            e.onClientTickEnd(id);
        }
        PlayerData p = e.get(id);
        System.out.println("  elytra hack=" + hack + " flags=" + p.check("elytra").flags() + " speed=" + p.check("speed").flags());
        return p.check("elytra").flags() + (hack ? 0 : p.check("speed").flags());
    }

    static boolean timerTest(long tickMs) {
        Engine e = newEngine();
        UUID id = UUID.randomUUID();
        PlayerData p = e.join(id, "t", 1);
        long ns = 1_000_000_000L;
        Engine.Out out = new Engine.Out();
        for (int i = 0; i < 400; i++) {
            ns += tickMs * 1_000_000L;
            Movement.onClientTick(e, p, ns, out);
        }
        return p.check("timer").flags() > 0;
    }

    static boolean timerTestJitter() {
        Engine e = newEngine();
        UUID id = UUID.randomUUID();
        PlayerData p = e.join(id, "t", 1);
        Random r = new Random(3);
        Engine.Out out = new Engine.Out();
        long last = 0;
        for (int i = 0; i < 4000; i++) {
            // 클라는 50ms마다 보낸다. 도착 시각 = 보낸 시각 + 지연(20~120ms 흔들림, 가끔 600ms 랙 스파이크 뒤 몰려 도착)
            double lat = 60 + r.nextGaussian() * 20;
            if (i % 300 >= 0 && i % 300 < 12) lat = 600 - (i % 300) * 50; // 랙: 12틱이 한꺼번에 도착
            long arrive = (long) ((i * 50 + Math.max(5, lat)) * 1_000_000L) + 1_000_000_000L;
            arrive = Math.max(arrive, last); // 순서 보장(TCP/QUIC 스트림)
            last = arrive;
            Movement.onClientTick(e, p, arrive, out);
        }
        return p.check("timer").flags() > 0;
    }

    static void reachTests() {
        Engine e = newEngine();
        UUID id = UUID.randomUUID();
        PlayerData p = e.join(id, "a", 1);
        // 대상 엔티티 2: (3.2, 0, 0)에 서 있음 → 히트박스 x 2.9~3.5, 눈에서 2.9
        for (long t = 1; t <= 20; t++) { e.serverTick(t); e.recordEntity(2, Box.ofPlayer(3.2, 0, 0, 0.6, 1.8)); }
        Combat.Attack ok = new Combat.Attack(2, null, 0, 1.62, 1.27, 0, 3.0, false, false, false, true);
        for (int i = 0; i < 10; i++) e.onAttack(id, ok);
        check(p.check("reach").flags() == 0, "리치 3.0 안 공격 통과(거리 2.9)");
        Engine e2 = newEngine();
        PlayerData p2 = e2.join(id, "a", 1);
        for (long t = 1; t <= 20; t++) { e2.serverTick(t); e2.recordEntity(2, Box.ofPlayer(4.0, 0, 0, 0.6, 1.8)); }
        for (int i = 0; i < 10; i++) e2.onAttack(id, ok);
        check(p2.check("reach").flags() > 0, "리치 3.7 적발");
        // 랙 보정: 대상이 멀어지는 중 — 퐁 확인된 과거 틱 위치로는 3 안
        Engine e3 = newEngine();
        PlayerData p3 = e3.join(id, "a", 1);
        for (long t = 1; t <= 40; t++) {
            e3.serverTick(t);
            e3.recordEntity(2, Box.ofPlayer(2.5 + t * 0.1, 0, 0, 0.6, 1.8));
            int pid = e3.pingFor(id);
            if (t <= 8) e3.onPong(id, pid); // 클라는 8틱까지만 확인(핑이 아주 높음) — 그때 대상은 3.3
        }
        for (int i = 0; i < 5; i++) e3.onAttack(id, ok);
        check(p3.check("reach").flags() == 0, "랙 보정: 과거 위치로 정상 판정");
    }

    static void worldTests() {
        Engine e = newEngine();
        UUID id = UUID.randomUUID();
        PlayerData p = e.join(id, "w", 1);
        // 블록 (0,-1,2) 윗면 클릭, 눈 높이 1.62 → 정상
        World.Place up = new World.Place(0, -1, 2, World.UP, 0.5, 0.0, 2.5, 0.5, 1.62, 1.27, 0.5, 4.5, false, false, false);
        for (int i = 0; i < 5; i++) e.onPlace(id, up);
        check(p.check("scaffold").flags() == 0, "보이는 윗면 설치 통과");
        // 발밑 블록 (0,-1,0) 아랫면 클릭 → 불가능
        World.Place down = new World.Place(0, -1, 0, World.DOWN, 0.5, -1.0, 0.5, 0.5, 1.62, 1.27, 0.5, 4.5, false, false, false);
        for (int i = 0; i < 5; i++) e.onPlace(id, down);
        check(p.check("scaffold").flags() > 0, "보이지 않는 아랫면 설치(스캐폴드) 적발");
        // 빠른 채굴: 돌 1틱 0.05 진행 → 20틱 필요한데 5틱 만에 STOP
        Engine e2 = newEngine();
        PlayerData q = e2.join(id, "w", 1);
        Box bb = new Box(0, 0, 2, 1, 1, 3);
        World.Dig start = new World.Dig(World.Dig.START, 0, 0, 2, World.NORTH, bb, 0.05, 0.5, 1.62, 1.27, 0.5, 4.5, false, false, false, true);
        World.Dig stop = new World.Dig(World.Dig.STOP, 0, 0, 2, World.NORTH, bb, 0.05, 0.5, 1.62, 1.27, 0.5, 4.5, false, false, false, true);
        for (int k = 0; k < 4; k++) {
            e2.onDig(id, start);
            for (int t = 0; t < 5; t++) e2.onClientTickEnd(id);
            e2.onDig(id, stop);
        }
        check(q.check("fastbreak").flags() > 0, "빠른 채굴 적발");
        Engine e3 = newEngine();
        PlayerData q3 = e3.join(id, "w", 1);
        for (int k = 0; k < 4; k++) {
            e3.onDig(id, start);
            for (int t = 0; t < 19; t++) e3.onClientTickEnd(id);
            e3.onDig(id, stop);
        }
        check(q3.check("fastbreak").flags() == 0, "정상 채굴 통과");
    }
}
