package io.github.duzhaokun123.yamf.xposed.hook

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.app.Application
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
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
import java.lang.reflect.Proxy
import java.util.stream.Stream


class HookLauncher : IXposedHookLoadPackage, IXposedHookZygoteInit {
    companion object {
        const val TAG = "YAMF_HookLauncher"
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
            override fun beforeHookedMethod(MethodHookParam param) {
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