package ai.cp.rl;

import ai.cp.config.RLConfig;

public class RewardCalculator {
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

    /** Call after healing the dragon to keep health tracking in sync. */
    public void syncMaxHealth(double maxHealth) {
        prevDragonHealth = maxHealth;
    }

    public double computeDense(double dragonHealth, double playerHealth,
                                int hitCount, int swingCount, double dragonDistance,
                                boolean isSprinting, boolean facingDragon, boolean isOverVoid,
                                boolean criticalHit, boolean breathNearby) {
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

        // Critical hit bonus — reward jump attacks
        if (criticalHit) {
            reward += RLConfig.REWARD_CRITICAL_HIT;
        }

        // Survival baseline
        reward += RLConfig.REWARD_SURVIVE_TICK;

        // Approach reward
        double distanceDelta = prevDragonDistance - dragonDistance;
        reward += distanceDelta * RLConfig.REWARD_APPROACH;

        // Sprint reward
        if (isSprinting) {
            reward += 0.005;
        }

        // Distance-based reward: smooth gradient encouraging closeness
        double distReward = RLConfig.REWARD_DISTANCE *
            Math.exp(-dragonDistance / RLConfig.REWARD_DISTANCE_DECAY);
        reward += distReward;

        // Proximity bonus
        if (dragonDistance < 10.0) {
            reward += RLConfig.REWARD_PROXIMITY;
        }

        // Facing dragon reward
        if (facingDragon) {
            reward += RLConfig.REWARD_FACE_DRAGON;
        }

        // Void penalty
        if (isOverVoid) {
            reward += RLConfig.REWARD_VOID_PENALTY;
        }

        // Dragon breath penalty
        if (breathNearby) {
            reward += RLConfig.REWARD_BREATH_PENALTY;
        }

        // Player damage penalty
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

    public double onDragonHurt() {
        return RLConfig.REWARD_DRAGON_HURT;
    }

    public double onPlayerDeath() {
        return RLConfig.REWARD_DEATH;
    }

    public double onDragonDeath() {
        return RLConfig.REWARD_DRAGON_DEATH;
    }
}
