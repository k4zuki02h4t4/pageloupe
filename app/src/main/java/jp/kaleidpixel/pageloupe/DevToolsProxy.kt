package jp.kaleidpixel.pageloupe

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class DevToolsTarget(
    val id: String,
    val type: String,
    val title: String,
    val url: String,
    val webSocketDebuggerUrl: String,
    val devtoolsFrontendUrl: String
)

/**
 * Direct access to this process's WebView DevTools socket
 * (abstract unix socket @webview_devtools_remote_<pid>).
 * Only reachable while WebView debugging is enabled.
 */
object DevToolsSocket {

    fun connect(): LocalSocket {
        // Only this process's own socket (pid-suffixed name). The
        // pid-less name is not used as a fallback: any app can bind
        // it, so answering there would break the premise that the
        // target list comes from our own WebView. The name
        // alone does not prove the peer either (an abstract name
        // belongs to whoever binds it first, and pids are easy to
        // guess), so the peer's credentials are verified right after
        // connecting. The trust in the target list rests on this
        // check, not on the name.
        val name = "webview_devtools_remote_" + Process.myPid()
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            val peer = s.peerCredentials
            if (peer == null || peer.uid != Process.myUid() || peer.pid != Process.myPid()) {
                // A mismatch means someone else took the name first;
                // leave a trace (the proxy path would otherwise close
                // silently).
                DebugLog.log("proxy", "rejected devtools socket peer (uid=${peer?.uid}, pid=${peer?.pid})")
                throw IOException("DevTools ソケットの相手が自プロセスではありません")
            }
            s.soTimeout = 10000
            return s
        } catch (e: Exception) {
            // A failed connect must not leak the socket's fd
            // until GC.
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * GET /json/list straight from the socket. The Host header carries our
     * proxy address so the returned devtoolsFrontendUrl / webSocketDebuggerUrl
     * already point back at the proxy.
     */
    fun fetchTargets(proxyPort: Int): List<DevToolsTarget> {
        val sock = connect()
        try {
            sock.soTimeout = 3000
            val req = "GET /json/list HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$proxyPort\r\n" +
                "Connection: close\r\n\r\n"
            sock.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            sock.outputStream.flush()
            val body = readHttpBody(sock.inputStream)
            val arr = JSONArray(body)
            val out = mutableListOf<DevToolsTarget>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    DevToolsTarget(
                        id = o.optString("id"),
                        type = o.optString("type"),
                        title = o.optString("title"),
                        url = o.optString("url"),
                        webSocketDebuggerUrl = o.optString("webSocketDebuggerUrl"),
                        devtoolsFrontendUrl = o.optString("devtoolsFrontendUrl")
                    )
                )
            }
            return out
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    fun readUntilEof(input: InputStream): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /**
     * Read one HTTP response body. Honors Content-Length so we do not block
     * waiting for the server to close a keep-alive connection.
     */
    fun readHttpBody(input: InputStream): String {
        // Headers, byte by byte until CRLFCRLF.
        val headBuf = ByteArrayOutputStream()
        val pattern = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (headBuf.size() < 65536) {
            val b = input.read()
            if (b < 0) break
            headBuf.write(b)
            matched = when {
                b.toByte() == pattern[matched] -> matched + 1
                b.toByte() == pattern[0] -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        val head = headBuf.toByteArray().toString(Charsets.ISO_8859_1)
        var contentLength = -1
        var chunked = false
        for (line in head.split("\r\n")) {
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) {
                contentLength = lower.substringAfter(':').trim().toIntOrNull() ?: -1
            }
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true
            }
        }
        val body = ByteArrayOutputStream()
        if (contentLength >= 0) {
            val buf = ByteArray(8192)
            var remaining = contentLength
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n < 0) break
                body.write(buf, 0, n)
                remaining -= n
            }
        } else if (chunked) {
            // Minimal chunked reader.
            while (true) {
                var sizeLine = ""
                while (true) {
                    val b = input.read()
                    if (b < 0) return body.toByteArray().toString(Charsets.UTF_8)
                    if (b == 13) {
                        input.read() // LF
                        break
                    }
                    sizeLine += b.toChar()
                }
                val size = sizeLine.trim().toIntOrNull(16) ?: 0
                if (size <= 0) break
                var remaining = size
                val buf = ByteArray(8192)
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size, remaining))
                    if (n < 0) break
                    body.write(buf, 0, n)
                    remaining -= n
                }
                input.read() // CR
                input.read() // LF
            }
        } else {
            body.write(readUntilEof(input))
        }
        return body.toByteArray().toString(Charsets.UTF_8)
    }
}


