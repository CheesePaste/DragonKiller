# Minecraft AI 屠龙训练与评估使用指南

本指南详细说明了本模组（DragonKiller Fabric Mod）与 Python 强化学习框架的联合训练与评估方法。

---

## 1. 核心设计：分阶段课程学习（Curriculum Learning）

由于直接在移动飞龙场景下让 AI 从零探索会面临“探索空间过大、冷启动极难”的问题，本系统采用了**先近战 -> 后远程打靶 -> 再远近混合**的课程学习策略：

```
                       ┌────────────────────────┐
                       │  Stage 1: 纯近战基础训练 │ (1. 静态龙 / 2. 禁用弩箭)
                       └───────────┬────────────┘
                                   │ (继承 weights)
                                   ▼
                       ┌────────────────────────┐
                       │  Stage 2: 远程打靶训练 │ (1. 高空静止龙 / 2. 仅弩能命中)
                       └───────────┬────────────┘
                                   │ (继承 weights)
                                   ▼
                       ┌────────────────────────┐
                       │  Stage 3: 混合多任务汇总 │ (1. 移动飞龙 / 2. 2近战+4混合并行实例)
                       └────────────────────────┘
```

为了确保前后阶段能够无缝继承并加载权重（防止因网络输入输出层大小变化报错），动作空间统一采用 **7 维连续动作**，状态空间采用 **39 维特征观察值**。

---

## 2. 三阶段训练详细命令

在开始任何训练阶段或更改参数前，请在终端（PowerShell）中执行清理脚本释放旧进程：
```powershell
.\stop_all.ps1
```

### 阶段一：纯近战肌肉记忆训练 (Melee Base)
* **目的**：AI 集中学习如何通过钻石剑接近龙、瞄准并产生最高近战 DPS。
* **第 1 步：启动 6 个纯近战 Minecraft 实例**：
  ```powershell
  .\gradlew runMultiEnv -PnumEnvs=6 -Prlcombatmode=melee_only
  ```
  *(注：系统会自动将所有实例配置为 `-Drlphase=p1` 静态龙状态，且不装备弩箭)*
* **第 2 步：启动 Python 端的 Stage 1 训练**：
  ```powershell
  python train/train_p2.py --total-timesteps 2000000 --save-freq 500000
  ```

---

### 阶段二：高空打靶与远程弹道学习 (Ranged Target Practice)
* **目的**：将龙挂在 Y=85 高空静止（剑砍不到），AI 必须学会在抛物线重力和龙本身细微移动的规律下进行射弩瞄准。
* **第 1 步：清理并启动 6 个远程打靶实例**：
  ```powershell
  .\stop_all.ps1
  .\gradlew runMultiEnv -PnumEnvs=6 -Prlcombatmode=ranged_only -Prlphase=p2
  ```
* **第 2 步：加载阶段一的权重，开始 Stage 2 训练**：
  ```powershell
  python train/train_p2.py --load-model models/p2/dragonkiller_p2_ppo_final.zip --total-timesteps 1500000 --save-freq 500000
  ```

---

### 阶段三：混合多任务汇总训练 (Combined Mixed Combat)
* **目的**：龙完全解冻（正常升降空）。通过**混合分流机制**（2个纯近战实例防遗忘，4个远近战混合实例学决策），汇总出最优屠龙模型。
* **第 1 步：清理并启动混合分流客户端**：
  ```powershell
  .\stop_all.ps1
  .\gradlew runMultiEnv -PnumEnvs=6 -Prlcombatmode=mixed
  ```
  *(注：前 2 个端口自动运行 `melee_only` 保护近战梯度，后 4 个端口自动运行正常飞龙交替的 `mixed` 模式)*
* **第 2 步：加载阶段二的权重，开始 Stage 3 训练**：
  ```powershell
  python train/train_p2.py --load-model models/p2/dragonkiller_p2_ppo_final.zip --total-timesteps 5000000 --save-freq 1000000
  ```

---

## 3. 模型评估 (Evaluation)

我们在 Python 端与 Gradle 端都单独封装了用于单实例、带图形界面的评估流程：

### 场景 A：20 TPS 正常速率观测（适合肉眼观察/录屏）
1. **启动评估用游戏客户端**（限定 20 TPS 帧率，龙远近战全开）：
   ```powershell
   .\gradlew runEvalEnv -Prltps=20
   ```
2. **启动评估脚本**（默认 10 个 Episodes）：
   ```powershell
   python train/eval.py --model models/p2/dragonkiller_p2_ppo_final.zip --episodes 10
   ```

### 场景 B：最大 TPS 压力测试（用于快速跑分以计算真实屠龙率）
1. **启动评估用游戏客户端**（不限制 tick 速率，按 CPU 最大性能 adaptive 加速运行）：
   ```powershell
   .\gradlew runEvalEnv
   ```
2. **启动评估脚本**：
   ```powershell
   python train/eval.py --model models/p2/dragonkiller_p2_ppo_final.zip --episodes 100
   ```

---

## 4. 训练与开发实用技巧

### 1. 手动中断自动保存 (Ctrl+C Save)
在任何时候（不论是 `train_p2.py` 还是 `eval.py`），如果您想要提早结束训练或中途保存，直接在终端按下 `Ctrl+C` 即可。
Python 脚本会**自动拦截中断信号，并将当前训练的所有权重实时保存为 `dragonkiller_p2_ppo_final.zip`**，并优雅断开与游戏底层的 TCP 端口链接。

### 2. 显式性能调整 (Memory Limit)
如果您的本地开发电脑无法同时承载 6 个 Minecraft 实例，您只需在配置和执行中将实例数量缩减（例如 `-PnumEnvs=4`）：
* **Gradle 端自动适配**：当 `numEnvs=4` 且处于阶段三（`mixed`）时，Gradle 会自动为您分配 `1个纯近战客户端 + 3个混合实战客户端`，依然保持混合多任务的比例特征。

### 3. TensorBoard 可视化
您可以通过 Tensorboard 随时监控 AI 的平均存活长度、累计奖励值及**飞龙击杀率 (dragon_kill_rate)** 的增长态势：
```powershell
tensorboard --logdir logs/p2
```
