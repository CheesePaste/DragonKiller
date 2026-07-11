import time
import gymnasium as gym
from gymnasium import spaces
import numpy as np
from .protocol import MCProtocol

# Normalization constants
POS_BOUND = 150.0       # x, z ±150
Y_MIN, Y_MAX = 0.0, 120.0
Y_MID = (Y_MIN + Y_MAX) / 2  # 60
VEL_BOUND = 10.0
PLAYER_HEALTH_MAX = 20.0
DRAGON_HEALTH_MAX = 200.0
DISTANCE_MAX = 64.0
TIME_MAX = 6000.0
DAMAGE_MAX = 200.0
COUNT_MAX = 1000.0


class DragonEnv(gym.Env):
    def __init__(self, host="127.0.0.1", port=5670,
                 connect_retries=300, connect_retry_delay=2.0):
        super().__init__()
        self.conn = None
        self.host = host
        self.port = port
        self.connect_retries = connect_retries
        self.connect_retry_delay = connect_retry_delay

        # 9 discrete actions (Phase 1: noop, forward, backward, turn L/R, look U/D, attack, sprint)
        self.action_space = spaces.Discrete(9)

        # All features normalized to ~[-1, 1], so Box(-1, 1)
        obs_dim = 583
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

        # ── Player (12) ──────────────────────────────────────────
        p = data["player"]
        px, py, pz = p["pos"]
        vec.append(np.clip(px / POS_BOUND, -1.0, 1.0))
        vec.append(np.clip((py - Y_MID) / Y_MID, -1.0, 1.0))
        vec.append(np.clip(pz / POS_BOUND, -1.0, 1.0))
        vec.append(p["rotation"][0] / 180.0)  # yaw [-180, 180] → [-1, 1]
        vec.append(p["rotation"][1] / 90.0)   # pitch [-90, 90] → [-1, 1]
        vec.append(p["health"] / PLAYER_HEALTH_MAX)
        vx, vy, vz = p["velocity"]
        vec.append(np.clip(vx / VEL_BOUND, -1.0, 1.0))
        vec.append(np.clip(vy / VEL_BOUND, -1.0, 1.0))
        vec.append(np.clip(vz / VEL_BOUND, -1.0, 1.0))
        vec.append(1.0 if p["on_ground"] else 0.0)
        vec.append(1.0 if p["sprinting"] else 0.0)
        vec.append(0.0)  # block_below placeholder

        # ── Dragon (13) ──────────────────────────────────────────
        d = data["dragon"]
        dx, dy, dz = d["pos"]
        vec.append(np.clip(dx / POS_BOUND, -1.0, 1.0))
        vec.append(np.clip((dy - Y_MID) / Y_MID, -1.0, 1.0))
        vec.append(np.clip(dz / POS_BOUND, -1.0, 1.0))
        dvx, dvy, dvz = d["velocity"]
        vec.append(np.clip(dvx / VEL_BOUND, -1.0, 1.0))
        vec.append(np.clip(dvy / VEL_BOUND, -1.0, 1.0))
        vec.append(np.clip(dvz / VEL_BOUND, -1.0, 1.0))
        bw, bh = d["bbox"]
        vec.append(np.clip(bw / 10.0, 0.0, 1.0))
        vec.append(np.clip(bh / 10.0, 0.0, 1.0))
        vec.append(d["health"] / DRAGON_HEALTH_MAX)
        vec.append(hash(d["phase"]) % 100 / 100.0)
        tx, ty, tz = d["target"]
        vec.append(np.clip(tx / POS_BOUND, -1.0, 1.0))
        vec.append(np.clip((ty - Y_MID) / Y_MID, -1.0, 1.0))
        vec.append(np.clip(tz / POS_BOUND, -1.0, 1.0))

        # ── Endermen (80 = 8 × 10) ───────────────────────────────
        endermen = data.get("endermen", [])
        for i in range(8):
            if i < len(endermen):
                e = endermen[i]
                ex, ey, ez = e["pos"]
                vec.append(np.clip(ex / POS_BOUND, -1.0, 1.0))
                vec.append(np.clip((ey - Y_MID) / Y_MID, -1.0, 1.0))
                vec.append(np.clip(ez / POS_BOUND, -1.0, 1.0))
                evx, evy, evz = e["velocity"]
                vec.append(np.clip(evx / VEL_BOUND, -1.0, 1.0))
                vec.append(np.clip(evy / VEL_BOUND, -1.0, 1.0))
                vec.append(np.clip(evz / VEL_BOUND, -1.0, 1.0))
                ebw, ebh = e["bbox"]
                vec.append(np.clip(ebw / 10.0, 0.0, 1.0))
                vec.append(np.clip(ebh / 10.0, 0.0, 1.0))
                vec.append(e["health"] / 40.0)
                vec.append(1.0 if e["angry"] else 0.0)
            else:
                vec.extend([0.0] * 10)

        # ── Inventory (14) ───────────────────────────────────────
        inv = data["inventory"]
        for i in range(9):
            vec.append(self._item_hash(inv["hotbar"][i]))  # already [-1, 1]
        vec.append(inv["selected_slot"] / 8.0)  # [0, 1]
        for i in range(4):
            item = inv["armor"][i] if i < len(inv["armor"]) else None
            vec.append(self._item_hash(item))

        # ── Terrain (450) ────────────────────────────────────────
        t = data["terrain"]
        for h in t["heightmap"]:
            vec.append(np.clip(h / 120.0, -1.0, 1.0))
        for s in t["surface"]:
            vec.append(s / 4.0)

        # ── Raytrace (8) ─────────────────────────────────────────
        r = data.get("raytrace", {})
        vec.append(self._item_hash(r.get("hit_entity")))
        vec.append(self._item_hash(r.get("hit_block")))
        vec.append(np.clip(float(r.get("distance", DISTANCE_MAX)) / DISTANCE_MAX, 0.0, 1.0))
        rpos = r.get("pos", [0, 0, 0])
        vec.append(np.clip(rpos[0] / POS_BOUND, -1.0, 1.0))
        vec.append(np.clip(rpos[2] / POS_BOUND, -1.0, 1.0))
        vec.append(0.0)  # block_pos placeholder
        vec.append(0.0)  # block_side placeholder
        vec.append(0.0)  # block_id placeholder

        # ── Stats (6) ────────────────────────────────────────────
        s = data.get("stats", {})
        vec.append(np.clip(s.get("time_alive", 0.0) / TIME_MAX, 0.0, 1.0))
        vec.append(np.clip(s.get("damage_dealt", 0.0) / DAMAGE_MAX, 0.0, 1.0))
        vec.append(np.clip(s.get("damage_taken", 0.0) / PLAYER_HEALTH_MAX, 0.0, 1.0))
        vec.append(np.clip(s.get("dragon_damage_dealt", 0.0) / DAMAGE_MAX, 0.0, 1.0))
        vec.append(np.clip(s.get("swing_count", 0.0) / COUNT_MAX, 0.0, 1.0))
        vec.append(np.clip(s.get("hit_count", 0.0) / COUNT_MAX, 0.0, 1.0))

        return np.array(vec, dtype=np.float32)

    @staticmethod
    def _item_hash(name) -> float:
        if name is None:
            return -1.0
        return float(hash(name) % 1000) / 1000.0

    def close(self):
        if self.conn:
            self.conn.close()
