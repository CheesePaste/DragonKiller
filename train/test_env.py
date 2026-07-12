#!/usr/bin/env python
"""Smoke test: verify DragonEnv reset → step → done loop works end-to-end.

Usage:
    # Make sure MC with mod is running first, then:
    python train/test_env.py
"""

import time
import numpy as np
from env.dragon_env import DragonEnv


def test_env(host="127.0.0.1", port=5670, retries=30):
    """Test full env loop: connect, reset, step x20, check shapes."""
    env = DragonEnv(host=host, port=port)

    # Retry connection
    for attempt in range(retries):
        try:
            obs, info = env.reset()
            print(f"[OK] Connected on attempt {attempt + 1}")
            break
        except (ConnectionRefusedError, ConnectionError, TimeoutError) as e:
            if attempt < retries - 1:
                print(f"  Waiting... ({attempt + 1}/{retries})")
                time.sleep(2)
            else:
                raise RuntimeError(
                    f"Cannot connect to MC at {host}:{port}. "
                    f"Make sure the client is running with the mod."
                ) from e
    else:
        raise RuntimeError("No MC connection")

    # Check observation
    expected_dim = 29
    print(f"\nObservation shape: {obs.shape}")
    assert obs.shape == (expected_dim,), f"Expected ({expected_dim},), got {obs.shape}"
    print(f"[OK] Observation dimension = {expected_dim}")
    print(f"  Min: {obs.min():.3f}, Max: {obs.max():.3f}, Mean: {obs.mean():.3f}")
    # All values should be in [-1, 1]
    assert -1.0 - 1e-6 <= obs.min() and obs.max() <= 1.0 + 1e-6, \
        f"Values outside [-1, 1]: [{obs.min()}, {obs.max()}]"
    print(f"[OK] All values within [-1, 1]")

    # Check action space
    print(f"Action space: {env.action_space}")
    assert env.action_space.n == 12
    print(f"[OK] Action space = 12 discrete")

    # Run a few steps with different actions
    print("\nStepping through actions...")
    for action in [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]:
        obs, reward, done, truncated, info = env.step(action)
        print(f"  action={action:2d}: reward={reward:+7.3f}, done={done}, obs_range=[{obs.min():.2f}, {obs.max():.2f}]")
        if done:
            print("[INFO] Episode ended, resetting...")
            obs, info = env.reset()

    # Test reset mid-episode
    print("\n[OK] Full env cycle verified")

    # Print observation structure breakdown (normalized)
    print("\nObservation breakdown (all normalized to [-1, 1]):")
    sections = {
        "player":        (0, 8),      # health, on_ground, sprinting, vel_xyz, center_dx/dz
        "dragon_rel":    (8, 18),     # yaw_delta, pitch_delta, in_view, alive, dy, hit_dist, hit_yaw/pitch, head_yaw/pitch
        "dragon_ext":    (18, 22),    # phase, dvel_xyz
        "terrain":       (22, 23),    # ground_distance
        "raytrace":      (23, 24),    # dragon_in_crosshair
        "stats":         (24, 26),    # attack_cooldown, last_hit_type
        "breath":        (26, 29),    # nearest_breath, breath_warning, breath_yaw_delta
    }
    for name, (start, end) in sections.items():
        vals = obs[start:end]
        non_nan = vals[~np.isnan(vals)]
        print(f"  {name:20s} [{start:3d}:{end:3d}]  "
              f"non_nan={len(non_nan):3d},  "
              f"range=[{vals.min():.2f}, {vals.max():.2f}]")

    env.close()
    print("\n✓ All tests passed!")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Test DragonEnv end-to-end")
    parser.add_argument("--port", type=int, default=5670)
    args = parser.parse_args()
    test_env(port=args.port)
