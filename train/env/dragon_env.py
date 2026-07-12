import time
import gymnasium as gym
from gymnasium import spaces
import numpy as np
from .protocol import MCProtocol

# Normalization constants
PLAYER_HEALTH_MAX = 20.0
VEL_BOUND = 10.0
DISTANCE_MAX = 100.0
GROUND_DIST_MAX = 100.0
RAYTRACE_MAX = 64.0
DRAGON_PHASE_MAX = 10.0
DRAGON_DY_BOUND = 50.0

# Phase 2: dragon velocity bound (faster than player)
DRAGON_VEL_BOUND = 20.0


class DragonEnv(gym.Env):
    def __init__(self, host="127.0.0.1", port=5670,
                 connect_retries=300, connect_retry_delay=2.0):
        super().__init__()
        self.conn = None
        self.host = host
        self.port = port
        self.connect_retries = connect_retries
        self.connect_retry_delay = connect_retry_delay

        # 10 discrete actions (Phase 1: noop, forward, backward, turn L/R, look U/D, attack, sprint, jump)
        self.action_space = spaces.Discrete(10)

        # 31-dimensional observation
        obs_dim = 31
        self.observation_space = spaces.Box(
            low=-1.0, high=1.0, shape=(obs_dim,), dtype=np.float32
        )

    def reset(self, seed=None, options=None):
        super().reset(seed=seed)

        for attempt in range(self.connect_retries):
            try:
                if self.conn is None:
                    self.conn = MCProtocol(self.host, self.port)
                msg = self.conn.reset()
                obs = self._parse_obs(msg["data"])
                return obs, {}
            except (ConnectionRefusedError, TimeoutError, OSError) as e:
                self.conn = None
                if attempt < self.connect_retries - 1:
                    print(f"  Waiting for MC... ({attempt + 1}/{self.connect_retries})")
                    time.sleep(self.connect_retry_delay)
                else:
                    raise RuntimeError(
                        f"Cannot connect to MC at {self.host}:{self.port}"
                    ) from e

    def step(self, action):
        msg = self.conn.step(int(action))
        obs = self._parse_obs(msg["data"])
        reward = float(msg["reward"])
        done = bool(msg["done"])
        return obs, reward, done, False, {}

    def _parse_obs(self, data: dict) -> np.ndarray:
        vec = []

        # ── Player state (8) ────────────────────────────────────────
        p = data.get("player", {})
        vec.append(p.get("health", 20.0) / PLAYER_HEALTH_MAX)
        vec.append(1.0 if p.get("on_ground", True) else 0.0)
        vec.append(1.0 if p.get("sprinting", False) else 0.0)
        vel = p.get("velocity", [0, 0, 0])
        vec.append(np.clip(float(vel[0]) / VEL_BOUND, -1.0, 1.0))
        vec.append(np.clip(float(vel[1]) / VEL_BOUND, -1.0, 1.0))
        vec.append(np.clip(float(vel[2]) / VEL_BOUND, -1.0, 1.0))
        vec.append(np.clip(float(p.get("center_dx", 0.0)), -1.0, 1.0))
        vec.append(np.clip(float(p.get("center_dz", 0.0)), -1.0, 1.0))

        # ── Dragon relative (11: dir, dist, alive, dy, hit_dist, hit_yaw/pitch, head_yaw/pitch) ─
        d = data.get("dragon_relative", {})
        vec.append(np.clip(float(d.get("yaw_delta", 0.0)) / 180.0, -1.0, 1.0))
        vec.append(np.clip(float(d.get("pitch_delta", 0.0)) / 90.0, -1.0, 1.0))
        vec.append(np.clip(float(d.get("distance", DISTANCE_MAX)) / DISTANCE_MAX, 0.0, 1.0))
        vec.append(1.0 if d.get("in_view", False) else 0.0)
        vec.append(1.0 if d.get("alive", True) else 0.0)
        vec.append(np.clip(float(d.get("dy", 0.0)) / DRAGON_DY_BOUND, -1.0, 1.0))
        vec.append(np.clip(float(d.get("hit_dist", DISTANCE_MAX)) / DISTANCE_MAX, 0.0, 1.0))
        vec.append(np.clip(float(d.get("hit_yaw_delta", 0.0)) / 180.0, -1.0, 1.0))
        vec.append(np.clip(float(d.get("hit_pitch_delta", 0.0)) / 90.0, -1.0, 1.0))
        vec.append(np.clip(float(d.get("head_yaw_delta", 0.0)) / 180.0, -1.0, 1.0))
        vec.append(np.clip(float(d.get("head_pitch_delta", 0.0)) / 90.0, -1.0, 1.0))

        # ── Dragon phase (1) + velocity (3) ──────────────────────────
        vec.append(np.clip(float(d.get("phase", 0)) / DRAGON_PHASE_MAX, 0.0, 1.0))
        dvel = d.get("velocity", [0, 0, 0])
        vec.append(np.clip(float(dvel[0]) / DRAGON_VEL_BOUND, -1.0, 1.0))
        vec.append(np.clip(float(dvel[1]) / DRAGON_VEL_BOUND, -1.0, 1.0))
        vec.append(np.clip(float(dvel[2]) / DRAGON_VEL_BOUND, -1.0, 1.0))

        # ── Terrain (1: ground_distance) ─────────────────────────────
        t = data.get("terrain", {})
        vec.append(np.clip(float(t.get("ground_distance", 0.0)) / GROUND_DIST_MAX, 0.0, 1.0))

        # ── Raytrace (3) ────────────────────────────────────────────
        r = data.get("raytrace", {})
        vec.append(1.0 if r.get("dragon_in_crosshair", False) else 0.0)
        vec.append(np.clip(float(r.get("distance", RAYTRACE_MAX)) / RAYTRACE_MAX, 0.0, 1.0))
        vec.append(float(r.get("hit_type", 0)) / 2.0)

        # ── Stats (2: attack_cooldown, last_hit_type) ─────────────────
        s = data.get("stats", {})
        vec.append(np.clip(float(s.get("attack_cooldown", 1.0)), 0.0, 1.0))
        vec.append(float(s.get("last_hit_type", 0)) / 2.0)

        # ── Breath (2: distance, warning) ────────────────────────────
        b = data.get("breath", {})
        vec.append(np.clip(float(b.get("nearest_breath", 1.0)), 0.0, 1.0))
        vec.append(1.0 if b.get("breath_warning", False) else 0.0)

        return np.array(vec, dtype=np.float32)

    def close(self):
        if self.conn:
            self.conn.close()
