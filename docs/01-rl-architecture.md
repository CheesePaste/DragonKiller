# 强化学习击杀末影龙 — 架构设计方案

## 1. 背景

在 Minecraft 中通过 RL 击杀末影龙属于**稀疏奖励、长时序决策**问题。当前业界主流方案分两类：

| 方案 | 代表项目 | 特点 |
|---|---|---|
| **全 Java 内嵌** | ReinforceMC | 模型训练跑在 MC JVM 内，自带神经网络实现。无需外部进程，但 ML 生态差（无 PyTorch/TF），调试困难 |
| **Python 桥接** | DreamerV3 + DiamondEnv, MineDojo, MineRL | 环境暴露为 Gym，Python 端做训练。生态成熟（PPO/DQN/DreamerV3），可视化好，迭代快 |

---

## 2. 推荐方案：Python 桥接（TCP Socket）

### 架构总览

```
┌───────────────────────────────────────────────────┐
│              Minecraft Instance 1 (Fabric)          │
│  ┌───────────────────────────────────────────────┐ │
│  │       DragonKiller Mod (Java)                  │ │
│  │  ┌──────────┐  ┌──────────┐  ┌────────────┐ │ │
│  │  │ Env       │  │ Action   │  │ TCP        │ │ │
│  │  │ Observer  │  │ Executor │  │ Client     │ │ │
│  │  └──────────┘  └──────────┘  └──────┬─────┘ │ │
│  │  ┌──────────────┐                    │        │ │
│  │  │ Episode Mgr  │ ◄── episode done   │        │ │
│  │  └──────────────┘                    │        │ │
│  └──────────────────────────────────────┼────────┘ │
└─────────────────────────────────────────┼──────────┘
                                          │
┌─────────────────────────────────────────┼──────────┐
│              Minecraft Instance 2 ...   │          │
│                                  ┌──────┴─────┐   │
│                                  │ TCP Client  │   │
│                                  └──────┬──────┘   │
└─────────────────────────────────────────┼──────────┘
                                          │
                 ┌────────────────────────┼──────────────┐
                 │          ┌─────────┐   │  ┌─────────┐ │
                 │   TCP    │ Port    │   │  │ Port    │ │
                 │   ──────►│ 5670 + i│   │  │ 5670 + i│ │
                 │          └─────────┘   │  └─────────┘ │
                 │                        │               │
┌────────────────┴────────────────────────┴───────────────┴──┐
│                    Python 训练进程                            │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  VecEnv (SubprocVecEnv)                                 │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐               │ │
│  │  │ Env 0    │ │ Env 1    │ │ Env N    │  ← 各连一个 MC│ │
│  │  │(Subproc) │ │(Subproc) │ │(Subproc) │   实例         │ │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘               │ │
│  │       │ obs/       │            │                       │ │
│  │       │ reward/    │            │                       │ │
│  │       │ done       │            │                       │ │
│  │       └────────────┴────────────┘                       │ │
│  └─────────────────────────┬──────────────────────────────┘ │
│                            │                                │
│  ┌─────────────────────────▼──────────────────────────────┐ │
│  │  PPO (SB3)                                             │ │
│  │  ┌──────────────────────────────────────────────────┐  │ │
│  │  │  Policy Network (MLP)                             │  │ │
│  │  │   obs(n) → 离散动作(17)                           │  │ │
│  │  └──────────────────────────────────────────────────┘  │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  TensorBoard ←─── training logs / 模型检查点                  │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. 关键设计决策（已确认）

| 决策 | 结论 | 理由 |
|---|---|---|
| **训练效率** | 多开 MC 实例并行 | 单个 episode 可能 1-10 分钟，必须并行才能积累足够数据 |
| **不必要的系统** | 早期全部掐断 | 关掉配方书、进度、饥饿值、世界生成延迟，只保留战斗所需 |
| **动作粒度** | 离散动作 | 简单高效，diamond_env 也证明 10-30 个离散动作足够完成复杂任务 |
| **动作频率** | 5 tick/步 (action_repeat=5) | 每秒 4 次决策，PPO 最佳步数 ≈1200/episode；防止高频抖动 |
| **攻击粘滞** | sticky_attack=10 tick | 钻石剑冷却≈10 tick，选攻击后自动连打覆盖整个冷却期 |
| **视觉输入** | 不需要 | 结构化数据（坐标、血量、实体状态）比 CNN 从像素学习高效精确 |
| **碰撞箱** | 必须在观察中表达 | AI 需要知道实体的碰撞箱来做精确瞄准和走位 |
| **末影人视线** | 必须表达 | 玩家看末影人会激怒它，AI 需要学会管理视线 |
| **物品槽位** | 固定分配 slot 0-3 | 剑/方块/水桶/盾牌，AI 用 select_slot 动作切换 |
| **Baritone** | 暂不加入 | 第一阶段不引入，后续 RL 困难时再考虑 |

### 关于 MC 实例多开

每个 MC 实例使用**相同的固定种子**，起同样的环境条件：
- 末地地表出生
- 全套钻石装备 + 无限方块 + 水桶 + 盾牌
- 末影水晶已破
- 无饥饿

Python 端通过 `SubprocVecEnv` 管理 N 个环境，每个子进程连接一个 MC 实例的 TCP 端口。

**渲染剥离**：MC 实例用 `--nojline` 或自定义启动参数，最小化渲染负载。甚至可以尝试 headless 模式（如果可用）。

---

## 4. 通信协议

基于 JSON-over-TCP，单连接、请求-响应模式：

```
Python → Mod:  {"type": "reset"}                           # 开始新 episode
Mod → Python:   {"type": "obs", "data": {...}}              # 返回初始观察

