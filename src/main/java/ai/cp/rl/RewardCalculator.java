package ai.cp.rl;

import ai.cp.config.RLConfig;

public class RewardCalculator {
    private double prevDragonHealth = 200.0;
    private double prevPlayerHealth = 20.0;
    private double prevCenterDistance;

    public void reset(double dragonHealth, double playerHealth, double centerDistance) {
        prevDragonHealth = dragonHealth;
        prevPlayerHealth = playerHealth;
        prevCenterDistance = centerDistance;
    }

    /** Call after healing the dragon to keep health tracking in sync. */
    public void syncMaxHealth(double maxHealth) {
        prevDragonHealth = maxHealth;
    }

    public double computeDense(double dragonHealth, double playerHealth,
                                double centerDistance,
                                boolean isSprinting, boolean isOverVoid,
                                boolean isDragonSitting,
                                double facingCenterFactor) {
        double reward = 0.0;

        // Pure damage reward: REWARD_DRAGON_DAMAGE × damage
        // Multiplied by sitting (2×) and headshot (2×) — multipliers stack
        double dragonDelta = prevDragonHealth - dragonHealth;
        if (dragonDelta > 0) {
            double dmgReward = dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
            if (isDragonSitting) dmgReward *= RLConfig.REWARD_SITTING_MULTIPLIER;
            reward += dmgReward;
        }

        // Survival baseline
        reward += RLConfig.REWARD_SURVIVE_TICK;

        // Center approach reward: immediate feedback for moving toward (0, 64, 0)
        double centerDelta = prevCenterDistance - centerDistance;
        reward += centerDelta * RLConfig.REWARD_APPROACH;

        // Sprint reward
        if (isSprinting) reward += RLConfig.REWARD_SPRINT;

        // Distance-to-center reward: encourages holding strategic position near (0, 64, 0)
        double distReward = RLConfig.REWARD_DISTANCE *
            Math.exp(-centerDistance / RLConfig.REWARD_DISTANCE_DECAY);
        reward += distReward;

        // Void penalty
        if (isOverVoid) reward += RLConfig.REWARD_VOID_PENALTY;

        // Player damage penalty
        double healthLoss = prevPlayerHealth - playerHealth;
        if (healthLoss > 0) {
            reward += healthLoss * RLConfig.REWARD_PLAYER_DAMAGE;
        }

        // Center-facing reward: cos(angle) × base — smooth gradient, not binary
        reward += facingCenterFactor * RLConfig.REWARD_FACE_CENTER;

        // Update previous values
        prevDragonHealth = dragonHealth;
        prevPlayerHealth = playerHealth;
        prevCenterDistance = centerDistance;

        return reward;
    }

    public double onPlayerDeath() {
        return RLConfig.REWARD_DEATH;
    }

    public double onDragonDeath() {
        return RLConfig.REWARD_DRAGON_DEATH;
    }
}
