# 需求文档：重力感应驱动视图分段变换

## 一、功能概述

通过重力传感器监听手机倾斜角度，定义 4 个固定目标角度区间。
当角度落入某区间时，调用 `update()` 将目标 View 旋转并平移，
使 View 相对用户方向保持一致（设备左倾时 View 顺时针，设备右倾时 View 逆时针），
使 **View 在各状态锚点对齐到活动区域指定角点**。

---

## 二、架构设计

### 2.1 Controller 与 View 解耦

- `TiltViewController` 直接绑定 `LifecycleOwner`（Activity 或 Fragment）
- View 通过独立的 `bind()` / `unbind()` 方法传入，可随时替换
- Controller 生命周期由 `LifecycleOwner` 管理，View 的绑定状态独立管理

### 2.2 传感器多实例说明

`SensorManager` 允许对同一硬件传感器注册多个 `SensorEventListener`，
每个 `TiltViewController` 持有独立 listener，多个实例可同时工作互不干扰。
**当前业务约束**：一个 Controller 实例只绑定一个 View。

---

## 三、初始化设计

### 3.1 构造函数

构造函数**只接收 LifecycleOwner 和配置，不执行任何副作用**：

```kotlin
class TiltViewController(
    private val lifecycleOwner: LifecycleOwner,
    private val config: TiltViewConfig = TiltViewConfig()
) : DefaultLifecycleObserver {

    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }
}
```

### 3.2 bind() 方法

绑定目标 View 和活动区域：

```kotlin
fun bind(view: View, boundary: RectF? = null): TiltViewController
```

**bind() 内部执行流程**：

1. 将 `targetView` 赋值为传入的 `view`
2. 处理活动区域：
   - `boundary != null` → 直接使用
   - `boundary == null` → 取 `view.parent as? ViewGroup`，调用 `getGlobalVisibleRect()` 获取父布局屏幕坐标，转为 `RectF`
   - 父布局也无法获取 → `boundaryRect` 保持 null，后续跳过边界约束
3. 若当前生命周期已处于 `RESUMED` 状态，立即触发一次 `update()` 初始化 View 位置
4. 返回 `this`，支持链式调用

### 3.3 unbind() 方法

```kotlin
fun unbind()
```

- `targetView = null`
- `boundaryRect = null`
- 取消进行中的动画

### 3.4 监听开关 API（手动控制）

```kotlin
fun startListening(): TiltViewController
fun stopListening(): TiltViewController
fun isListeningEnabled(): Boolean
fun isListeningActive(): Boolean
```

- `startListening()`：开启监听开关；若生命周期已在 `STARTED/RESUMED`，立即注册传感器
- `stopListening()`：关闭监听开关并注销传感器（幂等），同时将 View 复位到默认竖持状态（`STATE_D`）
- `isListeningEnabled()`：返回监听开关状态
- `isListeningActive()`：返回当前是否已完成传感器注册（受生命周期与开关共同影响）

---

## 四、生命周期回调

| 回调 | 执行操作 |
|------|---------|
| `onCreate` | 若监听开关开启则注册传感器（`SensorManager.registerListener`） |
| `onResume` | 若监听开关开启则注册传感器；且 `targetView != null` 时触发一次 `update()` |
| `onPause` | 注销传感器，取消进行中动画 |
| `onDestroy` | 调用 `unbind()`，移除 lifecycle observer，释放所有资源 |

---

## 五、update() 设计

### 5.1 触发时机

每次检测到当前倾斜角度所在区间发生变化时，调用 `update()`。
`onResume` 和 `bind()` 时也主动调用一次，确保 View 位置与当前倾斜状态一致。

### 5.2 update() 内部流程

```
1. 通过 isViewReady() 检查 View 合法性 → 不通过则直接返回
2. 计算当前区间对应的目标 rotation 角度
3. 根据 rotation 和 boundaryRect 计算目标 translationX / translationY（见第六节）
4. 与 View 当前的 rotation / translationX / translationY 对比：
   - 差值均在 epsilon（0.5f）以内 → 视为已在目标位置，跳过动画
   - 否则 → 取消当前动画，启动新动画过渡到目标值
```

