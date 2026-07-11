#!/usr/bin/env python
"""SB3 PPO baseline training script for DragonKiller.

Usage:
    python train/train.py                          # Train with default config
    python train/train.py --total-timesteps 500000 # Custom timesteps
"""

import argparse
import os
import time
from dataclasses import asdict

import numpy as np
from stable_baselines3 import PPO
from stable_baselines3.common.callbacks import (
    BaseCallback,
    CheckpointCallback,
    EvalCallback,
    CallbackList,
)
from stable_baselines3.common.vec_env import DummyVecEnv
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.utils import set_random_seed

from env.dragon_env import DragonEnv
from config import TrainConfig


class EpisodeInfoLogger(BaseCallback):
    """Log per-episode statistics to TensorBoard."""

    def __init__(self, verbose=0):
        super().__init__(verbose)
        self.episode_rewards = []
        self.episode_lengths = []

    def _on_step(self) -> bool:
        # Monitor wrapper stores episode info in .episode_returns/.episode_lengths
        for env_idx in range(self.training_env.num_envs):
            ep_info = self.training_env.get_attr("episode_returns", indices=[env_idx])[0]
            ep_len = self.training_env.get_attr("episode_lengths", indices=[env_idx])[0]
            if len(ep_info) > 0 and len(ep_info) > len(self.episode_rewards):
                # New episode completed
                reward = ep_info[-1]
                length = ep_len[-1]
                self.episode_rewards.append(reward)
                self.episode_lengths.append(length)
                self.logger.record("train/episode_reward", reward)
                self.logger.record("train/episode_length", length)

                # Running average (last 100 episodes)
                window = min(100, len(self.episode_rewards))
                self.logger.record(
                    "train/episode_reward_100",
                    np.mean(self.episode_rewards[-window:]),
                )
                self.logger.record(
                    "train/episode_length_100",
                    np.mean(self.episode_lengths[-window:]),
                )
        return True


class DragonKillRateCallback(BaseCallback):
    """Track dragon kill rate over the last N episodes."""

    def __init__(self, verbose=0):
        super().__init__(verbose)
        self.dragon_kills = 0
        self.total_episodes = 0

    def _on_step(self) -> bool:
        # We track this via episode info: dragon kill = episode_reward > 150
        # (onDragonDeath=200 + other rewards, vs dying gives -20)
        for env_idx in range(self.training_env.num_envs):
            ep_info = self.training_env.get_attr("episode_returns", indices=[env_idx])[0]
            current_count = len(ep_info)
            if current_count > self.total_episodes:
                # New episode finished
                episodes_gained = current_count - self.total_episodes
                for _ in range(episodes_gained):
                    reward = ep_info[-(episodes_gained)]
                    if reward > 150:  # Likely dragon kill
                        self.dragon_kills += 1
                self.total_episodes = current_count

                if self.total_episodes > 0:
                    kill_rate = self.dragon_kills / self.total_episodes * 100
                    self.logger.record("train/dragon_kill_rate", kill_rate)
        return True


def make_env(config: TrainConfig, retry_count: int = 0):
    """Create a single DragonEnv wrapped in Monitor."""
    env = DragonEnv(host=config.host, port=config.port)
    env = Monitor(env)
    return env


def train(config: TrainConfig):
    """Run PPO training."""
    print("=" * 60)
    print("DragonKiller PPO Training")
    print("=" * 60)
    print(f"Config: {asdict(config)}")
    print()

    # Create directories
    os.makedirs(config.save_dir, exist_ok=True)
    os.makedirs(config.log_dir, exist_ok=True)

    # Set random seed for reproducibility
    set_random_seed(config.seed)

    # Create vectorized environment (single env for baseline)
    env = DummyVecEnv([lambda: make_env(config)])

    # Create a separate eval environment
    eval_env = DummyVecEnv([lambda: make_env(config)])

    # Set up callbacks
    checkpoint_callback = CheckpointCallback(
        save_freq=max(config.save_freq_steps // config.n_steps, 1),
        save_path=config.save_dir,
        name_prefix="dragonkiller_ppo",
    )

    eval_callback = EvalCallback(
        eval_env,
        best_model_save_path=os.path.join(config.save_dir, "best"),
        log_path=config.log_dir,
        eval_freq=max(config.eval_freq_steps // config.n_steps, 1),
        n_eval_episodes=config.eval_episodes,
        deterministic=config.eval_deterministic,
        verbose=1,
    )

    callbacks = CallbackList([
        checkpoint_callback,
        # eval_callback, # Disabled: causes TCP port conflict with the training env on a single MC client
        EpisodeInfoLogger(),
        DragonKillRateCallback(),
    ])

    # Create PPO model
    model = PPO(
        "MlpPolicy",
        env,
        learning_rate=config.learning_rate,
        n_steps=config.n_steps,
        batch_size=config.batch_size,
        n_epochs=config.n_epochs,
        gamma=config.gamma,
        gae_lambda=config.gae_lambda,
        clip_range=config.clip_range,
        ent_coef=config.ent_coef,
        vf_coef=config.vf_coef,
        max_grad_norm=config.max_grad_norm,
        target_kl=config.target_kl,
        policy_kwargs={"net_arch": config.net_arch},
        tensorboard_log=config.log_dir,
        seed=config.seed,
        device=config.device,
        verbose=1,
    )

    print(f"Policy architecture: {model.policy}")
    print(f"Observation space:  {env.observation_space}")
    print(f"Action space:       {env.action_space}")
    print(f"Total timesteps:    {config.total_timesteps:,}")
    print()

    # Train
    start_time = time.time()
    model.learn(
        total_timesteps=config.total_timesteps,
        callback=callbacks,
        log_interval=config.log_interval,
        tb_log_name=f"ppo_{int(start_time)}",
        progress_bar=True,
    )

    elapsed = time.time() - start_time
    print(f"\nTraining completed in {elapsed:.1f}s ({elapsed/60:.1f} min)")

    # Save final model
    final_path = os.path.join(config.save_dir, "dragonkiller_ppo_final.zip")
    model.save(final_path)
    print(f"Final model saved: {final_path}")
    print(f"TensorBoard: tensorboard --logdir {config.log_dir}")

    env.close()
    eval_env.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="DragonKiller PPO Training")
    parser.add_argument("--total-timesteps", type=int, default=None,
                        help="Override total training timesteps")
    parser.add_argument("--port", type=int, default=None,
                        help="Override TCP port")
    parser.add_argument("--learning-rate", type=float, default=None,
                        help="Override PPO learning rate")
    args = parser.parse_args()

    config = TrainConfig()
    if args.total_timesteps:
        config.total_timesteps = args.total_timesteps
    if args.port:
        config.port = args.port
    if args.learning_rate:
        config.learning_rate = args.learning_rate

    train(config)
