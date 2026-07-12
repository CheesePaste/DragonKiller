"""Phase 2 training hyperparameters — mobile dragon AI."""
from dataclasses import dataclass, field


@dataclass
class TrainConfigP2:
    host: str = "127.0.0.1"
    port: int = 5670

    # Phase 2 is harder — more timesteps
    total_timesteps: int = 20_000_000

    learning_rate: float = 3e-4
    n_steps: int = 2048
    batch_size: int = 1024
    n_epochs: int = 10
    gamma: float = 0.99
    gae_lambda: float = 0.95
    clip_range: float = 0.2
    ent_coef: float = 0.01
    vf_coef: float = 0.5
    max_grad_norm: float = 0.5
    target_kl: float = 0.02

    net_arch: list = field(default_factory=lambda: [256, 256])

    seed: int = 42
    n_envs: int = 8
    device: str = "cuda"

    save_dir: str = "models/p2"
    save_freq_steps: int = 500_000  # save checkpoint every 500K timesteps
    load_model: str = ""  # Path to pre-trained Phase 1 model (optional)

    eval_episodes: int = 5
    eval_freq_steps: int = 50_000
    eval_deterministic: bool = True

    log_dir: str = "logs/p2"
    log_interval: int = 1

    connect_retries: int = 300
    connect_retry_delay: float = 2.0


@dataclass
class EvalConfigP2:
    model_path: str = ""
    num_episodes: int = 10
    host: str = "127.0.0.1"
    port: int = 5670
    deterministic: bool = True
    render: bool = False
    connect_retries: int = 30
    connect_retry_delay: float = 2.0
