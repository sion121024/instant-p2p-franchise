package kfc.udp.client.anticheat;

import java.util.ArrayList;
import java.util.List;

/**
 * 이동 검사 — 바닐라 플레이어 물리를 그대로 계산해 "이번 틱에 이 사람이 갈 수 있었던 곳"과 실제 위치를 비교한다
 * (Grim 식 예측). 스피드·스트레이프·비행·호버·고점프·스텝·노슬로우·타이머·노폴(지면 위조)·겉날개 비행·넉백 무시를 잡는다.
 * <p>
 * <b>정밀 예측</b>(평지·공중 걷기): 지난 틱 실제 이동량에 바닥 마찰·공기 저항을 곱한 관성 + 이번 틱 입력 가속(1.21.2+는
 * 클라가 보내는 실제 키 입력으로 정확히, 이전 버전은 가능한 최대 가속 원) + 점프·질주 점프 가속 + 서버가 보낸 넉백 후보 중
 * 실제 이동을 가장 잘 설명하는 것과의 차이(offset)를 잰다. 차이가 허용치를 넘으면 버퍼에 쌓고, 버퍼가 차면 위반.
 * <br><b>넓은 검사</b>(물·용암·사다리·거미줄·겉날개 등): 그 상태에서 낼 수 있는 최대 속도로만 본다.
 * <p>
 * 오탐 원칙: 모르는 것(충돌, 끼임, 밀림, 효과 막 끝남, 핑)은 항상 "더 많이 움직일 수 있었다" 쪽으로 해석한다.
 * 그래서 놓치는 핵은 있어도 정상 플레이어를 잡지 않는 쪽으로 기운다.
 */
final class Movement {

    /** 기본 허용 오차(블록) — float/double 반올림과 Mth.sin 표 근사. */
    static final double EPS = 0.003;
    /** 이동 판정에서 이만큼 넘게 한 틱에 움직였으면 서버가 시킨 텔레포트(포탈·/tp)로 본다. */
    static final double TELEPORT_DIST = 10;

    private Movement() {}

    static boolean DEBUG = Boolean.getBoolean("ac.debug");
    static int dbg;

    static float modFriction(float friction, float modifier) {
        return Math.max(0f, Math.min(1f, 1f - (1f - friction) * modifier));
    }

    /** 이번 틱 지면 가속 계수 — LivingEntity.getFrictionInfluencedSpeed. */
    static double groundAccel(double speed, float blockFriction) {
        return blockFriction > 0.6f ? speed * (0.21600002f / (blockFriction * blockFriction * blockFriction)) : speed;
    }

    /**
     * 키 입력 → 가속 벡터들(버전별 대각선 처리 차이 때문에 1~2개).
     * @return 각 원소 {x, z}
     */
    static List<double[]> inputAccel(MoveEnv env, float yaw, double accel, double inputMul) {
        List<double[]> out = new ArrayList<>(2);
        float strafe = (env.left ? 1 : 0) - (env.right ? 1 : 0);
        float fwd = (env.forward ? 1 : 0) - (env.backward ? 1 : 0);
        if (strafe == 0 && fwd == 0) {
            out.add(new double[]{0, 0});
            return out;
        }
        double sx = strafe * 0.98 * inputMul, sz = fwd * 0.98 * inputMul;
        // (a) 26.x/1.21.5+ : 정사각형 보정(대각선도 길이 1까지)
        double len = Math.sqrt(sx * sx + sz * sz);
        double dirX = sx / len, dirZ = sz / len;
        double toSquare = 1.0 / Math.max(Math.abs(dirX), Math.abs(dirZ));
        double modLen = Math.min(len * toSquare, 1.0);
        out.add(rotate(dirX * modLen, dirZ * modLen, accel, yaw));
        // (b) 이전 버전: 그대로, 길이 1 넘으면 정규화
        double l2 = len > 1 ? 1 / len : 1;
        out.add(rotate(sx * l2, sz * l2, accel, yaw));
        return out;
    }

