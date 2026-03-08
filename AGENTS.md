# 项目规范文档（Codex）

本规范参考 `CLAUDE.md`，用于 Codex 在本项目中的协作与实现。

## 基本约定

- **回复语言**：始终使用中文回复
- **项目性质**：功能/View 的独立 Demo 项目，开发完成后接入工作项目
- **UI 框架**：传统 Android View 体系（XML 布局 + View/ViewGroup），禁止使用 Jetpack Compose
- **规范文档同步**：新增或修改规则时，必须同时更新 `CLAUDE.md` 和 `AGENTS.md` 两个文档，两份内容保持一致
- **当前状态更新**：每次完成开发任务后，必须同步更新规范文档中的"当前状态"章节，反映最新进度
- **自动化执行参数**：
  - Claude 执行前固定使用 `--dangerously-skip-permissions`
  - Codex 执行前固定使用 `--dangerously-bypass-approvals-and-sandbox`

## 项目信息

- **包名**：`com.vam.demov`
- **minSdk**：24
- **targetSdk**：36
- **语言**：Kotlin
- **构建系统**：Gradle Kotlin DSL（`.kts`）

## 当前状态

### 第一个工具：TiltViewController（已完成）

assembleDebug 构建通过，实现了重力感应驱动 View 分段变换功能：

**新增文件：**
- `app/src/main/java/com/vam/demov/tilt/TiltViewConfig.kt` — 配置数据类
- `app/src/main/java/com/vam/demov/tilt/TiltViewController.kt` — 核心控制器
- `app/src/main/res/drawable/bg_boundary_area.xml` — boundary 区域背景
- `app/src/main/res/drawable/bg_target_view.xml` — 目标 View 背景

**修改文件：**
- `app/src/main/java/com/vam/demov/MainActivity.kt` — 接入 TiltViewController Demo
- `app/src/main/res/layout/activity_main.xml` — 添加演示 UI（角度显示、状态标签、boundary 区域、目标 View）

**依赖（全部 androidx，无三方库）：** `core-ktx`、`appcompat`、`constraintlayout`、`lifecycle-viewmodel-ktx`、`lifecycle-livedata-ktx`、`lifecycle-runtime-ktx`

**主题：** `Theme.AppCompat.Light.DarkActionBar`（纯 androidx，无 MaterialComponents）

**最新更新（2026-03-07）：**
- 已按交互定义修正 Tilt 方向语义：设备左倾（顶部到左侧）时 View 顺时针 `+90°`，设备右倾时 View 逆时针 `-90°`
- 已同步更新 `tilt_view_controller_requirement.md` 验收与角度映射说明，避免文档与实现方向定义不一致
- 已修复 `roll` 在 `-180/+180` 边界时的方向误判，避免跨边界触发 270° 异常大角度旋转
- 已修复生命周期恢复问题：`onPause` 注销后，`onResume` 会重新注册传感器并继续感应触发
- 已将传感器低通滤波升级为“角度环绕感知”实现，避免 `+179°/-179°` 跨界时平滑值穿越整圈导致状态抖动
- 已将旋转动画改为按目标角最短路径执行，并在动画结束后归一化 `view.rotation`，避免累积到 `540°/810°`
- 新增 `scripts/install_connected_device.sh` 自动化脚本：`assembleDebug` 成功后仅安装到当前已连接真机（过滤 emulator，不主动启动模拟器）
- 已在当前环境验证脚本可用：检测到 1 台真机并通过 `adb install -r` 安装成功
- 脚本已升级为“安装后自动启动”：安装完成后自动执行 `am start` 拉起 `com.vam.demov/.MainActivity`
- 已新增状态切换延迟确认：候选区间需稳定 `stateDebounceMs`（默认 160ms）才触发动画，降低短时间连续倾斜导致的频繁切换
- 已完成平移锚点接入：按状态将 `View 4` 分别对齐到活动区 `4/1/3/2`（D/A/B/C），实现“相对用户左下角”定位
- `update()` 已接入 boundary 解析与 rotation/translation 联合阈值判断，避免只旋转不平移或无效动画
- 已修正横屏态锚点映射：左倾改为 `View 4 -> boundary 3`、右倾改为 `View 4 -> boundary 1`，避免平移后 View 划出活动区域
- 已按实机反馈纠正 A/B 平移方向：左倾对齐 `boundary 1`、右倾对齐 `boundary 3`，修复“左右方向反了”的体验问题
- 已放开 Demo 视图测量约束：`FourCornerView` 在 `AT_MOST` 下不再被父布局截断，支持宽/高大于活动区域
- 已在 `boundaryArea` 开启 `clipChildren=false`，允许目标 View 越界绘制；保持“按现有逻辑计算并对齐锚点”的结果
- 已新增监听控制 API：`startListening()` / `stopListening()` / `isListeningEnabled()` / `isListeningActive()`
- 监听控制与生命周期联动：监听开关关闭时 `onCreate/onResume` 不自动注册；重复开启/关闭幂等
- `TiltDemoActivity` 新增“监听开关”按钮：实时展示监听状态（开启/停止/注册中），点击可在开启与停止之间切换
- 已新增停止监听复位行为：`stopListening()` 时强制回到 `STATE_D`，View 旋转和平移恢复为默认竖持位置

