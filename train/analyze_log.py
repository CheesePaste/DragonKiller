#!/usr/bin/env python
"""Parse TensorBoard logs and print grouped scalar summaries for quick analysis.

Usage:
    python train/analyze_log.py                          # auto: latest run in logs/
    python train/analyze_log.py logs/p2/run_name         # specific run
    python train/analyze_log.py logs/p2 --phase p2       # latest in logs/p2/
    python train/analyze_log.py --last 20                # show last 20 values per tag
"""

import argparse
import os
import re
from pathlib import Path

import numpy as np
from tensorboard.backend.event_processing.event_accumulator import EventAccumulator


# ── Tag groups ──────────────────────────────────────────────────────────────────
# Each group: (header_name, [tag_prefixes])
GROUPS = {
    "Rollout (PPO)": [
        "rollout/ep_rew_mean",
        "rollout/ep_len_mean",
        "rollout/success_rate",
    ],
    "Loss / Training": [
        "train/loss",
        "train/policy_loss",
        "train/value_loss",
        "train/approx_kl",
        "train/clip_fraction",
        "train/entropy_loss",
        "train/explained_variance",
        "train/learning_rate",
    ],
    "Time": [
        "time/fps",
    ],
    "Tracker -- Combat": [
        "train/damage_dealt",
        "train/hit_count",
        "train/headshot_count",
        "train/attack_attempts",
        "train/blocked_hits",
        "train/clawback_count",
    ],
    "Tracker -- Survival": [
        "train/player_damage_taken",
        "train/breath_ticks",
        "train/low_hp_ticks",
        "train/sprint_ticks",
    ],
    "Tracker -- Positioning": [
        "train/in_view_ticks",
        "train/crosshair_ticks",
        "train/avg_dragon_distance",
        "train/min_dragon_distance",
        "train/avg_center_distance",
    ],
    "Tracker -- Episode": [
        "train/episode_reward",
        "train/episode_length",
        "train/dragon_kill_rate",
        "train/end_reason",
    ],
}


def discover_runs(log_root: str) -> list[str]:
    """Find all run directories under *log_root* (single-level)."""
    p = Path(log_root)
    if not p.exists():
        return []
    # A run dir usually contains event files
    candidates = sorted(
        [str(d) for d in p.iterdir() if d.is_dir() and list(d.glob("events.out.tfevents*"))]
    )
    return candidates


def find_latest_run(base: str) -> str | None:
    """Return the most-recently modified run directory under *base*."""
    runs = discover_runs(base)
    if not runs:
        return None
    # Latest by mtime (most recently written to)
    runs.sort(key=lambda d: os.path.getmtime(d), reverse=True)
    return runs[0]


def load_scalars(run_path: str) -> dict[str, list[tuple[int, float, int]]]:
    """Load all scalars from a TB run directory."""
    ea = EventAccumulator(run_path, size_guidance={"scalars": 0})  # 0 = no limit
    ea.Reload()
    tags = ea.Tags().get("scalars", [])
    data = {}
    for tag in tags:
        events = ea.Scalars(tag)
        data[tag] = [(e.step, e.value, e.wall_time) for e in events]
    return data


def compute_trend(values: list[float], window: int = 20) -> str:
    """Simple trend: slope of last *window* points via linear regression."""
    if len(values) < window:
        window = len(values)
    if window < 3:
        return "-"
    y = np.array(values[-window:], dtype=float)
    x = np.arange(len(y), dtype=float)
    slope = np.polyfit(x, y, 1)[0]
    if abs(slope) < 1e-9:
        return "-"
    if slope > 0:
        return f"/ ({slope:+.2e}/step)"
    return f"\\ ({slope:+.2e}/step)"


def print_tag_info(
    tag: str, events: list, last_n: int = 10, show_trend: bool = True
):
    """Print a single tag's summary."""
    values = [e[1] for e in events]
    steps = [e[0] for e in events]
    if not values:
        return

    recent = values[-last_n:]
    # Adaptive format: more decimal places for small values
    max_abs = max(abs(v) for v in recent) if recent else 1.0
    fmt = ".4f" if max_abs < 0.01 else ".2f"
    recent_str = ", ".join(f"{v:{fmt}}" for v in recent)
    avg = np.mean(recent)
    key = tag.split("/", 1)[-1]  # strip "train/" prefix for display

    parts = [
        f"  {key:35s} last {len(recent):>3} = [{recent_str}]",
        f"  avg={avg:.3f}",
        f"  min={np.min(recent):.3f}  max={np.max(recent):.3f}",
    ]

    if show_trend and steps[-1] > 0:
        parts.append(f"  trend={compute_trend(values)}")

    # Include raw step range
    parts.append(f"  steps=[{steps[0]}..{steps[-1]}]")

    # Special: for kill rate, end_reason -- show latest value
    if "dragon_kill_rate" in tag:
        parts.append(f"  latest={values[-1]:.1f}%")
    if "end_reason" in tag:
        # this is a string tag (0/1 encoded) -- skip numeric stats
        pass

    print("  ".join(parts))


