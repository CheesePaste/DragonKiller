# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Minecraft 1.20.1 Fabric 模组，目标是通过强化学习训练 AI 击杀末影龙。模组在 Fabric 端实现所有自定义逻辑，包括环境配置、奖励机制、动作空间以及训练接口。

## 项目需求

- **固定世界种子**：保证训练环境可复现 ✅
- **出生点**：AI 直接出生在末地 (30.5, 70, 30.5)，远离龙巢避免碰撞 ✅
- **初始装备（Phase 1）**：钻石剑 + 全套钻石盔甲（无方块、水桶、盾牌）
- **动作空间（Phase 1）**：9 离散动作（noop、前进、后退、左转、右转、上视、下视、攻击、疾跑）
- **无饥饿**：AI 不会消耗饥饿值 ✅
- **末影水晶**：水晶保留在柱子上但龙不会连接回血 ✅
- **末影人**：末地不再生成末影人 ✅
- **后续迭代**：根据初步训练效果观察 AI 行为问题，逐步调整规则

## 构建命令

```bash
./gradlew build          # 编译模组
./gradlew clean          # 清理构建
./gradlew runClient      # 运行 Minecraft 客户端（用于调试）
```

## 项目结构

```
src/main/java/ai/cp/
├── DragonKiller.java              # 模组主入口
├── config/
│   └── RLConfig.java              # 配置：端口、种子、奖励系数
├── mixin/
│   ├── FixedSeedMixin.java        # 固定世界种子 (12345)
│   ├── EndSpawnMixin.java         # 末地出生 + 破水晶 (Y=70)
│   ├── EquipmentMixin.java        # 初始钻石装备
│   ├── WorldSimplifierMixin.java   # 阻止末影人生成
│   ├── NoDragonHealingMixin.java   # 龙不连接水晶回血
│   ├── StaticDragonMixin.java     # 静态龙（无 AI，Phase 1）
│   ├── HungerDisableMixin.java    # 禁用饥饿
│   └── RLTickMixin.java           # Server tick 驱动 RL 循环
└── rl/
    ├── RLTickHandler.java         # 主循环编排 (obs→action→reward)
    ├── ObservationBuilder.java    # 构建观察 JSON
    ├── ActionParser.java          # 21 离散动作 → MC 操作
    ├── RewardCalculator.java      # 密集/稀疏奖励计算
    ├── EpisodeManager.java        # Episode 生命周期
    └── network/
        ├── SocketServer.java      # TCP NIO 服务端 (port 5670)
        └── Protocol.java          # JSON 消息序列化

train/
├── env/
│   ├── __init__.py
│   ├── protocol.py                # Python TCP 客户端
│   └── dragon_env.py              # Gymnasium Env (9 动作, 22 维观察)
├── config.py                      # PPO 超参数配置
├── train.py                       # SB3 PPO 训练入口
├── watch.py                       # 已训练模型评估
├── test_env.py                    # 环境连接闭环测试
└── requirements.txt               # gymnasium, stable-baselines3, numpy, tensorboard
```

## Phase 1 已完成

- [x] Mixin: 固定种子 (GeneratorOptions.getSeed → 12345)
- [x] Mixin: 末地出生 (onSpawn teleport to END 30.5,70,30.5)
- [x] Mixin: 钻石装备 (全套盔甲 + 剑/方块/水桶/盾牌)
- [x] Mixin: 末影人生成阻止 (WorldSimplifierMixin → spawnEntity 层拦截)
- [x] Mixin: 龙回血禁用 (NoDragonHealingMixin → tickWithEndCrystals cancel)
- [x] Mixin: 饥饿禁用 (HungerManager.update cancel)
- [x] TCP Socket Server (port 5670, JSON-over-TCP, NIO)
- [x] 观察空间重构: 583→22 维，使用龙相对位置 (yaw_delta/pitch_delta/distance/in_view)
- [x] 自动面向龙: reset 时计算 yaw/pitch 让 AI 直接面对末影龙
- [x] 动作执行 (21→16→9 离散动作, action_repeat=3, sticky_attack=3)
- [x] 奖励计算 (密集+稀疏, delta 追踪)
- [x] Episode 管理 (RUNNING/DONE, 超时6000tick)
- [x] Python env wrapper (gymnasium, protocol)
- [x] 构建通过 (`./gradlew build` SUCCESS)

## Phase 2 — 基线训练

### Phase 1 动作表（当前）

| Index | Action | Type |
|-------|--------|------|
| 0 | noop | - |
| 1 | moveForward | continuous |
| 2 | moveBackward | continuous |
| 3 | turnLeft (-15° yaw) | one-shot |
| 4 | turnRight (+15° yaw) | one-shot |
| 5 | lookUp (-15° pitch) | one-shot |
| 6 | lookDown (+15° pitch) | one-shot |
| 7 | attack | sticky (3 ticks) |
| 8 | sprint | continuous |

### Phase 2 TODO

- [ ] 取消 `StaticDragonMixin`，恢复龙 AI（hover/charge/landing 阶段）
- [ ] 主手钻石剑 + 副手盾牌
- [ ] 新增动作：举盾（长按右键）
- [ ] 动作表扩展为 10 动作
- [ ] 观察空间扩展：重新启用末影人数据、龙 phase、龙 velocity/bbox
- [ ] 调整奖励函数适应防御行为

### 训练流程

