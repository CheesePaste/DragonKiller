# Minecraft AI 战斗武器课程学习与混合训练方案

## 1. 核心问题：如何分阶段学习且防止“灾难性遗忘”

在强化学习中，如果我们直接让 AI 在全新的环境（如纯飞龙阶段）下从头训练远程武器，或者在很长一段时间内只接触远程攻击，AI 会发生**灾难性遗忘 (Catastrophic Forgetting)**——即随着策略网络参数的更新，AI 会完全忘记在第一阶段学到的“走位贴脸、用钻石剑爆发输出”的近战技巧。

为了实现“第一阶段不影响近战 -> 第二阶段学弩 -> 最后汇总且不遗忘剑的使用”，我们需要在**动作空间设计**、**课程阶段划分**以及**多环境混合训练**三个维度上进行联合设计。

---

## 2. 核心设计方案

### 2.1 动作空间的一致性 (Action Space Consistency)
为了能够让后一阶段的训练**直接加载**前一阶段训练好的模型权重（`PPO.load()`），各个训练阶段的动作空间形状和定义**必须完全相同**。
* **统一动作空间 (7维)**：从第一阶段开始，动作空间就定义为 7 维（包含近战、移动、跳跃和远程射击 `aShoot`）。
* **阶段屏蔽机制**：在第一阶段，虽然神经网络输出 `aShoot` 动作，但 Java 服务端直接将其屏蔽（不给予弩，静默忽略射击动作）。由于该动作头没有任何环境反馈和奖励，PPO 会自然将其学习为“无效动作”（No-op），不影响近战剑的学习。

---

### 2.2 课程学习阶段划分 (Curriculum Stages)

我们通过在 JVM 启动参数中引入 `-Drlcombatmode=melee_only|ranged_only|mixed` 来控制环境的教学阶段：

```
                    ┌─────────────────────────┐
                    │  Stage 1: 纯近战基础训练  │
                    │  - 龙静止/坐下           │
                    │  - 弩箭禁用             │
                    └────────────┬────────────┘
                                 │  (加载权重继续训练)
                                 ▼
                    ┌─────────────────────────┐
                    │  Stage 2: 纯远程打靶训练  │
                    │  - 龙悬空静止           │
                    │  - 弩箭开启/剑无法触及    │
                    └────────────┬────────────┘
                                 │  (加载权重混合训练)
                                 ▼
                    ┌─────────────────────────┐
                    │  Stage 3: 混合并行多任务  │
                    │  - 混合环境并行训练      │
                    │  - 远近战交替，自适应决策 │
                    └─────────────────────────┘
```

#### 详细阶段设计：

1. **Stage 1 (纯近战基础阶段)**：`combat_mode = melee_only`
   * **环境配置**：末影龙处于静态/坐下状态。玩家仅装备钻石剑。
   * **动作屏蔽**：弩的发射指令被服务端忽略，`ranged_cooldown` 始终返回 `1.0`。
   * **目标**：AI 集中所有资源学习如何快速接近龙、管理视线、使用钻石剑进行高效近战输出。

2. **Stage 2 (远程狙击打靶阶段 - 可选)**：`combat_mode = ranged_only`
   * **环境配置**：末影龙被冻结在空中（例如 Y=85 的高空），钻石剑完全无法触及。
   * **动作屏蔽**：开启弩射击判定，装备弩。
   * **目标**：AI 无法通过近战获得任何奖励，被强迫集中学习如何转动视角瞄准、补偿重力并使用弩箭进行射击。此阶段可以非常快速地激活 AI 对 `aShoot` 动作头的映射。

3. **Stage 3 (混合武器终极汇总阶段)**：`combat_mode = mixed`
   * **环境配置**：开启完全解冻的移动龙 AI，龙在飞翔和降落/坐下状态间自由交替。
   * **目标**：AI 汇总前两个阶段的技能，在远距离自动使用弩箭磨血，在龙落地时自动冲锋并换成钻石剑爆砍。

---

### 2.3 终极防遗忘手段：混合环境并行训练 (Mixed-Environment Training)

即使我们做好了分阶段加载，如果在 Stage 3 训练中飞龙状态持续时间太长，AI 依然可能慢慢磨灭近战剑的记忆。

