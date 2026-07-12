package ai.cp.config;

public class RLConfig {
    public static final boolean IS_PHASE_2 = "p2".equals(System.getProperty("rlphase", "p1"));
    public static final int TCP_PORT = Integer.parseInt(System.getProperty("rlport", "5670"));
    public static final int ACTION_REPEAT = IS_PHASE_2 ? 1 : 3;
    public static final int EPISODE_TIMEOUT = IS_PHASE_2 ? 18000 : 6000;
    public static final long WORLD_SEED = 12345L;

    // Reward coefficients
    public static final double REWARD_DRAGON_DAMAGE = 3.0;    // pure damage reward: 3 * damage dealt
    public static final double REWARD_SITTING_MULTIPLIER = 2.0; // ×multiplier when dragon sitting
    public static final double REWARD_SURVIVE_TICK = 0.005;  // per observation baseline
    public static final double REWARD_HEALTH = 0.005;        // per tick at max HP
    public static final double REWARD_DEATH = -10.0;
    public static final double REWARD_DRAGON_DEATH = 1000.0;

    // Approach & movement rewards
    public static final double REWARD_APPROACH = 0.2;      // per block closer to center (0,64,0)
    public static final double REWARD_SPRINT = 0.01;       // per tick while sprinting
    public static final double REWARD_PROXIMITY = 0.1;     // per tick when within melee range of sitting dragon (was 0.2)
    public static final double REWARD_PROXIMITY_RANGE = 3.0; // max hit_dist for proximity reward
    public static final double REWARD_DISTANCE = 0.08;     // base reward at dist=0, decays exponentially
    public static final double REWARD_DISTANCE_DECAY = 6.0;
    public static final double REWARD_FACE_CENTER = 0.003;
    public static final double REWARD_VOID_PENALTY = -1.0; // per tick over void

    // Breath penalty: scaled by proximity to nearest breath cloud (0 at dist>=range, max at dist=0)
    public static final double REWARD_BREATH_PENALTY = -2.0;   // max per tick when inside breath
    public static final double BREATH_PENALTY_RANGE = 10.0;    // blocks — start penalizing at this range

    // Collision push penalty: penalize when the bot gets pushed away by dragon collision
    // Detected as: was within push range, now further away (got pushed out)
    public static final double REWARD_COLLISION_PENALTY = -10.0; // per push event — strong disincentive to face-hug
    public static final double COLLISION_PENALTY_RANGE = 2.0;   // blocks — within entity push range

    // Anti-trade window: ticks before/after damage that attack rewards are zeroed
    public static final int ANTI_TRADE_WINDOW_TICKS = 10;

}