1. **启动 MC 客户端** (`./gradlew runClient`)，确认 TCP 5670 端口监听
2. **Python 端测试**：`python train/test_env.py` 连接、reset、step 循环验证闭环
3. **单实例 PPO 训练**：`python train/train.py`，用 SB3 PPO + DragonEnv
4. **TensorBoard**：`tensorboard --logdir logs` 监控训练曲线
5. **评估模型**：`python train/watch.py --model models/best/best_model.zip`
6. **调参**：奖励系数、action_repeat、sticky_attack 根据训练效果调整

### Phase 2 已完成

- [x] 修复 Java 端奖励 bug（玩家死亡不再错误获得 +200 屠龙奖励）
- [x] 修复攻击距离 bug（`findClosestDragonPart` 超出 6 格返回 null）
- [x] `StaticDragonMixin` — 取消 `tickMovement()`，龙冻结在龙巢不 AI
- [x] 新增密集奖励：靠近龙 (+0.05/格)、疾跑 (+0.01/tick)、近距离 (+0.1/tick, <10格)
- [x] `train/config.py` — PPO 超参数与环境配置
- [x] `train/train.py` — SB3 PPO 训练流程（含 checkpoint、eval、TensorBoard 回调）
- [x] `train/watch.py` — 加载已训练模型进行评估
- [x] `train/test_env.py` — 环境连接闭环冒烟测试（obs 维度、action 空间、step 循环）
- [x] 构建通过 (`./gradlew build` SUCCESS)
- [x] 动作空间精简（21→16→9），移除潜行/切换物品/跳跃/使用/左右移动/组合攻击
- [x] 初始装备精简：仅钻石剑 + 钻石盔甲
- [x] 观察空间重构：583→22 维，龙相对位置(yaw_delta/pitch_delta/distance/in_view)
- [x] 奖励重构：移除 sprint 主导奖励，新增面向龙/距离指数/虚空惩罚，提高接近奖励权重
- [x] 出生面向修复：reset 时自动转向龙，解决 pitch 失控看天问题

## 观察空间设计

### 当前 25 维结构

```
[0-5]   player:       health, on_ground, sprinting, vel_xyz
[6-11]  dragon_rel:   yaw_delta, pitch_delta, distance, in_view, health, alive
[12-13] terrain:      ground_distance, is_over_void
[14-15] inventory:    has_sword, has_armor
[16-18] raytrace:     dragon_in_crosshair, distance, hit_type(0=none/1=block/2=dragon)
[19-22] stats:        time_alive, dragon_damage_dealt, hit_count, attack_cooldown
[23-24] breath:       nearest_breath(0-1), breath_warning(0/1)
```

### Phase 2 扩展计划

| 特征 | 当前 | Phase 2 | 理由 |
|------|------|---------|------|
| 地形 15×15 | 已删除 | 按需加回 5×5 | 末地主岛平坦，当前无用 |
| 末影人数据 | 已删除 | 重新启用 | Phase 1 无末影人，Phase 2 如启用则需加回 |
| 龙 AI phase | 已删除 | 重新启用 | 龙解冻后需要 hover/charge/landing 阶段信息 |
| 龙 velocity | 已删除 | 重新启用 | 龙解冻后需要知道运动方向 |
| 射线追踪 | 3 维紧凑版 | 保留或扩展 | 当前已够用：dragon_in_crosshair 助攻击决策 |

### 奖励系数一览

```java
// 战斗奖励
REWARD_DRAGON_DAMAGE = 2.0   // 每点龙伤害
REWARD_HIT          = 1.0   // 每次命中
REWARD_SWING_MISS   = -0.3  // 每次挥空
REWARD_DRAGON_HURT  = 3.0   // 第一次打中龙（单次）
REWARD_DRAGON_DEATH = 200.0 // 屠龙（单次）

// 生存惩罚
REWARD_SURVIVE_TICK = 0.002  // 每 tick 存活
REWARD_PLAYER_DAMAGE = -10.0 // 每点受伤（Phase 2 提高惩罚）
REWARD_DEATH        = -20.0  // 死亡（单次）
REWARD_VOID_PENALTY = -5.0   // 每 tick 在虚空上方

// 移动奖励（折中: Phase 1 有靠近动力 + Phase 2 不贴龙巢）
REWARD_APPROACH    = 0.1    // 每靠近龙 1 格 — 一次性奖励
REWARD_DISTANCE    = 0.03   // 距离指数奖励: 0.03 * exp(-dist/30) — 持续梯度
REWARD_PROXIMITY   = 0.05   // 每 tick 在 10 格内
REWARD_FACE_DRAGON = 0.01   // 每 tick 龙在视野内

// 龙息惩罚
REWARD_BREATH_PENALTY = -1.0 // 每 tick 在龙息云/火球 12 格内
```

## 关键技术栈

- **Minecraft**: 1.20.1
- **Fabric Loader**: >=0.19.3
- **Fabric API**: 0.92.9+1.20.1
- **Yarn Mappings**: 1.20.1+build.10 (named)
- **Java**: 17+
- **构建工具**: Fabric Loom (Gradle)

## Mixin 注意事项

1. `@Overwrite` 注解必须在 `dragonkiller.mixins.json` 中设置 `"requireAnnotations": true`（已配置）
2. 服务端 Mixin 和客户端 Mixin 分开配置，客户端 Mixin 仅在 client 环境下加载
3. 修改 Minecraft 核心机制（生成、物品栏、饥饿值、末影龙 AI 等）需要使用 Mixin
4. 当前所有 Mixin 都注册在 `dragonkiller.mixins.json`
