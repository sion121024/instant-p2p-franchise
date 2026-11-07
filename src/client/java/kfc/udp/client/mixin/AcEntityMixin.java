package kfc.udp.client.mixin;

import kfc.udp.client.anticheat.AcEntityAccess;
//? if >=26.1 {
/*import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
*///?} else {
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 안티치트 이동 예측이 쓰는 엔티티 내부 값 노출. 값을 바꾸지 않는다. */
@Mixin(Entity.class)
public abstract class AcEntityMixin implements AcEntityAccess {

    //? if >=26.1 {
    /*@Shadow protected Vec3 stuckSpeedMultiplier;
    @Shadow protected abstract float getBlockSpeedFactor();
    @Shadow protected abstract float getBlockJumpFactor();

    @Override public Object kfcudp$stuckMultiplier() { return this.stuckSpeedMultiplier; }
    @Override public float kfcudp$blockSpeedFactor() { return this.getBlockSpeedFactor(); }
    @Override public float kfcudp$blockJumpFactor() { return this.getBlockJumpFactor(); }
    *///?} else {
    @Shadow protected Vec3d movementMultiplier;
    @Shadow protected abstract float getVelocityMultiplier();
    @Shadow protected abstract float getJumpVelocityMultiplier();

    @Override public Object kfcudp$stuckMultiplier() { return this.movementMultiplier; }
    @Override public float kfcudp$blockSpeedFactor() { return this.getVelocityMultiplier(); }
    @Override public float kfcudp$blockJumpFactor() { return this.getJumpVelocityMultiplier(); }
    //?}
}
