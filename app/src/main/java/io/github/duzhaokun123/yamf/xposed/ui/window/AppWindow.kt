package io.github.duzhaokun123.yamf.xposed.ui.window

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.ITaskStackListener
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.IPackageManagerHidden
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.IRotationWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManagerHidden
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.window.TaskSnapshot
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.flingAnimationOf
import androidx.wear.widget.RoundedDrawable
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.google.android.material.color.MaterialColors
import io.github.duzhaokun123.yamf.BuildConfig
import io.github.duzhaokun123.yamf.R
import io.github.duzhaokun123.yamf.common.getAttr
import io.github.duzhaokun123.yamf.common.onException
import io.github.duzhaokun123.yamf.common.runMain
import io.github.duzhaokun123.yamf.databinding.WindowAppBinding
import io.github.duzhaokun123.yamf.xposed.services.YAMFManager
import io.github.duzhaokun123.yamf.xposed.utils.Instances
import io.github.duzhaokun123.yamf.xposed.utils.RunMainThreadQueue
import io.github.duzhaokun123.yamf.xposed.utils.TipUtil
import io.github.duzhaokun123.yamf.xposed.utils.dpToPx
import io.github.duzhaokun123.yamf.xposed.utils.getActivityInfoCompat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sign

@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AppWindow(val context: Context, private val densityDpi: Int, private val flags: Int, private val onVirtualDisplayCreated: ((Int) -> Unit)) :
    TextureView.SurfaceTextureListener, SurfaceHolder.Callback {
    companion object {
        const val TAG = "YAMF_AppWindow"
        const val ACTION_RESET_ALL_WINDOW = "io.github.duzhaokun123.yamf.ui.window.action.ACTION_RESET_ALL_WINDOW"

        // WindowConfiguration.ACTIVITY_TYPE_HOME (Android framework value).
        private const val ACTIVITY_TYPE_HOME = 2
    }

    lateinit var binding: WindowAppBinding
    lateinit var virtualDisplay: VirtualDisplay
    val taskStackListener = TaskStackListener()
    val rotationWatcher = RotationWatcher()
    val surfaceOnTouchListener = SurfaceOnTouchListener()
    val surfaceOnGenericMotionListener = SurfaceOnGenericMotionListener()
    var displayId = -1
    var rotateLock = false
    var isMini = false
    private var isIconMinimized = false
    @Volatile private var hasHostedTask = false
    @Volatile private var emptyDisplayCheckPending = false
    @Volatile private var destroyed = false
    var halfWidth = 0
    var halfHeight = 0
    lateinit var surfaceView: View
    
    val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESET_ALL_WINDOW) {
                val lp = binding.root.layoutParams as WindowManager.LayoutParams
                lp.apply {
                    x = 0
                    y = 0
                }
                Instances.windowManager.updateViewLayout(binding.root, lp)
                val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200F, context.resources.displayMetrics).toInt()
                val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300F, context.resources.displayMetrics).toInt()
                binding.vSizePreviewer.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
                binding.vSupporter.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
                surfaceView.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
            }
        }
    }

    init {
        runCatching {
            binding = WindowAppBinding.inflate(LayoutInflater.from(context))
        }.onException { e ->
            Log.e(TAG, "init: new window failed may you forget reboot", e)
            TipUtil.showToast("new window failed\nmay you forget reboot")
        }.onSuccess {
            doInit()
        }
    }

    fun doInit() {
        when(YAMFManager.config.surfaceView) {
            0 -> surfaceView = TextureView(context)
            1 -> surfaceView = SurfaceView(context)
        }
        binding.rlCardRoot.addView(surfaceView.apply {
            id = R.id.surface
        }, 0, RelativeLayout.LayoutParams(binding.vSizePreviewer.layoutParams).apply {
            addRule(RelativeLayout.BELOW, R.id.rl_top)
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
//            this as WindowLayoutParamsHidden
//            privateFlags = privateFlags or WindowLayoutParamsHidden.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
        }
        binding.root.let { layout ->
            layout.setOnTouchListener { _, event ->
                moveGestureDetector.onTouchEvent(event)
                moveToTopIfNeed(event)
                true
            }
            Instances.windowManager.addView(layout, params)
        }
        binding.ibResize.setOnTouchListener(object : View.OnTouchListener {
            var beginX = 0F
            var beginY = 0F
            var beginWidth = 0
            var beginHeight = 0

            var offsetX = 0F
            var offsetY = 0F

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when(event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        beginX = event.rawX
                        beginY = event.rawY
                        binding.vSizePreviewer.layoutParams.let {
                            beginWidth = it.width
                            beginHeight = it.height
                        }
                        binding.vSizePreviewer.visibility = View.VISIBLE
                    }
                    MotionEvent.ACTION_MOVE -> {
                        offsetX = event.rawX - beginX
                        offsetY = event.rawY - beginY
                        binding.vSizePreviewer.updateLayoutParams {
                            val targetWidth = beginWidth + offsetX.toInt()
                            if (targetWidth > 0)
                                width = targetWidth
                            val targetHeight = beginHeight + offsetY.toInt()
                            if (targetHeight > 0)
                                height = targetHeight
                            binding.tvSize.text = "${targetWidth}x$targetHeight"
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        surfaceView.updateLayoutParams {
                            val targetWidth = beginWidth + offsetX.toInt()
                            if (targetWidth > 0)
                                width = targetWidth
                            val targetHeight = beginHeight + offsetY.toInt()
                            if (targetHeight > 0)
                                height = targetHeight
                        }
                        binding.vSupporter.layoutParams = FrameLayout.LayoutParams(binding.vSizePreviewer.layoutParams)
                        binding.vSizePreviewer.visibility = View.GONE
                        moveToTopIfNeed(event)
                    }
                }
                return true
            }
        })
        surfaceView.setOnTouchListener(surfaceOnTouchListener)
        surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)
        binding.ibBack.setOnClickListener {
            val down = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK,
                0
            ).apply {
                source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(down, 0)
            val up = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BACK,
                0
            ).apply {
                source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(up, 0)
        }
        binding.ibBack.setOnLongClickListener {
            val down = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_HOME,
                0
            ).apply {
                source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(down, 0)
            val up = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_HOME,
                0
            ).apply {
                source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(up, 0)
            true
        }
        binding.ibRotate.setOnClickListener {
            rotate(Surface.ROTATION_90)
        }
        binding.ibRotate.setOnLongClickListener {
            rotateLock = rotateLock.not()
            TipUtil.showToast("rotateLock: $rotateLock")
            true
        }
        binding.ibClose.setOnClickListener {
            closeCurrentTask()
        }
        binding.ibMinimize.setOnClickListener {
            minimizeToIcon()
        }
        binding.ivMinimizedIcon.setOnClickListener {
            restoreFromIcon()
        }

        var miniStartRawX = 0F
        var miniStartRawY = 0F
        var miniStartWindowX = 0
        var miniStartWindowY = 0
        var miniDragged = false
        val miniTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
        binding.ivMinimizedIcon.setOnTouchListener { view, event ->
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    miniStartRawX = event.rawX
                    miniStartRawY = event.rawY
                    miniStartWindowX = params.x
                    miniStartWindowY = params.y
                    miniDragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - miniStartRawX
                    val dy = event.rawY - miniStartRawY
                    if (!miniDragged && (abs(dx) > miniTouchSlop || abs(dy) > miniTouchSlop)) {
                        miniDragged = true
                    }
                    if (miniDragged) {
                        val display = context.display
                        val screenWidth = display?.width ?: context.resources.displayMetrics.widthPixels
                        val screenHeight = display?.height ?: context.resources.displayMetrics.heightPixels
                        params.x = (miniStartWindowX + dx.toInt()).coerceIn(
                            0,
                            (screenWidth - binding.ivMinimizedIcon.width).coerceAtLeast(0)
                        )
                        params.y = (miniStartWindowY + dy.toInt()).coerceIn(
                            0,
                            (screenHeight - binding.ivMinimizedIcon.height).coerceAtLeast(0)
                        )
                        Instances.windowManager.updateViewLayout(binding.root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (miniDragged) {
                        if (!YAMFManager.isTop(displayId)) moveToTop()
                    } else {
                        view.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Keep the icon minimized if the gesture is interrupted.
                }
            }
            true
        }
        binding.ibClose.setOnLongClickListener {
            AppListWindow(context, displayId)
            true
        }
        binding.ibFullscreen.setOnClickListener {
            getTopRootTask()?.runCatching {
                Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
            }?.onFailure { t ->
                if (t is Error) throw t
                TipUtil.showToast("${t.message}")
            }?.onSuccess {
                // The task has moved to the main display. Remove only this YAMF window;
                // do not route through the Close button, which now removes the task.
                onDestroy()
            }
        }
        binding.ibFullscreen.setOnLongClickListener {
            changeMini()
            true
        }
        if (YAMFManager.config.showForceShowIME) {
            binding.ibIme.setOnClickListener {
                val params = binding.root.layoutParams as WindowManager.LayoutParams
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
                Instances.windowManager.updateViewLayout(binding.root, params)
                val imm = context.getSystemService(InputMethodManager::class.java)
                imm.showSoftInput(binding.root, 0)
            }
        } else {
            binding.ibIme.visibility = View.GONE
        }
        virtualDisplay = Instances.displayManager.createVirtualDisplay("yamf${System.currentTimeMillis()}", 1080, 1920, densityDpi, null, flags)
        displayId = virtualDisplay.display.displayId
        (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(displayId, if (YAMFManager.config.showImeInWindow) WindowManagerHidden.DISPLAY_IME_POLICY_LOCAL else WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY)
        Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
        (surfaceView as? TextureView)?.surfaceTextureListener = this
        (surfaceView as? SurfaceView)?.holder?.addCallback(this)
        var failCount = 0
        fun watchRotation() {
            runCatching {
                Instances.iWindowManager.watchRotation(rotationWatcher, displayId)
            }.onFailure {
                failCount++
                Log.d(TAG, "watchRotation: fail $failCount")
                watchRotation()
            }
        }
        watchRotation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW))
        }
        val width = YAMFManager.config.defaultWindowWidth.dpToPx().toInt()
        val height = YAMFManager.config.defaultWindowHeight.dpToPx().toInt()
        surfaceView.updateLayoutParams {
            this.width = width
            this.height = height
        }
        binding.vSizePreviewer.updateLayoutParams {
            this.width = width
            this.height = height
        }
        binding.vSupporter.layoutParams = FrameLayout.LayoutParams(binding.vSizePreviewer.layoutParams)
        onVirtualDisplayCreated(displayId)
    }

    /**
     * Close every root task hosted by this window's virtual display.
     * A failed removeTask call leaves the window alive so the app is not stranded
     * on an invisible display.
     */
    private fun closeCurrentTask() {
        if (destroyed) return

        val tasks = runCatching {
            Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        }.getOrElse { error ->
            Log.e(TAG, "closeCurrentTask: unable to read tasks on display $displayId", error)
            TipUtil.showToast("can't close application")
            return
        }

        if (tasks.isEmpty()) {
            onDestroy()
            return
        }

        val failedTaskIds = tasks.mapNotNull { task ->
            val removed = runCatching {
                Instances.activityTaskManager.invokeMethod(
                    "removeTask",
                    args(task.taskId),
                    argTypes(Integer.TYPE)
                ) as? Boolean ?: false
            }.onFailure { error ->
                Log.e(TAG, "closeCurrentTask: failed to remove task ${task.taskId}", error)
            }.getOrDefault(false)
            if (removed) null else task.taskId
        }

        if (failedTaskIds.isNotEmpty()) {
            Log.w(TAG, "closeCurrentTask: task removal failed: $failedTaskIds")
            TipUtil.showToast("can't close application")
            scheduleEmptyDisplayCheck()
            return
        }

        onDestroy()
    }

    /** Hide the full window while keeping its virtual display and app task alive. */
    private fun minimizeToIcon() {
        if (destroyed || isIconMinimized) return

        val appIcon = binding.ivIcon.drawable
        binding.ivMinimizedIcon.setImageDrawable(
            appIcon?.constantState?.newDrawable(context.resources)?.mutate() ?: appIcon
        )
        binding.flWindowContent.visibility = View.GONE
        binding.ivMinimizedIcon.visibility = View.VISIBLE
        isIconMinimized = true

        val params = binding.root.layoutParams as WindowManager.LayoutParams
        val screenWidth = context.display?.width ?: context.resources.displayMetrics.widthPixels
        val screenHeight = context.display?.height ?: context.resources.displayMetrics.heightPixels
        val iconSize = 56.dpToPx().toInt()
        params.x = params.x.coerceIn(0, (screenWidth - iconSize).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - iconSize).coerceAtLeast(0))
        Instances.windowManager.updateViewLayout(binding.root, params)
    }

    /** Restore the previous window without recreating its virtual display. */
    private fun restoreFromIcon() {
        if (destroyed || !isIconMinimized) return
        isIconMinimized = false
        binding.ivMinimizedIcon.visibility = View.GONE
        binding.flWindowContent.visibility = View.VISIBLE
        Instances.windowManager.updateViewLayout(binding.root, binding.root.layoutParams)
        if (!YAMFManager.isTop(displayId)) moveToTop()
    }

    /**
     * The system creates a dedicated SECONDARY_HOME task on virtual displays
     * with system decorations. It remains alive even when the user app has
     * left the display, so it must not keep the YAMF app window alive.
     *
     * TaskInfo.topActivityType is hidden on this API level; read it through
     * EzXHelper, with a component-name fallback for vendor framework variants.
     */
    private fun isSecondaryHomeTask(
        taskInfo: Any,
        topActivity: ComponentName?
    ): Boolean {
        val activityType = runCatching {
            taskInfo.getObject("topActivityType") as? Int
        }.getOrNull()
        val className = topActivity?.className.orEmpty()
        return activityType == ACTIVITY_TYPE_HOME ||
            className.endsWith("SecondaryDisplayLauncher") ||
            className.endsWith("SecondaryHomeActivity")
    }

    /**
     * RootTaskInfo may contain system home/launcher tasks on a decorated
     * virtual display. Only non-home application tasks count as hosted apps.
     */
    private fun getActiveRootTasks(): List<ActivityTaskManager.RootTaskInfo>? {
        return runCatching {
            Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).filter { task ->
                val isHomeTask = isSecondaryHomeTask(task, task.topActivity)
                if (isHomeTask) {
                    Log.d(
                        TAG,
                        "ignoring secondary-home task ${task.taskId}: " +
                            "${task.topActivity} (activityType=" +
                            runCatching { task.getObject("topActivityType") as? Int }.getOrNull() + ")"
                    )
                    false
                } else {
                    task.isRunning &&
                        task.numActivities > 0 &&
                        task.topActivity != null &&
                        task.childTaskIds.isNotEmpty()
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "getActiveRootTasks: unable to inspect display $displayId", error)
        }.getOrNull()
    }

    /**
     * Re-check the task list after display/task transitions settle. Require two
     * consecutive empty snapshots so a short reparent/launch transition cannot
     * destroy a window that is still acquiring its task.
     */
    @Synchronized
    private fun scheduleEmptyDisplayCheck() {
        if (destroyed || emptyDisplayCheckPending) return
        emptyDisplayCheckPending = true
        RunMainThreadQueue.add {
            try {
                delay(500)
                if (destroyed) return@add

                val firstCheck = getActiveRootTasks() ?: return@add
                if (firstCheck.isNotEmpty()) {
                    hasHostedTask = true
                    Log.d(TAG, "display $displayId still hosts tasks: ${firstCheck.map { it.taskId }}")
                    return@add
                }

                if (hasHostedTask) {
                    delay(250)
                    if (destroyed) return@add
                    val confirmation = getActiveRootTasks() ?: return@add
                    if (confirmation.isEmpty()) {
                        Log.i(TAG, "display $displayId has no running activities; removing stale window")
                        onDestroy()
                    } else {
                        Log.d(TAG, "display $displayId received tasks during cleanup confirmation: ${confirmation.map { it.taskId }}")
                    }
                }
            } finally {
                emptyDisplayCheckPending = false
            }
        }
    }

    private fun onDestroy() {
        if (destroyed) return
        destroyed = true
        runCatching { context.unregisterReceiver(broadcastReceiver) }
        runCatching { Instances.iWindowManager.removeRotationWatcher(rotationWatcher) }
        runCatching { Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener) }
        runCatching { YAMFManager.removeWindow(displayId) }
        runCatching { virtualDisplay.release() }
        runCatching { Instances.windowManager.removeView(binding.root) }
    }

    private fun getTopRootTask(): ActivityTaskManager.RootTaskInfo? {
        Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
            if (task.visible)
                return task
        }
        return null
    }

    private fun moveToTop() {
        Instances.windowManager.removeView(binding.root)
        Instances.windowManager.addView(binding.root, binding.root.layoutParams)
        YAMFManager.moveToTop(displayId)
    }

    private fun moveToTopIfNeed(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP && YAMFManager.isTop(displayId).not()) {
            moveToTop()
        }
    }

    private fun updateTask(taskInfo: ActivityManager.RunningTaskInfo) {
        RunMainThreadQueue.add {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2 && taskInfo.isVisible.not()) {
                delay(500) // fixme: 使用能直接确定可见性的方法
            }
            val topActivity = taskInfo.topActivity ?: return@add
            val taskDescription = Instances.activityTaskManager.getTaskDescription(taskInfo.taskId) ?: return@add
            val activityInfo = (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(topActivity, 0, taskInfo.getObjectAs("userId")) ?: return@add
            binding.ivIcon.setImageDrawable(RoundedDrawable().apply {
                drawable = runCatching { taskDescription.icon }.getOrNull()?.let { BitmapDrawable(it) } ?: activityInfo.loadIcon(Instances.packageManager)
                isClipEnabled = true
                radius = 100
            })
            if (isIconMinimized) {
                val appIcon = binding.ivIcon.drawable
                binding.ivMinimizedIcon.setImageDrawable(
                    appIcon?.constantState?.newDrawable(context.resources)?.mutate() ?: appIcon
                )
            }
            binding.tvLabel.text = taskDescription.label ?: activityInfo.loadLabel(Instances.packageManager)
            if (BuildConfig.DEBUG) {
                binding.tvLabel.text = "(${taskInfo.taskId}-$displayId) ${binding.tvLabel.text}"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && YAMFManager.config.coloredController) {
                val backgroundColor = taskDescription.backgroundColor
                binding.cvApp.setCardBackgroundColor(backgroundColor)

                val statusBarColor = taskDescription.statusBarColor
                binding.rlTop.setBackgroundColor(statusBarColor)
                val onStateBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(statusBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }
                binding.tvLabel.setTextColor(onStateBar)
                binding.ibClose.imageTintList = ColorStateList.valueOf(onStateBar)
                binding.ibMinimize.imageTintList = ColorStateList.valueOf(onStateBar)

                val navigationBarColor = taskDescription.navigationBarColor
                binding.rlButton.setBackgroundColor(navigationBarColor)
                val onNavigationBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(navigationBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }
                binding.ibBack.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibRotate.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibFullscreen.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibResize.imageTintList = ColorStateList.valueOf(onNavigationBar)
            }
        }
    }

    inner class TaskStackListener : ITaskStackListener.Stub() {
        override fun onTaskStackChanged() {
            scheduleEmptyDisplayCheck()
        }
        override fun onActivityPinned(packageName: String?, userId: Int, taskId: Int, stackId: Int) {}
        override fun onActivityUnpinned() {}
        override fun onActivityRestartAttempt(task: ActivityManager.RunningTaskInfo?, homeTaskVisible: Boolean, clearedTask: Boolean, wasVisible: Boolean) {}
        override fun onActivityForcedResizable(packageName: String?, taskId: Int, reason: Int) {}
        override fun onActivityDismissingDockedTask() {}
        override fun onActivityLaunchOnSecondaryDisplayFailed(taskInfo: ActivityManager.RunningTaskInfo?, requestedDisplayId: Int) {}
        override fun onActivityLaunchOnSecondaryDisplayRerouted(taskInfo: ActivityManager.RunningTaskInfo?, requestedDisplayId: Int) {
            if (requestedDisplayId == displayId) {
                Log.d(TAG, "launch rerouted away from display $displayId for task ${taskInfo?.taskId}")
                scheduleEmptyDisplayCheck()
            }
        }
        override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {}
        override fun onTaskRemoved(taskId: Int) {
            scheduleEmptyDisplayCheck()
        }
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            if (taskInfo.getObject("displayId") == displayId) {
                if (isSecondaryHomeTask(taskInfo, taskInfo.topActivity)) {
                    Log.d(TAG, "secondary home moved to front on display $displayId; not marking it as app")
                    scheduleEmptyDisplayCheck()
                    return
                }
                hasHostedTask = true
                updateTask(taskInfo)
            }
        }
        override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
            if (taskInfo.getObject("displayId") == displayId) {
                if (isSecondaryHomeTask(taskInfo, taskInfo.topActivity)) {
                    scheduleEmptyDisplayCheck()
                    return
                }
                hasHostedTask = true
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2 && !taskInfo.isVisible){
                    return
                }
                updateTask(taskInfo)
            }
        }
        override fun onActivityRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {}
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo?) {
            if (taskInfo?.getObject("displayId") == displayId) {
                scheduleEmptyDisplayCheck()
            }
        }
