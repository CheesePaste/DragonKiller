package ai.cp.config;

public class RLConfig {
    public static final boolean IS_PHASE_2 = "p2".equals(System.getProperty("rlphase", "p1"));
    public static final int TCP_PORT = Integer.parseInt(System.getProperty("rlport", "5670"));
    public static final int ACTION_REPEAT = IS_PHASE_2 ? 1 : 3;
    public static final int EPISODE_TIMEOUT = IS_PHASE_2 ? 3600 : 6000;
    public static final long WORLD_SEED = 12345L;

    public static final float MAX_TURN_SPEED = 20.0f;

    // Reward coefficients
    public static final double REWARD_DRAGON_DAMAGE = 3.0;    // pure damage reward: 3 * damage dealt
    public static final double REWARD_SITTING_MULTIPLIER = 2.0; // xmultiplier when dragon sitting
    public static final double REWARD_DRAGON_DEATH = 1000.0;

    // Survival: per tick alive (replaces old time penalty)
    public static final double REWARD_SURVIVE_TICK = 0.01;

    // Death penalty
    public static final double REWARD_DEATH = -20.0;

    // Center proximity reward: per-tick exponential decay, higher when closer to center
    // Gentle gradient — gives tendency toward center without trapping AI in breath range
    public static final double REWARD_CENTER_PROXIMITY = 0.03;   // max reward/tick at dist=0
    public static final double CENTER_PROXIMITY_DECAY = 30.0;    // decay radius (blocks)
    public static final double REWARD_VOID_PENALTY = -1.0; // per tick over void


    // Collision push penalty
    public static final double REWARD_COLLISION_PENALTY = -5.0;
    public static final double COLLISION_PENALTY_RANGE = 2.0;

    // Sitting dragon: rewards active only when dragon is sitting on portal
    public static final double REWARD_SITTING_APPROACH = 0.3;    // per block closer to dragon
    public static final double REWARD_SITTING_RANGE_REWARD = 0.1; // per tick in attack range
    public static final double SITTING_ATTACK_RANGE = 6.0;       // blocks

    // Anti-trade window: ticks before/after damage that attack rewards are zeroed
    public static final int ANTI_TRADE_WINDOW_TICKS = 10;

}
