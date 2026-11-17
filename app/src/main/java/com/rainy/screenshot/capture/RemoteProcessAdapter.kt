package com.rainy.screenshot.capture

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Adapts Shizuku's public AIDL IRemoteProcess to java.lang.Process, because Shizuku's own
 * ShizukuRemoteProcess constructor is package-private.
 */
class RemoteProcessAdapter(
    private val remote: IRemoteProcess
) : Process() {

    private val destroyLock = ReentrantLock()
    @Volatile
    private var destroyed = false

    @Volatile
    private var osPfd: ParcelFileDescriptor? = null
    @Volatile
    private var isPfd: ParcelFileDescriptor? = null
    @Volatile
    private var esPfd: ParcelFileDescriptor? = null

    override fun getOutputStream(): OutputStream {
        val pfd = osPfd ?: remote.outputStream.also { osPfd = it }
        return ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    }

    override fun getInputStream(): InputStream {
        val pfd = isPfd ?: remote.inputStream.also { isPfd = it }
        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    override fun getErrorStream(): InputStream {
        val pfd = esPfd ?: remote.errorStream.also { esPfd = it }
        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    @Throws(InterruptedException::class)
    override fun waitFor(): Int = try {
        remote.waitFor()
    } catch (e: RemoteException) {
        throw RuntimeException(e)
    }

    override fun exitValue(): Int = try {
        remote.exitValue()
    } catch (e: RemoteException) {
        throw RuntimeException(e)
    }

    override fun destroy() {
        destroyLock.withLock {
            if (destroyed) return
            destroyed = true
        }
        runCatching { remote.destroy() }

        runCatching { isPfd?.close() }
        runCatching { esPfd?.close() }
        runCatching { osPfd?.close() }
    }

    fun alive(): Boolean = try {
        remote.alive()
    } catch (e: RemoteException) {
        false
    }

    companion object {

        fun create(command: Array<String>): RemoteProcessAdapter {
            val binder = Shizuku.getBinder()
                ?: throw IllegalStateException("Shizuku binder not available")
            val service = IShizukuService.Stub.asInterface(binder)
            val remote = try {
                service.newProcess(command, null, null)
            } catch (e: RemoteException) {
                throw RuntimeException("newProcess failed: ${e.message}", e)
            }
            return RemoteProcessAdapter(remote)
        }
    }
}
