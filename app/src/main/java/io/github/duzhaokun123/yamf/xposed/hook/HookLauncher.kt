package io.github.duzhaokun123.yamf.xposed.hook

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AndroidAppHelper
import android.app.Application
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields.moduleRes
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findConstructor
import com.github.kyuubiran.ezxhelper.utils.findField
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAuto
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAutoAs
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.github.kyuubiran.ezxhelper.utils.paramCount
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.duzhaokun123.yamf.BuildConfig
import io.github.duzhaokun123.yamf.R
import io.github.duzhaokun123.yamf.xposed.services.YAMFManager
import io.github.duzhaokun123.yamf.xposed.utils.log
import io.github.duzhaokun123.yamf.xposed.utils.registerReceiver
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.stream.Stream


class HookLauncher : IXposedHookLoadPackage, IXposedHookZygoteInit {
    companion object {
        const val TAG = "YAMF_HookLauncher"

        // Target-specific support for the Transsion itel launcher.
        private const val ITEL_LAUNCHER_PACKAGE = "com.transsion.itel.launcher"
        private const val ITEL_FREEFORM_HANDLER =
            "com.android.quickstep.src.com.android.quickstep.z9\$h"
        const val ACTION_RECEIVE_LAUNCHER_CONFIG =
            "io.github.duzhaokun123.yamf.ACTION_RECEIVE_LAUNCHER_CONFIG"

        const val EXTRA_HOOK_RECENTS = "hookRecents"
        const val EXTRA_HOOK_TASKBAR = "hookTaskbar"
        const val EXTRA_HOOK_POPUP = "hookPopup"
        const val EXTRA_HOOK_TRANSIENT_TASKBAR = "hookTransientTaskbar"
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        EzXHelperInit.initHandleLoadPackage(lpparam)

        // The itel launcher uses a vendor-prefixed/obfuscated Recents implementation.
        // Handle it before the generic YAMF Launcher-class check, which can reject
        // vendor launchers that do not expose the usual class name.
        if (lpparam.packageName == ITEL_LAUNCHER_PACKAGE) {
            forceEnableItelFreeformSupport(lpparam)
            if (hookItelFreeformButton(lpparam)) {
                return
            }
            log(TAG, "itel Freeform handler not found; falling back to generic launcher hooks")
        }

        loadClassOrNull("com.android.launcher3.Launcher") ?: return
        Application::class.java.findMethod {
            name == "onCreate"
        }.hookAfter {
            val application = it.thisObject as Application
            application.registerReceiver(ACTION_RECEIVE_LAUNCHER_CONFIG) { _, intent ->
                val hookRecents = intent.getBooleanExtra(EXTRA_HOOK_RECENTS, false)
                val hookTaskbar = intent.getBooleanExtra(EXTRA_HOOK_TASKBAR, false)
                val hookPopup = intent.getBooleanExtra(EXTRA_HOOK_POPUP, false)
                val hookTransientTaskbar =
                    intent.getBooleanExtra(EXTRA_HOOK_TRANSIENT_TASKBAR, false)
                log(
                    TAG,
                    "receive config hookRecents=$hookRecents hookTaskbar=$hookTaskbar hookPopup=$hookPopup hookTranslucentTaskbar=$hookTransientTaskbar"
                )
                if (hookRecents) runCatching { hookRecents(lpparam) }.onFailure { e ->
                    log(TAG, "hook recents failed", e) }
                if (hookTaskbar) runCatching { hookTaskbar(lpparam) }.onFailure { e ->
                    log(TAG, "hook taskbar failed", e) }
                if (hookPopup) runCatching { hookPopup(lpparam) }.onFailure { e ->
                    log(TAG, "hook popup failed", e) }
//                if (hookTransientTaskbar) hookTransientTaskbar(lpparam)
                application.unregisterReceiver(this)
            }
            application.sendBroadcast(Intent(YAMFManager.ACTION_GET_LAUNCHER_CONFIG).apply {
                `package` = "android"
                putExtra("sender", application.packageName)
            })
        }

        hookTransientTaskbar(lpparam)
    }