Python → Mod:  {"type": "step", "action": {...}}            # 发送动作
Mod → Python:   {"type": "obs", "data": {...}, "reward": 0.0, "done": false}  # 返回结果

Python → Mod:  {"type": "config", ...}                      # 训练中调参
Python → Mod:  {"type": "close"}                            # 关闭连接
```

### 观察空间 (Observation)

```json
{
  "type": "obs",
  "data": {
    "player": {
      "pos": [x, y, z],
      "rotation": [yaw, pitch],
      "health": 20.0,
      "velocity": [vx, vy, vz],
      "on_ground": true,
      "sprinting": false,
      "block_below": "end_stone"
    },
    "dragon": {
      "pos": [x, y, z],
      "velocity": [vx, vy, vz],
      "bbox": [width, height],
      "health": 200.0,
      "phase": "hover",
      "target": [x, y, z],
      "looking_at": [x, y, z]
    },
    "endermen": [
      {
        "pos": [x, y, z],
        "velocity": [vx, vy, vz],
        "bbox": [width, height],
        "health": 40.0,
        "angry": false,
        "player_in_sight": false,
        "player_looking_at": false
      }
    ],
    "inventory": {
      "hotbar": ["diamond_sword", null, "water_bucket", ...],
      "selected_slot": 0,
      "armor": ["diamond_helmet", "diamond_chestplate", ...]
    },
    "terrain": {
      "heightmap": [[67, 67, 66, ...], [...], ...],
      "surface":   [[1, 1, 1, ...], [...], ...],
      "origin": [px, pz],
      "radius": 7
    },
    "raytrace": {
      "hit_entity": "ender_dragon",
      "hit_block": null,
      "distance": 12.5,
      "pos": [x, y, z],
      "entity_id": 42,
      "block_pos": null,
      "block_side": null,
      "block_id": null
    },
    "stats": {
      "time_alive": 120.0,
      "damage_dealt": 0.0,
      "damage_taken": 0.0,
      "dragon_damage_dealt": 0.0,
      "swing_count": 0,
      "hit_count": 0
    },
    "reduced": true
  },
  "reward": 0.0,
  "done": false
}
```

### 地形表示：局部高度图 (Heightmap Grid)

末地主岛虽大，但 AI 真正关心的是**周围 15×15 区域**的地形。

#### 原理

种子固定 → 末地主岛地形固定。Mod 启动时预计算全岛 2D 高度图，游戏运行时增量更新变化。

```
世界初始状态：
  ┌─────────────────────────────────────────────────┐
  │  固定种子 seed=12345                              │
  │  → Pre-compute 主岛高度图 (256×256)                │
  │  → 每个格子存: top_block_y + block_type            │
  └─────────────────────────────────────────────────┘

