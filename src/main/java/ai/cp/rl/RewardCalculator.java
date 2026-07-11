package ai.cp.rl;

import ai.cp.config.RLConfig;

public class RewardCalculator {
    // Previous tick values for delta calculation
    private double prevDragonHealth = 200.0;
    private double prevPlayerHealth = 20.0;
    private int prevHitCount;
    private int prevSwingCount;
    private double prevDragonDistance;

    public void reset(double dragonHealth, double playerHealth, double dragonDistance) {
        prevDragonHealth = dragonHealth;
        prevPlayerHealth = playerHealth;
        prevHitCount = 0;
        prevSwingCount = 0;
        prevDragonDistance = dragonDistance;
    }

    public double computeDense(double dragonHealth, double playerHealth, boolean endermanAngry,
                                int hitCount, int swingCount, double dragonDistance,
                                boolean isSprinting) {
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

        // Approach reward: closer to dragon = positive, farther = negative
        double distanceDelta = prevDragonDistance - dragonDistance;
        reward += distanceDelta * RLConfig.REWARD_APPROACH;

        // Sprint reward: encourage using fast movement
        if (isSprinting) {
            reward += RLConfig.REWARD_SPRINT;
        }

        // Proximity bonus: close to dragon = good
        if (dragonDistance < 10.0) {
            reward += RLConfig.REWARD_PROXIMITY;
        }

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
        prevDragonDistance = dragonDistance;

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
