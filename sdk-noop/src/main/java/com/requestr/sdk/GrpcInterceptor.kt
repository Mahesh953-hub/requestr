package com.requestr.sdk

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor

/**
 * No-op counterpart to the real `:sdk` module's `GrpcInterceptor.kt` -- see there for the full
 * contract this mirrors. Never calls [ManagedChannelBuilder.intercept]: [builder] is returned
 * completely unmodified, so calls made through it never pass through [RequestrGrpcInterceptor]
 * at all.
 */
fun <T : ManagedChannelBuilder<T>> ManagedChannelBuilder<T>.addRequestrInterceptor(observer: RequestrObserver): ManagedChannelBuilder<T> = this

/** No-op counterpart to the real `:sdk` module's `RequestrGrpcInterceptor` -- even if a host app
 * constructs and installs this directly (bypassing [addRequestrInterceptor] above), every call
 * passes straight through to [next] with no wrapping, so nothing is captured. */
class RequestrGrpcInterceptor(private val observer: RequestrObserver) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> = next.newCall(method, callOptions)
}
