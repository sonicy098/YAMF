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

        // Batasi hook ini ke launcher itel. Launcher Transsion menggunakan
        // class yang direlokasi, jadi jangan mensyaratkan class AOSP Launcher.
        if (lpparam.packageName != "com.transsion.itel.launcher") return

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

        val handlerClass = XposedHelpers.findClassIfExists(
            "com.android.quickstep.src.com.android.quickstep.z9\$h",
            lpparam.classLoader
        ) ?: XposedHelpers.findClassIfExists(
            "com.android.quickstep.z9\$h",
            lpparam.classLoader
        )

        if (handlerClass == null) {
            log(TAG, "itel Recents Freeform handler not found; skipping Recents hook")
            return
        }
        log(TAG, "found itel Recents handler: ${handlerClass.name}")

        XposedBridge.hookAllMethods(handlerClass, "onClick", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                // YAMF akan menangani task yang dipilih; cegah aksi Freeform bawaan launcher.
                param.result = null
                try {
                    val taskView = XposedHelpers.getObjectField(param.thisObject, "i")
                    val task = XposedHelpers.getObjectField(taskView, "y")
                    val key = XposedHelpers.getObjectField(task, "key")
                    val taskId = XposedHelpers.getIntField(key, "id")
                    val userId = XposedHelpers.getIntField(key, "userId")

                    val topActivity = runCatching {
                        XposedHelpers.getObjectField(task, "topActivity") as? ComponentName
                    }.getOrNull()
                    val realActivity = runCatching {
                        XposedHelpers.getObjectField(task, "realActivity") as? ComponentName
                    }.getOrNull()
                    val baseIntent = runCatching {
                        XposedHelpers.getObjectField(key, "baseIntent") as? Intent
                    }.getOrNull()
                    
                    val componentName = topActivity ?: realActivity ?: baseIntent?.component

                    // Paksa YAMF menggunakan ACTION_OPEN_APP untuk melakukan cold-start 
                    // agar aplikasi mati suri dirakit ulang oleh OS dari nol.
                    if (componentName != null) {
                        val yamfIntent = Intent(YAMFManager.ACTION_OPEN_APP).apply {
                            setPackage("android")
                            putExtra(YAMFManager.EXTRA_COMPONENT_NAME, componentName)
                            putExtra(YAMFManager.EXTRA_USER_ID, userId)
                            putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_RECENTS)
                        }
                        AndroidAppHelper.currentApplication().sendBroadcast(yamfIntent)
                        log(TAG, "YAMF Recents dipaksa ke Open App: component=$componentName userId=$userId")
                    } else {
                        log(TAG, "WARNING: componentName tidak ditemukan, jejak aplikasi sudah dihapus total oleh OS.")
                    }

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

    // Objek penyimpan identitas tombol YAMF buatan kita
    object YamfShortcutManager {
        var isHooked = false
        // Menyimpan objek klon khusus YAMF
        val yamfShortcutObjects = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
        val yamfAppData = java.util.concurrent.ConcurrentHashMap<Any, AppData>()

        class AppData(val componentName: ComponentName, val userHandle: UserHandle?)

        fun triggerYamf(appData: AppData) {
            val intent = Intent(YAMFManager.ACTION_OPEN_APP).apply {
                setPackage("android")
                putExtra(YAMFManager.EXTRA_COMPONENT_NAME, appData.componentName)
                if (appData.userHandle != null) {
                    putExtra(YAMFManager.EXTRA_USER_ID, appData.userHandle)
                }
                putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_POPUP)
            }
            AndroidAppHelper.currentApplication().sendBroadcast(intent)
        }
    }

    private fun hookPopup(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hooking Transsion custom popup safely ${lpparam.packageName}")

        val popupContainerClass = XposedHelpers.findClassIfExists(
            "com.transsion.xlauncher.popup.PopupContainer",
            lpparam.classLoader
        ) ?: return

        XposedBridge.hookAllMethods(popupContainerClass, "E", object : XC_MethodHook() {
            @Suppress("UNCHECKED_CAST")
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val componentName = param.args[4] as? ComponentName
                    val itemInfo = param.args[2]
                    val userHandle = if (itemInfo != null) {
                        runCatching { XposedHelpers.getObjectField(itemInfo, "user") as? UserHandle }.getOrNull()
                    } else null

                    var systemShortcuts = param.args[7] as? MutableList<Any>

                    if (!systemShortcuts.isNullOrEmpty() && componentName != null) {
                        val template = systemShortcuts[0]
                        val concreteClass = template.javaClass

                        // 1. DAFTARKAN HOOK GLOBAL (Hanya sekali)
                        if (!YamfShortcutManager.isHooked) {
                            YamfShortcutManager.isHooked = true
                            
                            val sysClass = XposedHelpers.findClass("com.transsion.xlauncher.popup.SystemShortcutInfo", lpparam.classLoader)

                            // Bajak Teks Label HANYA jika objek tersebut milik YAMF
                            XposedBridge.hookAllMethods(sysClass, "b", object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                    if (YamfShortcutManager.yamfShortcutObjects.contains(param.thisObject)) {
                                        param.result = "Open with YAMF"
                                    }
                                }
                            })

                            // Bajak Ikon Drawable HANYA jika objek tersebut milik YAMF
                            XposedBridge.hookAllMethods(sysClass, "a", object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                    if (YamfShortcutManager.yamfShortcutObjects.contains(param.thisObject)) {
                                        val context = param.args[0] as android.content.Context
                                        param.result = context.getDrawable(android.R.drawable.ic_menu_slideshow)
                                    }
                                }
                            })

                            // Bajak Eksekusi c(...) HANYA jika objek tersebut milik YAMF
                            XposedBridge.hookAllMethods(concreteClass, "c", object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                    if (YamfShortcutManager.yamfShortcutObjects.contains(param.thisObject)) {
                                        val appData = YamfShortcutManager.yamfAppData[param.thisObject]
                                        if (appData != null) {
                                            YamfShortcutManager.triggerYamf(appData)
                                        }
                                        // Cegah eksekusi bawaan tombol template (agar tidak membuka App Info bawaan)
                                        param.result = null
                                    }
                                }
                            })
                        }

                        // 2. DUPLIKASI OBJEK SECARA AMAN (Tanpa Unsafe, Variabel Terisi Sempurna)
                        val clonedShortcut = runCatching {
                            // Mencari method clone() jika ada
                            XposedHelpers.callMethod(template, "clone")
                        }.getOrElse {
                            // Jika tidak ada method clone, buat objek baru melalui refleksi copy instance dasar
                            val fields = concreteClass.declaredFields
                            val newInst = XposedHelpers.newInstance(concreteClass, *extractConstructorArgs(template))
                            fields.forEach { field ->
                                field.isAccessible = true
                                field.set(newInst, field.get(template))
                            }
                            newInst
                        }

                        // Tandai objek ini sebagai objek YAMF
                        YamfShortcutManager.yamfShortcutObjects.add(clonedShortcut)
                        YamfShortcutManager.yamfAppData[clonedShortcut] = YamfShortcutManager.AppData(componentName, userHandle)

                        // Masukkan ke daftar menu UI Transsion
                        try {
                            systemShortcuts.add(clonedShortcut)
                        } catch (e: UnsupportedOperationException) {
                            systemShortcuts = java.util.ArrayList(systemShortcuts)
                            systemShortcuts.add(clonedShortcut)
                            param.args[7] = systemShortcuts
                        }

                        log(TAG, "Berhasil menyuntikkan tombol YAMF aman ke Pop-up: $componentName")
                    }
                } catch (e: Throwable) {
                    log(TAG, "Gagal menyuntikkan tombol YAMF ke Pop-up Transsion", e)
                }
            }
        })
    }

    // Helper sederhana untuk mengekstrak parameter konstruktor dasar jika diperlukan
    private fun extractConstructorArgs(obj: Any): Array<Any?> {
        val constructors = obj.javaClass.constructors
        if (constructors.isNotEmpty()) {
            val pTypes = constructors[0].parameterTypes
            return Array(pTypes.size) { null }
        }
        return arrayOf()
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