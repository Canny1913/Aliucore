/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2024 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */
package com.aliucord

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.aliucord.api.NotificationsAPI
import com.aliucord.entities.CorePlugin
import com.aliucord.entities.NotificationData
import com.aliucord.patcher.Hook
import com.aliucord.patcher.Patcher
import com.aliucord.screens.UpdaterScreen
import com.aliucord.settings.ALIUCORD_SAFE_MODE_KEY
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object Main {
    /** Whether Aliucord Activity entrypoint has run at least once */
    var activityInitialized: AtomicBoolean = AtomicBoolean(false)
    @JvmField
    val logger: Logger = Logger()

    private var loadedPlugins = false

    /** Disables external plugins and non-essential core plugins. */
    @JvmField
    var safeMode = false

    lateinit var settings: SettingsUtilsJSON

    /**
     * Initial entrypoint for Aliucord. early-init plugins are loaded here
     * @noinspection unused
     */
    @Suppress("unused")
    fun onApplicationInit(app: Application) {
        Thread.setDefaultUncaughtExceptionHandler(Main::crashHandler)

        createDirectories() // injector ensures we have perms
        settings = SettingsUtilsJSON("Aliucord")
        safeMode = settings.getBool(ALIUCORD_SAFE_MODE_KEY, false)

        Patcher.addPatch(AppActivity::class.java, "onCreate", arrayOf(Bundle::class.java), Hook { param ->
            val activity = param.thisObject as AppActivity
            Utils.appActivity = activity // reset due to possible activity recreation

            if (!activityInitialized.getAndSet(true)) {
                onActivityInit(activity)
            }
        })

        Patcher.addPatch(WidgetChatList::class.java.getDeclaredConstructor(), Hook { param ->
            Utils.widgetChatList = param.thisObject as WidgetChatList
        })

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
    }
    fun onActivityInit(activity: AppActivity) {
        PluginManager.loadCorePlugins(activity)
        loadAllPlugins(activity)

        if (loadedPlugins) {
            PluginManager.startCorePlugins()
            startAllPlugins()
        }
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
                            settings.setBool(PluginManager.getPluginPrefKey(badPlugin), false)
                        }
                        break
                    } catch (_: ClassNotFoundException) {
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
                } catch (_: FileNotFoundException) {
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

            Toast.makeText(Utils.appContext, sb.toString(), Toast.LENGTH_LONG).show()
            Looper.loop()
        }.start()

        Thread.sleep(4200) // Wait for toast to end

        exitProcess(2)
    }

    private fun loadAllPlugins(context: Context) {
        if (safeMode) {
            logger.warn("Safe mode is enabled. skipping loading external plugins")
            loadedPlugins = true
            return
        }
        val dir = File(Constants.PLUGINS_PATH)

        val sortedPlugins = dir.listFiles()
        if (sortedPlugins != null) {
            // Always sort plugins alphabetically for reproducible results
            sortedPlugins.sortBy { it.name }

            for (f in sortedPlugins) {
                val name = f.getName()
                if (name.endsWith(".zip")) {
                    PluginManager.loadPlugin(context, f)
                } else if (name != "oat") { // Some roms create this
                    if (f.isDirectory()) {
                        Utils.showToast(
                            "Found directory $name in your plugins folder. DO NOT EXTRACT PLUGIN ZIPS!",
                            true
                        )
                    } else if (name == "classes.dex" || name.endsWith(".json")) {
                        Utils.showToast(
                            "Found extracted plugin file $name in your plugins folder. DO NOT EXTRACT PLUGIN ZIPS!",
                            true
                        )
                    }
                    f.deleteRecursively()
                }
            }

            if (!PluginManager.failedToLoad.isEmpty()) Utils.showToast("Some plugins failed to load. Check the plugins page for more info.")
        }
        loadedPlugins = true
    }

    private fun startAllPlugins() {
        for (entry in PluginManager.plugins.entries) {
            // coreplugins are started separately
            if (entry.value is CorePlugin) continue

            val name = entry.key
            try {
                if (PluginManager.isPluginEnabled(name)) PluginManager.startPlugin(name)
            } catch (e: Throwable) {
                PluginManager.logger.error("Exception while starting plugin: $name", e)
                PluginManager.stopPlugin(name)
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
                .setBody("Found ${updates.size} available plugin updates! Click to view...")
                .setAutoDismissPeriodSecs(30)
                .setOnClick { _: View? ->
                    Utils.openPage(Utils.appActivity, UpdaterScreen::class.java)
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
                .setBody("Failed to update $failed plugins! Click to view...")
                .setOnClick { _: View? ->
                    Utils.openPage(Utils.appActivity, UpdaterScreen::class.java)
                }
        } else {
            notification
                .setAutoDismissPeriodSecs(10)
                .setBody(("Automatically updated plugins: "
                    + ", " + succeeded.take(5)
                    + (if (succeeded.size > 5) ", and ${succeeded.size} others." else "")))
        }
        NotificationsAPI.display(notification)
    }
}
