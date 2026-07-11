#!/usr/bin/env python
"""Evaluate a trained DragonKiller PPO model.

Usage:
    python train/watch.py --model models/best/best_model.zip --episodes 10
"""

import argparse
import time

import numpy as np
from stable_baselines3 import PPO

from env.dragon_env import DragonEnv
from config import EvalConfig


def wait_for_connection(env: DragonEnv, config: EvalConfig):
    """Retry connection to MC until successful."""
    for attempt in range(config.connect_retries):
        try:
            msg = env.conn.reset()
            print(f"Connected on attempt {attempt + 1}")
            return msg
        except (ConnectionRefusedError, ConnectionError, TimeoutError):
            if attempt < config.connect_retries - 1:
                print(f"Waiting for MC connection ({attempt + 1}/{config.connect_retries})...")
                time.sleep(config.connect_retry_delay)
    raise ConnectionError("Failed to connect to MC after {config.connect_retries} attempts")


def evaluate(config: EvalConfig):
    """Run evaluation episodes with a trained model."""
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

        # Rough detection: dragon kill if reward is high (onDragonDeath=200)
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
    parser = argparse.ArgumentParser(description="Evaluate trained DragonKiller model")
    parser.add_argument("--model", type=str, default="models/dragonkiller_ppo_final.zip",
                        help="Path to model zip file")
    parser.add_argument("--episodes", type=int, default=10,
                        help="Number of evaluation episodes")
    parser.add_argument("--port", type=int, default=5670,
                        help="MC TCP port")
    parser.add_argument("--no-deterministic", action="store_true",
                        help="Use stochastic actions (default: deterministic)")
    args = parser.parse_args()

    config = EvalConfig(
        model_path=args.model,
        num_episodes=args.episodes,
        port=args.port,
        deterministic=not args.no_deterministic,
    )
    evaluate(config)