def detect_phase(run_path: str) -> str:
    """Guess phase from path or tag presence."""
    if "p2" in run_path or "P2" in run_path:
        return "p2"
    return "p1"


def analyze(run_path: str, last_n: int = 10):
    """Main analysis entry point."""
    if not os.path.isdir(run_path):
        print(f"Error: not a directory -- {run_path}")
        return

    phase = detect_phase(run_path)
    print(f"{'=' * 72}")
    print(f"  Log Analysis -- {run_path}")
    print(f"  Phase: {phase.upper()}")

    data = load_scalars(run_path)
    if not data:
        print("  (no scalar data found)")
        return

    # Collect ungrouped tags
    seen = set()

    for group_name, prefixes in GROUPS.items():
        matched = False
        for prefix in prefixes:
            # Try exact or startswith match
            key = None
            for tag in data:
                if prefix in tag:
                    key = tag
                    break
            if key is None:
                continue
            if not matched:
                dash_len = 60 - len(group_name)
                print(f"\n-- {group_name} {'-' * max(dash_len, 2)}")
                matched = True
            seen.add(key)
            print_tag_info(key, data[key], last_n=last_n)

    # Remaining tags not in any group
    ungrouped = sorted(set(data) - seen)
    if ungrouped:
        dash_len = 60 - len(str(len(ungrouped)))
        print(f"\n-- Other ({len(ungrouped)} tags) {'-' * max(dash_len, 2)}")
        for tag in ungrouped:
            print_tag_info(tag, data[tag], last_n=last_n, show_trend=False)

    # Summary
    episode_count = len(data.get("train/episode_reward", []))
    print(f"\n{'-' * 72}")
    print(f"  Episodes recorded: {episode_count}")
    # Show step range across all tags
    all_steps = [e[0] for tag_data in data.values() for e in tag_data]
    if all_steps:
        print(f"  Step range: {min(all_steps)} .. {max(all_steps)}")
    print(f"{'-' * 72}")

    # If combat data available, show a quick health-economy check
    dmg_dealt_tag = "train/damage_dealt"
    dmg_taken_tag = "train/player_damage_taken"
    if dmg_dealt_tag in data and dmg_taken_tag in data:
        dd = np.mean([e[1] for e in data[dmg_dealt_tag][-50:]])
        dt = np.mean([e[1] for e in data[dmg_taken_tag][-50:]])
        print(f"  Health economy (last 50 ep): damage_dealt={dd:.1f}  player_damage_taken={dt:.1f}")

    # Kill rate check
    kr_tag = "train/dragon_kill_rate"
    if kr_tag in data:
        kr_vals = [e[1] for e in data[kr_tag]]
        print(f"  Dragon kill rate (overall): {kr_vals[-1]:.1f}%  (last 50: {np.mean(kr_vals[-50:]):.1f}%)")

    print()

    # Return structured data for programmatic use
    return data


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Analyze TensorBoard logs from DragonKiller training")
    parser.add_argument("run", nargs="?", default=None,
                        help="Path to TB run directory (default: latest run in logs/ or logs/p2/)")
    parser.add_argument("--last", type=int, default=10,
                        help="Number of recent values to display (default: 10)")
    parser.add_argument("--phase", choices=["p1", "p2"], default=None,
                        help="Force phase (default: auto-detect from path)")
    args = parser.parse_args()

    run_path = args.run
    if run_path is None:
        # Try latest in logs/p2/ first, then logs/
        for candidate in ["logs/p2", "logs"]:
            latest = find_latest_run(candidate)
            if latest:
                run_path = latest
                break

    if run_path is None:
        print("No TensorBoard runs found in logs/ or logs/p2/")
        print("Usage: python train/analyze_log.py [run_directory]")
        exit(1)

    analyze(run_path, last_n=args.last)
