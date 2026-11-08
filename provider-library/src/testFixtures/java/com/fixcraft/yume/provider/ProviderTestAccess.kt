package com.fixcraft.yume.provider

/** Test-only access. This source set is absent from both production APKs. */
object ProviderTestAccess {
    fun beginState(owner: ProviderAuthorization, context: ProviderCodec.Context,
                   descriptor: ProviderCodec.Descriptor, epoch: String,
                   registration: ProviderRegistration, generation: Long): String =
        owner.beginRequest(context, descriptor, epoch, registration, generation).toUri().getQueryParameter("state")!!
}
