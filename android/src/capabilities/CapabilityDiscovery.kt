package dev.deal.embedding.capabilities

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android discovery by protocol action, never by provider package/method name. Call off main. */
class CapabilityDiscovery(private val context: Context, private val trusted: (Int) -> Boolean) : AutoCloseable {
    data class Endpoint(val component: ComponentName, val uid: Int, val contract: CapabilityContract, val remote: ICapabilityService)
    private val connections = mutableMapOf<ComponentName, ServiceConnection>()
    private val endpoints = mutableMapOf<ComponentName, Endpoint>()
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    fun discover(): List<Endpoint> {
        check(Looper.myLooper() != Looper.getMainLooper())
        val services = context.packageManager.queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA)
        require(services.size <= 32)
        for (resolved in services) {
            val info = resolved.serviceInfo
            if (!info.exported || !trusted(info.applicationInfo.uid)) continue
            val component = ComponentName(info.packageName, info.name)
            synchronized(this) { if (closed) error("Discovery closed") }
            if (synchronized(this) { endpoints.containsKey(component) }) continue
            release(component)
            val latch = CountDownLatch(1)
            var binder: IBinder? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; latch.countDown() }
                override fun onServiceDisconnected(name: ComponentName) { synchronized(this@CapabilityDiscovery) { endpoints.remove(component) } }
                override fun onBindingDied(name: ComponentName) { release(component); latch.countDown() }
                override fun onNullBinding(name: ComponentName) { release(component); latch.countDown() }
            }
            synchronized(this) { connections[component] = connection }
            main.post {
                try { if (!context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)) latch.countDown() }
                catch (_: SecurityException) { latch.countDown() }
            }
            if (!latch.await(5, TimeUnit.SECONDS) || binder == null) { release(component); continue }
            try {
                val remote = ICapabilityService.Stub.asInterface(binder)
                val contract = CapabilityContract.parse(remote.describe())
                val endpoint = Endpoint(component, info.applicationInfo.uid, contract, remote)
                binder.linkToDeath({ release(component) }, 0)
                synchronized(this) { if (!closed) endpoints[component] = endpoint }
            } catch (_: Exception) { release(component) }
        }
        return synchronized(this) { endpoints.values.toList() }
    }
    private fun release(component: ComponentName) {
        val connection = synchronized(this) { endpoints.remove(component); connections.remove(component) }
        if (connection != null) main.post { try { context.unbindService(connection) } catch (_: IllegalArgumentException) {} }
    }
    override fun close() { val keys = synchronized(this) { closed = true; connections.keys.toList() }; keys.forEach(::release) }
    companion object { const val ACTION = "dev.deal.embedding.CAPABILITIES_V1" }
}
