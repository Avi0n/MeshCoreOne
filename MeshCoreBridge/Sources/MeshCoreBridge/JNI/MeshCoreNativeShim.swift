#if canImport(SwiftJavaJNICore)
import Foundation
import MeshCore
import SwiftJava
import SwiftJavaJNICore
import SwiftJavaRuntimeSupport

// A hand-rolled JNI construction entry point for the Kotlin `meshcore-kt` layer.
//
// jextract (JNI mode, swift-java 0.4.2) generates correct Kotlin->Swift downcalls for every
// `MeshCoreBridge` command, but exports the four listener protocols as Swift-backed
// `JNISwiftInstance` interfaces: the generated `$init` thunk dereferences each listener's
// `memoryAddress`, so a Kotlin object implementing the interface cannot be passed. Only the
// constructor is unusable. This file supplies the missing piece — construction from a Kotlin
// callback object — and leaves all command/lifecycle calls to the generated bindings.
//
// The four `BridgeSendListener`/`BridgeResultListener`/`BridgeEventListener`/`BridgeLogListener`
// conformers below hold a global reference to one Kotlin object and forward each callback to it
// through raw JNI. Swift drives these from its own threads (the event-forwarding task, the command
// dispatch tasks), never a JNI native frame, so every upcall attaches the current thread and opens
// its own local-reference frame.
//
// Ownership: `nativeCreate` returns a raw `UnsafeMutablePointer<MeshCoreBridge>` as a jlong, exactly
// as the generated `$init` does. The Kotlin side wraps it with `MeshCoreBridge.wrapMemoryAddressUnsafe`
// registered in a *confined* `SwiftArena`; closing that arena runs `SwiftObjects.destroy`, which
// releases the bridge (cancelling its event task) and, transitively, this file's callback holder
// (whose deinit drops the JNI global references). The confined arena is used deliberately: the auto
// (GC) arena would destroy the bridge at a nondeterministic time while the event task still runs.

/// Obtains a JNI environment for the calling Swift thread, attaching it to the JVM as a daemon.
///
/// The JVM was cached by swift-java's own `JNI_OnLoad` when `libMeshCoreBridgeKit.so` loaded, so
/// `shared()` adopts it without creating one. Attaching as a daemon lets the JVM exit without
/// waiting on Swift's cooperative-pool threads.
private func attachedEnvironment() -> JNIEnvironment? {
  guard let vm = try? JavaVirtualMachine.shared() else { return nil }
  return try? vm.environment(asDaemon: true)
}

/// Holds the Kotlin callback object and the resolved method IDs shared by all four conformers.
///
/// One global reference per bridge; released in `deinit` when the bridge (and thus every conformer
/// referencing this) is destroyed. `jmethodID`s stay valid for the lifetime of the retained class
/// reference, so they are resolved once at construction.
private final class KotlinCallbackTarget: @unchecked Sendable {
  let target: jobject
  let targetClass: jclass
  let sendBytes: jmethodID
  let advertisesWriteWithoutResponse: jmethodID
  let supportsPipelinedReads: jmethodID
  let onSuccess: jmethodID
  let onFailure: jmethodID
  let onEvent: jmethodID
  let onLog: jmethodID

  init?(environment env: JNIEnvironment, callbacks: jobject) {
    guard let globalTarget = env.interface.NewGlobalRef(env, callbacks),
          let localClass = env.interface.GetObjectClass(env, callbacks),
          let globalClass = env.interface.NewGlobalRef(env, localClass),
          let sendBytes = env.interface.GetMethodID(env, globalClass, "sendBytes", "([BZ)V"),
          let advertisesWriteWithoutResponse = env.interface.GetMethodID(
            env, globalClass, "advertisesWriteWithoutResponse", "()Z"
          ),
          let supportsPipelinedReads = env.interface.GetMethodID(
            env, globalClass, "supportsPipelinedReads", "()Z"
          ),
          let onSuccess = env.interface.GetMethodID(env, globalClass, "onSuccess", "(J[B)V"),
          let onFailure = env.interface.GetMethodID(env, globalClass, "onFailure", "(JILjava/lang/String;)V"),
          let onEvent = env.interface.GetMethodID(env, globalClass, "onEvent", "([B)V"),
          let onLog = env.interface.GetMethodID(env, globalClass, "onLog", "(Ljava/lang/String;)V")
    else { return nil }
    env.interface.DeleteLocalRef(env, localClass)
    self.target = globalTarget
    self.targetClass = globalClass
    self.sendBytes = sendBytes
    self.advertisesWriteWithoutResponse = advertisesWriteWithoutResponse
    self.supportsPipelinedReads = supportsPipelinedReads
    self.onSuccess = onSuccess
    self.onFailure = onFailure
    self.onEvent = onEvent
    self.onLog = onLog
  }

