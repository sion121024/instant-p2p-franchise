package kfc.udp.client.anticheat;

/**
 * 엔티티의 보호된(protected) 이동 값에 접근 — 믹스인({@code AcEntityMixin})이 마크 Entity에 붙인다.
 * 반환 타입이 매핑마다 달라(Vec3/Vec3d) 끼임 배율은 Object로 돌려준다.
 */
public interface AcEntityAccess {
    /** 거미줄·덤불 끼임 배율 벡터(마크 Vec3) — 끼어 있지 않으면 0 벡터. */
    Object kfcudp$stuckMultiplier();

    /** 발밑 블록 속도 배율(영혼 모래·꿀 0.4) — LivingEntity의 이동 효율 보정 포함. */
    float kfcudp$blockSpeedFactor();

    /** 발밑 블록 점프 배율(꿀 0.5). */
    float kfcudp$blockJumpFactor();
}
