"""Training hyperparameters and configuration for DragonKiller PPO training."""

from dataclasses import dataclass, field


@dataclass
class TrainConfig:
    # Environment
    host: str = "127.0.0.1"
    port: int = 5670

    # Training duration
    total_timesteps: int = 2_000_000

    # PPO hyperparameters (SB3 defaults tuned for this env)
    learning_rate: float = 3e-4
    n_steps: int = 2048
    batch_size: int = 64
    n_epochs: int = 10
    gamma: float = 0.99
    gae_lambda: float = 0.95
    clip_range: float = 0.2
    ent_coef: float = 0.01
    vf_coef: float = 0.5
    max_grad_norm: float = 0.5
    target_kl: float = 0.02  # Early stopping if KL divergence exceeds this

    # Network architecture (MLP)
    net_arch: list = field(default_factory=lambda: [256, 256])

    # Reproducibility
    seed: int = 42
    n_envs: int = 4  # Number of environments to run in parallel
    device: str = "cpu"  # Changed from "cpu" to "cuda" for GPU acceleration

    # Checkpointing
    save_dir: str = "models"
    save_freq_steps: int = 100_000

    # Evaluation
    eval_episodes: int = 5
    eval_freq_steps: int = 50_000
    eval_deterministic: bool = True

    # Logging
    log_dir: str = "logs"
    log_interval: int = 1  # Log every N episodes

    # Connection
    connect_retries: int = 300  # Number of times to retry connecting to MC
    connect_retry_delay: float = 2.0  # Seconds between retries


@dataclass
class EvalConfig:
    """Configuration for model evaluation / watch mode."""
    model_path: str = ""
    num_episodes: int = 10
    host: str = "127.0.0.1"
    port: int = 5670
    deterministic: bool = True
    render: bool = False
    connect_retries: int = 30
    connect_retry_delay: float = 2.0