### 第二个工具：DialogTopLiftController（已完成）

assembleDebug 构建通过，实现了"Dialog 底部弹出后，驱动页面内目标 View 向上平移等量距离"功能：

**核心设计：**
- 目标 View 与 Dialog 相互独立：目标 View 位于页面布局的 FrameLayout 活动区内，Dialog 仅提供高度参考
- Dialog `onShowListener` 回调时读取内容区高度（此时高度已可靠），驱动目标 View 向上平移等量距离
- Dialog dismiss 时自动复位目标 View

**新增文件：**
- `app/src/main/java/com/vam/demov/dialog/DialogTopLiftConfig.kt` — 配置数据类（动画时长、复位时长、活动区位置）
- `app/src/main/java/com/vam/demov/dialog/DialogTopLiftController.kt` — 核心控制器
- `app/src/main/java/com/vam/demov/DialogTopLiftDemoActivity.kt` — 独立演示页面
- `app/src/main/res/layout/activity_dialog_top_lift_demo.xml` — Demo 页面布局（ConstraintLayout + FrameLayout 活动区 2/3 高度 + FourCornerView 左下角）
- `app/src/main/res/layout/dialog_top_lift_content.xml` — Dialog 内容布局（底部弹出，含把手条、标题、示意内容区）
- `app/src/main/res/drawable/bg_dialog_panel.xml` — Dialog 面板背景

**修改文件：**
- `app/src/main/java/com/vam/demov/MainActivity.kt` — 新增第二个工具入口
- `app/src/main/res/layout/activity_main.xml` — 新增入口按钮
- `app/src/main/AndroidManifest.xml` — 注册 `DialogTopLiftDemoActivity`
- `app/src/main/res/values/strings.xml` — 新增第二个工具文案资源

**对外 API（DialogTopLiftController）：**
- `bindTarget(view)` — 绑定目标 View（页面布局中的 View）
- `attachDialog(dialog)` — 附加 Dialog，自动注册 show/dismiss 监听
- `liftUp(deltaY)` — 手动触发上移（attachDialog 内部调用）
- `resetDown()` — 复位到初始位置（dismiss 自动调用）
- `release()` — 释放引用（onDestroy 调用）

**最新更新（2026-03-08）：**
- 重新设计：目标 View 从 Dialog 内部移到页面布局的 FrameLayout 活动区，与 Dialog 解耦
- 活动区 FrameLayout 高度占父布局 2/3，clipChildren=true，超出不显示
- 目标 View（FourCornerView）固定在活动区左下角（layout_gravity="bottom|start"）
- Controller 通过 `android.R.id.content` 获取 Dialog 内容区高度，方案 A（onShowListener 时取高度，可靠稳定）
- assembleDebug 构建通过

### 第三个工具：UploadProgressFragment（已完成）

assembleDebug 构建通过，实现了“可对外调用的上传进度 UI（Fragment 载体 + 自绘进度条）”功能：

