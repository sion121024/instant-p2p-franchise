package kfc.udp.client.anticheat;

/** 클라가 보낸 이동 패킷 내용 그대로(ServerboundMovePlayerPacket). */
public record MoveInput(boolean hasPos, boolean hasRot, double x, double y, double z, float yaw, float pitch,
                        boolean onGround, boolean horizontalCollision) {}
