package ai.cp.rl;

import ai.cp.config.RLConfig;

public class RewardCalculator {
    private double prevDragonHealth = 200.0;

    public void reset(double dragonHealth) {
        prevDragonHealth = dragonHealth;
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

        // Pure damage reward: ONLY when bot actually attacked this cycle
        if (didAttack) {
            double dragonDelta = prevDragonHealth - dragonHealth;
            if (dragonDelta > 0) {
                double dmgReward = dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
                if (isDragonSitting) dmgReward *= RLConfig.REWARD_SITTING_MULTIPLIER;
                reward += dmgReward;
            }
        }

        // Center proximity reward: per-tick exponential decay
        // Gentle pull toward center without trapping AI in breath range
        reward += RLConfig.REWARD_CENTER_PROXIMITY * Math.exp(-centerDistance / RLConfig.CENTER_PROXIMITY_DECAY);

        // Void penalty
        if (isOverVoid) reward += RLConfig.REWARD_VOID_PENALTY;

        // Update previous values
        prevDragonHealth = dragonHealth;

        return reward;
    }

    public double onPlayerDeath() {
        return RLConfig.REWARD_DEATH;
    }

    public double onDragonDeath() {
        return RLConfig.REWARD_DRAGON_DEATH;
    }
}
