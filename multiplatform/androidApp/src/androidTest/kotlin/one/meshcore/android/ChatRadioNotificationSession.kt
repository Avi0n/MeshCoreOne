package one.meshcore.android

import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.meshcore.MeshSession
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class ChatRadioNotificationSession {
    private val delegate = RecordingReplySession()
    val floodCalls = AtomicInteger()
    val batteryReads = AtomicInteger()
    val otherCalls = AtomicInteger()
    val otherMethods = CopyOnWriteArrayList<String>()
    val session: MeshSession = Proxy.newProxyInstance(
        MeshSession::class.java.classLoader,
        arrayOf(MeshSession::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "setFloodScope", "setFloodScopeUnscoped" -> {
                floodCalls.incrementAndGet()
                Unit
            }
            "getEvents" -> delegate.events
            "getBattery" -> {
                batteryReads.incrementAndGet()
                BatteryInfo(level = 0)
            }
            "close" -> Unit
            "toString" -> "Synthetic chat radio session"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> arguments?.firstOrNull() === proxy
            else -> {
                otherCalls.incrementAndGet()
                otherMethods.add(method.name)
                try {
                    method.invoke(delegate, *(arguments ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
        }
    } as MeshSession
}