**【最佳实践】使用多实例混合环境（Vectorized Env Mixing）**：
* 阶段三训练时，我们在 Python 端启动 6 个并行的 Minecraft 实例（`SubprocVecEnv`），但将它们配置为**不同的战斗模式**：
  * **2 个实例** 运行 `melee_only` 战斗模式（末影龙强制处于降落或坐下状态，AI 必须用剑）。
  * **4 个实例** 运行 `mixed` 战斗模式（龙正常飞行和交替）。
* **原理**：由于 PPO 算法同时从这 6 个实例收集样本并混合成一个 Batch 进行梯度更新，策略网络在更新远程射击参数的同时，**源源不断地收到来自近战实例的近战奖励和梯度更新**。
* **效果**：这彻底从数学上避免了“灾难性遗忘”，强制神经网络在同一套参数内压缩近战与远程的双重策略，最终收敛出完美的混合决策体。

---

## 3. 实现指南与代码结构变更

### 3.1 Java 端：引入战斗模式参数与逻辑屏蔽
在 `RLConfig.java` 中引入 `CombatMode`：

```java
public enum CombatMode {
    MELEE_ONLY,  // 仅近战 (Stage 1)
    RANGED_ONLY, // 仅远程 (Stage 2)
    MIXED        // 混合 (Stage 3)
}

public class RLConfig {
    public static final CombatMode COMBAT_MODE = CombatMode.valueOf(
        System.getProperty("rlcombatmode", "MIXED").toUpperCase()
    );
    // ...
}
```

在 `ActionParser.java` 中限制射击：
```java
private static void performRangedAttack(ServerPlayerEntity player, ServerWorld world) {
    // 若配置为 MELEE_ONLY，直接禁用弩箭发射
    if (RLConfig.COMBAT_MODE == CombatMode.MELEE_ONLY) {
        return; 
    }
    // ... 原有射击仿真代码 ...
}
```

在 `RLTickHandler.java` 中限制近战与初始化装备：
```java
private static void giveBotEquipment() {
    // 钻石剑始终给
    botPlayer.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD));

    // 只有在非纯近战模式下才在副手装备弩
    if (RLConfig.COMBAT_MODE != CombatMode.MELEE_ONLY) {
        botPlayer.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.CROSSBOW));
    }
    // ... 盔甲 ...
}
```

同时，若在 `RANGED_ONLY` 模式下，可以在重置龙时将其传送至高空并冻结：
```java
// RLTickHandler.handleReset()
if (RLConfig.COMBAT_MODE == CombatMode.RANGED_ONLY) {
    newDragon.setPosition(0.0, 85.0, 0.0);
    // 强制冻结其 AI 行为，作为固定高空靶子
    newDragon.getPhaseManager().setPhase(PhaseType.HOVER); 
}
```

---

### 3.2 Python 端：VecEnv 混合启动配置
在 `train_p2.py` 中，通过端口动态为不同的 Minecraft 实例传递启动参数：

```python
# train/train_p2.py

def make_env(host, port, seed, combat_mode="mixed"):
    def _init():
        # 将 combat_mode 作为环境变量或参数传入，以便启动对应的 MC 实例
        # 例如通过不同的 TCP 端口，或者在启动脚本中带上 -Drlcombatmode={combat_mode}
        env = DragonEnv(host=host, port=port)
        env = Monitor(env)
        return env
    return _init

# 并行 6 个环境的混合配置：
# 实例 0, 1: 强制 melee_only (维护近战记忆)
# 实例 2, 3, 4, 5: 混合 mixed 战斗模式 (学习远程与自适应)
env_fns = []
for i in range(config.n_envs):
    port = config.port + i
    mode = "melee_only" if i < 2 else "mixed"
    env_fns.append(make_env(config.host, port, config.seed, combat_mode=mode))
```

---

## 4. 总结与过渡方案

通过上述方案，您可以极为丝滑地实现分步汇总学习：
1. **第一阶段**：使用 `-Drlcombatmode=melee_only` 训练纯近战（生成 `best_melee.zip` 权重模型）。
2. **第二阶段**：加载 `best_melee.zip` 权重，使用混合环境启动参数（2个实例设为 `melee_only`，4个实例设为 `mixed`），继续微调训练。
3. **汇总表现**：AI 既不会丢失近战贴脸的高 DPS 肌肉记忆，又会在飞龙离得远时自发端起弩箭进行远程抛物线狙击。
