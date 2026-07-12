package ai.cp.rl;

import ai.cp.config.RLConfig;

public class RewardCalculator {
    private double prevDragonHealth = 200.0;
    private double prevCenterDistance;

    public void reset(double dragonHealth, double centerDistance) {
        prevDragonHealth = dragonHealth;
        prevCenterDistance = centerDistance;
    }

    /** Call after healing the dragon to keep health tracking in sync. */
    public void syncMaxHealth(double maxHealth) {
        prevDragonHealth = maxHealth;
    }

    public double computeDense(double dragonHealth, double playerHealth, double playerMaxHealth,
                                double centerDistance,
                                boolean isSprinting, boolean isOverVoid,
                                boolean isDragonSitting,
                                double facingCenterFactor,
                                boolean didAttack) {
        double reward = 0.0;

        // Pure damage reward: ONLY when bot actually attacked this cycle
        // This excludes environmental damage (void, collision) from generating reward
        if (didAttack) {
            double dragonDelta = prevDragonHealth - dragonHealth;
            if (dragonDelta > 0) {
                double dmgReward = dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
                if (isDragonSitting) dmgReward *= RLConfig.REWARD_SITTING_MULTIPLIER;
                reward += dmgReward;
            }
        }

        // Survival baseline
        reward += RLConfig.REWARD_SURVIVE_TICK;

        // Health reward: proportional to current HP
        reward += (playerHealth / playerMaxHealth) * RLConfig.REWARD_HEALTH;

        // Center approach reward
        double centerDelta = prevCenterDistance - centerDistance;
        reward += centerDelta * RLConfig.REWARD_APPROACH;

        // Sprint reward
        if (isSprinting) reward += RLConfig.REWARD_SPRINT;

        // Distance-to-center reward
        double distReward = RLConfig.REWARD_DISTANCE *
            Math.exp(-centerDistance / RLConfig.REWARD_DISTANCE_DECAY);
        reward += distReward;

        // Void penalty
        if (isOverVoid) reward += RLConfig.REWARD_VOID_PENALTY;

        // Center-facing reward
        reward += facingCenterFactor * RLConfig.REWARD_FACE_CENTER;

        // Update previous values
        prevDragonHealth = dragonHealth;
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
