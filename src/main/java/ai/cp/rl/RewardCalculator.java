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

    public double computeDense(double dragonHealth, double playerHealth,
                                double centerDistance,
                                boolean isOverVoid,
                                boolean isDragonSitting,
                                boolean didAttack) {
        double reward = 0.0;

        // Time penalty to prevent farming
        reward += RLConfig.REWARD_TIME_PENALTY;

        // Pure damage reward: ONLY when bot actually attacked this cycle
        if (didAttack) {
            double dragonDelta = prevDragonHealth - dragonHealth;
            if (dragonDelta > 0) {
                double dmgReward = dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
                if (isDragonSitting) dmgReward *= RLConfig.REWARD_SITTING_MULTIPLIER;
                reward += dmgReward;
            }
        }

        // Center approach reward (Potential-based, cannot be farmed)
        double centerDelta = prevCenterDistance - centerDistance;
        reward += centerDelta * RLConfig.REWARD_APPROACH;

        // Void penalty
        if (isOverVoid) reward += RLConfig.REWARD_VOID_PENALTY;

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
