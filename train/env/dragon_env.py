import gymnasium as gym
from gymnasium import spaces
import numpy as np
from .protocol import MCProtocol


class DragonEnv(gym.Env):
    def __init__(self, host="127.0.0.1", port=5670):
        super().__init__()
        self.conn = None
        self.host = host
        self.port = port

        # 21 discrete actions
        self.action_space = spaces.Discrete(21)

        # Observation: flattened structured data
        # player(12) + dragon(14) + endermen(8*10=80) + inventory(13) + terrain(225*2=450) + raytrace(8) + stats(6) = ~583
        obs_dim = 12 + 14 + 80 + 13 + 450 + 8 + 6
        self.observation_space = spaces.Box(
            low=-np.inf, high=np.inf, shape=(obs_dim,), dtype=np.float32
        )

    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        if self.conn is None:
            self.conn = MCProtocol(self.host, self.port)
        msg = self.conn.reset()
        obs = self._parse_obs(msg["data"])
        return obs, {}

    def step(self, action):
        msg = self.conn.step(int(action))
        obs = self._parse_obs(msg["data"])
        reward = float(msg["reward"])
        done = bool(msg["done"])
        return obs, reward, done, False, {}

    def _parse_obs(self, data: dict) -> np.ndarray:
        vec = []

        # Player (12)
        p = data["player"]
        vec.extend(p["pos"])
        vec.extend(p["rotation"])
        vec.append(p["health"])
        vec.extend(p["velocity"])
        vec.append(1.0 if p["on_ground"] else 0.0)
        vec.append(1.0 if p["sprinting"] else 0.0)
        vec.append(0.0)  # block_below encoding placeholder

        # Dragon (14)
        d = data["dragon"]
        vec.extend(d["pos"])
        vec.extend(d["velocity"])
        vec.extend(d["bbox"])
        vec.append(d["health"])
        vec.append(hash(d["phase"]) % 100 / 100.0)
        vec.extend(d["target"])
        # looking_at skipped (redundant with target)

        # Endermen: pad to 8 * 10
        endermen = data.get("endermen", [])
        for i in range(8):
            if i < len(endermen):
                e = endermen[i]
                vec.extend(e["pos"])
                vec.extend(e["velocity"])
                vec.extend(e["bbox"])
                vec.append(e["health"])
                vec.append(1.0 if e["angry"] else 0.0)
            else:
                vec.extend([0.0] * 10)

        # Inventory (13)
        inv = data["inventory"]
        for i in range(9):
            item = inv["hotbar"][i]
            vec.append(self._item_hash(item))
        vec.append(float(inv["selected_slot"]))
        for i in range(4):
            item = inv["armor"][i] if i < len(inv["armor"]) else None
            vec.append(self._item_hash(item))

        # Terrain (450)
        t = data["terrain"]
        vec.extend(t["heightmap"])
        vec.extend(t["surface"])

        # Raytrace (8)
        r = data.get("raytrace", {})
        vec.append(self._item_hash(r.get("hit_entity")))
        vec.append(self._item_hash(r.get("hit_block")))
        vec.append(float(r.get("distance", 64.0)))
        pos = r.get("pos", [0, 0, 0])
        vec.extend(pos[:2])  # x, z only
        vec.append(0.0)  # block_pos placeholder
        vec.append(0.0)  # block_side placeholder
        vec.append(0.0)  # block_id placeholder

        # Stats (6)
        s = data.get("stats", {})
        vec.append(s.get("time_alive", 0.0))
        vec.append(s.get("damage_dealt", 0.0))
        vec.append(s.get("damage_taken", 0.0))
        vec.append(s.get("dragon_damage_dealt", 0.0))
        vec.append(s.get("swing_count", 0.0))
        vec.append(s.get("hit_count", 0.0))

        return np.array(vec, dtype=np.float32)

    @staticmethod
    def _item_hash(name) -> float:
        if name is None:
            return -1.0
        return float(hash(name) % 1000) / 1000.0

    def close(self):
        if self.conn:
            self.conn.close()
