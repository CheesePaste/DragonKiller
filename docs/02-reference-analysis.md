# 参考项目分析

## 1. diamond_env (DreamerV3 挖钻石环境)

**路径**: `C:\Users\huhai\Desktop\Java-mcmod\diamond_env`

### 架构

```
example.py → DiamondEnv (task.py) → Env (env.py) → Backend (backend.py) → MineRL
```

- **Backend** 继承自 `minerl.herobraine.env_spec.EnvSpec`，用 XML 定义 Minecraft 任务
- **Env** 封装底层 MineRL，提供自定义 obs/act space、sticky actions、reward processing
- **DiamondEnv** 在 Env 上叠加 12 个里程碑奖励（从木头到钻石）

### 观察空间

```python
{
    'image':           (64,64,3) uint8,   # POV 视角
    'inventory':       (N,) float32,       # 各物品数量
    'inventory_max':   (N,) float32,       # 各物品最大数量（历史最大）
    'equipped':        (M,) float32,       # 主手物品 one-hot
    'health':          scalar float32,     # 归一化血量
    'hunger':          scalar float32,
    'breath':          scalar float32,
    'reward':          scalar float32,
    'is_first/last/terminal': bool,
}
```

### 动作空间

离散动作索引 → 映射为动作 dict：

```python
BASIC_ACTIONS = {
    'noop':        {},
    'attack':      {'attack': 1},
    'turn_up':     {'camera': (-15, 0)},
    'turn_down':   {'camera': (15, 0)},
    'turn_left':   {'camera': (0, -15)},
    'turn_right':  {'camera': (0, 15)},
    'forward':     {'forward': 1},
    'back':        {'back': 1},
    'left':        {'left': 1},
    'right':       {'right': 1},
    'jump':        {'jump': 1, 'forward': 1},
    'place_dirt':  {'place': 'dirt'},
}
```

+ DiamondEnv 叠加 craft/place/equip/smelt 动作，共 ~26 个离散动作。

### 奖励设计

```python
rewards = [
    CollectReward('log', once=1),
    CollectReward('planks', once=1),
    CollectReward('stick', once=1),
    # ... 12 个里程碑
    CollectReward('diamond', once=1),
    HealthReward(),    # 每 tick 血量变化 * 0.01
]
```

- `CollectReward`: once 是首次获得该物品的一次性奖励；repeated 是每多一个的边际奖励
- `HealthReward`: 血量变化 * 0.01，鼓励不掉血

### 关键设计点

1. **Sticky Attack**：攻击动作会持续 30 tick，防止 RL 以帧率频率点射
2. **Sticky Jump**：跳跃时同时按 forward，防止跳不起来
3. **Pitch Limit**：俯仰角限制在 ±60°，防止转晕
4. **Repeat**：每个动作重复执行 N 帧，降低控制频率
5. **RestartOnException**：异常时自动 reset，训练不中断

---

## 2. Minecraft-PVP-bot

**路径**: `C:\Users\huhai\Desktop\Java-mcmod\Minecraft-PVP-bot`

### 架构

```
Screen Capture (MSS) → CNN → PPO (SB3) → pydirectinput (键盘/鼠标模拟)
                              ↑
                       Reward: hit/miss detection
```

### 核心特点

| 方面 | 实现 |
|---|---|
| **不需要 Mod** | 纯外部控制，截屏 + 模拟输入 |
| **观察** | `84x84x3` RGB 屏幕截图，CNN 提取特征 |
| **动作** | 16 个离散动作（WASD + 攻击 + 视角 + 组合） |
| **算法** | PPO (stable-baselines3) |
| **奖励** | 基于屏幕分析：命中 +50，miss -10，连续 miss 额外 -15 |

### 动作空间 (16)

```
 0: noop         8: crouch
 1: forward      9: look left
 2: back        10: look right
 3: strafe left 11: look up
 4: strafe right12: look down
 5: attack      13: forward + attack
 6: jump        14: strafe left + attack
 7: sprint      15: strafe right + attack
```

### 关键设计点

1. **纯 Python**：无需 mod、无桥接服务，启动简单
2. **视觉命中检测**：通过分析屏幕中心区域的红色通道变化判断是否命中实体
3. **误判惩罚**：连续 miss 叠加惩罚，强迫 AI 提高瞄准精度
4. **攻击冷却**：每次攻击后 5 帧冷却，防止攻击 spam

### 局限性

1. **屏幕分析不可靠**：光照、视角、距离变化都会影响命中检测准确率
2. **模拟输入延迟**：`pydirectinput` 有额外延迟，不如 Mixin 直接操作游戏逻辑精确
3. **无法获取内部状态**：看不到实体血量、位置、装备等结构化数据
4. **需要窗口聚焦**：训练期间不能动鼠标键盘

---

## 3. 对我们的启示

### 应采用 diamond_env 的设计理念：

1. **结构化观察 > 像素观察**：坐标、血量、实体状态比 CNN 从像素学习高效得多
2. **离散动作空间**：diamond_env 证明了 10-30 个离散动作足矣完成复杂任务
3. **Sticky 机制**：防止 RL 以过高频率做细微动作
4. **里程碑奖励**：对于末影龙击杀这种极稀疏奖励场景，课程学习 + 子目标奖励是关键

### 应避免 PVP-bot 的：

1. **屏幕截图观察**：不可靠，且需要 CNN 增加计算量。我们通过 Mixin 直接读取游戏状态
2. **模拟输入**：延迟高、不可靠。通过 Mixin 直接调用游戏内部方法执行动作

### 应借鉴 PVP-bot 的：

1. **SB3 + PPO**：成熟的基线算法，开箱即用
2. **简洁的离散动作空间**：16 个动作足矣覆盖战斗需求
3. **TensorBoard 可视化**：实时监控训练进度

---

## 4. 最终方案确认

综合两个参考项目和我们的需求，当前方案定位：

```
Mod (Fabric Mixin)        ← 结构化状态 →       Python (SB3 PPO)
  ├─ 读取游戏状态                              ├─ Gym-style env wrapper
  ├─ 执行动作                                  ├─ 奖励计算
  ├─ Episode 管理                              ├─ TensorBoard 日志
  └─ TCP Socket 服务端                          └─ 模型保存/加载
```

比 diamond_env **更轻量**（不需要 MineRL 全套依赖），比 PVP-bot **更精确**（结构化数据而非像素）。

下一步：确认文档后开始写 Mod 端代码（Mixin + SocketServer + ObservationBuilder + ActionParser）。
