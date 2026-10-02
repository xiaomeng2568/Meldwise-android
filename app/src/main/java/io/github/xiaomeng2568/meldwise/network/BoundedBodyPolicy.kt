package io.github.xiaomeng2568.meldwise.network

/** Bounds apply to bytes actually read (after OkHttp's transparent decompression).
 * Only a successful account model catalog gets extra room for provider metadata.
 * Streams continue to use SseParser, not this buffered request path.
 */
internal object BoundedBodyPolicy {
    const val CONSERVATIVE_BYTES = 256 * 1024
    const val MODEL_CATALOG_BYTES = 2 * 1024 * 1024

    fun limit(operation: Operation, status: Int): Int =
        if (operation == Operation.MODELS && status == 200) MODEL_CATALOG_BYTES else CONSERVATIVE_BYTES
}