每 tick 发送：
  ┌─────────────────────────────────────────────────┐
  │  取玩家所在 XZ 坐标为中心                          │
  │  裁剪 15×15 局部高度图                            │
  │                                 (玩家)            │
  │      ┌─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐            │
  │      │  .  .  .  .  .  .  .  .  . │            │
  │      │  .  .  .  .  .  .  .  .  . │            │
  │      │  .  .  .  .  ●  .  .  .  . │  ← 15×15  │
  │      │  .  .  .  .  .  .  .  .  . │            │
  │      │  .  .  .  .  .  .  .  .  . │            │
  │      └─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘            │
  └─────────────────────────────────────────────────┘

方块变化时：
  ┌─────────────────────────────────────────────────┐
  │  玩家放水 → 监听到 BlockPlaceEvent               │
  │  → 更新高度图对应格子: height=y, surface=water(3) │
  │  下一 tick 的观察自动包含更新后的高度图             │
  └─────────────────────────────────────────────────┘
```

#### 数据结构

每个格子包含 2 个值：

| 字段 | 类型 | 说明 |
|---|---|---|
| **height** | float | 地表高度（Y 坐标），虚空为 -100 |
| **surface** | int (0-4) | 地表类型编码 |

surface 编码：
```
0 = VOID（虚空，无地面）
1 = END_STONE（末地石）
2 = OBSIDIAN（黑曜石，柱子）
3 = WATER（水源，AI 放置的）
4 = OTHER（其他方块，含玩家放置的）
```

#### 为什么 15×15

| 参数 | 值 | 理由 |
|---|---|---|
| 半径 | 7 格 | 步行 1 秒内可到达的范围 |
| 高度跨度 | 末地高度范围 | 一层地表 + 一层天花板（柱子涉及） |
| 总格子数 | 225 | 225×2 = 450 个 float，网络传输极小 |

#### 对 RL 的价值

- **虚空边界检测**：高度图边缘的 VOID 格子告诉 AI 前方是虚空
- **柱子感知**：OBSIDIAN 格子让 AI 知道柱子在哪个方向
- **水方块记忆**：WATER 格子让 AI 知道哪里是它放过水的安全着陆点
- **地形起伏**：高度差让 AI 预判能否在地面行走

#### 实现方式

```
Mod 启动时:
  EndIslandHeightmap.precompute(seed)
    → 遍历主岛 XZ 范围 (-128..128, -128..128)
    → 每列取最高非空气方块 = height
    → 存入 HashMap<ChunkPos, int[][]>

游戏运行时:
  BlockPlaceEvent / BlockBreakEvent 监听
    → 更新本地缓存
    → 标记对应格子 surface 类型

每 tick 构建观察:
  terrain.heightmap = slice_around_player(15x15, height)
  terrain.surface   = slice_around_player(15x15, type)
  terrain.origin    = [player.x, player.z]
  terrain.radius    = 7
```

#### 关键观察字段说明

| 字段 | 说明 |
|---|---|
| `dragon.bbox` | 龙的实际碰撞箱大小 (width, height)。用于瞄准判断 |
| `endermen[i].player_in_sight` | 该末影人是否能看到玩家头部（激怒检测） |
| `endermen[i].player_looking_at` | 玩家视线是否落在该末影人的碰撞箱上（决定是否激怒它） |
| `endermen[i].angry` | 末影人当前的愤怒状态 |
| `terrain.heightmap` | 15×15 浮点数矩阵，玩家周围每列的地表高度 |
| `terrain.surface` | 15×15 整数矩阵，每列的地表类型编码 |
| `raytrace.hit_entity` | 玩家准星指向的实体（若有），null 表示没对准实体 |
| `raytrace.hit_block` | 准星指向的方块 ID（若有），如 `"minecraft:end_stone"` |
| `raytrace.block_pos` | 准星指向的方块坐标 [x, y, z] |
| `raytrace.block_side` | 指向方块的面，如 `"up"`、`"side"`（用于在正确位置放方块） |
| `reduced` | 当前是否为简化世界模式 |

Python 端发送离散整数（0~20），Mod 端映射为具体操作：

```
移动/转向/战斗（基础 14 个）:
 0: noop                   → 什么都不做
 1: forward                → 前进
 2: back                   → 后退
 3: strafe_left            → 左移
 4: strafe_right           → 右移
 5: turn_left              → 左转 15°
 6: turn_right             → 右转 15°
 7: turn_up                → 抬头 15°
 8: turn_down              → 低头 15°
 9: attack                 → 左键攻击（粘滞 10 tick）
