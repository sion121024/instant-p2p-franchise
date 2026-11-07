package kfc.udp.client.anticheat;

/**
 * 이동 패킷 하나를 판정할 때 필요한 서버 쪽 상태 — 훅({@code AcHooks})이 마인크래프트 객체에서 읽어 채운다.
 * 엔진은 마인크래프트를 모르므로, 매핑·버전마다 다른 이름은 전부 훅 쪽에서 해결하고 여기에는 숫자/참거짓만 들어온다.
 * <p>
 * "prev"는 이번 패킷 직전(=지난 틱 끝)의 상태, "new"는 패킷이 말하는 새 위치에서 월드를 조회한 결과다.
 * 모르는 값은 기본값(바닐라 기본 속성)을 그대로 두면 된다 — 엔진은 기본값일 때 더 너그럽게 본다.
 */
public final class MoveEnv {
    // ── 게임 모드·능력 ──
    public boolean creative, spectator, mayFly, flying;
    public float flySpeed = 0.05f;

    // ── 예외 상태(이 동안은 정밀 예측 대신 넓은 범위 검사) ──
    public boolean passenger, sleeping, gliding, riptide, dead;
    /** 바닐라 서버가 텔레포트 확인(accept)을 기다리는 중 — 이 사이의 이동은 클라가 무시하므로 보지 않는다. */
    public boolean awaitingTeleport;
    /** 플레이어 주변 청크가 아직 안 불러졌다(클라가 중력 대신 -0.1로 떨어진다). */
    public boolean unloadedChunk;
    /** 피스톤·셜커 상자처럼 플레이어를 밀 수 있는 것이 근처에서 움직이는 중. */
    public boolean pushedByBlock;
    /** 이번 틱 플레이어를 밀 수 있는(겹친) 엔티티 수 — 군중 밀림 보정. */
    public int pushingEntities;
    /** 몸에 붙은 폭죽(겉날개 가속) 수. */
    public int fireworkBoosts;

    // ── 블록 환경 (prev 위치 기준) ──
    public boolean inWater, inLava, underWater, inPowderSnow, onClimbable, inBubbleColumn, inScaffolding;
    /** 거미줄·달콤한 열매 덤불·가루눈 같은 "끼임" 블록 — 이동량을 곱셈으로 줄인다. */
    public boolean stuck;
    /** 꿀·슬라임 벽 미끄러짐처럼 바닐라가 속도를 직접 덮어쓰는 블록이 몸에 닿아 있다. */
    public boolean nearSticky;
    /** 발밑 블록의 미끄러움(기본 0.6, 얼음 0.98, 파란 얼음 0.989, 슬라임 0.8) — 26.x는 FRICTION_MODIFIER 적용 후 값. */
    public float blockFriction = 0.6f;
    /** 발밑 블록 속도 배율(영혼 모래·꿀 0.4) — 이동 효율 속성 적용 후 값. */
    public float blockSpeedFactor = 1.0f;
    /** 발밑 블록 점프 배율(꿀 0.5). */
    public float blockJumpFactor = 1.0f;
    /** 발밑이 슬라임(튕김 1.0) 또는 침대(0.66) — 0이면 튕김 없음. */
    public float bounceFactor;

    // ── 속성 ──
    /** 현재 이동 속도 속성값(질주 수정자·신속/구속 효과 포함). */
    public double movementSpeed = 0.1;
    public boolean sprinting;
    public boolean sneakingPose;
    public boolean usingItem;
    public float itemUseSpeed = 0.2f;
    public double jumpStrength = 0.42;
    public int jumpBoostAmp = -1;
    public double gravity = 0.08;
    public boolean slowFalling;
    public int levitationAmp = -1;
    public boolean dolphinsGrace;
    public double stepHeight = 0.6;
    public double sneakingSpeed = 0.3;
    /** 26.x AIR_DRAG_MODIFIER(기본 1) — 이전 버전은 1. */
    public float airDragModifier = 1.0f;
    public double waterMovementEfficiency;
    public double safeFallDistance = 3.0;

    // ── 입력 키 (1.21.2+ ServerboundPlayerInputPacket) ──
    public boolean hasInput;
    public boolean forward, backward, left, right, jumpKey, shiftKey, sprintKey;