    /**
     * Makes the launcher's feature check report Freeform support. This is intentionally
     * limited to the launcher process; system-window behavior is not changed here.
     */
    private fun forceEnableItelFreeformSupport(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val systemProperties = XposedHelpers.findClass(
                "android.os.SystemProperties", lpparam.classLoader
            )
            XposedBridge.hookAllMethods(
                systemProperties,
                "get",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.isNotEmpty() &&
                            param.args[0] == "ro.os_freeform_support"
                        ) {
                            param.result = "1"
                        }
                    }
                }
            )
            log(TAG, "hooked ro.os_freeform_support for itel launcher")
        }.onFailure { log(TAG, "failed to hook Freeform property", it) }
    }

    /**
     * Hooks the existing Freeform action in the itel Recents UI. MainHook confirmed that
     * the handler's field `i` is its TaskView and TaskView field `y` is the selected Task.
     * We do not create SystemUI overlays or replace YAMF's window-management code here.
     */
    private fun hookItelFreeformButton(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        val handlerClass = XposedHelpers.findClassIfExists(
            ITEL_FREEFORM_HANDLER, lpparam.classLoader
        ) ?: XposedHelpers.findClassIfExists(
            "com.android.quickstep.z9\$h", lpparam.classLoader
        ) ?: run {
            log(TAG, "itel Freeform handler class not found")
            return false
        }

        val hooks = XposedBridge.hookAllMethods(
            handlerClass,
            "onClick",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // This onClick is the Freeform action's handler; suppress its OEM action
                    // and launch the selected task using the verified native Freeform mode.
                    param.result = null
                    try {
                        val taskView = XposedHelpers.getObjectField(param.thisObject, "i")
                        val task = XposedHelpers.getObjectField(taskView, "y")
                        val key = XposedHelpers.getObjectField(task, "key")
                        val taskId = XposedHelpers.getIntField(key, "id")
                        val title = runCatching {
                            XposedHelpers.getObjectField(task, "title")?.toString()
                        }.getOrNull() ?: "unknown"

                        val options = ActivityOptions.makeBasic()
                        XposedHelpers.callMethod(options, "setLaunchWindowingMode", 5)
                        // Coordinates tested successfully on the itel P55 5G (720x1612).
                        options.setLaunchBounds(Rect(40, 120, 680, 1020))

                        log(TAG, "itel Freeform clicked: taskId=$taskId title=$title")
                        val launched = launchItelTaskFromRecents(
                            param.thisObject.javaClass.classLoader,
                            taskId,
                            options
                        )
                        log(TAG, "itel native Freeform launch requested: success=$launched")
                    } catch (e: Throwable) {
                        log(TAG, "failed to launch selected task in itel Freeform", e)
                    }
                }
            }
        )

        log(TAG, "hooked itel Freeform handler ${handlerClass.name}; methods=${hooks.size}")
        return hooks.isNotEmpty()
    }

    /** Uses the same ActivityManagerWrapper path that was verified on the target device. */
    private fun launchItelTaskFromRecents(
        classLoader: ClassLoader,
        taskId: Int,
        options: ActivityOptions
    ): Boolean {
        val wrapperNames = arrayOf(
            "com.android.quickstep.src.com.android.systemui.shared.system.ActivityManagerWrapper",
            "com.android.systemui.shared.system.ActivityManagerWrapper",
            "com.android.quickstep.src.com.android.quickstep.util.ActivityManagerWrapper",
            "com.android.quickstep.util.ActivityManagerWrapper"
        )

        for (name in wrapperNames) {
            val wrapperClass = XposedHelpers.findClassIfExists(name, classLoader) ?: continue
            try {
                val instance = XposedHelpers.callStaticMethod(wrapperClass, "getInstance")
                if (invokeItelStartActivityFromRecents(wrapperClass, instance, taskId, options)) {
                    return true
                }
            } catch (e: Throwable) {
                log(TAG, "ActivityManagerWrapper failed: $name", e)
            }
        }

        // Fallback mirrors MainHook's reflection path if the wrapper class is unavailable.
        return runCatching {
            val activityTaskManager = Class.forName("android.app.ActivityTaskManager")
            val getService = activityTaskManager.getDeclaredMethod("getService").apply {
                isAccessible = true
            }
            val service = getService.invoke(null)
            invokeItelStartActivityFromRecents(service.javaClass, service, taskId, options)
        }.onFailure { log(TAG, "ActivityTaskManager fallback failed", it) }.getOrDefault(false)
    }

    private fun invokeItelStartActivityFromRecents(
        startClass: Class<*>,
        receiver: Any?,
        taskId: Int,
        options: ActivityOptions
    ): Boolean {
        var currentClass: Class<*>? = startClass
        while (currentClass != null) {
            for (method in currentClass.declaredMethods) {
                if (method.name != "startActivityFromRecents") continue
                val parameterTypes = method.parameterTypes
                if (parameterTypes.size != 2) continue
                if (parameterTypes[0] != java.lang.Integer.TYPE &&
                    parameterTypes[0] != java.lang.Integer::class.java
                ) continue

                val secondArgument: Any = when {
                    parameterTypes[1].isAssignableFrom(ActivityOptions::class.java) -> options
                    parameterTypes[1].isAssignableFrom(Bundle::class.java) -> options.toBundle()
                    else -> continue
                }

                try {
                    method.isAccessible = true
                    val result = method.invoke(
                        if (Modifier.isStatic(method.modifiers)) null else receiver,
                        taskId,
                        secondArgument
                    )
                    log(TAG, "invoked ${currentClass.name}.startActivityFromRecents result=$result")
                    if (result is Boolean && !result) continue
                    return true
                } catch (e: Throwable) {
                    log(TAG, "startActivityFromRecents invocation failed", e)
                }
            }
            currentClass = currentClass.superclass
        }
        return false
    }

    private fun hookRecents(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hooking recents custom itel/transsion ${lpparam.packageName}")
        
        // Sesuaikan dengan nama handler click Recents yang terbukti bekerja di itel P55 5G
        val handlerClass = XposedHelpers.findClassIfExists(
            "com.android.quickstep.src.com.android.quickstep.z9\$h", 
            lpparam.classLoader
        ) ?: XposedHelpers.findClassIfExists(
            "com.android.quickstep.z9\$h", 
            lpparam.classLoader
        ) ?: return

        XposedBridge.hookAllMethods(handlerClass, "onClick", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Batalkan aksi bawaan launcher agar tidak bentrok
                param.result = null 
                try {
                    // Ambil Task ID secara dinamis dari struktur Recents itel
                    val taskView = XposedHelpers.getObjectField(param.thisObject, "i")
                    val task = XposedHelpers.getObjectField(taskView, "y")
                    val key = XposedHelpers.getObjectField(task, "key")
                    val taskId = XposedHelpers.getIntField(key, "id")
                    
                    // Ambil komponen aplikasi (Component Name)
                    val topActivity = XposedHelpers.getObjectField(task, "topActivity")
                    val componentName = if (topActivity != null) {
                        ComponentName.unflattenFromString(topActivity.toString())
                    } else null
                    
                    val userId = XposedHelpers.getIntField(key, "userId")

                    log(TAG, "Recents clicked Freeform request for taskId=$taskId")

                    // Lempar sinyal pembukaan Freeform langsung ke YAMF Manager Service
                    val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                        putExtra(YAMFManager.EXTRA_TASK_ID, taskId)
                        if (componentName != null) {
                            putExtra(YAMFManager.EXTRA_COMPONENT_NAME, componentName)
                        }
                        putExtra(YAMFManager.EXTRA_USER_ID, userId)
                        putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_RECENTS)
                    }
                    AndroidAppHelper.currentApplication().sendBroadcast(intent)

                } catch (e: Throwable) {
                    log(TAG, "Gagal memicu Freeform YAMF dari Recents", e)
                }
            }
        })
    }

    private fun hookTaskbar(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hooking taskbar ${lpparam.packageName}")
        loadClass("com.android.launcher3.taskbar.TaskbarActivityContext").apply {
            findMethodOrNull { name == "startItemInfoActivity" }
                ?.hookReplace {
                    val infoIntent = it.args[0].invokeMethodAutoAs<Intent>("getIntent")!!
                    val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                        putExtra(YAMFManager.EXTRA_COMPONENT_NAME, infoIntent.component)
                        putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_TASKBAR)
                    }
                    AndroidAppHelper.currentApplication().sendBroadcast(intent)
                }
            val class_WorkspaceItemInfo =
                loadClass("com.android.launcher3.model.data.WorkspaceItemInfo")
            findMethod { name == "onTaskbarIconClicked" }
                .hookBefore {
                    val tag = it.args[0].invokeMethodAuto("getTag")!!
                    if (class_WorkspaceItemInfo.isInstance(tag)) {
                        val infoIntent = tag.invokeMethodAutoAs<Intent>("getIntent")!!
                        val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                            setPackage("android")
                            putExtra(YAMFManager.EXTRA_COMPONENT_NAME, infoIntent.component)
                            putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_TASKBAR)
                        }
                        AndroidAppHelper.currentApplication().sendBroadcast(intent)
                        it.result = Unit
                    }
                }
        }

    }

    var proxyClass: Any? = null

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun handleProxyMethod(param: XC_MethodHook.MethodHookParam) {
        val methodName = param.method.name
        val thiz = param.thisObject
        when (methodName) {
            "onClick" -> {
                val mItemInfo = thiz.getObject("mItemInfo")
                val componentName = mItemInfo.invokeMethod("getTargetComponent") as ComponentName
                val userId = (mItemInfo.getObject("user") as UserHandle)
                AndroidAppHelper.currentApplication()
                    .sendBroadcast(Intent(YAMFManager.ACTION_OPEN_APP).apply {
                        setPackage("android")
                        putExtra(YAMFManager.EXTRA_COMPONENT_NAME, componentName)
                        putExtra(YAMFManager.EXTRA_USER_ID, userId)
                        putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_POPUP)
                    })
                thiz.invokeMethodAuto("dismissTaskMenuView", thiz.getObject("mTarget"))
                param.result = Unit
            }

            "setIconAndContentDescriptionFor" -> {
                val view = param.args[0] as ImageView
                view.setImageDrawable(
                    moduleRes.getDrawable(
                        R.drawable.ic_picture_in_picture_alt_24,
                        null
                    )
                )
                view.contentDescription = moduleRes.getString(R.string.open_with_yamf)
                param.result = Unit
            }

            "setIconAndLabelFor" -> {
                val iconView = param.args[0] as View
                val labelView = param.args[1] as TextView
                iconView.background =
                    moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null)
                labelView.text = moduleRes.getString(R.string.open_with_yamf)
                param.result = Unit
            }
        }
    }

    private fun hookPopup(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hooking popup ${lpparam.packageName}")
//        loadClass("com.android.launcher3.Launcher")
//            .findMethod { name == "getSupportedShortcuts" }
//            .hookAfter {
//                val r = (it.result as Stream<*>).toArray()
//                it.result = Stream.of(*r, getOpenInYAMFSystemShortcutFactory(lpparam.classLoader))
//            }
        loadClass("com.android.launcher3.popup.SystemShortcut")
            .findField { name == "INSTALL" }
            .set(null, getOpenInYAMFSystemShortcutFactory(lpparam.classLoader))
        loadClass("com.android.launcher3.popup.SystemShortcut")
            .findAllMethods { true }
            .hookBefore {
                val thiz = it.thisObject
                if (thiz !== proxyClass) return@hookBefore
                handleProxyMethod(it)
            }
        loadClass("com.android.launcher3.popup.SystemShortcut\$Install")
            .findAllMethods { true }
            .hookBefore {
                val thiz = it.thisObject
                if (thiz !== proxyClass) return@hookBefore
                handleProxyMethod(it)
            }
    }

    fun getOpenInYAMFSystemShortcutFactory(classLoader: ClassLoader): Any {
        return Proxy.newProxyInstance(
            classLoader, arrayOf(loadClass("com.android.launcher3.popup.SystemShortcut\$Factory"))
        ) { _, method, args ->
            if (method.name != "getShortcut") return@newProxyInstance Unit
            return@newProxyInstance loadClass("com.android.launcher3.popup.SystemShortcut\$Install")
                .findConstructor { paramCount == 3 }
                .newInstance(args[0], args[1], args[2])
                .also { proxyClass = it }
        }
    }

    private fun hookTransientTaskbar(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hook transientTaskbar ${lpparam.packageName}")
        loadClass("com.android.launcher3.util.DisplayController")
            .findMethod { name == "isTransientTaskbar" }
            .hookReturnConstant(true)
    }
}