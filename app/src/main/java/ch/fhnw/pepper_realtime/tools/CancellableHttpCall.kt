package ch.fhnw.pepper_realtime.tools

import okhttp3.Call
import okhttp3.Response

inline fun <T> ToolContext.withHttpCall(call: Call, read: (Response) -> T): T {
    ensureActive()
    val cancellation = onCancel { call.cancel() }
    return try { call.execute().use(read) } finally { cancellation.close() }
}
