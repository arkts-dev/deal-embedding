package dev.deal.embedding

/** Independent owners must all be stopped before cleanup failures are reported. */
fun teardown(vararg operations:()->Unit) {
    var primary:Throwable?=null
    for(operation in operations)try{operation()}catch(error:Throwable){
        val first=primary
        if(first==null)primary=error else if(first!==error)first.addSuppressed(error)
    }
    primary?.let{throw it}
}