10: use_item               → 右键使用/放置（放方块、倒水、举盾）
11: jump                   → 跳跃
12: sneak                  → 潜行
13: sprint                 → 冲刺

组合动作（3 个）:
14: forward + attack       → 前进同时攻击
15: strafe_left + attack   → 左移同时攻击
16: strafe_right + attack  → 右移同时攻击

物品切换（4 个）:
17: select_slot_0          → 切换到槽位 0（钻石剑）
18: select_slot_1          → 切换到槽位 1（方块）
19: select_slot_2          → 切换到槽位 2（水桶）
20: select_slot_3          → 切换到槽位 3（盾牌）
```

共 **21 个离散动作**。选择逻辑：
- 4 个关键槽位固定分配，AI 先切槽位再使用/攻击
- 组合动作（前进+攻击）减少 AI 每步需要做多步决策的负担
- 物品切换与战斗分离，AI 可以独立学习"什么时候用什么"
- 后续可按需扩充

---

### 动作频率：action_repeat=5

AI 不是每 tick 做一次决策，而是**每 5 tick（250ms）做一次决策**。

**为什么不是 1 tick/步？**

| 因素 | 1 tick (50ms) | 5 tick (250ms) |
|---|---|---|
| **PPO 最优步数** | 10 分钟战斗 = 12000 步，远超 PPO 默认 horizon (2048) | 12000/5 = 2400 步，适配良好 |
| **钻石剑冷却** | 冷却 10 tick，AI 要学"攻1等9"的微观节奏 | 每 2 步一次完整攻-待循环 |
| **动作意义** | 50ms MC 内部还没完成动作响应 | 250ms 足够完成一次有效动作 |
| **抖动风险** | RL 在细粒度空间容易学左右振荡 | 粗粒度天然稳定 |
| **训练速度** | 同样时间经历更少 episode | 5 倍 episode 数，PPO 更新更频繁 |

**攻击粘滞 (sticky_attack)**：当 AI 选择 `attack` 动作时，Mod 自动保持攻击状态 10 tick（覆盖钻石剑冷却期）。这防止 AI 需要精准 timing，它只需要"想攻击时选 attack"即可，粘滞机制保证每次攻击都生效。

**逻辑实现**：
```
每 tick:
  if (action_freeze_counter > 0):
    action_freeze_counter--
    if (sticky_attack_active):
      keep_attacking()
    skip_reading_new_action()        ← 还在执行上一个动作
  else:
    read_new_action_from_network()
    action_freeze_counter = 5        ← 冻结 5 tick
    if (action == attack):
      sticky_attack_active = true
      sticky_attack_counter = 10     ← 额外粘滞攻击 10 tick