    /** Entity.getInputVector — 입력(x=좌우, z=앞뒤)에 속도를 곱해 yaw만큼 돌린다. */
    static double[] rotate(double ix, double iz, double speed, float yaw) {
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad), cos = Math.cos(rad);
        double mx = ix * speed, mz = iz * speed;
        return new double[]{mx * cos - mz * sin, mz * cos + mx * sin};
    }

    static void onMove(Engine e, PlayerData p, MoveInput m, MoveEnv env, long nowMs, Engine.Out out) {
        // ── 회전 ──
        if (m.hasRot()) {
            Combat.onRotation(e, p, m.yaw(), m.pitch(), nowMs);
            Packets.checkPitch(e, p, m.pitch(), nowMs);
        }
        boolean on = e.cfg.categoryOn(Check.Category.MOVEMENT);
        if (!m.hasPos()) {
            if (on && !env.exemptAll()) groundSpoof(e, p, m, env, nowMs, out, 0);
            return;
        }
        long nowNs = e.nowNs();
        int idleTicks = p.tickEndSeen ? p.ticksSinceMove
                : (p.lastMoveNs != 0 && (nowNs - p.lastMoveNs) > 80_000_000L ? 2 : 1);
        p.ticksSinceMove = 0;
        p.lastMoveNs = nowNs;
        onMovePacketForTimer(e, p, nowNs, out);

        if (env.awaitingTeleport) {
            // 텔레포트 확인 전에 오는 옛 이동은 바닐라도 무시한다 — 기준점을 잡지 말고 확인 뒤 첫 이동에서 잡는다.
            p.hasPos = false;
            return;
        }
        if (!p.hasPos || env.exemptAll()) {
            resync(p, m, env);
            if (!env.exemptAll()) p.setLegit(m.x(), m.y(), m.z());
            return;
        }
        double dx = m.x() - p.x, dy = m.y() - p.y, dz = m.z() - p.z;
        if (dx * dx + dz * dz > TELEPORT_DIST * TELEPORT_DIST || Math.abs(dy) > 32) {
            // 바닐라가 "moved too quickly"로 이미 되돌린다 — 우리는 기준점만 다시 잡는다.
            resync(p, m, env);
            return;
        }
        if (!on) {
            store(p, m, env, dx, dy, dz, false, false);
            return;
        }

        float yaw = m.hasRot() ? m.yaw() : p.yaw;
        boolean special = !env.plainPhysics();
        if (special) p.graceTicks = 10;
        boolean precise = !special && p.lastDeltaKnown && p.uncertainTicks <= 0 && p.graceTicks <= 0 && idleTicks <= 1;
        if (p.uncertainTicks > 0) p.uncertainTicks--;
        if (!special && p.graceTicks > 0) p.graceTicks--;

        // 효과가 막 끝났을 때(서버는 끝났는데 클라는 아직 모름) 오탐 방지 — 최근 핑+1초 사이 최대값.
        // 질주 배율(1.3)은 빼고 기록한다 — 질주를 멈춘 직후를 "효과가 끝난 것"으로 오해하지 않게.
        double sprintMul = env.sprinting ? 1.3 : 1.0;
        double baseMax = p.recentMax(p.speedHist, env.movementSpeed / sprintMul, nowMs, (long) (p.tx.rttMs() + 1000));
        double speedAttr = env.movementSpeed;
        double speedAttrMax = baseMax * sprintMul;
        int jumpAmp = (int) p.recentMax(p.jumpHist, env.jumpBoostAmp, nowMs, (long) (p.tx.rttMs() + 1000));

        double hTol = EPS + 0.06 * env.pushingEntities + (env.hurtTime > 0 && !p.tx.working() ? 0.6 : 0);
        double vTol = EPS + 0.03 * env.pushingEntities + (env.hurtTime > 0 && !p.tx.working() ? 0.6 : 0);

        boolean flaggedH = false, flaggedV = false;
        String detailH = null, detailV = null;

        // ── 넉백 후보 ──
        p.expireVelocities();
        List<PlayerData.Velocity> kbs = new ArrayList<>(p.velocities);

        double vdrag = modFriction(0.98f, env.airDragModifier);
        MoveEnv le = p.lastEnv != null ? p.lastEnv : env;
        float carryFriction = (p.groundStartOfLast ? le.blockFriction : 1f) * modFriction(0.91f, le.airDragModifier);

        // ━━ 수평 ━━
        double bestH;
        PlayerData.Velocity usedKb = null;
        double bestNoKb;
        if (precise) {
            double[][] carries = carries(p, env, carryFriction);
            boolean groundNow = p.lastGround;
            // 가속 후보: 지금 속성값, 최근 최대값(효과 막 끝남), 질주 상태 반대(질주 시작/멈춤이 같은 틱)
            double[] accels;
            if (groundNow) {
                double alt = env.sprinting ? speedAttr / 1.3 : speedAttr * 1.3;
                accels = new double[]{groundAccel(speedAttr, env.blockFriction), groundAccel(alt, env.blockFriction),
                        groundAccel(speedAttrMax, env.blockFriction)};
            } else {
                accels = new double[]{env.sprinting ? 0.025999999f : 0.02f, env.sprinting ? 0.02f : 0.025999999f};
            }
            double accel = 0;
            for (double a : accels) accel = Math.max(accel, a);
            // 점프했을 수 있으면(지면에서 위로) 질주 점프 가속(시선 방향 0.2)도 후보 — 계단 오르기와 구분이 안 되니 둘 다.
            List<double[]> jvs = new ArrayList<>(2);
            jvs.add(new double[]{0, 0});
            if (groundNow && dy > 1e-4 && (env.sprinting || env.sprintKey)) {
                double r = Math.toRadians(yaw);
                jvs.add(new double[]{-Math.sin(r) * 0.2, Math.cos(r) * 0.2});
            }
            List<Double> muls = inputMultipliers(p, env);
            bestNoKb = bestHorizontal(env, yaw, carries, jvs, accels, muls, dx, dz);
            bestH = bestNoKb;
            for (PlayerData.Velocity v : kbs) {
                double[][] kc = new double[carries.length][];
                for (int i = 0; i < carries.length; i++) {
                    kc[i] = v.additive ? new double[]{carries[i][0] + v.x, carries[i][1] + v.z} : new double[]{v.x, v.z};
                }
                double o = bestHorizontal(env, yaw, kc, jvs, accels, muls, dx, dz);
                if (o < bestH) { bestH = o; usedKb = v; }
            }
            // 웅크린 채 모서리에서는 바닐라가 이동만 잘라내고 속도는 그대로 둔다 — 관성 추정이 틀리므로 크기만 본다.
            boolean edgeSneak = (env.sneakingPose || env.shiftKey) && groundNow;
            if (m.horizontalCollision() || env.horizontalBlockedNew || edgeSneak) {
                // 벽에 막혔으면 방향 대신 크기만 — 막힌 축은 덜 움직였을 수밖에 없다.
                double maxSpeed = 0;
                double[] jv = jvs.get(jvs.size() - 1);
                for (double[] c : carries) maxSpeed = Math.max(maxSpeed, Math.hypot(c[0] + jv[0], c[1] + jv[1]));
                for (PlayerData.Velocity v : kbs) maxSpeed = Math.max(maxSpeed, Math.hypot(v.x, v.z) + Math.hypot(carries[0][0], carries[0][1]));
                maxSpeed += accel;
                bestH = Math.min(bestH, Math.max(0, Math.hypot(dx, dz) - maxSpeed));
                bestNoKb = bestH;
            }
            if (bestH > hTol && DEBUG && dbg++ < 15) System.out.printf("    dbg H off=%.5f d=(%.4f,%.4f) last=(%.4f,%.4f) g=%s gs=%s spr=%s jmp=%s dy=%.4f use=%s/%d sn=%s/%d keys=%s%s%s%s yaw=%.1f%n",
                    bestH, dx, dz, p.lastDx, p.lastDz, p.lastGround, p.groundStartOfLast, env.sprinting, env.jumpKey, dy,
                    env.usingItem, p.useTicks, env.sneakingPose, p.sneakTicks, env.forward?"F":"", env.backward?"B":"", env.left?"L":"", env.right?"R":"", yaw);
            if (bestH > hTol) {
                detailH = String.format("offset=%.4f", bestH);
                p.lastOffset = bestH;
                flaggedH = p.check("speed").fail(nowMs, bestH - hTol, Math.min(5, 0.5 + bestH * 10));
                if (flaggedH) e.flag(p, "speed", detailH, out);
            } else {
                p.check("speed").reward();
            }
            velocityCheck(e, p, usedKb, bestNoKb, bestH, hTol, kbs, env, m, nowMs, out);
        } else {
            double h = Math.hypot(dx, dz);
            double lastH = Math.hypot(p.lastDx, p.lastDz);
            double kbH = 0;
            for (PlayerData.Velocity v : kbs) kbH = Math.max(kbH, Math.hypot(v.x, v.z));
            double cap = envelopeCap(env, lastH, speedAttrMax) + kbH + hTol;
            if (idleTicks > 1) cap *= idleTicks; // 패킷이 몇 틱 비었으면 그만큼
            if (env.fireworkBoosts > 0 || env.riptide) cap = Math.max(cap, 4.0);
            if (h > cap) {
                detailH = String.format("h=%.3f cap=%.3f", h, cap);
                flaggedH = p.check("speed").fail(nowMs, h - cap, Math.min(5, 0.5 + (h - cap) * 5));
                if (flaggedH) e.flag(p, "speed", detailH, out);
            } else {
                p.check("speed").reward();
            }
            p.velocities.removeIf(v -> v.confirmed && ++v.movesSinceConfirm > 2);
        }

        // ━━ 수직 ━━
        double predY = Double.NaN;
        if (precise && !env.gliding) {
            // 지난 틱에 땅·천장에 부딪혔으면 바닐라가 수직 속도를 0으로(슬라임·침대면 튕김) 만든다.
            double vyPost = p.lastDy;
            if (p.lastVerticalCollision) {
                vyPost = (env.bounceFactor > 0 && p.lastPreLandVy < 0 && !env.shiftKey) ? -p.lastPreLandVy * env.bounceFactor : 0;
            }
            // 느린 낙하가 막 시작/끝났으면(서버·클라가 핑만큼 어긋남) 두 중력을 다 후보로.
            boolean sfRecent = p.recentMax(p.slowFallHist, env.slowFalling ? 1 : 0, nowMs, (long) (p.tx.rttMs() + 1000)) > 0;
            double g = env.gravity;
            if (env.slowFalling && vyPost <= 0) g = Math.min(g, 0.01);
            double pred = (vyPost - g) * vdrag;
            double predAltGravity = vyPost <= 0 && sfRecent ? (vyPost - (env.slowFalling ? env.gravity : Math.min(env.gravity, 0.01))) * vdrag : pred;
            if (Math.abs(pred) < 0.003) pred = 0; // 바닐라: 틱 시작 수직 속도 0.003 미만은 0
            double maxC = pred, minC = pred;
            if (Math.abs(pred) < 0.0035) { maxC = Math.max(maxC, 0); minC = Math.min(minC, 0); }
            maxC = Math.max(maxC, predAltGravity);
            minC = Math.min(minC, predAltGravity);
            if (p.lastGround) {
                double jp = env.jumpStrength * env.blockJumpFactor + (jumpAmp >= 0 ? 0.1 * (jumpAmp + 1) : 0);
                maxC = Math.max(maxC, Math.max(jp, pred));
            }
            for (PlayerData.Velocity v : kbs) {
                double c = v.additive ? pred + v.y : v.y;
                maxC = Math.max(maxC, c);
                minC = Math.min(minC, c);
            }
            predY = pred;
            boolean landing = env.groundBelowNew && dy <= vTol;
            // 스텝: 땅에서, 또는 떨어지다 옆 모서리에 걸렸을 때(바닐라는 낙하 중 가로 충돌이면 스텝 허용)
            boolean sideHit = m.horizontalCollision() || env.horizontalBlockedPrev || env.horizontalBlockedNew;
            boolean step = (p.lastGround || env.groundBelowPrev || (pred < 0 && sideHit))
                    && dy > 0 && dy <= env.stepHeight + vTol && env.groundBelowNew;
            p.stepThisTick = step;
            p.maxCThisTick = maxC;
            if (dy > maxC + vTol && !landing && !step) {
                double off = dy - maxC;
                detailV = String.format("dy=%.4f pred=%.4f", dy, maxC);
                flaggedV = p.check("fly").fail(nowMs, off, Math.min(5, 0.5 + off * 8));
                if (flaggedV) e.flag(p, "fly", detailV, out);
            } else if (dy < minC - vTol && !env.ceilingAboveNew && !(env.onClimbable)) {
                double off = minC - dy;
                detailV = String.format("fastfall dy=%.4f pred=%.4f", dy, minC);
                // 빨리 떨어지는 건 이득이 적은 핵이라 가볍게.
                flaggedV = p.check("fly").fail(nowMs, off * 0.5, Math.min(2, off * 4));
                if (flaggedV) e.flag(p, "fly", detailV, out);
            } else {
                p.check("fly").reward();
            }
        } else if (!env.gliding && !env.riptide && !env.flying && env.levitationAmp < 0 && !env.inBubbleColumn
                && !env.pushedByBlock && p.uncertainTicks <= 0) {
            // 넓은 수직 검사: 물·용암·사다리 밖에서 오래 안 떨어지는 호버, 액체 위 걷기(제수스)
            hoverEnvelope(e, p, m, env, dy, nowMs, out);
        }

        if (env.gliding) elytra(e, p, env, dx, dy, dz, nowMs, out);

        groundSpoof(e, p, m, env, nowMs, out, dy);

        // ── 셋백 ──
        boolean moveFlag = flaggedH || flaggedV;
        if (moveFlag && e.mitigate() && p.legitKnown && p.allowSetback(nowMs)
                && (p.check("speed").vl >= 4 || p.check("fly").vl >= 4 || p.lastOffset > 0.5)) {
            out.setback = new double[]{p.legitX, p.legitY, p.legitZ};
            p.uncertainTicks = 5;
            p.lastDeltaKnown = false;
            p.hasPos = false; // 텔레포트 확인 후 다시 잡는다
            p.velocities.clear();
            return;
        }

        // 넉백 사용 처리
        if (usedKb != null) p.velocities.remove(usedKb);

        // 땅에 닿음(착지·스텝 포함) 또는 머리를 박음(점프 후보까지 포함한 예측보다 덜 올라감) → 바닐라는 수직 속도 0
        boolean verticalCollision = (env.groundBelowNew && (dy <= vTol || p.stepThisTick || m.onGround()))
                || (env.ceilingAboveNew && !Double.isNaN(predY) && dy < p.maxCThisTick - vTol);
        p.stepThisTick = false;
        if (verticalCollision && dy <= 0) p.lastPreLandVy = Double.isNaN(predY) ? dy : predY;
        store(p, m, env, dx, dy, dz, m.horizontalCollision() || env.horizontalBlockedNew, verticalCollision);
        if (!moveFlag && (m.onGround() && env.groundBelowNew || env.inWater || env.onClimbable)) p.setLegit(m.x(), m.y(), m.z());
    }

    /** 관성 후보들 — 블록 속도 배율 적용/미적용, 지난 틱 벽에 막혔으면 그 축 0. */
    private static double[][] carries(PlayerData p, MoveEnv env, float friction) {
        double bx = p.lastDx * friction, bz = p.lastDz * friction;
        double sf = env.blockSpeedFactor;
        List<double[]> c = new ArrayList<>(8);
        c.add(new double[]{bx * sf, bz * sf});
        if (sf != 1.0) c.add(new double[]{bx, bz});
        if (p.lastHorizontalCollision || env.horizontalBlockedPrev) {
            c.add(new double[]{0, bz * sf});
            c.add(new double[]{bx * sf, 0});
            c.add(new double[]{0, 0});
        }
        // 바닐라: 틱 시작에 수평 속도가 0.003 미만이면 0으로 만든다(플레이어).
        if (bx * bx + bz * bz < 9.0E-6 * 1.5 || bx * bx * sf * sf + bz * bz * sf * sf < 9.0E-6 * 1.5) c.add(new double[]{0, 0});
        return c.toArray(new double[0][]);
    }

    /**
     * 입력 배율 후보 — 서버의 현재 웅크림·아이템 사용 상태가 기준(클라 패킷이 이동보다 먼저 와서 같다). 최근 3틱 안에
     * 바뀌었으면(먹기가 서버 틱에서 끝나는 등 클라와 어긋날 수 있음) 반대 상태도 후보. 오래 같은 상태면 그것만 —
     * 그래야 먹으면서 제 속도로 달리는 노슬로우가 잡힌다.
     */
    private static List<Double> inputMultipliers(PlayerData p, MoveEnv env) {
        List<Double> m = new ArrayList<>(4);
        boolean sneakRecent = Math.abs(p.sneakTicks) < 3 || (p.sneakTicks > 0) != env.sneakingPose;
        boolean useRecent = Math.abs(p.useTicks) < 3 || (p.useTicks > 0) != env.usingItem;
        boolean[] sneakOpts = sneakRecent ? new boolean[]{env.sneakingPose, !env.sneakingPose} : new boolean[]{env.sneakingPose};
        boolean[] useOpts = useRecent ? new boolean[]{env.usingItem, !env.usingItem} : new boolean[]{env.usingItem};
        for (boolean s : sneakOpts) {
            for (boolean u : useOpts) {
                double v = 1;
                if (u) v *= env.itemUseSpeed;
                if (s) v *= env.sneakingSpeed;
                if (!m.contains(v)) m.add(v);
            }
        }
        return m;
    }

    private static double bestHorizontal(MoveEnv env, float yaw, double[][] carries, List<double[]> jvs,
                                         double[] accels, List<Double> muls, double dx, double dz) {
        double best = Double.MAX_VALUE;
        for (double[] c : carries) for (double[] jv : jvs) {
            double bx = c[0] + jv[0], bz = c[1] + jv[1];
            for (double a : accels) {
                if (env.hasInput) {
                    for (double mul : muls) {
                        for (double[] acc : inputAccel(env, yaw, a, mul)) {
                            double ox = dx - (bx + acc[0]), oz = dz - (bz + acc[1]);
                            best = Math.min(best, Math.sqrt(ox * ox + oz * oz));
                        }
                    }
                } else {
                    // 키를 모르는 버전: 가능한 최대 가속 원 안이면 0
                    double ox = dx - bx, oz = dz - bz;
                    best = Math.min(best, Math.max(0, Math.sqrt(ox * ox + oz * oz) - a));
                }
            }
        }
        return best;
    }

    /** 특수 상태별 최대 수평 속도(블록/틱) — 넉넉하게. */
    private static double envelopeCap(MoveEnv env, double lastH, double speedAttr) {
        if (env.flying) return lastH * 0.91 + env.flySpeed * 2 + 0.1;
        if (env.gliding) return Math.max(3.6, lastH * 1.05 + 0.2);
        if (env.riptide) return 4.0;
        if (env.stuck || env.inPowderSnow) return Math.max(0.35, lastH);
        if (env.onClimbable) return Math.max(0.25, lastH * 0.91 + 0.1);
        if (env.inLava) return lastH * 0.5 + 0.05 + 0.1;
        if (env.inWater || env.inBubbleColumn) {
            double f = env.dolphinsGrace ? 0.96 : (env.waterMovementEfficiency > 0 ? 0.96 : 0.9);
            double a = 0.04 + 0.12 * env.waterMovementEfficiency + (env.dolphinsGrace ? 0.08 : 0);
            return lastH * f + a + 0.08;
        }
        // 평범한 상태인데 정밀 예측을 못 하는 틱(텔레포트 직후·패킷 공백): 질주 점프 최대치 + 여유
        return Math.max(lastH * 0.91 + groundAccel(speedAttr, 0.6f) + 0.2 + 0.1, 0.75)
                * (env.blockFriction > 0.9 ? 1.6 : 1.0);
    }

    private static void hoverEnvelope(Engine e, PlayerData p, MoveInput m, MoveEnv env, double dy, long nowMs, Engine.Out out) {
        boolean liquidSurface = (env.inWater || env.inLava || env.liquidAtNew) && !env.underWater;
        if (liquidSurface && m.onGround() && !env.groundBelowNew && Math.abs(dy) < 1e-3) {
            // 액체 표면에서 땅인 척 — 제수스
            if (p.check("fly").fail(nowMs, 0.02, 1)) e.flag(p, "fly", "jesus", out);
            return;
        }
        if (env.inWater || env.inLava || env.onClimbable || env.stuck || env.inScaffolding || env.inPowderSnow) {
            p.airHoverTicks = 0;
            return;
        }
        if (!env.groundBelowNew && dy > -0.03) {
            if (++p.airHoverTicks > 40) {
                p.airHoverTicks = 0;
                if (p.check("fly").fail(nowMs, 0.2, 2)) e.flag(p, "fly", "hover", out);
            }
        } else {
            p.airHoverTicks = 0;
            p.check("fly").reward();
        }
    }

    /**
     * 겉날개 — 바닐라 LivingEntity.updateFallFlyingMovement를 그대로 계산해 비교한다(폭죽 없을 때).
     * 지난 틱 실제 이동량 → 이번 틱 시선으로 활공 공식 → 예상 이동량. 회전 시점 차이 때문에 직전·이번 시선 둘 다 후보.
     * 정지 비행(호버)도 따로 잡는다.
     */
    private static void elytra(Engine e, PlayerData p, MoveEnv env, double dx, double dy, double dz, long nowMs, Engine.Out out) {
        if (env.fireworkBoosts > 0 || env.riptide || p.uncertainTicks > 0 || !p.lastDeltaKnown || p.lastHorizontalCollision
                || env.horizontalBlockedNew || env.groundBelowNew || env.ceilingAboveNew) {
            p.elytraHoverTicks = 0;
            return;
        }
        double g = env.gravity;
        double best = Double.MAX_VALUE;
        for (float[] r : new float[][]{{p.yaw, p.pitch}, {p.prevYaw, p.prevPitch}}) {
            for (boolean sf : new boolean[]{false, true}) {
                double gg = sf ? Math.min(g, 0.01) : g;
                if (sf && !env.slowFalling && p.slowFallHist.isEmpty()) continue;
                double[] v = glide(p.lastDx, p.lastDy, p.lastDz, r[0], r[1], gg);
                double ox = dx - v[0], oy = dy - v[1], oz = dz - v[2];
                best = Math.min(best, Math.sqrt(ox * ox + oy * oy + oz * oz));
            }
        }
        double tol = 0.01 + 0.05 * env.pushingEntities;
        if (best > tol) {
            if (p.check("elytra").fail(nowMs, best - tol, Math.min(4, 0.5 + best * 10)))
                e.flag(p, "elytra", String.format("offset=%.4f", best), out);
        } else {
            p.check("elytra").reward();
        }
        double sp = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (sp < 0.08 && dy > -0.04) {
            if (++p.elytraHoverTicks > 30) {
                p.elytraHoverTicks = 0;
                if (p.check("elytra").fail(nowMs, 1, 2)) e.flag(p, "elytra", "hover", out);
            }
        } else {
            p.elytraHoverTicks = 0;
        }
    }

    /** LivingEntity.updateFallFlyingMovement (26.3과 1.21.x 같은 식). 반환 = 이번 틱 이동량. */
    static double[] glide(double mx, double my, double mz, float yaw, float pitch, double gravity) {
        double[] look = Box.look(yaw, pitch);
        double lean = Math.toRadians(pitch);
        double lookHor = Math.sqrt(look[0] * look[0] + look[2] * look[2]);
        double moveHor = Math.sqrt(mx * mx + mz * mz);
        double lift = Math.cos(lean) * Math.cos(lean);
        my += gravity * (-1.0 + lift * 0.75);
        if (my < 0 && lookHor > 0) {
            double c = my * -0.1 * lift;
            mx += look[0] * c / lookHor; my += c; mz += look[2] * c / lookHor;
        }
        if (lean < 0 && lookHor > 0) {
            double c = moveHor * -Math.sin(lean) * 0.04;
            mx += -look[0] * c / lookHor; my += c * 3.2; mz += -look[2] * c / lookHor;
        }
        if (lookHor > 0) {
            mx += (look[0] / lookHor * moveHor - mx) * 0.1;
            mz += (look[2] / lookHor * moveHor - mz) * 0.1;
        }
        return new double[]{mx * 0.99f, my * 0.98f, mz * 0.99f};
    }

    /** 노폴/지면 위조 — 공중인데 "땅에 있다"고 보내면 낙하 피해가 사라진다. 판정을 서버 사실로 바꿔 놓는다. */
    private static void groundSpoof(Engine e, PlayerData p, MoveInput m, MoveEnv env, long nowMs, Engine.Out out, double dy) {
        if (!m.onGround() || env.groundBelowNew) {
            if (!m.onGround()) p.check("groundspoof").reward();
            return;
        }
        if (env.inWater || env.inLava || env.onClimbable || env.inScaffolding || env.stuck || env.inPowderSnow
                || env.pushedByBlock || p.uncertainTicks > 0) return;
        out.groundOverride = Boolean.FALSE; // 피해 판정은 항상 서버 사실대로
        if (p.check("groundspoof").fail(nowMs, 1, 1)) {
            e.flag(p, "groundspoof", String.format("dy=%.3f", dy), out);
        }
    }

    /** 넉백 무시(안티 넉백·벨로시티) — 클라가 넉백을 확실히 받은(퐁 확인) 뒤 첫 이동이 넉백 없이 설명되면. */
    private static void velocityCheck(Engine e, PlayerData p, PlayerData.Velocity usedKb, double bestNoKb, double bestH,
                                      double tol, List<PlayerData.Velocity> kbs, MoveEnv env, MoveInput m, long nowMs, Engine.Out out) {
        for (PlayerData.Velocity v : kbs) {
            if (!v.confirmed || v == usedKb) continue;
            v.movesSinceConfirm++;
            if (v.movesSinceConfirm < 2) continue; // 확인 직후 한 번은 패킷 경계 차이로 봐준다
            double kbH = Math.hypot(v.x, v.z);
            if (kbH > 0.08 && bestNoKb <= tol && !m.horizontalCollision() && !env.horizontalBlockedNew) {
                if (p.check("velocity").fail(nowMs, 1, 1)) {
                    e.flag(p, "velocity", String.format("kb=%.3f ignored", kbH), out);
                }
            }
            p.velocities.remove(v);
        }
        if (usedKb != null) p.check("velocity").reward();
    }

    static void resync(PlayerData p, MoveInput m, MoveEnv env) {
        p.x = m.x(); p.y = m.y(); p.z = m.z();
        p.hasPos = true;
        p.lastDeltaKnown = false;
        p.uncertainTicks = Math.max(p.uncertainTicks, 3);
        p.lastGround = m.onGround();
        p.groundStartOfLast = m.onGround();
        p.lastEnv = env;
        p.elytraEnergy = Double.NaN;
    }

    private static void store(PlayerData p, MoveInput m, MoveEnv env, double dx, double dy, double dz,
                              boolean hCol, boolean vCol) {
        p.lastDx = dx; p.lastDy = dy; p.lastDz = dz;
        p.lastDeltaKnown = true;
        p.groundStartOfLast = p.lastGround;
        p.lastGround = m.onGround() && (env.groundBelowNew || env.inWater || env.onClimbable || env.inScaffolding);
        p.lastHorizontalCollision = hCol;
        p.lastVerticalCollision = vCol;
        p.lastEnv = env;
        p.x = m.x(); p.y = m.y(); p.z = m.z();
        p.sneakTicks = env.sneakingPose ? Math.max(1, p.sneakTicks + 1) : Math.min(-1, p.sneakTicks - 1);
        p.useTicks = env.usingItem ? Math.max(1, p.useTicks + 1) : Math.min(-1, p.useTicks - 1);
    }

    // ── 타이머 ──

    /** 클라 틱 끝(1.21.2+). 클라 틱 하나 = 50ms. 실제 시간보다 빨리 오면 타이머 핵. */
    static void onClientTick(Engine e, PlayerData p, long nowNs, Engine.Out out) {
        p.tickEndSeen = true;
        p.clientTicks++;
        p.ticksSinceMove++;
        timerStep(e, p, nowNs, out);
    }

    /** 틱 끝 패킷이 없는 버전(1.21~1.21.1) — 위치 패킷 하나를 클라 틱 하나로 본다(움직이는 동안만 매 틱 온다). */
    static void onMovePacketForTimer(Engine e, PlayerData p, long nowNs, Engine.Out out) {
        if (p.tickEndSeen) return;
        timerStep(e, p, nowNs, out);
    }

    private static void timerStep(Engine e, PlayerData p, long nowNs, Engine.Out out) {
        // 첫 기준점이 랙 중에 잡혔을 수 있다 — 처음엔 1초 빚을 깔아 둔다(뒤늦게 몰려 오는 패킷 흡수).
        if (p.timerLastNs == 0) { p.timerLastNs = nowNs; p.timerBalanceMs = -1000; return; }
        double elapsed = (nowNs - p.timerLastNs) / 1e6;
        p.timerLastNs = nowNs;
        p.timerBalanceMs += 50 - elapsed;
        // 랙으로 늦게 온 만큼은 "빚"으로 3초까지 인정 — 네트워크가 잠깐 막혔다 풀리며 몰려 오는 패킷은 이 안에서 갚아진다.
        if (p.timerBalanceMs < -3000) p.timerBalanceMs = -3000;
        if (p.timerBalanceMs > 160 && e.cfg.categoryOn(Check.Category.MOVEMENT)) {
            double over = p.timerBalanceMs;
            p.timerBalanceMs = 0;
            if (p.check("timer").failNow(e.nowMs(), Math.min(4, 1 + (over - 160) / 100))) {
                e.flag(p, "timer", String.format("+%.0fms", over), out);
            }
            if (e.mitigate() && p.legitKnown && p.allowSetback(e.nowMs())) {
                out.setback = new double[]{p.legitX, p.legitY, p.legitZ};
                p.hasPos = false;
            }
        }
    }
}