**新增文件：**
- `app/src/main/java/com/vam/demov/upload/UploadProgressConfig.kt` — 配置数据类
- `app/src/main/java/com/vam/demov/upload/UploadProgressBarView.kt` — 自绘水平进度条 View（非系统 ProgressBar）
- `app/src/main/java/com/vam/demov/upload/UploadProgressFragment.kt` — 对外 API 载体 Fragment
- `app/src/main/java/com/vam/demov/UploadProgressDemoActivity.kt` — 独立演示页面
- `app/src/main/res/layout/fragment_upload_progress.xml` — 上传进度 Fragment 布局
- `app/src/main/res/layout/activity_upload_progress_demo.xml` — Demo 页面布局
- `app/src/main/res/drawable/bg_upload_progress_panel.xml` — 进度容器背景
- `app/src/main/res/drawable/bg_upload_close_button.xml` — 关闭按钮背景
- `app/src/main/res/drawable/bg_upload_thumb_placeholder.xml` — 完成态缩略图占位图

**修改文件：**
- `app/src/main/java/com/vam/demov/MainActivity.kt` — 新增第三个工具入口
- `app/src/main/res/layout/activity_main.xml` — 新增入口按钮
- `app/src/main/AndroidManifest.xml` — 注册 `UploadProgressDemoActivity`
- `app/src/main/res/values/strings.xml` — 新增第三个工具文案与状态文案
- `app/src/main/res/values/attrs.xml` — 新增 `UploadProgressBarView` 自定义属性

**最新更新（2026-03-07）：**
- 已实现 Fragment 对外调用 API：`applyConfig()` / `updateProgress()` / `markUploadCompleted()` / `close()` / `resetAndShow()`
- 已实现自绘水平矩形进度条，支持宽高、底色、进度色、圆角配置，不依赖系统 ProgressBar
- 已实现“上传中/上传完成”状态文案切换，完成后显示右侧缩略图
- 已新增缩略图点击回调：`setOnThumbnailClickListener`，具体点击行为由调用方实现
- 已实现 close 行为：支持开关关闭动画；关闭后整体 View `GONE`，并按配置禁用后续进度更新
- Demo 页已提供“开始模拟上传 / 直接完成 / 关闭进度条（带动画开关）”验证流程
- 已完成样式简化：进度条作为底层铺满父容器（`match_parent`），状态文案/缩略图/关闭按钮改为前景层覆盖在进度条之上
- 已按 `TiltViewController` 风格补齐第三个工具核心注释：类注释、公开 API 注释、分区注释与关键逻辑说明
- 已将上传进度动画插值器改为单一平滑 `easeInOut` 曲线，替换“前段线性、末段减速”效果，提升每次进度更新的连续性
- 已将进度动画升级为“按帧追赶最新目标值”方案，避免 300ms 高频更新时反复重启动画；对单次 `+20` 等大步进也能保持自然平滑

### 第四个工具：CenterTabView（已完成）

assembleDebug 构建通过，实现了"居中选中 Tab 控件（平移动画 + 字重切换 + 圆点指示器）"功能：

**新增文件：**
- `app/src/main/java/com/vam/demov/tab/CenterTabConfig.kt` — 配置数据类
- `app/src/main/java/com/vam/demov/tab/CenterTabView.kt` — 核心自定义 ViewGroup
- `app/src/main/java/com/vam/demov/CenterTabDemoActivity.kt` — 独立演示页面
- `app/src/main/res/layout/activity_center_tab_demo.xml` — Demo 页面布局

**修改文件：**
- `app/src/main/java/com/vam/demov/MainActivity.kt` — 新增第四个工具入口
- `app/src/main/res/layout/activity_main.xml` — 新增入口按钮
- `app/src/main/AndroidManifest.xml` — 注册 `CenterTabDemoActivity`
- `app/src/main/res/values/strings.xml` — 新增第四个工具文案资源
- `app/src/main/res/values/attrs.xml` — 新增 `CenterTabView` styleable 声明

