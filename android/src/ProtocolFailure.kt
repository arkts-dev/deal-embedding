package dev.deal.embedding

/** A classified public failure: the host sees `code` and a bounded, located message. */
open class ProtocolFailure(val code:String, message:String):IllegalStateException(message)
