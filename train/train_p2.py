#!/usr/bin/env python
"""Phase 2 PPO training — mobile dragon AI.

Usage:
    python train/train_p2.py                                      # Phase 2 default (20M steps)
    python train/train_p2.py --total-timesteps 500000             # Custom timesteps
    python train/train_p2.py --load-model models/best/model.zip   # Continue from Phase 1
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
    CallbackList,
)
from stable_baselines3.common.vec_env import DummyVecEnv
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.utils import set_random_seed

from env.dragon_env import DragonEnv
from config_p2 import TrainConfigP2


class EpisodeInfoLogger(BaseCallback):
    def __init__(self, verbose=0):
        super().__init__(verbose)
        self.episode_rewards = []
        self.episode_lengths = []
        self._last_ep_keys = {}  # env_idx -> (last_reward, last_length) tuple

    def _on_step(self) -> bool:
        for env_idx in range(self.training_env.num_envs):
            ep_returns = self.training_env.get_attr("episode_returns", indices=[env_idx])[0]
            ep_len = self.training_env.get_attr("episode_lengths", indices=[env_idx])[0]

            if len(ep_returns) > 0:
                ep_key = (ep_returns[-1], ep_len[-1])
                if self._last_ep_keys.get(env_idx) != ep_key:
                    self._last_ep_keys[env_idx] = ep_key
                    reward = ep_returns[-1]
                    length = ep_len[-1]
                    self.episode_rewards.append(reward)
                    self.episode_lengths.append(length)
                    self.logger.record("train/episode_reward", reward)
                    self.logger.record("train/episode_length", length)
                    window = min(100, len(self.episode_rewards))
                    self.logger.record(
                        "train/episode_reward_100",
                        np.mean(self.episode_rewards[-window:]),
                    )
                    self.logger.record(
                        "train/episode_length_100",
                        np.mean(self.episode_lengths[-window:]),
                    )

                    # Log per-episode tracker stats from Java
                    tracker = self.training_env.get_attr("_episode_tracker", indices=[env_idx])[0]
                    if tracker:
                        for key, value in tracker.items():
                            self.logger.record(f"train/{key}", value)
        return True


class DragonKillRateCallback(BaseCallback):
    def __init__(self, verbose=0):
        super().__init__(verbose)
        self._last_ep_keys = {}
        self._cumulative_kills = 0
        self._cumulative_total = 0

    def _on_step(self) -> bool:
        for env_idx in range(self.training_env.num_envs):
            ep_returns = self.training_env.get_attr("episode_returns", indices=[env_idx])[0]
            ep_len = self.training_env.get_attr("episode_lengths", indices=[env_idx])[0]
            if len(ep_returns) > 0:
                ep_key = (ep_returns[-1], ep_len[-1])
                if self._last_ep_keys.get(env_idx) != ep_key:
                    self._last_ep_keys[env_idx] = ep_key
                    reward = ep_returns[-1]
                    self._cumulative_total += 1
                    if reward > 150:
                        self._cumulative_kills += 1
                    if self._cumulative_total > 0:
                        self.logger.record("train/dragon_kill_rate",
                            self._cumulative_kills / self._cumulative_total * 100)
        return True


def make_env(host, port, seed):
    def _init():
        env = DragonEnv(host=host, port=port)
        env = Monitor(env)
        return env
    return _init


def train(config: TrainConfigP2):
    print("=" * 60)
    print("DragonKiller Phase 2 PPO Training")
    print("=" * 60)
    print(f"Config: {asdict(config)}")
    print()

    os.makedirs(config.save_dir, exist_ok=True)
    os.makedirs(config.log_dir, exist_ok=True)
    set_random_seed(config.seed)

    from stable_baselines3.common.vec_env import SubprocVecEnv

    ports = [config.port + i for i in range(config.n_envs)]
    env_fns = [make_env(config.host, p, config.seed) for p in ports]

    if config.n_envs == 1:
        env = DummyVecEnv(env_fns)
    else:
        env = SubprocVecEnv(env_fns)

    eval_env = DummyVecEnv([make_env(config.host, config.port, config.seed)])

    checkpoint_callback = CheckpointCallback(
        save_freq=max(config.save_freq_steps // config.n_envs, 1),
        save_path=config.save_dir,
        name_prefix="dragonkiller_p2_ppo",
    )

    callbacks = CallbackList([
        checkpoint_callback,
        EpisodeInfoLogger(),
        DragonKillRateCallback(),
    ])

    if config.load_model:
        print(f"Loading pre-trained model from: {config.load_model}")
        model = PPO.load(config.load_model, env=env, device=config.device)
    else:
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

    start_time = time.time()
    try:
        model.learn(
            total_timesteps=config.total_timesteps,
            callback=callbacks,
            log_interval=config.log_interval,
            tb_log_name=f"p2_ppo_{int(start_time)}",
            progress_bar=True,
        )
        print(f"\nPhase 2 training completed.")
    except KeyboardInterrupt:
        print("\nTraining interrupted by user (Ctrl+C). Saving current model weights...")
    finally:
        elapsed = time.time() - start_time
        print(f"Elapsed time: {elapsed:.1f}s ({elapsed/60:.1f} min)")

        final_path = os.path.join(config.save_dir, "dragonkiller_p2_ppo_final.zip")
        model.save(final_path)
        print(f"Model saved successfully: {final_path}")
        print(f"TensorBoard: tensorboard --logdir {config.log_dir}")

        env.close()
        eval_env.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="DragonKiller Phase 2 PPO Training")
    parser.add_argument("--total-timesteps", type=int, default=None)
    parser.add_argument("--port", type=int, default=None)
    parser.add_argument("--learning-rate", type=float, default=None)
    parser.add_argument("--save-freq", type=int, default=None,
                        help="Checkpoint save frequency in steps")
    parser.add_argument("--load-model", type=str, default=None,
                        help="Path to a pre-trained Phase 1 model zip to continue training from")
    args = parser.parse_args()

    config = TrainConfigP2()
    if args.total_timesteps:
        config.total_timesteps = args.total_timesteps
    if args.port:
        config.port = args.port
    if args.learning_rate:
        config.learning_rate = args.learning_rate
    if args.save_freq:
        config.save_freq_steps = args.save_freq
    if args.load_model:
        config.load_model = args.load_model

    train(config)