**设计要点（2026-03-08）：**
- 控件继承 `ViewGroup`，宽度铺满父布局，高度自适应（item 高 + 圆点间距 + 圆点直径）
- item 宽度由文字内容 wrap_content 决定（非等分槽位），`onLayout` 记录每个 item 的实际 left 坐标到 `itemLefts`
- 切换 tab 时整体内容通过 `translationX` 平移动画使目标 item 对齐控件中心；居中公式：`tx = width/2 - (itemLeft + itemWidth/2)`
- 字重切换使用 `paint.isFakeBoldText`（非 `setTypeface`）：仅影响绘制层，**不触发 `requestLayout()`**，不会打断正在进行的平移动画，消除切换闪烁
- 圆点固定绘制在控件水平中心底部（`onDraw`），选中 item 已对齐中心，圆点自然落在其下方
- 对外 API：`setItems()` / `selectTab()` / `applyConfig()` / `setOnTabSelectedListener()` / `enableItemClick()` / `disableItemClick()` / `isItemClickEnabled()`
- 点击开关：`enableItemClick()` / `disableItemClick()` 动态禁用/恢复用户点击，程序调用 `selectTab()` 不受影响；幂等设计，重复调用安全
- 点击防抖：点击回调中检查 `runningAnimator?.isRunning`，动画执行期间忽略点击，动画结束后恢复响应；复用已有动画句柄，无额外字段
- Demo 页提供“上一个/下一个”及直接跳转 Tab 0~1 共 4 个操作按钮验证效果
- Demo 场景：相机"视频/照片"切换，`itemPaddingHorizontalDp = 6f` 紧凑间距，默认选中"照片"（index 1）

**最新更新（2026-03-08）：**
- 已按实现修正第四工具文档：等分槽位描述改为 `wrap_content` 实际布局；Demo 验证流程改为 2 tab + 4 按钮
- 已修正 `CenterTabView Demo` 初始状态文案为“当前选中：[1] 照片”，与默认选中项一致
- 已确认 `CLAUDE.md` 与 `AGENTS.md` 保持同步

### 第五个工具：CameraPreviewActivity 集成 Demo（已完成）

assembleDebug 构建通过，将前四个工具集成到水印相机预览界面，验证各组件协作效果。

**新增文件：**
- `app/src/main/java/com/vam/demov/camera/CameraPreviewActivity.kt` — 集成 Activity（Camera2 预览 + 四工具联动）
- `app/src/main/java/com/vam/demov/camera/CameraEngine.kt` — Camera2 预览引擎（打开/关闭/切换前后摄/闪光灯）
- `app/src/main/res/layout/activity_camera_preview.xml` — 预览界面布局（TopBar / 预览区 / BottomBar / FourCornerView / 上传条）
- `app/src/main/res/layout/dialog_camera_gallery.xml` — 相册 Dialog 内容布局
- 若干 drawable：`ic_arrow_back`、`ic_flash_on`、`ic_flash_off`、`ic_flip_camera`、`bg_capture_button`、`bg_gallery_button`

**修改文件：**
- `app/src/main/java/com/vam/demov/MainActivity.kt` — 新增第五个工具入口
- `app/src/main/res/layout/activity_main.xml` — 新增入口按钮
- `app/src/main/AndroidManifest.xml` — 注册 `CameraPreviewActivity`（LAUNCHER / portrait / NoActionBar）
- `app/src/main/res/values/strings.xml` — 新增相机界面文案资源

**集成关系（2026-03-08）：**
- `TiltViewController`：绑定 `FourCornerView`，活动区取 `cameraPreviewContainer` 屏幕坐标；Gallery Dialog 弹出时 `stopListening()`，关闭后 `startListening()`
- `DialogTopLiftController`：绑定 `FourCornerView`，附加 Gallery Dialog；弹出时 FourCornerView 上移对齐 Dialog 顶部，关闭后复位
- `UploadProgressFragment`：挂载到 `uploadProgressContainer`，循环模拟进度（每 300ms 随机 +1..10，完成后短暂展示并自动隐藏）
- `CenterTabView`：BottomBar 内"视频/拍照"Tab 切换，默认选中"拍照"（index 1）
- `CameraPreviewActivity` 已将上传进度模拟步进改为随机值 `1..10`，避免固定增量导致观感过于机械

