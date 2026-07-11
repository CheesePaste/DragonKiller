package ai.cp.rl;

import ai.cp.config.RLConfig;

public class RewardCalculator {
    // Previous tick values for delta calculation
    private double prevDragonHealth = 200.0;
    private double prevPlayerHealth = 20.0;
    private int prevHitCount;
    private int prevSwingCount;

    public void reset(double dragonHealth, double playerHealth) {
        prevDragonHealth = dragonHealth;
        prevPlayerHealth = playerHealth;
        prevHitCount = 0;
        prevSwingCount = 0;
    }

    public double computeDense(double dragonHealth, double playerHealth, boolean endermanAngry,
                                int hitCount, int swingCount) {
        double reward = 0.0;

        // Dragon damage dealt
        double dragonDelta = prevDragonHealth - dragonHealth;
        if (dragonDelta > 0) {
            reward += dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
        }

        // Hit reward
        int hitDelta = hitCount - prevHitCount;
        reward += hitDelta * RLConfig.REWARD_HIT;

        // Swing miss penalty
        int swingDelta = swingCount - prevSwingCount;
        int missCount = swingDelta - hitDelta;
        reward += missCount * RLConfig.REWARD_SWING_MISS;

        // Survival
        reward += RLConfig.REWARD_SURVIVE_TICK;

        // Enderman angry penalty
        if (endermanAngry) {
            reward += RLConfig.REWARD_ENDERMAN_ANGRY;
        }

        // Player damage
        double healthLoss = prevPlayerHealth - playerHealth;
        if (healthLoss > 0) {
            reward += healthLoss * RLConfig.REWARD_PLAYER_DAMAGE;
        }

        // Update previous values
        prevDragonHealth = dragonHealth;
        prevPlayerHealth = playerHealth;
        prevHitCount = hitCount;
        prevSwingCount = swingCount;

        return reward;
    }

    // Sparse rewards for events
    public double onDragonHurt() {
        return RLConfig.REWARD_DRAGON_HURT;
    }

    public double onPlayerDeath() {
        return RLConfig.REWARD_DEATH;
    }

    public double onEndermanKill() {
        return RLConfig.REWARD_ENDERMAN_KILL;
    }

    public double onDragonDeath() {
        return RLConfig.REWARD_DRAGON_DEATH;
    }
}