### 5.3 View 合法性检查（isViewReady）

执行任何 View 操作前必须全部通过：

```kotlin
private fun isViewReady(): Boolean {
    val view = targetView ?: return false
    if (!view.isAttachedToWindow) return false
    if (view.width <= 0 || view.height <= 0) return false
    if (view.visibility != View.VISIBLE) return false
    return true
}
```

检查不通过时静默跳过，不抛异常。

---

## 六、目标位置几何计算

### 6.1 核心规则

> **View 在各状态使用对应锚点，对齐到活动区域指定角点。**

View 以自身中心为 pivot 进行旋转（Android 默认行为），
旋转后锚点实际坐标会偏移，需要通过 translationX/Y 补偿回来。

### 6.2 各区间目标 rotation

| 区间 | 目标 rotation |
|------|--------------|
| State D（默认竖直） | 0° |
| State A（左倾 90°） | +90° |
| State B（右倾 90°） | -90° |
| State C（倒置 180°） | 180° |

### 6.3 translationX / translationY 计算方法

设：
- View 宽 = `w`，高 = `h`
- View 中心在父布局中的原始坐标 = `(cx, cy)`
- 目标对齐点（活动区角点）坐标 = `(tx, ty)`

活动区角点编号（顺时针）：
- `1` = 左上
- `2` = 右上
- `3` = 右下
- `4` = 左下

各区间的目标对齐角点（活动区）：

| 区间 | 目标角点 |
|------|---------|
| State D（0°） | 4（左下） |
| State A（+90°） | 1（左上） |
| State B（-90°） | 3（右下） |
| State C（180°） | 2（右上） |

各区间锚点相对 View 中心偏移（`offsetX`, `offsetY`）：

| 区间 | θ | offsetX | offsetY |
|------|---|---------|---------|
| 默认 0° | 0° | `-w/2` | `h/2` |
| 左倾 +90° | +90° | `-h/2` | `-w/2` |
| 右倾 -90° | -90° | `h/2` | `w/2` |
| 倒置 180° | 180° | `w/2` | `-h/2` |

平移公式：
```
translationX = tx - cx - offsetX
translationY = ty - cy - offsetY
```

实现时直接按以上公式计算，无需运行时三角函数（均为 0°/90°/180° 特殊角）。

### 6.4 boundaryRect 为 null 时

跳过位置计算，`translationX = 0f`，`translationY = 0f`，只应用 rotation。

---

## 七、角度区间定义

| 区间 | 目标角度 | 触发范围 | 对应状态 |
|------|---------|---------|---------|
| State D 默认 | 0° | -20° ~ +20° | rotation = 0° |
| State A 左90° | +90° | +70° ~ +110° | rotation = +90° |
| State B 右90° | -90° | -70° ~ -110° | rotation = -90° |
| State C 倒置 | ±180° | >+160° 或 <-160° | rotation = 180° |

- 触发范围 = 目标角度 ± `zoneTolerance`（默认 20°）
- 区间之间为过渡区，保持当前状态不动
- 离开区间需额外偏移 `hysteresis`（默认 5°）才切换（迟滞防抖）
- 新区间需连续稳定 `stateDebounceMs`（默认 160ms）后才触发动画（延迟确认防抖）

---

## 八、传感器角度计算

- 优先使用 `Sensor.TYPE_GRAVITY`，降级使用 `Sensor.TYPE_ACCELEROMETER`
- 采样频率：`SensorManager.SENSOR_DELAY_GAME`
- Roll 角：`atan2(x, y)`，范围 -180° ~ +180°
- 低通滤波：`smoothed += (raw - smoothed) * smoothFactor`（默认 0.15f）

---

## 九、数据类定义