```

---

## 5. 简化 Minecraft 环境

为了训练效率，Mod 在启动时执行以下简化：

| 修改 | 实现方式 | 目的 |
|---|---|---|
| **禁用饥饿** | Mixin 注入 `FoodData`，锁定为满 | 减少一个需要管理的状态 |
| **禁用配方书** | Mixin 屏蔽 RecipeBook 逻辑 | 减少不必要的网络和数据同步 |
| **禁用进度** | Mixin 屏蔽 Advancement 触发 | 同上 |
| **预破水晶** | 世界加载时遍历并摧毁所有 Ender Crystal | AI 只需专注打龙 |
| **固定种子** | 通过 ServerProperties 注入 | 环境可复现 |
| **初始装备** | 玩家生成事件给予全套钻石装备 | AI 有足够战斗力 |
| **无限资源** | 创造模式或无限物品栏 | AI 不需要操心资源管理 |
| **末地出生** | Mixin 修改生成点逻辑 | 跳过前置流程 |
| **最小化渲染** | 启动参数：渲染距离 2，关粒子/云/雾 | GPU 负载降至最低，支持多开 |

---

## 6. 奖励函数设计（初步）

### 6.1 密集奖励（每 tick 计算）

| 信号 | 公式 | 说明 |
|---|---|---|
| **对龙伤害** | `dragon_damage * 2.0` | 对龙造成伤害立即奖励 |
| **攻击命中** | `+1.0 if swing_and_hit` | 成功命中（非空挥）奖励 |
| **空挥惩罚** | `-0.5 if swing_and_miss` | 挥剑没砍到，轻微惩罚 |
| **存活** | `+0.001 / tick` | 鼓励存活更久 |
| **末影人激怒惩罚** | `-1.0 if enderman.angry` | 被末影人盯上要扣分 |
| **玩家受伤** | `-health_loss * 5.0` | 受伤惩罚，鼓励规避 |

### 6.2 稀疏奖励（事件触发）

| 信号 | 值 | 触发 |
|---|---|---|
| **龙每受伤** | `+3.0` | 龙血量减少事件（每减少 1 HP） |
| **玩家死亡** | `-20.0` | episode done |
| **末影人击杀** | `-5.0` | 打末影人浪费时间，轻微惩罚 |
| **龙死亡** | `+200.0` | 主要目标，episode done |

### 6.3 课程学习 (Curriculum Learning)

技能递进，每个阶段专注解决一个核心问题，防止 agent 同时学习冲突技能：

```
Phase 1: 静态龙（不移不攻，无末影人）
  → 学习瞄准、攻击节奏、走位
  → 龍不会反击，末影人不干扰，专注"如何打中龙"

Phase 2a: 完整龙 + 零末影人
  → 学习完整战斗节奏（走位、规避龙息、追击）
  → 无末影人干扰

Phase 2b: 静态龙 + 全部末影人（约 8-10 只）
  → 专注学习管理视线 + 站位
  → 龙静止，agent 不必同时躲龙攻，可集中精力处理末影人
  → 静止龙仍可作攻击目标，保持正奖励

Phase 2c: 完整龙 + 全部末影人（完全体）
  → 综合所有技能

Phase 3: 从零到击杀稳定化
  → 长时训练，追求 ≥90% 击杀率
  → 优化击杀时间
```

---

## 7. 技术栈

### Minecraft 端 (Fabric Mod)

| 组件 | 实现方式 |
|---|---|
| **观察收集** | Mixin + Server Tick 事件 + 实体查询 |
| **碰撞箱获取** | 直接读取 Entity#getBoundingBox |
| **末影人视线检测** | 玩家视锥检测 + 末影人检测玩家头部光线追踪 |
| **动作执行** | Mixin 注入直接操作 LivingEntity#movementControls / 发送使用物品包 |
| **世界简化** | Mixin 注入跳过饥饿、配方书、进度 |
| **Socket 通信** | Java NIO（轻量，单连接延迟低） |
| **Episode 管理** | PlayerDeathCallback + DragonDeathDetection → 自动重置 |

### Python 端

| 组件 | 推荐库 | 说明 |
|---|---|---|
| **并行环境** | `stable-baselines3` VecEnv | 多进程管理多个 MC 实例 |
| **通信** | `socket` + `json` | TCP 客户端 |
| **PPO** | `stable-baselines3` | 离散动作空间基线算法 |
| **特征提取** | MLP (无 CNN) | 结构化数据，不需要视觉特征提取 |
| **可视化** | `tensorboard` | 训练曲线 |
| **实验管理** | `wandb` (可选) | 多开实验对比 |

---

## 8. 文件结构

### Mod 部分（新增）

```
src/main/java/ai/cp/
├── DragonKiller.java                    # 已有的主入口
├── mixin/
│   ├── WorldSimplifierMixin.java        # 关饥饿、配方书、进度
│   ├── EndSpawnMixin.java               # 末地出生点
│   ├── CrystalDestroyerMixin.java       # 初始破坏水晶
│   ├── FixedSeedMixin.java              # 固定种子
│   └── EquipmentMixin.java              # 初始装备
├── rl/
│   ├── RLTickHandler.java               # Server tick 事件驱动
│   ├── ObservationBuilder.java          # 构建观察 JSON（含碰撞箱、视线）
│   ├── ActionParser.java                # 离散动作索引 → MC 操作
│   ├── RewardCalculator.java            # 奖励计算
│   ├── EpisodeManager.java              # Episode 生命周期
│   └── network/
│       ├── SocketServer.java            # TCP Socket 服务端
│       └── Protocol.java                # 消息序列化/反序列化
└── config/
    └── RLConfig.java                    # 配置：端口、奖励系数、训练模式
