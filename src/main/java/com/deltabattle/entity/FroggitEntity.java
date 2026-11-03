package com.deltabattle.entity;

import com.deltabattle.battle.Battle;
import com.deltabattle.battle.BattleManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Фроггит в мире: прыгает, а при контакте/клике/ударе запускает битву для всей пати игрока. */
public class FroggitEntity extends PathfinderMob {
    private boolean inBattle;
    private int hopCooldown = 20, encounterCooldown;

    public FroggitEntity(EntityType<? extends PathfinderMob> type, Level level) { super(type, level); }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 30).add(Attributes.MOVEMENT_SPEED, 0.2);
    }

    public boolean isInBattle() { return inBattle; }

    public void setInBattle(boolean v) { inBattle = v; setNoAi(v); setInvulnerable(v); if (v) setDeltaMovement(Vec3.ZERO); }

    public void finishBattle(int result) {
        setInBattle(false);
        encounterCooldown = 200;
        if (!(level() instanceof ServerLevel sl)) return;
        if (result == Battle.RES_KILLED) { sl.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.5, getZ(), 20, 0.3, 0.3, 0.3, 0.02); discard(); }
        else if (result == Battle.RES_SPARED) { sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + 0.6, getZ(), 12, 0.3, 0.3, 0.3, 0); discard(); }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide || inBattle) return;
        if (encounterCooldown > 0) encounterCooldown--;
        if (onGround() && --hopCooldown <= 0) {      // прыжки вместо ходьбы
            hopCooldown = 25 + random.nextInt(35);
            double a = random.nextDouble() * Math.PI * 2;
            setYRot((float) Math.toDegrees(a));
            setDeltaMovement(-Math.sin(a) * 0.22, 0.42, Math.cos(a) * 0.22);
            hasImpulse = true;
        }
        if (encounterCooldown == 0 && tickCount % 4 == 0) {
            Player p = level().getNearestPlayer(this, 1.8);
            if (p instanceof ServerPlayer sp && !sp.isCreative() && !sp.isSpectator()) BattleManager.start(sp, this);
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide && player instanceof ServerPlayer sp) BattleManager.start(sp, this);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean hurt(DamageSource src, float amount) {
        if (src.getEntity() instanceof ServerPlayer sp && !level().isClientSide) { BattleManager.start(sp, this); return false; }
        return inBattle ? false : super.hurt(src, amount);
    }

    @Override public boolean removeWhenFarAway(double d) { return !inBattle; }
}