### 第六个工具：PermissionTipView（已完成）

assembleDebug 构建通过，实现"可独立复用的权限提示横条 View，与上传进度区域同位置、相互独立"功能。

**新增文件：**
- `app/src/main/java/com/vam/demov/permission/PermissionTipConfig.kt` — 配置数据类（背景色、文字色、操作按钮文案、动画时长）
- `app/src/main/java/com/vam/demov/permission/PermissionTipView.kt` — 核心自定义 FrameLayout
- `app/src/main/res/layout/view_permission_tip.xml` — View 内部布局（merge）

**修改文件：**
- `app/src/main/res/layout/activity_camera_preview.xml` — 新增 `permissionTipView`（与 `uploadProgressContainer` 同约束，独立叠放）
- `app/src/main/java/com/vam/demov/camera/CameraPreviewActivity.kt` — 接入权限提示：权限缺失时显示、权限授予后隐藏、"去授权"按钮重新触发申请
- `app/src/main/res/values/strings.xml` — 新增权限提示文案资源

**设计要点（2026-03-08）：**
- 继承 `FrameLayout`，内部 inflate `view_permission_tip.xml`（merge 标签，无多余层级）
- 横条结构：左侧提示文案（`weight=1` 填满）+ 右侧操作按钮（`null` 时隐藏）
- 动画：`showTip` 从下方滑入（`translationY: height→0`），`hideTip` 向下滑出（`translationY: 0→height`）；`post {}` 保证高度有效
- 与 `uploadProgressContainer` 约束完全一致（`constraintTop_toTopOf="@id/bottomBar"`），两者各自独立显示/隐藏，互不影响
- 不负责实际权限申请，操作按钮通过 `setOnActionClickListener` 回调交由调用方处理

**对外 API（PermissionTipView）：**
- `applyConfig(config)` — 设置背景色、文字色、操作按钮文案
- `showTip(message, animate)` — 显示提示文案（已显示则仅更新文案）
- `hideTip(animate)` — 隐藏提示条
- `setOnActionClickListener(listener)` — 注册操作按钮回调
- `isShowing()` — 当前是否显示

### 工程维护（已完成）

**最新更新（2026-03-08）：**
- 已新增仓库级 `.ignore`，在无 `.git` 目录场景下也能让 `rg`/Codex 搜索忽略 `build`、`.gradle`、`.idea`、`.kotlin`、`.cxx`、`.externalNativeBuild`、`captures` 等产物目录，避免无关结果干扰

## 开发规范

### 布局

- 使用 XML 布局文件（`res/layout/`）
- 优先使用 `ConstraintLayout`，避免过度嵌套
- 布局命名：`activity_xxx.xml` / `fragment_xxx.xml` / `item_xxx.xml` / `view_xxx.xml`

### 代码结构

- 自定义 View 放在独立类中，继承自系统 View/ViewGroup
- Activity/Fragment 只负责页面逻辑，业务逻辑抽离到 ViewModel 或独立类
- 文件命名使用大驼峰（PascalCase），变量/方法使用小驼峰（camelCase）

### 日志规范

- 生成的工具类或自定义 View 核心类，必须固定定义 `TAG` 变量，便于调试时统一控制日志
- 每个核心工具类必须预置 `logD`、`logI`、`logE` 方法
- 日志方法参数命名固定为 `tag` 和 `content`

### 接入性要求

- 自定义 View 的构造函数保持标准三参数形式（兼容 XML 使用）
- 避免硬编码尺寸，使用 `dp`/`sp` 单位并通过 `resources` 转换
- 对外暴露的属性/方法加注释说明，方便接入工作项目时理解

### 依赖管理

- 只使用 AndroidX 官方库，禁止引入任何三方库
- 需要新增依赖时，必须确认是 AndroidX 官方库，并同步更新 `libs.versions.toml` 和 `build.gradle.kts`
- 新增依赖前确认工作项目是否兼容
