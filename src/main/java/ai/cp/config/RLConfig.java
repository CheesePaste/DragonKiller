package ai.cp.config;

public class RLConfig {
    public static final int TCP_PORT = 5670;
    public static final int ACTION_REPEAT = 5;
    public static final int STICKY_ATTACK = 10;
    public static final int EPISODE_TIMEOUT = 6000;
    public static final long WORLD_SEED = 12345L;
    public static final int HEIGHTMAP_RADIUS = 7;

    // Reward coefficients
    public static final double REWARD_DRAGON_DAMAGE = 2.0;
    public static final double REWARD_HIT = 1.0;
    public static final double REWARD_SWING_MISS = -0.5;
    public static final double REWARD_SURVIVE_TICK = 0.001;
    public static final double REWARD_ENDERMAN_ANGRY = -1.0;
    public static final double REWARD_PLAYER_DAMAGE = -5.0;
    public static final double REWARD_DRAGON_HURT = 3.0;
    public static final double REWARD_DEATH = -20.0;
    public static final double REWARD_ENDERMAN_KILL = -5.0;
    public static final double REWARD_DRAGON_DEATH = 200.0;
}
