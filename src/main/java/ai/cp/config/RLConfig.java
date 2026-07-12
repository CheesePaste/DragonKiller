package ai.cp.config;

public class RLConfig {
    public static final boolean IS_PHASE_2 = "p2".equals(System.getProperty("rlphase", "p1"));
    public static final int TCP_PORT = Integer.parseInt(System.getProperty("rlport", "5670"));
    public static final int ACTION_REPEAT = 3;
    public static final int EPISODE_TIMEOUT = IS_PHASE_2 ? 24000 : 6000;
    public static final long WORLD_SEED = 12345L;

    // Reward coefficients
    public static final double REWARD_DRAGON_DAMAGE = 3.0;    // pure damage reward: 3 * damage dealt
    public static final double REWARD_SITTING_MULTIPLIER = 2.0; // ×multiplier when dragon sitting
    public static final double REWARD_SURVIVE_TICK = 0.015;
    public static final double REWARD_PLAYER_DAMAGE = -2.0;
    public static final double REWARD_DEATH = -10.0;
    public static final double REWARD_DRAGON_DEATH = 1000.0;

    // Approach & movement rewards
    public static final double REWARD_APPROACH = 0.2;      // per block closer to center (0,64,0)
    public static final double REWARD_SPRINT = 0.01;       // per tick while sprinting
    public static final double REWARD_PROXIMITY = 0.2;     // per 3-tick step when within melee range of sitting dragon
    public static final double REWARD_PROXIMITY_RANGE = 3.0; // max hit_dist for proximity reward (match melee range)
    public static final double REWARD_DISTANCE = 0.08;     // base reward at dist=0, decays exponentially
    public static final double REWARD_DISTANCE_DECAY = 6.0; // sharper falloff — less reward at 10+ blocks
    public static final double REWARD_FACE_CENTER = 0.003; // per tick when facing dragon perch (0,69,0)
    public static final double REWARD_VOID_PENALTY = -1.0; // per tick over void

}