    // ── 새 위치에서 월드 조회 결과 ──
    /** 새 위치 바로 아래(0.03 이내)에 단단한 충돌 상자가 있다. */
    public boolean groundBelowNew;
    /** 새 위치 머리 위(0.03 이내)에 충돌 상자가 있다. */
    public boolean ceilingAboveNew;
    /** 새 위치에서 옆으로(0.03 이내) 막혀 있다. */
    public boolean horizontalBlockedNew;
    /** 이전 위치 바로 아래 충돌. */
    public boolean groundBelowPrev;
    /** 이전 위치에서 옆으로 막혀 있었다. */
    public boolean horizontalBlockedPrev;
    /** 새 위치가 블록과 겹친다(벽 통과). */
    public boolean insideBlockNew;
    /** 새 위치 발이 액체(물·용암) 안이다. */
    public boolean liquidAtNew;

    /** 이 플레이어가 서버에 들어온 뒤 지난 틱 수. */
    public int ticksExisted;
    public int hurtTime;
    public float foodLevel = 20;

    public MoveEnv copy() {
        MoveEnv e = new MoveEnv();
        e.creative = creative; e.spectator = spectator; e.mayFly = mayFly; e.flying = flying; e.flySpeed = flySpeed;
        e.passenger = passenger; e.sleeping = sleeping; e.gliding = gliding; e.riptide = riptide; e.dead = dead;
        e.awaitingTeleport = awaitingTeleport; e.unloadedChunk = unloadedChunk; e.pushedByBlock = pushedByBlock;
        e.pushingEntities = pushingEntities; e.fireworkBoosts = fireworkBoosts;
        e.inWater = inWater; e.inLava = inLava; e.underWater = underWater; e.inPowderSnow = inPowderSnow;
        e.onClimbable = onClimbable; e.inBubbleColumn = inBubbleColumn; e.inScaffolding = inScaffolding; e.stuck = stuck;
        e.blockFriction = blockFriction; e.blockSpeedFactor = blockSpeedFactor; e.blockJumpFactor = blockJumpFactor;
        e.bounceFactor = bounceFactor;
        e.movementSpeed = movementSpeed; e.sprinting = sprinting; e.sneakingPose = sneakingPose; e.usingItem = usingItem;
        e.itemUseSpeed = itemUseSpeed; e.jumpStrength = jumpStrength; e.jumpBoostAmp = jumpBoostAmp; e.gravity = gravity;
        e.slowFalling = slowFalling; e.levitationAmp = levitationAmp; e.dolphinsGrace = dolphinsGrace;
        e.stepHeight = stepHeight; e.sneakingSpeed = sneakingSpeed; e.airDragModifier = airDragModifier;
        e.waterMovementEfficiency = waterMovementEfficiency; e.safeFallDistance = safeFallDistance;
        e.hasInput = hasInput; e.forward = forward; e.backward = backward; e.left = left; e.right = right;
        e.jumpKey = jumpKey; e.shiftKey = shiftKey; e.sprintKey = sprintKey;
        e.groundBelowNew = groundBelowNew; e.ceilingAboveNew = ceilingAboveNew; e.horizontalBlockedNew = horizontalBlockedNew;
        e.groundBelowPrev = groundBelowPrev; e.horizontalBlockedPrev = horizontalBlockedPrev; e.insideBlockNew = insideBlockNew;
        e.liquidAtNew = liquidAtNew; e.ticksExisted = ticksExisted; e.hurtTime = hurtTime; e.foodLevel = foodLevel;
        return e;
    }

    /** 정밀 예측(지상/공중 걷기 물리)이 맞는 평범한 상태인지. */
    boolean plainPhysics() {
        return !inWater && !inLava && !inPowderSnow && !onClimbable && !inBubbleColumn && !inScaffolding && !stuck && !nearSticky
                && !gliding && !riptide && !passenger && !flying && levitationAmp < 0 && !pushedByBlock;
    }

    /** 이동 검사를 통째로 쉬어야 하는 상태. */
    boolean exemptAll() {
        return spectator || sleeping || dead || awaitingTeleport || unloadedChunk || passenger
                || (flying && mayFly) || ticksExisted < 40;
    }
}