  deinit {
    guard let env = attachedEnvironment() else { return }
    env.interface.DeleteGlobalRef(env, target)
    env.interface.DeleteGlobalRef(env, targetClass)
  }

  /// Invokes a void Kotlin method inside a fresh local-reference frame so any argument references
  /// (byte arrays, strings) created for the call are reclaimed when it returns.
  func callVoid(_ method: jmethodID, buildArguments: (JNIEnvironment) -> [jvalue]) {
    guard let env = attachedEnvironment() else { return }
    guard env.interface.PushLocalFrame(env, 8) == 0 else { return }
    let arguments = buildArguments(env)
    arguments.withUnsafeBufferPointer { buffer in
      env.interface.CallVoidMethodA(env, target, method, buffer.baseAddress)
    }
    if env.interface.ExceptionCheck(env) != 0 {
      env.interface.ExceptionClear(env)
    }
    _ = env.interface.PopLocalFrame(env, nil)
  }

  /// Invokes a Kotlin `()Z` method. Returns false on attach/JNI failure so a
  /// missing capability cannot enable pipelining.
  func callBoolean(_ method: jmethodID) -> Bool {
    guard let env = attachedEnvironment() else { return false }
    guard env.interface.PushLocalFrame(env, 4) == 0 else { return false }
    let result = env.interface.CallBooleanMethodA(env, target, method, nil)
    if env.interface.ExceptionCheck(env) != 0 {
      env.interface.ExceptionClear(env)
      _ = env.interface.PopLocalFrame(env, nil)
      return false
    }
    _ = env.interface.PopLocalFrame(env, nil)
    return result != 0
  }
}

private func objectValue(_ object: jobject?) -> jvalue {
  var value = jvalue()
  value.l = object
  return value
}

private func longValue(_ raw: Int64) -> jvalue {
  var value = jvalue()
  value.j = jlong(raw)
  return value
}

private func intValue(_ raw: Int32) -> jvalue {
  var value = jvalue()
  value.i = jint(raw)
  return value
}

private func boolValue(_ raw: Bool) -> jvalue {
  var value = jvalue()
  value.z = raw ? jboolean(JNI_TRUE) : jboolean(JNI_FALSE)
  return value
}

private final class JNISendListener: BridgeSendListener {
  let callbacks: KotlinCallbackTarget
  init(_ callbacks: KotlinCallbackTarget) { self.callbacks = callbacks }

  func sendBytes(_ bytes: [UInt8], withoutResponse: Bool) {
    callbacks.callVoid(callbacks.sendBytes) { env in
      [objectValue(bytes.getJNIValue(in: env)), boolValue(withoutResponse)]
    }
  }

  var advertisesWriteWithoutResponse: Bool {
    callbacks.callBoolean(callbacks.advertisesWriteWithoutResponse)
  }

  var supportsPipelinedReads: Bool {
    callbacks.callBoolean(callbacks.supportsPipelinedReads)
  }
}

private final class JNIResultListener: BridgeResultListener {
  let callbacks: KotlinCallbackTarget
  init(_ callbacks: KotlinCallbackTarget) { self.callbacks = callbacks }

  func onSuccess(requestID: Int64, resultBytes: [UInt8]) {
    callbacks.callVoid(callbacks.onSuccess) { env in
      [longValue(requestID), objectValue(resultBytes.getJNIValue(in: env))]
    }
  }

  func onFailure(requestID: Int64, errorCode: Int32, message: String) {
    callbacks.callVoid(callbacks.onFailure) { env in
      [longValue(requestID), intValue(errorCode), objectValue(message.getJNIValue(in: env))]
    }
  }
}

private final class JNIEventListener: BridgeEventListener {
  let callbacks: KotlinCallbackTarget
  init(_ callbacks: KotlinCallbackTarget) { self.callbacks = callbacks }