/**
 * The relay loop behind DevToolsProxy's pipes: copy chunks from
 * [input] to [output], flushing each one, and hand each chunk to
 * [observe] only AFTER it has been forwarded, so the
 * panel never waits for the observer's analysis of the chunk it
 * just received. A chunk whose write or flush fails is never
 * observed: it never reached the panel either, and the loop
 * ends there anyway. Any exception ends the loop quietly; the
 * caller closes both sides. An exception from the observer ends
 * the loop too, which is why the observer must contain its own
 * failures — WsMessageTap.feed does (it catches Throwable), so
 * observing cannot take the relay down in practice. Extracted
 * from the proxy so the ordering is covered by JVM tests.
 */
internal fun relayChunks(
    input: InputStream,
    output: OutputStream,
    observe: ((ByteArray, Int, Int) -> Unit)? = null
) {
    // 32KB (PER-7): the 8KB chunk cost a write + flush per
    // 8KB on bulk transfers (screencast frames); the flush
    // per chunk stays, so interactive latency is unchanged
    // in shape, only the syscall count drops.
    val buf = ByteArray(32_768)
    try {
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
            observe?.invoke(buf, 0, n)
        }
    } catch (_: Exception) {
    }
}

/**
 * Loopback-only TCP proxy in front of the DevTools socket.
 * The hosted Chrome DevTools frontend (https://chrome-devtools-frontend.appspot.com)
 * talks plain HTTP + WebSocket, which a WebView page cannot do against an
 * abstract unix socket — this proxy bridges the two. It only runs while the
 * built-in DevTools screen is open, and binds 127.0.0.1 only.
 *
 * Loopback alone does not say WHO is connecting: a browsed page's script
 * or another app on the device can reach 127.0.0.1 too, and CDP hands
 * over full control of every tab. So every connection must carry this
 * session's random token as a path prefix (/t/<token>/...), and a
 * browser Origin is accepted only when it is the hosted frontend's own.
 * The Origin is then stripped before forwarding, because Chromium's
 * DevTools server rejects Origins outside its allow-list and the hosted
 * frontend's Origin is not on it (Chrome 111+).
 */
class DevToolsProxy {

    /** When set, CDP text messages on page-target WebSockets are observed. */
    @Volatile var messageListener: ((String) -> Unit)? = null

    /** Diagnostics: total upstream bytes that passed through the tap. */
    @Volatile var tapBytes: Long = 0

    /** Diagnostics: the most recently created tap, for its counters/samples. */
    @Volatile var lastTap: WsMessageTap? = null

    /** Random per-session token; regenerated by every start(). Never logged. */
    @Volatile var sessionToken: String = ""
        private set

    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null

    /**
     * Serializes start()/stop() and the accept loop's give-up
     * teardown, so a stale accept thread can never tear down a
     * session that a newer start() has just created.
     */
    private val lifecycleLock = Any()

    // Live connection sockets, so stop() cuts established sessions
    // instead of only closing the listener.
    private val clientSockets =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val upstreamSockets =
        Collections.newSetFromMap(ConcurrentHashMap<LocalSocket, Boolean>())

    @Volatile var port: Int = -1
        private set

