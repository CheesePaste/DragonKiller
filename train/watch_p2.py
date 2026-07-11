#!/usr/bin/env python
"""Evaluate a trained Phase 2 DragonKiller PPO model.

Usage:
    python train/watch_p2.py --model models/p2/best_model.zip --episodes 10
"""

import argparse
import time

import numpy as np
from stable_baselines3 import PPO

from env.dragon_env import DragonEnv
from config_p2 import EvalConfigP2


def evaluate(config: EvalConfigP2):
    print(f"Loading model from: {config.model_path}")
    model = PPO.load(config.model_path, device="auto")
    print(f"Model loaded. Policy: {model.policy}")

    env = DragonEnv(host=config.host, port=config.port)
    episode_rewards = []
    episode_lengths = []
    dragon_kills = 0

    for episode in range(1, config.num_episodes + 1):
        obs, _ = env.reset()
        done = False
        total_reward = 0.0
        step_count = 0

        while not done:
            action, _ = model.predict(obs, deterministic=config.deterministic)
            obs, reward, done, _, _ = env.step(int(action))
            total_reward += reward
            step_count += 1

        episode_rewards.append(total_reward)
        episode_lengths.append(step_count)

        if total_reward > 150:
            dragon_kills += 1
            result = "DRAGON KILLED"
        else:
            result = "died/timeout"

        print(f"Episode {episode:3d}: reward={total_reward:+8.1f}, "
              f"steps={step_count:5d}, {result}")

    env.close()

    print()
    print("=" * 50)
    print("Evaluation Summary")
    print("=" * 50)
    print(f"Episodes:        {config.num_episodes}")
    print(f"Kill rate:       {dragon_kills}/{config.num_episodes} "
          f"({dragon_kills/config.num_episodes*100:.1f}%)")
    print(f"Avg reward:      {np.mean(episode_rewards):+.1f} ± {np.std(episode_rewards):.1f}")
    print(f"Avg steps:       {np.mean(episode_lengths):.0f} ± {np.std(episode_lengths):.0f}")
    print(f"Max reward:      {np.max(episode_rewards):+.1f}")
    print(f"Min reward:      {np.min(episode_rewards):+.1f}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Evaluate trained Phase 2 DragonKiller model")
    parser.add_argument("--model", type=str, default="models/p2/dragonkiller_p2_ppo_final.zip",
                        help="Path to model zip file")
    parser.add_argument("--episodes", type=int, default=10)
    parser.add_argument("--port", type=int, default=5670)
    parser.add_argument("--no-deterministic", action="store_true")
    args = parser.parse_args()

    config = EvalConfigP2(
        model_path=args.model,
        num_episodes=args.episodes,
        port=args.port,
        deterministic=not args.no_deterministic,
    )
    evaluate(config)
