package com.vam.demov.tilt

import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.view.doOnLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * 重力感应驱动的 View 分段变换控制器。
 *
 * 通过重力传感器监听手机倾斜角度，定义 4 个固定目标角度区间（默认竖直 / 左倾 90° / 右倾 90° / 倒置 180°）。
 * 当角度落入某区间时，自动将绑定的 View 旋转并平移，使 View 的左下角始终与活动区域左下角对齐。
 *
 * 使用示例：
 * ```kotlin
 * tiltController = TiltViewController(lifecycleOwner = this)
 * tiltController.bind(binding.cardView)
 * ```
 *
 * 生命周期由 [LifecycleOwner] 自动管理，无需手动注册/注销传感器。
 */
class TiltViewController(
    private val lifecycleOwner: LifecycleOwner,
    private val config: TiltViewConfig = TiltViewConfig()
) : DefaultLifecycleObserver {

    // -------------------------------------------------------------------------
    // 日志
    // -------------------------------------------------------------------------

    companion object {
        const val TAG = "TiltViewController"
    }

    private fun logD(tag: String, content: String) = Log.d(tag, content)
    private fun logI(tag: String, content: String) = Log.i(tag, content)
    private fun logE(tag: String, content: String) = Log.e(tag, content)

    // -------------------------------------------------------------------------
    // 状态枚举
    // -------------------------------------------------------------------------

    /** 倾斜角度对应的视图状态 */
    private enum class TiltState {
        /** 默认竖直，rotation = 0° */
        STATE_D,
        /** 左倾 90°，rotation = +90°（顺时针） */
        STATE_A,
        /** 右倾 90°，rotation = -90°（逆时针） */
        STATE_B,
        /** 倒置 180°，rotation = 180° */
        STATE_C
    }

    // -------------------------------------------------------------------------
    // 传感器
    // -------------------------------------------------------------------------

    private val sensorManager: SensorManager by lazy {
        val context = lifecycleOwner as? Context
            ?: (lifecycleOwner as Activity)
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val rawRoll = rawRollFromValues(x, y)

            // 角度低通滤波（含 -180/+180 环绕处理）
            if (!hasSmoothedRoll) {
                smoothedRoll = rawRoll
                hasSmoothedRoll = true
            } else {
                val delta = normalizeRotation(rawRoll - smoothedRoll)
                smoothedRoll = normalizeRotation(smoothedRoll + delta * config.smoothFactor)
            }

            onRollUpdated(smoothedRoll)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** 当前平滑后的 Roll 角（度） */
    private var smoothedRoll: Float = 0f
    private var hasSmoothedRoll: Boolean = false
    private var sensorListeningEnabled: Boolean = true
    private var isSensorRegistered: Boolean = false

    // -------------------------------------------------------------------------
    // 状态回调（可选，供 Demo / 外部监听使用）
    // -------------------------------------------------------------------------

    /**
     * 区间切换时的回调，参数为区间名称（"D" / "A" / "B" / "C"）和当前 Roll 角（度）。
     * 在主线程回调。
     */
    var onStateChanged: ((stateName: String, roll: Float) -> Unit)? = null

    /**
     * 每帧 Roll 角更新回调（平滑后），用于 UI 实时显示角度。
     * 在主线程回调，频率与传感器采样一致，请勿在此做耗时操作。
     */
    var onRollChanged: ((roll: Float) -> Unit)? = null

    // -------------------------------------------------------------------------
    // View 绑定
    // -------------------------------------------------------------------------

    private var targetView: View? = null
    private var boundaryRect: RectF? = null
    // bind() 时传入的原始 boundary 参数，每次 update() 时重新解析为屏幕坐标
    private var pendingBoundary: RectF? = null

    // -------------------------------------------------------------------------
    // 区间状态
    // -------------------------------------------------------------------------

    private var currentState: TiltState = TiltState.STATE_D
    private var pendingState: TiltState? = null
    private var pendingStateSinceMs: Long = 0L
    private var pendingStateRoll: Float = 0f

    // -------------------------------------------------------------------------
    // epsilon：差值在此范围内视为已到达目标，跳过动画
    // -------------------------------------------------------------------------

    private val epsilon = 0.5f

    // -------------------------------------------------------------------------
    // 初始化：将自身注册到 LifecycleOwner
    // -------------------------------------------------------------------------

    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    // -------------------------------------------------------------------------
    // 生命周期回调
    // -------------------------------------------------------------------------

    override fun onCreate(owner: LifecycleOwner) {
        if (sensorListeningEnabled) {
            registerSensor()
            logD(TAG, "onCreate: 传感器已注册")
        } else {
            logD(TAG, "onCreate: 监听开关关闭，跳过注册")
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (sensorListeningEnabled) {
            registerSensor()
        }
        if (targetView != null) {
            // 用 post 延到下一帧，确保 layout 已完成、坐标有效
            targetView?.post {
                logD(TAG, "onResume: 触发一次 update() 同步当前倾斜状态")
                update(null)
            }
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        unregisterSensor()
        cancelAnimation()
        clearPendingState()
        logD(TAG, "onPause: 传感器已注销，动画已取消")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unbind()
        lifecycleOwner.lifecycle.removeObserver(this)
        logD(TAG, "onDestroy: 资源已释放")
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 绑定目标 View 和活动区域。
     *
     * @param view 要被控制旋转和平移的目标 View
     * @param boundary 活动区域（屏幕坐标 RectF）。
     *   为 null 时自动取 view 的父布局可见区域；父布局也无法获取时只旋转不平移。
     * @return this，支持链式调用
     */
    fun bind(view: View, boundary: RectF? = null): TiltViewController {
        targetView = view
        // 保存外部传入的 boundary 原始参数，用于每次 update() 时重新解析坐标
        pendingBoundary = boundary
        // 推迟到 layout 完成后执行首次 update，确保坐标有效
        view.doOnLayout {
            val lifecycleState = lifecycleOwner.lifecycle.currentState
            if (lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                logD(TAG, "bind.doOnLayout: 当前已 RESUMED，触发 update()")
                update(null)
            }
        }
        return this
    }

    /**
     * 解绑目标 View，停止响应倾斜变化。
     */
    fun unbind() {
        cancelAnimation()
        clearPendingState()
        targetView = null
        boundaryRect = null
        pendingBoundary = null
        logI(TAG, "unbind: 已解绑")
    }

    /**
     * 开启重力感应监听（可重复调用，幂等）。
     * 若当前生命周期已处于 STARTED/RESUMED，将立即注册传感器。
     */
    fun startListening(): TiltViewController {
        sensorListeningEnabled = true
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registerSensor()
        }
        if (targetView != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            targetView?.post { update(null) }
        }
        return this
    }

    /**
     * 关闭重力感应监听（可重复调用，幂等）。
     */
    fun stopListening(): TiltViewController {
        sensorListeningEnabled = false
        unregisterSensor()
        clearPendingState()
        cancelAnimation()
        val stateChanged = currentState != TiltState.STATE_D
        currentState = TiltState.STATE_D
        targetView?.post {
            if (stateChanged) {
                onStateChanged?.invoke(stateLabel(currentState), smoothedRoll)
            }
            update(null)
        }
        return this
    }

    /**
     * 监听功能开关是否打开（与生命周期无关）。
     */
    fun isListeningEnabled(): Boolean = sensorListeningEnabled

    /**
     * 传感器监听是否处于已注册状态（受生命周期和开关共同影响）。
     */
    fun isListeningActive(): Boolean = isSensorRegistered

    // -------------------------------------------------------------------------
    // 传感器注册 / 注销
    // -------------------------------------------------------------------------

    private fun registerSensor() {
        if (isSensorRegistered) return
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val sensor = gravity ?: accel
        if (sensor != null) {
            val success = sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
            isSensorRegistered = success
            if (success) {
                hasSmoothedRoll = false
                logD(TAG, "registerSensor: 使用传感器 ${sensor.name}")
            } else {
                logE(TAG, "registerSensor: 监听器注册失败")
            }
        } else {
            logE(TAG, "registerSensor: 设备不支持重力/加速度传感器")
        }
    }

    private fun unregisterSensor() {
        if (!isSensorRegistered) return
        sensorManager.unregisterListener(sensorListener)
        isSensorRegistered = false
    }

    // -------------------------------------------------------------------------
    // Roll 角计算与区间判断
    // -------------------------------------------------------------------------

    /** 从传感器 x/y 分量计算 Roll 角（度，范围 -180 ~ +180） */
    private fun rawRollFromValues(x: Float, y: Float): Float {
        return Math.toDegrees(Math.atan2(x.toDouble(), y.toDouble())).toFloat()
    }

    /**
     * 接收最新平滑 Roll 角，判断是否切换区间，切换时触发 update()。
     */
    private fun onRollUpdated(roll: Float) {
        val candidateState = resolveState(roll, currentState)
        // 回调 Roll 角更新（切回主线程）
        targetView?.post { onRollChanged?.invoke(roll) }

        if (candidateState == currentState) {
            clearPendingState()
            return
        }

        val debounceMs = config.stateDebounceMs
        if (debounceMs <= 0L) {
            commitStateSwitch(candidateState, roll)
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        if (pendingState != candidateState) {
            pendingState = candidateState
            pendingStateSinceMs = nowMs
            pendingStateRoll = roll
            logD(TAG, "onRollUpdated: 记录候选状态 $currentState → $candidateState（roll=${"%.1f".format(roll)}°）")
            return
        }

        pendingStateRoll = roll
        val stableDuration = nowMs - pendingStateSinceMs
        if (stableDuration >= debounceMs) {
            commitStateSwitch(candidateState, pendingStateRoll)
        }
    }

    private fun commitStateSwitch(newState: TiltState, triggerRoll: Float) {
        clearPendingState()
        logD(TAG, "onRollUpdated: 区间切换 $currentState → $newState（roll=${"%.1f".format(triggerRoll)}°）")
        currentState = newState
        targetView?.post {
            onStateChanged?.invoke(stateLabel(currentState), triggerRoll)
            update(triggerRoll)
        }
    }

    private fun clearPendingState() {
        pendingState = null
        pendingStateSinceMs = 0L
        pendingStateRoll = 0f
    }

    /** 将内部枚举转为对外的区间名称 */
    private fun stateLabel(state: TiltState): String = when (state) {
        TiltState.STATE_D -> "D"
        TiltState.STATE_A -> "A"
        TiltState.STATE_B -> "B"
        TiltState.STATE_C -> "C"
    }

    /**
     * 根据当前 roll 角和已处于的区间（含迟滞），解析出目标区间。
     *
     * 进入规则：roll 在 [目标角 - zoneTolerance, 目标角 + zoneTolerance] 内
     * 离开规则：需要额外偏移 hysteresis 才离开当前区间（防抖）
     */
    private fun resolveState(roll: Float, current: TiltState): TiltState {
        val zt = config.zoneTolerance
        val hz = config.hysteresis

        // 先检查当前区间是否仍然成立（迟滞扩大边界）
        val stayInCurrent = when (current) {
            TiltState.STATE_D -> roll in (-(zt + hz))..(zt + hz)
            TiltState.STATE_A -> roll in (90f - zt - hz)..(90f + zt + hz)
            TiltState.STATE_B -> roll in (-90f - zt - hz)..(-90f + zt + hz)
            TiltState.STATE_C -> roll >= (180f - zt - hz) || roll <= -(180f - zt - hz)
        }
        if (stayInCurrent) return current

        // 离开当前区间，检查能否进入新区间（使用标准 zoneTolerance）
        return when {
            roll in (-zt)..zt -> TiltState.STATE_D
            roll in (90f - zt)..(90f + zt) -> TiltState.STATE_A
            roll in (-90f - zt)..(-90f + zt) -> TiltState.STATE_B
            roll >= (180f - zt) || roll <= -(180f - zt) -> TiltState.STATE_C
            else -> current // 过渡区，保持不变
        }
    }

    // -------------------------------------------------------------------------
    // update()：核心更新逻辑
    // -------------------------------------------------------------------------

    /**
     * @param triggerRoll 触发本次切换的 Roll 角。
     *   仅用于日志。
     */
    private fun update(triggerRoll: Float?) {
        if (!isViewReady()) return

        val view = targetView ?: return
        // 每次更新都重新解析 boundary，保证父布局位置变化后坐标仍正确。
        boundaryRect = resolveBoundary(view, pendingBoundary)

        // 固定目标角（0 / ±90 / 180），动画总是走最短路径，避免累积到 540/810 这类角度。
        val targetRotation = rotationForState(currentState)
        val currentRotation = normalizeRotation(view.rotation)
        val rotationDelta = normalizeRotation(targetRotation - currentRotation)
        val (targetTx, targetTy) = translationForState(view, currentState)
        val txDelta = Math.abs(view.translationX - targetTx)
        val tyDelta = Math.abs(view.translationY - targetTy)

        if (Math.abs(rotationDelta) <= epsilon && txDelta <= epsilon && tyDelta <= epsilon) {
            logD(TAG, "update: 已在目标位置，跳过动画")
            return
        }

        cancelAnimation()
        logD(
            TAG,
            "update: state=$currentState triggerRoll=$triggerRoll current=$currentRotation " +
                "delta=$rotationDelta target=$targetRotation tx=$targetTx ty=$targetTy"
        )

        view.animate()
            .rotation(view.rotation + rotationDelta)
            .translationX(targetTx)
            .translationY(targetTy)
            .setDuration(config.animDuration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // 防止 rotation 数值持续累加，稳定后续判定与日志观察。
                view.rotation = normalizeRotation(view.rotation)
                logD(TAG, "update: 动画完成，view.rotation=${view.rotation}")
            }
            .start()
    }

    /**
     * 立即将当前状态应用到 View，不做动画。
     * 主要用于 stopListening 后快速回到默认位，避免过渡过程中的视觉偏移。
     */
    private fun applyCurrentStateImmediately() {
        if (!isViewReady()) return
        val view = targetView ?: return
        boundaryRect = resolveBoundary(view, pendingBoundary)
        val rotation = rotationForState(currentState)
        val (tx, ty) = translationForState(view, currentState)
        view.animate().cancel()
        view.rotation = rotation
        view.translationX = tx
        view.translationY = ty
        logD(TAG, "applyCurrentStateImmediately: state=$currentState rotation=$rotation tx=$tx ty=$ty")
    }

    /**
     * 将任意角度归一化到 (-180, 180]。
     * 用于计算两个旋转量之间的最短角度差。
     */
    private fun normalizeRotation(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    // -------------------------------------------------------------------------
    // 几何计算
    // -------------------------------------------------------------------------

    /**
     * 各区间对应目标 rotation 角度（Android rotation 正值 = 顺时针）。
     *   STATE_D 默认竖直：0°
     *   STATE_A 设备左倾 → View 顺时针旋转：+90°
     *   STATE_B 设备右倾 → View 逆时针旋转：-90°
     *   STATE_C 设备倒置：180°
     */
    private fun rotationForState(state: TiltState): Float = when (state) {
        TiltState.STATE_D -> 0f
        TiltState.STATE_A -> 90f
        TiltState.STATE_B -> -90f
        TiltState.STATE_C -> 180f
    }

    /**
     * 根据区间计算目标 translationX / translationY。
     *
     * 核心规则：按状态将 View 锚点对齐到活动区域对应角点。
     * View 以自身中心为 pivot 旋转，旋转后锚点偏移量（offsetX/offsetY）
     * 使用各特殊角（0°/±90°/180°）的预计算值，无需运行时三角函数。
     *
     * 坐标系说明：Android Y 轴向下，rotation 正值顺时针。
     *
     * boundary 角点编号（顺时针）：
     *   1=左上, 2=右上, 3=右下, 4=左下
     * 对齐目标：
     *   STATE_D -> boundary 4
     *   STATE_A -> boundary 1
     *   STATE_B -> boundary 3
     *   STATE_C -> boundary 2
     */
    private fun translationForState(view: View, state: TiltState): Pair<Float, Float> {
        val boundary = boundaryRect ?: return Pair(0f, 0f)

        val w = view.width.toFloat()
        val h = view.height.toFloat()

        // 使用父容器的屏幕偏移 + view.left/top（布局坐标）计算 View 中心的原始屏幕位置。
        // view.left/top 是 layout pass 确定的，不受 translation 和 rotation 影响，
        // 因此无论当前 View 旋转/平移到哪里，cx/cy 始终是稳定的布局原点。
        val parentLocation = IntArray(2)
        (view.parent as? View)?.getLocationOnScreen(parentLocation)
        val cx = parentLocation[0] + view.left + w / 2f
        val cy = parentLocation[1] + view.top + h / 2f

        val (targetX, targetY) = when (state) {
            TiltState.STATE_D -> Pair(boundary.left, boundary.bottom)   // 4
            TiltState.STATE_A -> Pair(boundary.left, boundary.top)      // 1
            TiltState.STATE_B -> Pair(boundary.right, boundary.bottom)  // 3
            TiltState.STATE_C -> Pair(boundary.right, boundary.top)     // 2
        }

        // 各状态锚点在旋转后的相对中心偏移。
        val (offsetX, offsetY) = when (state) {
            TiltState.STATE_D -> Pair(-w / 2f, h / 2f)
            TiltState.STATE_A -> Pair(-h / 2f, -w / 2f)
            TiltState.STATE_B -> Pair(h / 2f, w / 2f)
            TiltState.STATE_C -> Pair(w / 2f, -h / 2f)
        }

        val tx = targetX - cx - offsetX
        val ty = targetY - cy - offsetY
        logD(TAG, "translationForState: state=$state w=$w h=$h cx=$cx cy=$cy targetX=$targetX targetY=$targetY tx=$tx ty=$ty")
        return Pair(tx, ty)
    }

    // -------------------------------------------------------------------------
    // View 合法性检查
    // -------------------------------------------------------------------------

    private fun isViewReady(): Boolean {
        val view = targetView ?: return false
        if (!view.isAttachedToWindow) return false
        if (view.width <= 0 || view.height <= 0) return false
        if (view.visibility != View.VISIBLE) return false
        return true
    }

    // -------------------------------------------------------------------------
    // 动画取消
    // -------------------------------------------------------------------------

    private fun cancelAnimation() {
        targetView?.animate()?.cancel()
    }

    // -------------------------------------------------------------------------
    // boundary 解析
    // -------------------------------------------------------------------------

    /**
     * 解析活动区域：
     * - boundary 不为 null → 直接使用
     * - 否则取 view 父布局的屏幕可见区域
     * - 父布局不可用 → 返回 null（只旋转不平移）
     */
    private fun resolveBoundary(view: View, boundary: RectF?): RectF? {
        if (boundary != null) return boundary
        val parent = view.parent as? ViewGroup ?: return null
        val rect = android.graphics.Rect()
        val visible = parent.getGlobalVisibleRect(rect)
        if (!visible) return null
        return RectF(rect)
    }
}