```

### Python 端（新目录 `train/`）

```
train/
├── env/
│   ├── __init__.py
│   ├── dragon_env.py                    # Gym Env，TCP → MC 实例
│   └── protocol.py                      # 通信协议封装
├── train.py                              # 主训练入口（VecEnv + PPO）
├── watch.py                              # 观察已训练模型表现
├── config.py                             # 超参数
└── requirements.txt                      # 依赖
```

---

## 9. 多开训练架构

```
                  ┌────────────────────────────────────────────┐
                  │  Python 主进程                              │
                  │                                            │
                  │  SubprocVecEnv(N=4)                         │
                  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐     │
                  │  │Env 0 │ │Env 1 │ │Env 2 │ │Env 3 │     │
                  │  └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘     │
                  │     │  TCP   │   TCP   │  TCP    │         │
                  │     │ 5670   │  5671   │ 5672    │  5673   │
                  └─────┼────────┼─────────┼─────────┼─────────┘
                        │        │         │         │
               ┌────────┘  ┌─────┘  ┌──────┘  ┌──────┘
               ▼           ▼        ▼         ▼
         ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
         │MC 实例 0 │ │MC 实例 1 │ │MC 实例 2 │ │MC 实例 3 │
         │Fabric    │ │Fabric    │ │Fabric    │ │Fabric    │
         │dragon-0  │ │dragon-1  │ │dragon-2  │ │dragon-3  │
         └──────────┘ └──────────┘ └──────────┘ └──────────┘
```

启动脚本自动拉起 N 个 MC 客户端，每个分配不同端口。

---

## 10. 开发路线

```
Phase 0 ─ 参考分析（已完成）
  ├── 阅读 diamond_env 源码
  ├── 阅读 Minecraft-PVP-bot 源码
  └── 分析 ReinforceMC 结构

Phase 1 ─ 环境搭建（当前）
  ├── Mixin: 固定种子、末地出生、钻石装备
  ├── Mixin: 破水晶、关饥饿、简化世界
  ├── TCP Socket Server
  ├── 观察收集 (ObservationBuilder + 碰撞箱 + 视线)
  ├── 动作执行 (ActionParser)
  └── Python env wrapper + protocol

Phase 2 ─ 基线训练
  ├── 单实例 PPO 训练
  ├── TensorBoard 可视化
  └── 调参 + 奖励调整

Phase 3 ─ 多开加速
  ├── SubprocVecEnv 多进程
  ├── MC 实例多开脚本
  └── 确认加速比

Phase 4 ─ 课程学习
  ├── Phase I: 静态龙（学习瞄准和攻击）
  ├── Phase IIa: 完整龙 + 零末影人（学习战斗节奏）
  ├── Phase IIb: 静态龙 + 全部末影人（学习管视线）
  ├── Phase IIc: 完整龙 + 全部末影人（综合）
  └── Phase III: 稳定击杀率

Phase 5 ─ 优化迭代
  ├── 失败模式分析
  ├── 奖励函数调优
  ├── 必要时引入 Baritone
  └── 100% 击杀率达成
```