  func onEvent(eventBytes: [UInt8]) {
    callbacks.callVoid(callbacks.onEvent) { env in
      [objectValue(eventBytes.getJNIValue(in: env))]
    }
  }
}

private final class JNILogListener: BridgeLogListener {
  let callbacks: KotlinCallbackTarget
  init(_ callbacks: KotlinCallbackTarget) { self.callbacks = callbacks }

  func onLog(line: String) {
    callbacks.callVoid(callbacks.onLog) { env in
      [objectValue(line.getJNIValue(in: env))]
    }
  }
}

/// Constructs a `MeshCoreBridge` whose four listeners forward to a single Kotlin callback object.
///
/// Returns the bridge as a raw `UnsafeMutablePointer<MeshCoreBridge>` encoded in a jlong, matching
/// the generated `$init` so that `MeshCoreBridge.wrapMemoryAddressUnsafe` and `SwiftObjects.destroy`
/// operate on it unchanged. Returns 0 if the callback methods cannot be resolved.
#if compiler(>=6.3)
@used
#endif
@_cdecl("Java_one_meshcore_shared_meshcore_jni_MeshCoreNative_nativeCreate")
public func Java_one_meshcore_shared_meshcore_jni_MeshCoreNative_nativeCreate(
  environment env: JNIEnvironment!,
  thisClass: jclass,
  callbacks: jobject?,
  advertisesWriteWithoutResponse: jboolean,
  clientIdentifier: jstring?
) -> jlong {
  guard let callbacks,
        let target = KotlinCallbackTarget(environment: env, callbacks: callbacks)
  else { return 0 }

  let bridge = MeshCoreBridge(
    sendListener: JNISendListener(target),
    resultListener: JNIResultListener(target),
    eventListener: JNIEventListener(target),
    logListener: JNILogListener(target),
    advertisesWriteWithoutResponse: Bool(fromJNI: advertisesWriteWithoutResponse, in: env),
    clientIdentifier: String(fromJNI: clientIdentifier, in: env)
  )

  let pointer = UnsafeMutablePointer<MeshCoreBridge>.allocate(capacity: 1)
  pointer.initialize(to: bridge)
  return jlong(Int64(Int(bitPattern: pointer)))
}

/// Reads a Java byte array into a Swift buffer. Returns an empty array for a null reference, which
/// the request decoder rejects as malformed.
private func bytes(from array: jbyteArray?, in env: JNIEnvironment) -> [UInt8] {
  guard let array else { return [] }
  let count = Int(env.interface.GetArrayLength(env, array))
  guard count > 0 else { return [] }
  var buffer = [UInt8](repeating: 0, count: count)
  buffer.withUnsafeMutableBytes { raw in
    env.interface.GetByteArrayRegion(
      env, array, 0, jsize(count),
      raw.baseAddress!.assumingMemoryBound(to: jbyte.self)
    )
  }
  return buffer
}

/// Decrypts a batch of RX-log rows, the one bridge call that answers on the calling thread.
///
/// Every other command is fire-and-forget because it waits on the radio. This one waits on nothing:
/// it is a pure function of the request bytes, so returning the response directly costs the caller
/// one boundary crossing instead of a round trip through the result listener.
#if compiler(>=6.3)
@used
#endif
@_cdecl("Java_one_meshcore_shared_meshcore_jni_MeshCoreNative_nativeDecryptRxLog")
public func Java_one_meshcore_shared_meshcore_jni_MeshCoreNative_nativeDecryptRxLog(
  environment env: JNIEnvironment!,
  thisClass: jclass,
  request: jbyteArray?
) -> jbyteArray? {
  RxLogDecryptService.decrypt(requestBytes: bytes(from: request, in: env)).getJNIValue(in: env)
}

/// Attributes a batch of received packets to their flood regions, answering on the calling thread
/// for the same reason the decrypt entry point does: it is a pure function of the request bytes.
#if compiler(>=6.3)
@used
#endif
@_cdecl("Java_one_meshcore_shared_meshcore_jni_MeshCoreNative_nativeResolveRegions")
public func Java_one_meshcore_shared_meshcore_jni_MeshCoreNative_nativeResolveRegions(
  environment env: JNIEnvironment!,
  thisClass: jclass,
  request: jbyteArray?
) -> jbyteArray? {
  RegionResolveService.resolve(requestBytes: bytes(from: request, in: env)).getJNIValue(in: env)
}
#endif
