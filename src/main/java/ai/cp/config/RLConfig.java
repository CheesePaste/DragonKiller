package ai.cp.config;

public class RLConfig {
    public enum CombatMode {
        MELEE_ONLY,
        RANGED_ONLY,
        MIXED
    }

    public static final CombatMode COMBAT_MODE = CombatMode.valueOf(
        System.getProperty("rlcombatmode", "MIXED").toUpperCase()
    );

    public static final boolean IS_PHASE_2 = "p2".equals(System.getProperty("rlphase", "p1"));
    public static final int TCP_PORT = Integer.parseInt(System.getProperty("rlport", "5670"));
    public static final int ACTION_REPEAT = IS_PHASE_2 ? 1 : 3;
    public static final int EPISODE_TIMEOUT = IS_PHASE_2 ? 18000 : 2000; // P1: ~667 RL steps; P2: 6000 steps
    public static final long WORLD_SEED = 12345L;

    public static final float MAX_TURN_SPEED = 20.0f;

    // Reward coefficients
    public static final double REWARD_DRAGON_DAMAGE = 3.0;    // pure damage reward: 3 * damage dealt
    public static final double REWARD_SITTING_MULTIPLIER = 2.0; // ×multiplier when dragon sitting
    public static final double REWARD_TIME_PENALTY = -0.002;  // per tick penalty (prevents farming)
    public static final double REWARD_DEATH = -500.0;
    public static final double REWARD_DRAGON_DEATH = 1000.0;

    // Approach & movement rewards
    public static final double REWARD_APPROACH = 0.2;      // per block closer to center (0,64,0)
    public static final double REWARD_PROXIMITY_RANGE = 3.0; // max hit_dist for combat interactions
    public static final double REWARD_VOID_PENALTY = -1.0; // per tick over void

    // Breath penalty: scaled by proximity to nearest breath cloud (0 at dist>=range, max at dist=0)
    public static final double REWARD_BREATH_PENALTY = -0.5;   // max per tick when inside breath
    public static final double BREATH_PENALTY_RANGE = 10.0;    // blocks — start penalizing at this range

    public static final double REWARD_PLAYER_DAMAGE_PENALTY = -1.0; // penalty per point of player health lost (1 HP)

    // Ranged miss penalty
    public static final double REWARD_RANGED_MISS = -1.0;

    // Shield and Kiting rewards
    public static final double REWARD_SHIELD_BLOCK = 5.0;      // reward for blocking damage with shield

}
