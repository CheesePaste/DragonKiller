package ai.cp.rl;

import ai.cp.config.RLConfig;

public class RewardCalculator {
    private double prevDragonHealth = 200.0;
    private double prevPlayerHealth = 20.0;
    private int prevHitCount;
    private int prevSwingCount;
    private double prevCenterDistance;

    public void reset(double dragonHealth, double playerHealth, double centerDistance) {
        prevDragonHealth = dragonHealth;
        prevPlayerHealth = playerHealth;
        prevHitCount = 0;
        prevSwingCount = 0;
        prevCenterDistance = centerDistance;
    }

    /** Call after healing the dragon to keep health tracking in sync. */
    public void syncMaxHealth(double maxHealth) {
        prevDragonHealth = maxHealth;
    }

    public double computeDense(double dragonHealth, double playerHealth,
                                int hitCount, int swingCount, double dragonDistance,
                                double centerDistance,
                                boolean isSprinting, boolean facingDragon, boolean isOverVoid,
                                boolean criticalHit, boolean isDragonSitting,
                                boolean facingCenter) {
        double reward = 0.0;

        // Dragon damage dealt
        double dragonDelta = prevDragonHealth - dragonHealth;
        if (dragonDelta > 0) {
            double dmgReward = dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
            if (isDragonSitting) dmgReward *= RLConfig.REWARD_SITTING_MULTIPLIER;
            reward += dmgReward;
        }

        // Hit reward
        int hitDelta = hitCount - prevHitCount;
        double hitReward = hitDelta * RLConfig.REWARD_HIT;
        if (isDragonSitting) hitReward *= RLConfig.REWARD_SITTING_MULTIPLIER;
        reward += hitReward;

        // Swing miss penalty (currently 0)
        int swingDelta = swingCount - prevSwingCount;
        int missCount = swingDelta - hitDelta;
        reward += missCount * RLConfig.REWARD_SWING_MISS;

        // Critical hit bonus — reward jump attacks
        if (criticalHit) reward += RLConfig.REWARD_CRITICAL_HIT;

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

        // Proximity bonus: reward approaching the dragon when it's sitting/vulnerable
        if (isDragonSitting && dragonDistance < 10.0) {
            reward += RLConfig.REWARD_PROXIMITY;
        }

        // Facing dragon reward: only when dragon is sitting/vulnerable
        if (isDragonSitting && facingDragon && dragonDistance < RLConfig.REWARD_FACE_DRAGON_RANGE) {
            reward += RLConfig.REWARD_FACE_DRAGON;
        }

        // Void penalty
        if (isOverVoid) reward += RLConfig.REWARD_VOID_PENALTY;

        // Player damage penalty
        double healthLoss = prevPlayerHealth - playerHealth;
        if (healthLoss > 0) {
            reward += healthLoss * RLConfig.REWARD_PLAYER_DAMAGE;
        }

        // Center-facing reward: gentle nudge to look toward dragon perch
        if (facingCenter) {
            reward += RLConfig.REWARD_FACE_CENTER;
        }

        // Update previous values
        prevDragonHealth = dragonHealth;
        prevPlayerHealth = playerHealth;
        prevHitCount = hitCount;
        prevSwingCount = swingCount;
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