//        override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?, userId: Int) {}

        override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskSnapshotChanged(taskId: Int, snapshot: TaskSnapshot?) {}
        override fun onBackPressedOnTaskRoot(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
            // Do not mark the display as hosting an app here: this callback can
            // also describe the system SECONDARY_HOME task. The next task query
            // will mark hasHostedTask only when a non-home app task is present.
            Log.d(TAG, "task $taskId changed display to $newDisplayId (window display=$displayId)")
            scheduleEmptyDisplayCheck()
        }
        override fun onRecentTaskListUpdated() {
            scheduleEmptyDisplayCheck()
        }
        override fun onRecentTaskListFrozenChanged(frozen: Boolean) {}
        override fun onTaskFocusChanged(taskId: Int, focused: Boolean) {}
        override fun onTaskRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {}
        override fun onActivityRotation(displayId: Int) {}
        override fun onTaskMovedToBack(taskInfo: ActivityManager.RunningTaskInfo?) {
            if (taskInfo?.getObject("displayId") == displayId) {
                scheduleEmptyDisplayCheck()
            }
        }
        override fun onLockTaskModeChanged(mode: Int) {}
    }

    inner class RotationWatcher : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            runMain {
                if (rotateLock.not())
                    rotate(rotation)
            }
        }
    }

    /**
     * 进行一个 rotation 度的旋转 而不是旋转到
     */
    fun rotate(rotation: Int) {
        if (rotation == 1 || rotation == 3) {
            val t = halfHeight
            halfHeight = halfWidth
            halfWidth = t
            val surfaceWidth = surfaceView.width
            val surfaceHeight = surfaceView.height
            binding.vSizePreviewer.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
            surfaceView.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
            binding.vSupporter.layoutParams = FrameLayout.LayoutParams(binding.vSizePreviewer.layoutParams)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (isMini.not()) {
            virtualDisplay.resize(width, height, densityDpi)
            surface.setDefaultBufferSize(width, height)
            halfWidth = width % 2
            halfHeight = height % 2
        } else {
            virtualDisplay.resize(width * 2 + halfWidth, height * 2 + halfHeight, densityDpi)
            surface.setDefaultBufferSize(width * 2 + halfWidth, height * 2 + halfHeight)
        }
        virtualDisplay.surface = Surface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (isMini.not()) {
            virtualDisplay.resize(width, height, densityDpi)
            surface.setDefaultBufferSize(width, height)
            halfWidth = width % 2
            halfHeight = height % 2
        } else {
            virtualDisplay.resize(width * 2 + halfWidth, height * 2 + halfHeight, densityDpi)
            surface.setDefaultBufferSize(width * 2 + halfWidth, height * 2 + halfHeight)
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {

    }

    private fun changeMini() {
        if (surfaceView is SurfaceView) {
            TipUtil.showToast("can't scale SurfaceView")
            return
        }
        if (isMini) {
            isMini = false
            surfaceView.updateLayoutParams {
                width = virtualDisplay.display.width
                height = virtualDisplay.display.height
            }
            binding.vSupporter.updateLayoutParams {
                width = virtualDisplay.display.width
                height = virtualDisplay.display.height
            }
            binding.rlTop.visibility = View.VISIBLE
            binding.rlButton.visibility = View.VISIBLE
            surfaceView.setOnTouchListener(surfaceOnTouchListener)
            surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)
        } else {
            isMini = true
            surfaceView.updateLayoutParams {
                width = virtualDisplay.display.width / 2
                height = virtualDisplay.display.height / 2
            }
            binding.vSupporter.updateLayoutParams {
                width = virtualDisplay.display.width / 2
                height = virtualDisplay.display.height / 2
            }
            binding.rlTop.visibility = View.GONE
            binding.rlButton.visibility = View.GONE
            surfaceView.setOnTouchListener(null)
            surfaceView.setOnGenericMotionListener(null)
        }
    }

    fun forwardMotionEvent(event: MotionEvent) {
        val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
        val pointerProperties: Array<MotionEvent.PointerProperties?> =
            arrayOfNulls(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            val oldCoords = MotionEvent.PointerCoords()
            val pointerProperty = MotionEvent.PointerProperties()
            event.getPointerCoords(i, oldCoords)
            event.getPointerProperties(i, pointerProperty)
            pointerCoords[i] = oldCoords
            pointerCoords[i]!!.apply {
                x = oldCoords.x
                y = oldCoords.y
            }
            pointerProperties[i] = pointerProperty
        }

        val newEvent = MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            event.action,
            event.pointerCount,
            pointerProperties,
            pointerCoords,
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags
        )
        newEvent.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
        Instances.inputManager.injectInputEvent(newEvent, 0)
        newEvent.recycle()
    }

    inner class SurfaceOnTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            forwardMotionEvent(event)
            moveToTopIfNeed(event)
            return true
        }
    }

    inner class SurfaceOnGenericMotionListener : View.OnGenericMotionListener {
        override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
            forwardMotionEvent(event)
            return true
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        virtualDisplay.surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        virtualDisplay.resize(width, height, densityDpi)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        virtualDisplay.surface = null
    }

    val moveGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        var startX = 0
        var startY = 0
        var xAnimation: FlingAnimation? = null
        var yAnimation: FlingAnimation? = null
        var lastX = 0F
        var lastY = 0F
        var last2X = 0F
        var last2Y = 0F

        override fun onDown(e: MotionEvent): Boolean {
            xAnimation?.cancel()
            yAnimation?.cancel()
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            startX = params.x
            startY = params.y
            return true
        }


        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            e1 ?: return false
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            params.x = (startX + (e2.rawX - e1.rawX)).toInt()
            params.y = (startY + (e2.rawY - e1.rawY)).toInt()
            Instances.windowManager.updateViewLayout(binding.root, params)
            last2X = lastX
            last2Y = lastY
            lastX = e2.rawX
            lastY = e2.rawY
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            e1 ?: return false
            if (e1.source == InputDevice.SOURCE_MOUSE) return false
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            runCatching {
                if (sign(velocityX) != sign(e2.rawX - last2X)) return@runCatching
                xAnimation = flingAnimationOf({
                    params.x = it.toInt()
                    Instances.windowManager.updateViewLayout(binding.root, params)
                }, {
                    params.x.toFloat()
                })
                    .setStartVelocity(velocityX)
                    .setMinValue(0F)
                    .setMaxValue(context.display!!.width.toFloat() - binding.root.width)
                xAnimation?.start()
            }
            runCatching {
                if (sign(velocityY) != sign(e2.rawY - last2Y)) return@runCatching
                yAnimation = flingAnimationOf({
                    params.y = it.toInt()
                    Instances.windowManager.updateViewLayout(binding.root, params)
                }, {
                    params.y.toFloat()
                })
                    .setStartVelocity(velocityY)
                    .setMinValue(0F)
                    .setMaxValue(context.display!!.height.toFloat() - binding.root.height)
                yAnimation?.start()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isMini) changeMini()
            return true
        }
    })
}