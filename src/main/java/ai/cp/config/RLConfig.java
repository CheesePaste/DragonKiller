package ai.cp.config;

public class RLConfig {
    public static final int TCP_PORT = Integer.parseInt(System.getProperty("rlport", "5670"));
    public static final int ACTION_REPEAT = 3;
    public static final int EPISODE_TIMEOUT = 6000;
    public static final long WORLD_SEED = 12345L;

    // Reward coefficients
    public static final double REWARD_DRAGON_DAMAGE = 2.0;
    public static final double REWARD_HIT = 1.0;
    public static final double REWARD_SWING_MISS = 0.0;
    public static final double REWARD_SURVIVE_TICK = 0.001;
    public static final double REWARD_PLAYER_DAMAGE = -1.0;
    public static final double REWARD_DRAGON_HURT = 3.0;
    public static final double REWARD_DEATH = -20.0;
    public static final double REWARD_DRAGON_DEATH = 0.0;

    // Approach & movement rewards
    public static final double REWARD_APPROACH = 0.1;      // per block closer to dragon
    public static final double REWARD_SPRINT = 0.01;       // per tick while sprinting
    public static final double REWARD_PROXIMITY = 0.05;    // per tick when within 10 blocks
    public static final double REWARD_DISTANCE = 0.03;     // base reward at dist=0, decays exponentially
    public static final double REWARD_DISTANCE_DECAY = 30.0;
    public static final double REWARD_FACE_DRAGON = 0.01;  // per tick when dragon is in view
    public static final double REWARD_VOID_PENALTY = -1.0; // per tick over void

    // Defense rewards
    public static final double REWARD_BREATH_PENALTY = -1.0;    // per tick near dragon breath

    // Combat technique rewards
    public static final double REWARD_FULL_CHARGE_HIT = 0.0;    // (removed — let AI discover naturally)
    public static final double REWARD_CRITICAL_HIT = 5.0;      // hit while airborne (jump crit)
}
