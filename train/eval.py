#!/usr/bin/env python
"""Evaluate a trained Phase 2 DragonKiller PPO model.

Usage:
    python train/eval.py --model models/p2/dragonkiller_p2_ppo_final.zip --episodes 10 --port 5670
"""

import argparse
import time
import numpy as np
from stable_baselines3 import PPO
from env.dragon_env import DragonEnv

def evaluate(model_path: str, num_episodes: int, port: int, deterministic: bool):
    print("=" * 60)
    print("DragonKiller Phase 2 Evaluation")
    print("=" * 60)
    print(f"Loading model from: {model_path}")
    model = PPO.load(model_path, device="auto")
    print(f"Model loaded. Policy: {model.policy}")
    print(f"Connecting to Minecraft environment on port: {port}")

    env = DragonEnv(host="127.0.0.1", port=port)
    episode_rewards = []
    episode_lengths = []
    dragon_kills = 0

    print(f"\nStarting evaluation of {num_episodes} episodes...")
    for episode in range(1, num_episodes + 1):
        obs, _ = env.reset()
        done = False
        total_reward = 0.0
        step_count = 0

        while not done:
            action, _ = model.predict(obs, deterministic=deterministic)
            # Pass the continuous action array directly to env.step
            obs, reward, done, _, _ = env.step(action)
            total_reward += reward
            step_count += 1

        episode_rewards.append(total_reward)
        episode_lengths.append(step_count)

        # Retrieve tracker stats to determine if the dragon was killed
        tracker = getattr(env, "_episode_tracker", {})
        kill_detected = False
        if tracker:
            # Check if dragon health is 0 (or lower)
            if tracker.get("dragon_health", 200.0) <= 0:
                kill_detected = True
        
        # Fallback to high reward if tracker is empty
        if not kill_detected and total_reward > 150:
            kill_detected = True

        result = "DRAGON KILLED" if kill_detected else "died/timeout"
        if kill_detected:
            dragon_kills += 1

        print(f"Episode {episode:3d}: reward={total_reward:+8.1f}, "
              f"steps={step_count:5d}, {result}")

    env.close()

    print()
    print("=" * 50)
    print("Evaluation Summary")
    print("=" * 50)
    print(f"Episodes:        {num_episodes}")
    print(f"Kill rate:       {dragon_kills}/{num_episodes} "
          f"({dragon_kills/num_episodes*100:.1f}%)")
    print(f"Avg reward:      {np.mean(episode_rewards):+.1f} ± {np.std(episode_rewards):.1f}")
    print(f"Avg steps:       {np.mean(episode_lengths):.0f} ± {np.std(episode_lengths):.0f}")
    print(f"Max reward:      {np.max(episode_rewards):+.1f}")
    print(f"Min reward:      {np.min(episode_rewards):+.1f}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Evaluate trained Phase 2 DragonKiller model")
    parser.add_argument("--model", type=str, default="models/p2/dragonkiller_p2_ppo_final.zip",
                        help="Path to model zip file")
    parser.add_argument("--episodes", type=int, default=10,
                        help="Number of episodes to evaluate")
    parser.add_argument("--port", type=int, default=5670,
                        help="Minecraft connection port")
    parser.add_argument("--no-deterministic", action="store_true",
                        help="Use stochastic actions instead of deterministic")
    args = parser.parse_args()

    evaluate(
        model_path=args.model,
        num_episodes=args.episodes,
        port=args.port,
        deterministic=not args.no_deterministic,
    )
