package app.revanced.utils.adb

import app.revanced.cli.command.MainCommand.logger
import se.vidstige.jadb.JadbConnection
import se.vidstige.jadb.JadbDevice
import se.vidstige.jadb.managers.PackageManager
import java.io.File
import java.util.concurrent.Executors

internal class Adb(
    private val file: File,
    private val packageName: String,
    deviceName: String,
    private val modeInstall: Boolean = false,
    private val logging: Boolean = true
) {
    private val device: JadbDevice

    init {
        device = JadbConnection().devices.find { it.serial == deviceName }
            ?: throw IllegalArgumentException("No such device with name $deviceName")

        if (!modeInstall && device.run("su -h", false) != 0)
            throw IllegalArgumentException("Root required on $deviceName. Deploying failed")
    }

    private fun String.replacePlaceholder(with: String? = null): String {
        return this.replace(Constants.PLACEHOLDER, with ?: packageName)
    }

    internal fun deploy() {
        if (modeInstall) {
            logger.info("Installing without mounting")

            PackageManager(device).install(file)
        } else {
            logger.info("Installing by mounting")

            // push patched file
            device.copy(Constants.PATH_INIT_PUSH, file)

            // create revanced path
            device.run("${Constants.COMMAND_CREATE_DIR} ${Constants.PATH_REVANCED}")

            // prepare mounting the apk
            device.run(Constants.COMMAND_PREPARE_MOUNT_APK.replacePlaceholder())

            // push mount script
            device.createFile(
                Constants.PATH_INIT_PUSH,
                Constants.CONTENT_MOUNT_SCRIPT.replacePlaceholder()
            )
            // install mount script
            device.run(Constants.COMMAND_INSTALL_MOUNT.replacePlaceholder())

            // push umount script
            device.createFile(
                Constants.PATH_INIT_PUSH,
                Constants.CONTENT_UMOUNT_SCRIPT.replacePlaceholder()
            )
            // install mount script
            device.run(Constants.COMMAND_INSTALL_UMOUNT.replacePlaceholder())

            // unmount the apk for sanity
            device.run(Constants.PATH_UMOUNT.replacePlaceholder())
            // mount the apk
            device.run(Constants.PATH_MOUNT.replacePlaceholder())

            // relaunch app
            device.run(Constants.COMMAND_RESTART.replacePlaceholder())

            // log the app
            log()
        }
    }

    private fun log() {
        val executor = Executors.newSingleThreadExecutor()
        val pipe = if (logging) {
            ProcessBuilder.Redirect.INHERIT
        } else {
            ProcessBuilder.Redirect.PIPE
        }

        val process = device.buildCommand(Constants.COMMAND_LOGCAT.replacePlaceholder())
            .redirectOutput(pipe)
            .redirectError(pipe)
            .useExecutor(executor)
            .start()

        Thread.sleep(500) // give the app some time to start up.
        while (true) {
            try {
                while (device.run("${Constants.COMMAND_PID_OF} $packageName") == 0) {
                    Thread.sleep(1000)
                }
                break
            } catch (e: Exception) {
                throw RuntimeException("An error occurred while monitoring the state of app", e)
            }
        }
        logger.info("Stopped logging because the app was closed")
        process.destroy()
        executor.shutdown()
    }
}