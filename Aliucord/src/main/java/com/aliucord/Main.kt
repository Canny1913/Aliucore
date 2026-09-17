/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2024 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */
package com.aliucord

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aliucord.PluginManager.getPluginPrefKey
import com.aliucord.PluginManager.isPluginEnabled
import com.aliucord.PluginManager.isSafeModeEnabled
import com.aliucord.PluginManager.loadCorePlugins
import com.aliucord.PluginManager.loadPlugin
import com.aliucord.PluginManager.startCorePlugins
import com.aliucord.PluginManager.startPlugin
import com.aliucord.PluginManager.stopPlugin
import com.aliucord.Utils.appContext
import com.aliucord.Utils.openPage
import com.aliucord.Utils.showToast
import com.aliucord.api.NotificationsAPI
import com.aliucord.entities.CorePlugin
import com.aliucord.entities.NotificationData
import com.aliucord.patcher.Hook
import com.aliucord.patcher.Patcher
import com.aliucord.screens.UpdaterScreen
import com.aliucord.settings.AUTO_DISABLE_ON_CRASH_KEY
import com.aliucord.updater.*
import com.aliucord.updater.ManagerBuild.hasPatches
import com.aliucord.utils.ReflectUtils
import com.discord.app.AppActivity
import com.discord.models.domain.Model
import com.discord.models.domain.ModelUserSettings
import com.discord.widgets.chat.list.WidgetChatList
import java.io.*
import java.sql.Timestamp
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object Main {
    /** Whether Aliucord has been preInitialized  */
    var preInitialized: Boolean = false

    /** Whether Aliucord has been initialized  */
    var initialized: Boolean = false
    val logger: Logger = Logger()

    private var loadedPlugins = false

    lateinit var settings: SettingsUtilsJSON

    /**
     * Aliucord's preInit hook. Plugins are loaded here
     * @noinspection unused
     */
    @Suppress("unused")
    fun preInit(activity: AppActivity) {
        if (preInitialized) return
        preInitialized = true

        Utils.appActivity = activity

        if (checkPermissions(activity)) preInitWithPermissions(activity)

        // Since 1.4.0, this is implemented via a smali patch to ensure it works even if Aliucord failed to load
        if (!hasPatches("1.4.0")) {
            // Fix 2025-04-03 gateway change that ported visual refresh theme names over the legacy user settings
            // Theme entries like "darker" and "midnight" are unsupported
            Patcher.addPatch(ModelUserSettings::class.java,
                "assignField",
                arrayOf(Model.JsonReader::class.java),
                Hook { param ->
                    val modelUserSettings = param.thisObject as ModelUserSettings
                    when (modelUserSettings.theme) {
                        null, ModelUserSettings.THEME_DARK, ModelUserSettings.THEME_LIGHT, ModelUserSettings.THEME_PURE_EVIL -> return@Hook
                        else -> try {
                            ReflectUtils.setField(modelUserSettings, "theme", "dark")
                        } catch (e: Exception) {
                            logger.error("Failed to fix ModelUserSettings theme", e)
                        }
                    }
                })
        }

        Patcher.addPatch(AppActivity::class.java, "onCreate", arrayOf(Bundle::class.java), Hook { param ->
            Utils.appActivity = param.thisObject as AppActivity
        })

        Patcher.addPatch(WidgetChatList::class.java.getDeclaredConstructor(), Hook { param ->
            Utils.widgetChatList = param.thisObject as WidgetChatList
        })
    }

    private fun preInitWithPermissions(activity: AppCompatActivity) {
        createDirectories()
        settings = SettingsUtilsJSON("Aliucord")
        loadCorePlugins(activity)
        loadAllPlugins(activity)
    }

    private fun createDirectories() {
        val settingsDir = File(Constants.SETTINGS_PATH)
        if (!settingsDir.exists() && !settingsDir.mkdirs()) {
            throw RuntimeException(IOException("Failed to create Aliucord settings directory"))
        }

        val pluginsDir = File(Constants.PLUGINS_PATH)
        if (!pluginsDir.exists() && !pluginsDir.mkdirs()) {
            throw RuntimeException(IOException("Failed to create Aliucord plugins directory"))
        }
    }

    /**
     * Aliucord's init hook. Plugins are started here
     * @noinspection unused
     */
    fun init(activity: AppActivity?) {
        if (initialized) return
        initialized = true

        Thread.setDefaultUncaughtExceptionHandler(Main::crashHandler)

        if (loadedPlugins) {
            startCorePlugins()
            startAllPlugins()
        }
    }

    private fun crashHandler(thread: Thread, throwable: Throwable) {
        if (Looper.getMainLooper().thread != thread) {
            logger.error("Uncaught exception on thread " + thread.name, throwable)
            return
        }
        thread {
            Looper.prepare()
            var badPlugin: String? = null
            var disabledPlugin = false
            for (ele in throwable.stackTrace) {
                val className = ele.className

                for (entry in PluginManager.classLoaders.entries) {
                    try {
                        val loadedClass = entry.key.loadClass(className)
                        if (loadedClass.getClassLoader() != entry.key) {
                            // class was loaded from the parent classloader, ignore
                            continue
                        }

                        badPlugin = entry.value.name
                        if (settings.getBool(AUTO_DISABLE_ON_CRASH_KEY, true)) {
                            disabledPlugin = true
                            settings.setBool(getPluginPrefKey(badPlugin), false)
                        }
                        break
                    } catch (ignored: ClassNotFoundException) {
                    }
                }

                if (badPlugin != null) {
                    break
                }
            }
            val folder = File(Constants.CRASHLOGS_PATH)
            if (folder.exists() || folder.mkdir()) {
                val file = File(folder, Timestamp(System.currentTimeMillis()).toString().replace(":".toRegex(), "_") + ".txt")
                try {
                    PrintStream(file).use { ps ->
                        throwable.printStackTrace(ps)
                    }
                } catch (ignored: FileNotFoundException) {
                }
            }

            val sb = StringBuilder("An unrecoverable crash occurred. ")
            if (badPlugin != null) {
                sb.append("This crash was caused by ").append(badPlugin)
                if (disabledPlugin) {
                    sb.append(", so I automatically disabled it for you")
                }
                sb.append(". ")
            }
            sb.append("Check the crashes section in the settings for more info.")

            Toast.makeText(appContext, sb.toString(), Toast.LENGTH_LONG).show()
            Looper.loop()
        }.start()

        Thread.sleep(4200) // Wait for toast to end

        exitProcess(2)
    }

    private fun loadAllPlugins(context: Context) {
        if (isSafeModeEnabled()) {
            logger.warn("Safe mode is enabled. skipping loading external plugins")
            loadedPlugins = true
            return
        }
        val dir = File(Constants.PLUGINS_PATH)
        if (!dir.exists()) {
            val res = dir.mkdirs()
            if (!res) {
                logger.error("Failed to create directories!", null)
                return
            }
        }

        val sortedPlugins = dir.listFiles()
        if (sortedPlugins != null) {
            // Always sort plugins alphabetically for reproducible results
            sortedPlugins.sortBy { it.name }

            for (f in sortedPlugins) {
                val name = f.getName()
                if (name.endsWith(".zip")) {
                    loadPlugin(context, f)
                } else if (name != "oat") { // Some roms create this
                    if (f.isDirectory()) {
                        showToast(
                            String.format("Found directory %s in your plugins folder. DO NOT EXTRACT PLUGIN ZIPS!", name),
                            true
                        )
                    } else if (name == "classes.dex" || name.endsWith(".json")) {
                        showToast(
                            String.format("Found extracted plugin file %s in your plugins folder. DO NOT EXTRACT PLUGIN ZIPS!", name),
                            true
                        )
                    }
                    f.deleteRecursively()
                }
            }

            if (!PluginManager.failedToLoad.isEmpty()) showToast("Some plugins failed to load. Check the plugins page for more info.")
        }
        loadedPlugins = true
    }

    private fun startAllPlugins() {
        for (entry in PluginManager.plugins.entries) {
            // coreplugins are started separately
            if (entry.value is CorePlugin) continue

            val name = entry.key
            try {
                if (isPluginEnabled(name)) startPlugin(name)
            } catch (e: Throwable) {
                PluginManager.logger.error("Exception while starting plugin: $name", e)
                stopPlugin(name)
            }
        }

        Utils.threadPool.execute {
            if (CoreUpdater.isUpdaterDisabled()) return@execute
            CoreUpdater.checkForUpdates()
            checkForPluginUpdates()
        }
    }

    private fun checkForPluginUpdates() {
        val updates = PluginUpdater.fetchUpdates(PluginUpdaterSource())
        if (updates.isEmpty()) return

        if (!PluginUpdater.isAutoUpdateEnabled()) {
            val notificationData = NotificationData()
                .setTitle("Updater")
                .setBody(String.format("Found %s available plugin updates! Click to view...", updates.size))
                .setAutoDismissPeriodSecs(30)
                .setOnClick { view: View? ->
                    openPage(Utils.appActivity, UpdaterScreen::class.java)
                    Constants.UNIT
                }

            NotificationsAPI.display(notificationData)
            return
        }

        var failed = 0
        val succeeded = ArrayList<String?>()
        for (update in updates) {
            if (!update.isUpdatePossible()) continue
            if (PluginUpdater.updatePlugin(update)) {
                succeeded.add(update.pluginName)
            } else {
                failed++
            }
        }

        val notification = NotificationData()
            .setTitle("Updater")
        if (failed > 0) {
            notification
                .setAutoDismissPeriodSecs(30)
                .setBody(String.format("Failed to update %s plugins! Click to view...", failed))
                .setOnClick { _: View? ->
                    openPage(Utils.appActivity, UpdaterScreen::class.java)

                }
        } else {
            notification
                .setAutoDismissPeriodSecs(10)
                .setOnClick { _: View? -> Unit.a }
                .setBody(("Automatically updated plugins: "
                    + ", " + succeeded.take(5)
                    + (if (succeeded.size > 5) ", and ${succeeded.size} others." else "")))
        }
        NotificationsAPI.display(notification)
    }

    private fun permissionGrantedCallback(activity: AppCompatActivity, granted: Boolean) {
        if (granted) {
            preInitWithPermissions(activity)
            startCorePlugins()
            startAllPlugins()
        } else Toast.makeText(activity, "You have to grant storage permission to use Aliucord", Toast.LENGTH_LONG).show()
    }

    private fun checkPermissions(activity: AppCompatActivity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageLegacy()) {
            if (Environment.isExternalStorageManager()) return true
            Toast.makeText(
                activity,
                "Please grant all files permission, so Aliucord can access its folder in Internal Storage",
                Toast.LENGTH_LONG
            ).show()
            activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result: ActivityResult? -> permissionGrantedCallback(activity, Environment.isExternalStorageManager()) }.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:" + activity.packageName))
            )
            return false
        }

        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return true
        activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted: Boolean? -> permissionGrantedCallback(activity, granted!!) }.launch(perm)
        return false
    }
}
