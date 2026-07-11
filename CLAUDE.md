# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Minecraft 1.20.1 Fabric 模组，目标是通过强化学习训练 AI 击杀末影龙。模组在 Fabric 端实现所有自定义逻辑，包括环境配置、奖励机制、动作空间以及训练接口。

## 项目需求

- **固定世界种子**：保证训练环境可复现 ✅
- **出生点**：AI 直接出生在末地 (30.5, 70, 30.5)，远离龙巢避免碰撞 ✅
- **初始装备**：全套钻石装备 + 钻石剑、方块、水桶、盾牌 ✅
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
│   └── dragon_env.py              # Gymnasium Env (21 动作, 583 维观察)
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
- [x] 观察收集 (player/dragon/endermen/inventory/terrain/raytrace/stats)
- [x] 动作执行 (21 离散动作, action_repeat=5, sticky_attack=10)
- [x] 奖励计算 (密集+稀疏, delta 追踪)
- [x] Episode 管理 (RUNNING/DONE, 超时6000tick)
- [x] Python env wrapper (gymnasium, protocol)
- [x] 构建通过 (`./gradlew build` SUCCESS)

## Phase 2 — 基线训练

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