```kotlin
data class TiltViewConfig(
    /**
     * 各角度区间的触发容差（单位：度）。
     * 触发范围 = 目标角度 ± zoneTolerance。
     * 例如默认值 20f 表示：左倾 90° 区间的实际触发范围为 70° ~ 110°。
     */
    val zoneTolerance: Float = 20f,

    /**
     * 迟滞防抖容差（单位：度）。
     * 离开当前区间时，需要额外偏移 hysteresis 才判定为切换，防止在区间边界抖动时反复触发动画。
     * 例如默认值 5f 表示：进入左倾 90° 区间需 > 70°，退出则需 < 65°。
     */
    val hysteresis: Float = 5f,

    /**
     * 低通滤波平滑系数，范围建议 0.05f ~ 0.3f。
     * 值越小传感器数据越平滑但响应越慢，值越大响应越灵敏但抖动越明显。
     * 公式：smoothed += (raw - smoothed) * smoothFactor
     */
    val smoothFactor: Float = 0.15f,

    /**
     * 区间切换时 View 过渡动画的时长（单位：毫秒）。
     * 动画使用 ViewPropertyAnimator + DecelerateInterpolator。
     */
    val animDuration: Long = 300L,

    /**
     * 状态切换延迟确认时间（单位：毫秒）。
     * 候选状态需连续稳定达到该时长才真正触发动画，避免短时间快速倾斜导致频繁切换。
     * 设置为 0L 可关闭延迟确认（保持即时切换）。
     */
    val stateDebounceMs: Long = 160L
)
```

> 不再包含 stateDefault / stateLeft90 等视图状态，
> 目标位置由几何公式自动计算，无需外部配置。

---

## 十、完整接入示例

```kotlin
class MyActivity : AppCompatActivity() {

    private lateinit var tiltController: TiltViewController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 传入 LifecycleOwner，自动绑定生命周期
        tiltController = TiltViewController(lifecycleOwner = this)

        // 绑定 View，活动区域自动取父布局
        tiltController.bind(binding.cardView)

        // 需要替换 View 时
        tiltController.bind(binding.anotherView, RectF(0f, 0f, 900f, 500f))

        // 解绑
        tiltController.unbind()
    }
}
```

---

## 十一、边界条件与注意事项

| 场景 | 处理方式 |
|------|----------|
| View 在 bind() 时宽高为 0 | `update()` 内 isViewReady() 不通过，静默跳过；待 View layout 完成后下次区间切换时自然触发 |
| boundaryRect 为 null | 只旋转，不平移（translationX/Y = 0） |
| 父布局获取失败 | 同上，boundaryRect = null |
| View 不可见 / 未 attach | isViewReady() 不通过，静默跳过 |
| 当前值已在目标位置 | update() 内比对 epsilon 后跳过动画 |
| 快速连续跨越区间 | 取消旧动画，直接动画到最新目标 |
| 传感器回调线程 | View 操作通过 `view.post { }` 切回主线程 |
| onDestroy 后 | targetView 置空，observer 移除，无内存泄漏 |

---

## 十二、验收标准

1. 构造函数执行完毕，无传感器注册等副作用
2. `bind()` 后，View 位置立即同步到当前倾斜状态
3. `unbind()` 后，View 不再响应倾斜变化
4. 手机竖直时，View rotation = 0°，对齐到 `boundary 4`
5. 左倾 90° 时，View rotation = +90°，对齐到 `boundary 1`
6. 右倾 90° 时，View rotation = -90°，对齐到 `boundary 3`
7. 倒置 180° 时，View rotation = 180°，对齐到 `boundary 2`
8. View 已在目标位置时，`update()` 不触发动画
9. 生命周期自动管理，无需手动 register/unregister
10. `onDestroy` 后无内存泄漏
11. `startListening()/stopListening()` 可重复调用且行为幂等，不会重复注册或抛异常
12. `stopListening()` 后 View 会回到默认竖持位置与角度（`STATE_D`）