    fun start(): Int = synchronized(lifecycleLock) {
        if (running) return port
        // Bind first: if the socket cannot be created, no token or
        // mask is left behind for a listener that does not exist.
        val srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        sessionToken = newToken()
        DebugLog.setMaskedSecret(sessionToken)
        server = srv
        port = srv.localPort
        running = true
        thread(name = "pl-proxy-accept") {
            // Loop for this server instance only: after a stop/start
            // cycle the old loop must not spin on its closed socket
            // just because `running` is true again.
            var acceptFailures = 0
            while (running && server === srv) {
                try {
                    val client = srv.accept()
                    try { client.tcpNoDelay = true } catch (_: Exception) {}
                    acceptFailures = 0
                    if (clientSockets.size >= MAX_CLIENTS) {
                        // Say so instead of a silent drop, so a client
                        // can tell "busy" from "gone". The close is in
                        // finally: a failed 503 write must not leak
                        // the socket into the failure counter's path.
                        try {
                            reject(client.getOutputStream(), "503 Service Unavailable")
                        } catch (_: Exception) {
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                        }
                        continue
                    }
                    clientSockets.add(client)
                    thread(name = "pl-proxy-conn") { handle(client) }
                } catch (e: Exception) {
                    if (!running || server !== srv) break
                    // A persistent accept failure (e.g. out of file
                    // descriptors) must not spin. The sleep is retry
                    // throttling only (a pause between attempts, not
                    // a guess that something became ready); after a
                    // bounded run, give up and say so in the state:
                    // running=false lets the next open start fresh
                    // instead of trusting a dead listener.
                    acceptFailures++
                    if (acceptFailures >= MAX_ACCEPT_FAILURES) {
                        DebugLog.log("proxy", "accept loop stopped after repeated failures")
                        // Full teardown, exactly like a panel close:
                        // leaving the listen socket (and any live
                        // sessions) behind would leak them for the
                        // process lifetime, and a later start() would
                        // just overwrite the reference. Conditional
                        // and under the lifecycle lock: if a newer
                        // start() has already replaced this server,
                        // the live session is not ours to tear down.
                        synchronized(lifecycleLock) {
                            if (server === srv) stop()
                        }
                        break
                    }
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        synchronized(lifecycleLock) {
                            if (server === srv) stop()
                        }
                        break
                    }
                }
            }
        }
        return port
    }

    fun stop() {
        synchronized(lifecycleLock) {
            running = false
            // Invalidate the session token first, so a connection
            // that is mid-handshake fails its gate check from here on.
            sessionToken = ""
            DebugLog.setMaskedSecret(null)
            try { server?.close() } catch (_: Exception) {}
            server = null
            port = -1
            // Cut established sessions too: a CDP connection that
            // outlives the panel keeps full control of the tabs.
            for (s in clientSockets.toList()) try { s.close() } catch (_: Exception) {}
            for (s in upstreamSockets.toList()) shutdownAndClose(s)
            clientSockets.clear()
            upstreamSockets.clear()
        }
    }

    private fun shutdownAndClose(s: LocalSocket) {
        // shutdown before close: on Android a bare close() may not
        // unblock a read parked in another thread.
        try { s.shutdownInput() } catch (_: Exception) {}
        try { s.shutdownOutput() } catch (_: Exception) {}
        try { s.close() } catch (_: Exception) {}
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun handle(client: Socket) {
        var upstream: LocalSocket? = null
        var tap: WsMessageTap? = null
        try {
            // A peer that never finishes its handshake must not hold a
            // connection slot forever.
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val cin = client.getInputStream()
            val cout = client.getOutputStream()
            // soTimeout alone is per-read: a peer dripping one byte at
            // a time could hold its slot indefinitely. The header read
            // gets an absolute deadline instead, enforced by shrinking
            // the per-read timeout to the remaining budget.
            val deadline = System.nanoTime() + HANDSHAKE_TIMEOUT_MS * 1_000_000L
            val headerBytes = readHeaders(cin, deadline) { ms -> client.soTimeout = ms }
                ?: return
            if (!running) return
            val headerText = headerBytes.toString(Charsets.ISO_8859_1)
            // The whole gate is a pure function (DevToolsProxyGate):
            // token, Host pin, path pin, Origin, upgrade, in that
            // order — see its doc for the reasoning. Only the
            // logging and the rejection bytes live here. In
            // particular there is no /json relay: target discovery
            // happens over the unix socket inside the app, so
            // nothing legitimate needs one.
            val realPath = when (val decision =
                DevToolsProxyGate.evaluate(headerText, sessionToken, port)) {
                is DevToolsProxyGate.Decision.Allow -> decision.realPath
                is DevToolsProxyGate.Decision.Reject -> {
                    when (decision.reason) {
                        "token" -> DebugLog.log(
                            "proxy", "rejected a request without a valid token")
                        "host" -> DebugLog.log(
                            "proxy", "rejected a request with an unexpected Host")
                        "path" -> DebugLog.log(
                            "proxy", "rejected a request for a non-page path")
                        "origin" -> DebugLog.log(
                            "proxy", "rejected origin: ${logSafe(decision.detail ?: "")}")
                        "upgrade" -> DebugLog.log(
                            "proxy", "rejected a non-WebSocket request")
                    }
                    reject(cout, decision.status)
                    return
                }
            }

            val up = DevToolsSocket.connect()
            upstream = up
            upstreamSockets.add(up)
            if (!running) {
                // stop() raced with connect()+add: if stop() already
                // snapshotted the set it cannot see this socket, so
                // close it here. If stop() runs after this check, the
                // socket IS in its snapshot (the add came first) and
                // stop() closes it. Either way no established CDP
                // session survives stop().
                upstreamSockets.remove(up)
                shutdownAndClose(up)
                return
            }
            val uout = up.outputStream
            val uin = up.inputStream
            up.soTimeout = HANDSHAKE_TIMEOUT_MS
            // Forward with the token prefix removed and the Origin
            // stripped (see the class doc for why stripping stays).
            val outHeaders = rewriteRequestLine(
                DevToolsProxyGate.stripOrigin(headerText), realPath)
            uout.write(outHeaders.toByteArray(Charsets.ISO_8859_1))
            uout.flush()
            // Forward the handshake response headers first, byte by
            // byte so no frame bytes are swallowed. The tap below
            // must only ever see WebSocket frames: feeding it the
            // "HTTP/1.1 101 ..." header block corrupts its framing.
            val upDeadline = System.nanoTime() + HANDSHAKE_TIMEOUT_MS * 1_000_000L
            val respHead = readHeaders(uin, upDeadline) { ms -> up.soTimeout = ms }
            if (respHead == null) {
                // The upstream handshake did not complete (EOF or
                // oversize). Relaying the remaining bytes without
                // their header block would hand the client a corrupt
                // stream; closing is the honest outcome.
                DebugLog.log("proxy", "upstream handshake incomplete; closing")
                return
            }
            cout.write(respHead)
            cout.flush()
            val upgraded = DevToolsProxyGate.isUpgradeResponse(
                respHead.toString(Charsets.ISO_8859_1)
            )
            if (!upgraded) {
                // Not a WebSocket session (FULL-SEC-1): the
                // response head is all this connection gets.
                // Entering the raw relay here would carry any
                // further requests on this keep-alive connection
                // past the gate, which inspected only the first
                // one — a token holder could then reach the
                // target list and the browser-level endpoints
                // the path pin exists to keep away from it.
                // Closing fails the frontend's connect attempt,
                // the honest outcome for an error response. The
                // finally below closes both sockets.
                DebugLog.log("proxy", "upstream did not upgrade; closing")
                return
            }
            client.soTimeout = 0
            up.soTimeout = 0
            // Observe the stream only when recording was on when the
            // panel opened (the listener is set accordingly); a
            // non-101 handshake already closed the connection
            // above, so only real WebSocket frames reach here.
            // The listener still
            // re-checks the setting per message, so turning recording
            // OFF mid-session stops at once; turning it ON takes
            // effect at the next panel open, because a
            // permessage-deflate stream cannot be joined mid-way.
            val listener = messageListener
            // The tap observes each chunk only after the pipe has
            // forwarded it: feeding on the read side would run the
            // message analysis (inflate, string conversion, the
            // recorder) ahead of the write, so a large message's
            // tail would wait for its own analysis before the panel
            // could see it.
            val t2: WsMessageTap? = if (listener != null) {
                WsMessageTap(ConsoleLogRecorder.HANDLED_METHODS, listener).also {
                    it.onBytes = { n -> tapBytes += n }
                    tap = it
                    lastTap = it
                }
            } else {
                null
            }
            val t = thread(name = "pl-proxy-up") {
                try {
                    pipe(uin, cout, t2)
                } finally {
                    // One direction ending closes both sides, so the
                    // peer notices the disconnect right away instead of
                    // writing into a dead session.
                    try { client.close() } catch (_: Exception) {}
                    shutdownAndClose(up)
                }
            }
            try {
                pipe(cin, uout)
            } finally {
                try { client.close() } catch (_: Exception) {}
                shutdownAndClose(up)
            }
            t.join(2000)
        } catch (_: Exception) {
            // Connection dropped; nothing useful to report per-connection.
        } finally {
            tap?.close()
            upstream?.let {
                upstreamSockets.remove(it)
                shutdownAndClose(it)
            }
            clientSockets.remove(client)
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun reject(out: OutputStream, status: String) {
        try {
            out.write(
                "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            out.flush()
        } catch (_: Exception) {}
    }

    /** Make an attacker-controlled string safe and bounded for the log. */
    private fun logSafe(value: String): String =
        value.take(64).map { ch ->
            if (ch.code < 0x20 || ch.code == 0x7F || ch.code in 0x80..0x9F) '?' else ch
        }.joinToString("")

    private fun rewriteRequestLine(headers: String, newPath: String): String {
        val nl = headers.indexOf("\r\n")
        if (nl < 0) return headers
        val first = headers.substring(0, nl)
        val sp1 = first.indexOf(' ')
        val sp2 = if (sp1 >= 0) first.indexOf(' ', sp1 + 1) else -1
        if (sp1 < 0 || sp2 < 0) return headers
        return first.substring(0, sp1 + 1) + newPath + first.substring(sp2) + headers.substring(nl)
    }

    private fun pipe(input: InputStream, output: OutputStream, tap: WsMessageTap? = null) {
        relayChunks(input, output, tap?.let { it::feed })
    }

    /**
     * Read one header block, up to and including the CRLFCRLF
     * terminator. Returns null when the stream ends, the size cap is
     * hit, or the absolute deadline passes before the terminator
     * arrives: a partial block is not a header block, and treating it
     * as one would forward truncated requests upstream.
     * [adjustTimeout] is called with the remaining budget in
     * milliseconds whenever it has moved materially, so the peer
     * cannot stretch the handshake by dripping bytes.
     */
    private fun readHeaders(
        input: InputStream,
        deadlineNanos: Long,
        adjustTimeout: (Int) -> Unit
    ): ByteArray? {
        val out = ByteArrayOutputStream()
        val pattern = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        var lastTimeoutMs = -1
        while (out.size() < 65536) {
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return null
            val ms = remainingMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            if (lastTimeoutMs < 0 || lastTimeoutMs - ms >= 100) {
                adjustTimeout(ms)
                lastTimeoutMs = ms
            }
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = when {
                b.toByte() == pattern[matched] -> matched + 1
                b.toByte() == pattern[0] -> 1
                else -> 0
            }
            if (matched == 4) return out.toByteArray()
        }
        return null
    }

    companion object {
        /** Host the DevTools frontend is served from. */
        const val FRONTEND_HOST = "chrome-devtools-frontend.appspot.com"

        /** The DevTools frontend's origin. */
        const val FRONTEND_ORIGIN = "https://$FRONTEND_HOST"
        private const val MAX_CLIENTS = 8
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val MAX_ACCEPT_FAILURES = 50
    }
}

/**
 * Pure rules for what the DevTools proxy will relay (audit
 * hardening). The proxy's session token already gates access;
 * these two pins bound what a leaked token could reach and
 * what a browser driven to the port by a hostile page could
 * address, so the token is not the only wall.
 */
object DevToolsProxyPolicy {

    /**
     * The only path the proxy ever forwards: a page target's
     * socket endpoint, /devtools/page/<id>, where the id is a
     * single segment of the characters Chromium target ids
     * actually use (ASCII letters, digits, '-'). Anything
     * else — extra segments, a query marker, escapes, any
     * non-ASCII character — is not a target id this socket
     * issued. Everything else the DevTools socket serves
     * (target lists, browser-level endpoints) stays
     * unreachable through the proxy.
     */
    fun isAllowedRelayPath(realPath: String): Boolean {
        val prefix = "/devtools/page/"
        if (!realPath.startsWith(prefix)) return false
        val id = realPath.substring(prefix.length)
        return id.isNotEmpty() && id.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-'
        }
    }

    /**
     * The only Host a request to this proxy may name: the
     * loopback address and the proxy's own port, which is how
     * the app's own frontend addresses it. Any other Host means
     * the request was aimed at some other name and reached this
     * socket by rebinding or forwarding, not by intent.
     */
    fun isExpectedHost(hostHeader: String?, port: Int): Boolean =
        port > 0 && hostHeader == "127.0.0.1:$port"
}
