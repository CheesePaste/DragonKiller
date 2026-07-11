package ai.cp.config;

public class RLConfig {
    public static final boolean IS_PHASE_2 = "p2".equals(System.getProperty("rlphase", "p1"));
    public static final int TCP_PORT = Integer.parseInt(System.getProperty("rlport", "5670"));
    public static final int ACTION_REPEAT = 3;
    public static final int EPISODE_TIMEOUT = 36000;
    public static final long WORLD_SEED = 12345L;

    // Reward coefficients
    public static final double REWARD_DRAGON_DAMAGE = 2.0;
    public static final double REWARD_HIT = 5.0;              // per successful hit (was 1.0)
    public static final double REWARD_SITTING_MULTIPLIER = 2.0; // ×multiplier on damage+hit when dragon sitting
    public static final double REWARD_SWING_MISS = 0.0;
    public static final double REWARD_SURVIVE_TICK = 0.002;
    public static final double REWARD_PLAYER_DAMAGE = -1.0;
    public static final double REWARD_DRAGON_HURT = 3.0;
    public static final double REWARD_DEATH = -20.0;
    public static final double REWARD_DRAGON_DEATH = 0.0;

    // Approach & movement rewards
    public static final double REWARD_APPROACH = 0.2;      // per block closer to dragon
    public static final double REWARD_SPRINT = 0.01;       // per tick while sprinting
    public static final double REWARD_PROXIMITY = 0.05;    // per tick when within 10 blocks
    public static final double REWARD_DISTANCE = 0.08;     // base reward at dist=0, decays exponentially
    public static final double REWARD_DISTANCE_DECAY = 12.0; // faster falloff (was 30)
    public static final double REWARD_FACE_DRAGON = 0.01;  // per tick when dragon in view AND close
    public static final double REWARD_FACE_DRAGON_RANGE = 10.0; // only count facing reward within this range
    public static final double REWARD_VOID_PENALTY = -1.0; // per tick over void

    // Combat technique rewards
    public static final double REWARD_FULL_CHARGE_HIT = 0.0;    // (removed — let AI discover naturally)
    public static final double REWARD_CRITICAL_HIT = 5.0;      // hit while airborne (jump crit)
}
