package jp.kaleidpixel.pageloupe

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collects console entries from Chrome DevTools Protocol messages that flow
 * through the local proxy while the real DevTools panel is open
 * (Log.entryAdded / Runtime.consoleAPICalled / Runtime.exceptionThrown).
 */
class ConsoleLogRecorder {

    data class Frame(
        val functionName: String?,
        val url: String?,
        val scriptId: String?,
        val line: Int?,
        val column: Int?
    )

    data class Entry(
        val timeMs: Long,
        val level: String,
        val text: String,
        val source: String?,
        val url: String?,
        val line: Int?,
        val column: Int? = null,
        val scriptId: String? = null,
        val frames: List<Frame> = emptyList(),
        val networkRequestId: String? = null,
        /** The CDP event as parsed-and-reserialized JSON; kept only when the original message fits the raw cap. */
        val raw: String? = null
    )

    /** One network request (one record per redirect hop). */
    data class NetReq(
        val requestId: String,
        val hop: Int = 0,
        val startWallMs: Long = System.currentTimeMillis(),
        var method: String? = null,
        var url: String? = null,
        var type: String? = null,
        var status: Int? = null,
        var statusText: String? = null,
        var mimeType: String? = null,
        var errorText: String? = null,
        var initiator: String? = null,
        var durationMs: Long? = null,
        var encodedBytes: Long? = null,
        var requestHeaders: String? = null,
        var responseHeaders: String? = null,
        var postData: String? = null,
        var startMonoSec: Double = Double.NaN
    )

    private val entries = mutableListOf<Entry>()
    private val netList = mutableListOf<NetReq>()
    private val netById = HashMap<String, NetReq>()

    // Approximate retained size in chars, guarded by the owning
    // collection's lock. The per-entry caps alone do not bound the
    // total (5000 near-cap entries would be hundreds of MB), so
    // both collections also keep a total budget and evict the
    // oldest past it.
    private var entriesChars = 0L
    private var netChars = 0L

    /**
     * Diagnostics: how many CDP messages reached the recorder at
     * all, including an internal Document then excluded from the
     * recordings.
     */
    @Volatile var messagesSeen: Int = 0
        private set

    /** Entries / requests evicted because the cap was reached (oldest first). */
    @Volatile var entriesDropped: Int = 0
        private set
    @Volatile var requestsDropped: Int = 0
        private set

    private val recentMethods = mutableListOf<String>()

    /**
     * The exact data: URL the inspected tab's current start page
     * was loaded with, consulted only for a data: Document (see
     * isInternalDocumentUrl). Set by the owner that feeds this
     * recorder (MainActivity) for the life of a recording
     * session; the default knows no start page load.
     */
    @Volatile
    var startPageDataUrl: () -> String? = { null }

    /**
     * Diagnostics: messages that failed JSON parsing, plus a
     * sample. Counted before the internal-Document exclusion
     * runs, so it includes messages then excluded.
     */
    @Volatile var parseFailures: Int = 0
        private set
    @Volatile var lastParseSample: String = ""
        private set

    fun recentMethods(): List<String> = synchronized(recentMethods) { recentMethods.toList() }

    fun lastMethod(): String = synchronized(recentMethods) { recentMethods.lastOrNull() ?: "" }

    /** Empties the recordings and the diagnostics. */
    fun clear() {
        synchronized(entries) {
            entries.clear()
            entriesChars = 0
            entriesDropped = 0
        }
        synchronized(netList) {
            netList.clear()
            netById.clear()
            netChars = 0
            requestsDropped = 0
        }
        synchronized(this) {
            messagesSeen = 0
            parseFailures = 0
        }
        lastParseSample = ""
        synchronized(recentMethods) { recentMethods.clear() }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun onCdpMessage(json: String) {
        synchronized(this) { messagesSeen++ }
        // Most CDP traffic is not ours (Page, DOM, CSS, ...). A full
        // JSONObject parse of every message just to discover that
        // would be waste: extract the method name with a cheap
        // scan and parse only the handful of methods the recorder
        // actually handles. Unhandled methods are still noted
        // for the diagnostics readout. The scan is
        // a pre-filter only -- it can over-match a "method" key
        // inside a payload, which merely costs one full parse whose
        // dispatch re-reads the authoritative method, so the
        // diagnostics list is approximate by design.
        val method = extractMethodName(json)
        if (method == null || method !in HANDLED_METHODS) {
            if (method != null) noteMethod(method)
            return
        }
        try {
            handle(JSONObject(json), json.length)
        } catch (_: Exception) {
            synchronized(this) { parseFailures++ }
            lastParseSample = json.take(100).replace('\n', ' ')
        }
    }

    private fun noteMethod(method: String) {
        synchronized(recentMethods) {
            recentMethods.add(method)
            while (recentMethods.size > 30) recentMethods.removeAt(0)
        }
    }

    /**
     * The top-level "method" value without parsing the JSON. CDP
     * serializes events with the method key ahead of params, so the
     * first occurrence is the real one; a false positive (the word
     * inside a string) only costs one full parse, whose dispatch
     * then reads the authoritative method anyway.
     */
    private fun extractMethodName(json: String): String? {
        val key = "\"method\""
        var i = json.indexOf(key)
        if (i < 0) return null
        i += key.length
        while (i < json.length && (json[i] == ' ' || json[i] == ':')) i++
        if (i >= json.length || json[i] != '"') return null
        val end = json.indexOf('"', i + 1)
        if (end < 0) return null
        return json.substring(i + 1, end)
    }

    /**
     * Whether this requestWillBeSent is one of the app's own
     * internal Document loads, which is not recorded (see
     * isInternalDocumentUrl). Everything else is recorded, even
     * while the tab shows the start page: the start page emits
     * nothing but its own load, so the rest of the stream
     * belongs to a real page. A redirect hop is never an
     * internal load here: the message also carries the previous
     * hop's redirect response, and dropping it would strip
     * that response from the recorded previous request.
     */
    private fun isInternalDocument(params: JSONObject?): Boolean {
        if (params == null || params.optString("type") != "Document") return false
        val url = params.optJSONObject("request")?.optString("url")
            ?.ifEmpty { null } ?: return false
        val isData = url.startsWith("data:")
        val internal = isInternalDocumentUrl(url, if (isData) startPageDataUrl() else null)
        if (isData) {
            // The classification of a data: Document rests on
            // its reported form (see isInternalDocumentUrl):
            // keep it observable in the gated debug log as a
            // boolean only — never the URL itself — so a WebView
            // change that breaks the form is noticed instead of
            // silently misclassifying.
            DebugLog.log("gate", "data document: internal=$internal")
        }
        return internal && params.optJSONObject("redirectResponse") == null
    }

    private fun handle(obj: JSONObject, rawLength: Int) {
        val method = obj.optString("method")
        if (method.isNotEmpty()) noteMethod(method)
        // Internal loads are not recorded: a Document request
        // for the app's own start page (or its about:blank) is
        // excluded by its own URL (see isInternalDocument). The
        // Target wrapper is never a Document itself; the
        // unwrapped inner message re-enters through
        // onCdpMessage and is classified on its own fields.
        if (method == "Network.requestWillBeSent" &&
            isInternalDocument(obj.optJSONObject("params"))
        ) {
            return
        }
        // The event JSON is kept only when the ORIGINAL
        // message was within the raw cap: re-serializing a huge
        // message just to throw it away would be pure waste.
        // Lazy: only the three entry-producing branches use it --
        // serializing for every Network event (the bulk of the
        // traffic) would be the same waste at any size.
        fun rawOf(): String? = if (rawLength <= MAX_RAW_CHARS) obj.toString() else null
        when (method) {
            "Log.entryAdded" -> {
                val e = obj.optJSONObject("params")?.optJSONObject("entry") ?: return
                val frames = parseFrames(e.optJSONObject("stackTrace"))
                val top = frames.firstOrNull()
                add(
                    Entry(
                        timeMs = normalizeTs(e.optDouble("timestamp", Double.NaN)),
                        level = e.optString("level", "log"),
                        text = e.optString("text"),
                        source = e.optString("source").ifEmpty { null },
                        url = e.optString("url").ifEmpty { null } ?: top?.url,
                        line = if (e.has("lineNumber")) e.optInt("lineNumber") + 1 else top?.line,
                        column = top?.column,
                        scriptId = top?.scriptId,
                        frames = frames,
                        networkRequestId = e.optString("networkRequestId").ifEmpty { null },
                        raw = rawOf()
                    )
                )
            }
            "Network.requestWillBeSent" -> {
                val p = obj.optJSONObject("params") ?: return
                val id = p.optString("requestId")
                if (id.isEmpty()) return
                // NetReq fields are read by the export formatters on
                // another thread: every mutation happens under the
                // netList lock, and snapshots hand out copies, so a
                // formatter can never observe a half-updated record.
                synchronized(netList) {
                    val mono = p.optDouble("timestamp", Double.NaN)
                    val redirect = p.optJSONObject("redirectResponse")
                    val prev = if (redirect != null) netById[id] else null
                    if (prev != null && redirect != null) {
                        val prevBefore = reqCost(prev)
                        if (redirect.has("status")) prev.status = redirect.optInt("status")
                        prev.statusText =
                            capText(redirect.optString("statusText").ifEmpty { null })
                                ?: prev.statusText
                        if (!mono.isNaN() && !prev.startMonoSec.isNaN()) {
                            prev.durationMs = ((mono - prev.startMonoSec) * 1000).toLong()
                        }
                        netChars += reqCost(prev) - prevBefore
                    }
                    val nr = newReqLocked(id, (prev?.hop ?: -1) + 1)
                    nr.startMonoSec = mono
                    val req = p.optJSONObject("request")
                    nr.method = capText(req?.optString("method")?.ifEmpty { null })
                    nr.url = capText(req?.optString("url")?.ifEmpty { null })
                    nr.requestHeaders = capField(req?.optJSONObject("headers")?.toString())
                    nr.postData = capText(req?.optString("postData")?.ifEmpty { null })
                    nr.type = capText(p.optString("type").ifEmpty { null })
                    nr.initiator = capText(frameRef(initiatorFrame(p.optJSONObject("initiator"))))
                    netChars += reqCost(nr)
                    evictNetLocked(nr)
                }
            }
            "Network.responseReceived" -> {
                val p = obj.optJSONObject("params") ?: return
                val id = p.optString("requestId")
                if (id.isEmpty()) return
                val resp = p.optJSONObject("response") ?: return
                synchronized(netList) {
                    val nr = netById[id] ?: return
                    val before = reqCost(nr)
                    if (resp.has("status")) nr.status = resp.optInt("status")
                    nr.statusText =
                        capText(resp.optString("statusText").ifEmpty { null }) ?: nr.statusText
                    nr.mimeType =
                        capText(resp.optString("mimeType").ifEmpty { null }) ?: nr.mimeType
                    nr.url = capText(resp.optString("url").ifEmpty { null }) ?: nr.url
                    // Replace when a headers object was actually
                    // sent: keeping the previous hop's headers when
                    // the new ones are oversize would misattribute
                    // them.
                    val respHeaders = resp.optJSONObject("headers")
                    if (respHeaders != null) nr.responseHeaders = capField(respHeaders.toString())
                    nr.type = capText(p.optString("type").ifEmpty { null }) ?: nr.type
                    netChars += reqCost(nr) - before
                    evictNetLocked(nr)
                }
            }
            "Network.loadingFailed" -> {
                val p = obj.optJSONObject("params") ?: return
                val id = p.optString("requestId")
                if (id.isEmpty()) return
                synchronized(netList) {
                    val nr = netById[id] ?: return
                    val before = reqCost(nr)
                    var err = p.optString("errorText").ifEmpty { null }
                    if (err != null && p.optBoolean("canceled")) err += " (canceled)"
                    if (err != null) nr.errorText = capText(err)
                    val mono = p.optDouble("timestamp", Double.NaN)
                    if (!mono.isNaN() && !nr.startMonoSec.isNaN()) {
                        nr.durationMs = ((mono - nr.startMonoSec) * 1000).toLong()
                    }
                    netChars += reqCost(nr) - before
                    evictNetLocked(nr)
                }
            }
            "Network.loadingFinished" -> {
                val p = obj.optJSONObject("params") ?: return
                val id = p.optString("requestId")
                if (id.isEmpty()) return
                synchronized(netList) {
                    val nr = netById[id] ?: return
                    val mono = p.optDouble("timestamp", Double.NaN)
                    if (!mono.isNaN() && !nr.startMonoSec.isNaN()) {
                        nr.durationMs = ((mono - nr.startMonoSec) * 1000).toLong()
                    }
                    if (p.has("encodedDataLength")) {
                        nr.encodedBytes = p.optDouble("encodedDataLength").toLong()
                    }
                }
            }
            "Runtime.consoleAPICalled" -> {
                val p = obj.optJSONObject("params") ?: return
                val args = p.optJSONArray("args")
                val parts = mutableListOf<String>()
                if (args != null) {
                    for (i in 0 until args.length()) {
                        parts.add(stringifyArg(args.optJSONObject(i)))
                    }
                }
                val frames = parseFrames(p.optJSONObject("stackTrace"))
                val top = frames.firstOrNull()
                add(
                    Entry(
                        timeMs = normalizeTs(p.optDouble("timestamp", Double.NaN)),
                        level = p.optString("type", "log"),
                        text = parts.joinToString(" "),
                        source = "console",
                        url = top?.url,
                        line = top?.line,
                        column = top?.column,
                        scriptId = top?.scriptId,
                        frames = frames,
                        raw = rawOf()
                    )
                )
            }
            "Runtime.exceptionThrown" -> {
                val d = obj.optJSONObject("params")?.optJSONObject("exceptionDetails") ?: return
                val exc = d.optJSONObject("exception")
                val text = exc?.optString("description")?.ifEmpty { null }
                    ?: d.optString("text", "Uncaught exception")
                val frames = parseFrames(d.optJSONObject("stackTrace"))
                val top = frames.firstOrNull()
                add(
                    Entry(
                        timeMs = System.currentTimeMillis(),
                        level = "error",
                        text = text,
                        source = "javascript",
                        url = d.optString("url").ifEmpty { null } ?: top?.url,
                        line = if (d.has("lineNumber")) d.optInt("lineNumber") + 1 else top?.line,
                        column = top?.column,
                        scriptId = top?.scriptId,
                        frames = frames,
                        raw = rawOf()
                    )
                )
            }
            "Target.receivedMessageFromTarget" -> {
                // Browser-level session wrapping: unwrap and send
                // the inner message through the same entry point, so
                // it gets the same pre-filter and failure counting.
                val inner = obj.optJSONObject("params")?.optString("message") ?: return
                onCdpMessage(inner)
            }
        }
    }

    /** Caller must hold the netList lock. */
    private fun newReqLocked(id: String, hop: Int): NetReq {
        val nr = NetReq(requestId = id, hop = hop)
        netList.add(nr)
        netById[id] = nr
        return nr
    }

    /** Approximate retained size of one request record, in chars. */
    private fun reqCost(r: NetReq): Long =
        (r.requestId.length +
            (r.url?.length ?: 0) + (r.postData?.length ?: 0) +
            (r.requestHeaders?.length ?: 0) + (r.responseHeaders?.length ?: 0) +
            (r.initiator?.length ?: 0) + (r.errorText?.length ?: 0) +
            (r.method?.length ?: 0) + (r.statusText?.length ?: 0) +
            (r.mimeType?.length ?: 0) + (r.type?.length ?: 0)).toLong()

    /**
     * Caller holds the netList lock. Evicts the oldest records until
     * the count and total-size budgets hold; [protect] (the record
     * currently being built or updated) is never evicted.
     * Eviction is a removeAt on an ArrayList: measured with a
     * throwaway JVM bench (not kept in the repo), the increment
     * over the under-cap baseline at the cap is a median of
     * ~7µs and an average of a few tens of µs per eviction, so
     * a deque is not warranted. ART costs may differ; re-measure
     * on device if it ever matters. Revisit if the caps grow
     * or the list type changes.
     */
    private fun evictNetLocked(protect: NetReq?) {
        while (netList.size > MAX_NET_REQS || netChars > MAX_TOTAL_REQ_CHARS) {
            val old = netList.firstOrNull { it !== protect } ?: break
            // NetReq is a data class: remove(old) would match by
            // VALUE and could evict a different, equal record (in
            // the worst case [protect] itself). Remove by identity.
            val idx = netList.indexOfFirst { it === old }
            if (idx >= 0) netList.removeAt(idx)
            if (netById[old.requestId] === old) netById.remove(old.requestId)
            netChars -= reqCost(old)
            requestsDropped++
        }
    }

    fun requestsSnapshot(): List<NetReq> = synchronized(netList) { netList.map { it.copy() } }

    /** Both request views under one lock acquisition (export snapshot). */
    fun requestsAndNetwork(): Pair<List<NetReq>, Map<String, NetReq>> =
        synchronized(netList) {
            val byId = HashMap<String, NetReq>()
            for ((k, v) in netById) byId[k] = v.copy()
            netList.map { it.copy() } to byId
        }

    /** Truncate an oversized free-text field, marking the cut. */
    private fun capText(s: String?): String? =
        if (s != null && s.length > MAX_FIELD_CHARS) {
            s.substring(0, MAX_FIELD_CHARS) + "…[truncated]"
        } else {
            s
        }

    /**
     * Cap a structured field (headers JSON). Over the cap the field
     * is replaced by a visible marker instead of being silently
     * dropped, so an export can tell "no headers" from "headers
     * omitted". Truncated JSON would be corrupt
     * data, so the field is never cut mid-way.
     */
    private fun capField(s: String?): String? = when {
        s == null -> null
        s.length <= MAX_FIELD_CHARS -> s
        else -> HEADERS_OMITTED
    }

    private fun parseFrames(stackTrace: JSONObject?): List<Frame> {
        val arr = stackTrace?.optJSONArray("callFrames") ?: return emptyList()
        val out = mutableListOf<Frame>()
        // A pathological stack must not dominate the entry: the
        // frame count and the string fields are capped.
        val limit = minOf(arr.length(), MAX_FRAMES)
        for (i in 0 until limit) {
            val f = arr.optJSONObject(i) ?: continue
            out.add(
                Frame(
                    functionName = capText(f.optString("functionName").ifEmpty { null }),
                    url = capText(f.optString("url").ifEmpty { null }),
                    scriptId = f.optString("scriptId").ifEmpty { null },
                    line = f.optInt("lineNumber", -1).takeIf { it >= 0 }?.plus(1),
                    column = f.optInt("columnNumber", -1).takeIf { it >= 0 }?.plus(1)
                )
            )
        }
        return out
    }

    /** The frame a network request was initiated from, if the initiator has one. */
    private fun initiatorFrame(initiator: JSONObject?): JSONObject? {
        if (initiator == null) return null
        val frames = initiator.optJSONObject("stack")?.optJSONArray("callFrames")
        if (frames != null && frames.length() > 0) return frames.optJSONObject(0)
        val url = initiator.optString("url")
        if (url.isNotEmpty()) {
            return JSONObject()
                .put("url", url)
                .put("lineNumber", initiator.optInt("lineNumber", 0))
        }
        return null
    }

    /** "url:line" style reference for a call frame object (CDP field names). */
    private fun frameRef(f: JSONObject?): String? {
        if (f == null) return null
        val url = f.optString("url").ifEmpty { null }
        val scriptId = f.optString("scriptId").ifEmpty { null }
        val base = url ?: scriptId?.let { "script $it" } ?: return null
        val line = f.optInt("lineNumber", -1)
        return if (line >= 0) "$base:${line + 1}" else base
    }

    /** "url:line:column" style reference for a parsed frame. */
    private fun frameRef(f: Frame): String {
        val base = f.url ?: f.scriptId?.let { "script $it" } ?: "<anonymous>"
        val sb = StringBuilder(base)
        f.line?.let { sb.append(":").append(it) }
        if (f.line != null && f.column != null) sb.append(":").append(f.column)
        return sb.toString()
    }

    private fun stringifyArg(arg: JSONObject?): String {
        if (arg == null) return "undefined"
        if (arg.has("value")) {
            val v = arg.opt("value")
            return when (v) {
                is String -> v
                else -> v.toString()
            }
        }
        arg.optString("description").takeIf { it.isNotEmpty() }?.let { return it }
        return arg.optString("type", "undefined")
    }

    /** Approximate retained size of one entry, in chars. */
    private fun entryCost(e: Entry): Long {
        var c = e.text.length + (e.raw?.length ?: 0) + (e.url?.length ?: 0) +
            e.level.length + (e.source?.length ?: 0) + (e.scriptId?.length ?: 0) +
            (e.networkRequestId?.length ?: 0)
        for (f in e.frames) {
            c += (f.functionName?.length ?: 0) + (f.url?.length ?: 0) +
                (f.scriptId?.length ?: 0)
        }
        return c.toLong()
    }

    private fun add(e: Entry) {
        // Per-entry size caps: one giant console
        // message must not dominate the recording or the export.
        val capped = e.copy(
            text = capText(e.text) ?: "",
            url = capText(e.url),
            raw = e.raw?.takeIf { it.length <= MAX_RAW_CHARS }
        )
        val cost = entryCost(capped)
        synchronized(entries) {
            // Both collections keep the NEWEST records and evict
            // the oldest past the count cap AND the total-size
            // budget, with the loss counted and noted in the
            // export — never silently drop the new entry instead.
            // The removeAt(0) eviction cost at the cap was
            // measured and is documented at evictNetLocked; it
            // is why the list stays an ArrayList.
            entries.add(capped)
            entriesChars += cost
            while ((entries.size > MAX_ENTRIES || entriesChars > MAX_TOTAL_ENTRY_CHARS) &&
                entries.size > 1
            ) {
                val old = entries.removeAt(0)
                entriesChars -= entryCost(old)
                entriesDropped++
            }
        }
    }

    /** CDP timestamps come as epoch milliseconds or seconds depending on the event. */
    private fun normalizeTs(raw: Double): Long {
        if (raw.isNaN() || raw <= 0) return System.currentTimeMillis()
        return if (raw < 1e11) (raw * 1000).toLong() else raw.toLong()
    }

    // The formatters are pure functions of their arguments: they
    // must never read the recorder's live state, because they run
    // on the export thread while the caller may already have
    // cleared the recorder for the next session.
    fun toJson(list: List<Entry>, net: Map<String, NetReq>): String {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("time", isoFormat(e.timeMs))
            o.put("level", e.level)
            o.put("text", e.text)
            e.source?.let { o.put("source", it) }
            e.url?.let { o.put("url", it) }
            e.line?.let { o.put("line", it) }
            e.column?.let { o.put("column", it) }
            e.scriptId?.let { o.put("scriptId", it) }
            if (e.frames.isNotEmpty()) {
                val fa = JSONArray()
                for (f in e.frames) {
                    val fo = JSONObject()
                    f.functionName?.let { fo.put("functionName", it) }
                    f.url?.let { fo.put("url", it) }
                    f.scriptId?.let { fo.put("scriptId", it) }
                    f.line?.let { fo.put("line", it) }
                    f.column?.let { fo.put("column", it) }
                    fa.put(fo)
                }
                o.put("stackTrace", fa)
            }
            val nr = e.networkRequestId?.let { net[it] }
            if (e.networkRequestId != null || nr != null) {
                val no = JSONObject()
                e.networkRequestId?.let { no.put("requestId", it) }
                if (nr != null) {
                    nr.method?.let { no.put("method", it) }
                    nr.url?.let { no.put("url", it) }
                    nr.status?.let { no.put("status", it) }
                    nr.mimeType?.let { no.put("mimeType", it) }
                    nr.errorText?.let { no.put("errorText", it) }
                    nr.initiator?.let { no.put("initiator", it) }
                }
                o.put("network", no)
            }
            // raw is already a JSON text: emit it AS a string
            // instead of re-parsing every entry into a JSONObject
            // tree at export time. With the store at its caps
            // (8M chars of raw), re-parsing would multiply into a
            // transient heap peak of tens of MB on top of the
            // resident store, and an OOM there would also lose the
            // recording to the clear() that follows the export.
            // The field carries the CDP event (parsed and
            // re-serialized, so key order and number formatting
            // may differ from the wire bytes) whenever it was
            // kept under the raw cap.
            e.raw?.let { o.put("raw", it) }
            arr.put(o)
        }
        return arr.toString(2)
    }

    /**
     * The tap-loss header notes shared by both text exports —
     * one generator, so the two exports cannot drift apart.
     * Tap-level losses happen BEFORE the recorder (oversize or
     * truncated messages never arrive): without these notes the
     * file would look complete when it is not.
     */
    private fun tapLossNotes(tapDropped: Int, tapFailed: Boolean): String {
        val sb = StringBuilder()
        if (tapDropped > 0) {
            sb.append("（サイズ上限等のため、タップで ${tapDropped} 件のメッセージを記録できませんでした）\n")
        }
        if (tapFailed) {
            sb.append("（タップが途中で停止したため、以降のメッセージは記録されていません）\n")
        }
        return sb.toString()
    }

    fun toText(
        list: List<Entry>,
        net: Map<String, NetReq>,
        droppedEntries: Int,
        tapDropped: Int = 0,
        tapFailed: Boolean = false
    ): String {
        val sb = StringBuilder()
        if (droppedEntries > 0) {
            sb.append("（記録上限のため、古いコンソール記録 ${droppedEntries} 件を破棄しました）\n")
        }
        sb.append(tapLossNotes(tapDropped, tapFailed))
        for (e in list) {
            sb.append(textFormat(e.timeMs))
            sb.append(" [").append(DebugLog.escapeLine(e.level)).append("] ")
            e.source?.let { sb.append("[").append(DebugLog.escapeLine(it)).append("] ") }
            sb.append(DebugLog.escapeLine(e.text))
            val nr = e.networkRequestId?.let { net[it] }
            if (nr != null && (nr.method != null || nr.url != null)) {
                sb.append("\n    request: ")
                nr.method?.let { sb.append(DebugLog.escapeLine(it)).append(" ") }
                nr.url?.let { sb.append(DebugLog.escapeLine(it)) }
                nr.status?.let { sb.append(" -> ").append(it) }
                nr.errorText?.let { sb.append(" -> ").append(DebugLog.escapeLine(it)) }
            }
            if (e.url != null || e.scriptId != null) {
                sb.append("\n    at ")
                e.frames.firstOrNull()?.functionName?.let { sb.append(DebugLog.escapeLine(it)).append(" (") }
                sb.append(
                    e.url?.let { DebugLog.escapeLine(it) }
                        ?: DebugLog.escapeLine("script ${e.scriptId}")
                )
                e.line?.let { sb.append(":").append(it) }
                if (e.line != null && e.column != null) sb.append(":").append(e.column)
                if (e.frames.firstOrNull()?.functionName != null) sb.append(")")
                nr?.initiator?.let { sb.append("\n    initiator: ").append(DebugLog.escapeLine(it)) }
            } else {
                nr?.initiator?.let { sb.append("\n    initiator: ").append(DebugLog.escapeLine(it)) }
            }
            if (e.frames.size > 1) {
                for (f in e.frames.drop(1).take(5)) {
                    sb.append("\n      at ")
                    f.functionName?.let { sb.append(DebugLog.escapeLine(it)).append(" (") }
                    sb.append(DebugLog.escapeLine(frameRef(f)))
                    if (f.functionName != null) sb.append(")")
                }
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Stream the requests export into [out] with a JsonWriter
     * instead of building the whole JSONArray tree and the
     * rendered string first: at the 5000-entry cap,
     * re-parsing every headers string and holding all the
     * trees — plus the full output string and its copies —
     * would put a transient heap peak next to the resident
     * snapshot, and an OOM there would lose the recording to
     * the clear() that follows the export. The caller streams
     * this into a temp file and renames it, so a mid-write
     * failure leaves no truncated export behind.
     *
     * Headers stay nested objects — the export format does
     * not change: each stored string is parsed on
     * its own — one transient tree at a time — and walked into
     * the writer. A string that does not parse becomes a
     * visible marker instead of being silently dropped by an
     * empty catch, the same policy as the size-cap marker;
     * in practice the stored values always parse (they are
     * JSONObject output, capped whole by capField), so that
     * branch is defensive. It is also the one deliberate
     * output choice: an unparseable field becomes a marker
     * instead of being omitted entirely.
     *
     * Cosmetic properties of the output, all semantically
     * identical JSON: "/" in strings is not backslash-escaped
     * (org.json's pretty-print escaped it) — this difference
     * is confirmed in a device export. Two more follow from
     * reading the framework implementations rather than from
     * device measurement: JsonWriter escapes U+2028/U+2029
     * (org.json did not), and
     * a non-string numeric header value such as 5.0 would
     * print as 5.0 where org.json printed 5 — stored header
     * values are JSONObject output, in which such a value is
     * already normalized to an integer, so this cannot occur.
     *
     * The writer is NOT closed here: the caller owns [out]
     * and closes it. This function only flushes, so the
     * JsonWriter's own buffer is pushed downstream before the
     * caller finishes the file.
     */
    fun writeRequestsJson(list: List<NetReq>, out: java.io.Writer) {
        val jw = android.util.JsonWriter(out)
        jw.setIndent("  ")
        writeRequestsJson(list, AndroidJsonSink(jw))
    }

    internal fun writeRequestsJson(list: List<NetReq>, w: JsonSink) {
        w.beginArray()
        for (r in list) {
            w.beginObject()
            w.name("time").value(isoFormat(r.startWallMs))
            w.name("requestId").value(r.requestId)
            if (r.hop > 0) w.name("redirectHop").value(r.hop)
            r.type?.let { w.name("type").value(it) }
            r.method?.let { w.name("method").value(it) }
            r.url?.let { w.name("url").value(it) }
            r.status?.let { w.name("status").value(it) }
            r.statusText?.let { w.name("statusText").value(it) }
            r.mimeType?.let { w.name("mimeType").value(it) }
            r.errorText?.let { w.name("errorText").value(it) }
            r.durationMs?.let { w.name("durationMs").value(it) }
            r.encodedBytes?.let { w.name("encodedBytes").value(it) }
            r.initiator?.let { w.name("initiator").value(it) }
            r.postData?.let { w.name("postData").value(it) }
            r.requestHeaders?.let { writeHeaders(w, "requestHeaders", it) }
            r.responseHeaders?.let { writeHeaders(w, "responseHeaders", it) }
            w.endObject()
        }
        w.endArray()
        w.flush()
    }

    /**
     * The subset of android.util.JsonWriter the requests export
     * uses, behind an interface so the sequential writer can run
     * on the JVM in tests (the framework JsonWriter is a stub
     * there). Production goes through [AndroidJsonSink], which
     * forwards every call unchanged and in the same order, so
     * the output properties in the writeRequestsJson doc (all
     * the real JsonWriter's) do not change.
     */
    internal interface JsonSink {
        fun beginArray(): JsonSink
        fun endArray(): JsonSink
        fun beginObject(): JsonSink
        fun endObject(): JsonSink
        fun name(name: String): JsonSink
        fun value(value: String?): JsonSink
        fun value(value: Long): JsonSink
        fun value(value: Boolean): JsonSink
        fun value(value: Number): JsonSink
        fun nullValue(): JsonSink
        fun flush()
    }

    /** [JsonSink] over the framework JsonWriter (production). */
    private class AndroidJsonSink(
        private val w: android.util.JsonWriter
    ) : JsonSink {
        override fun beginArray(): JsonSink = apply { w.beginArray() }
        override fun endArray(): JsonSink = apply { w.endArray() }
        override fun beginObject(): JsonSink = apply { w.beginObject() }
        override fun endObject(): JsonSink = apply { w.endObject() }
        override fun name(name: String): JsonSink = apply { w.name(name) }
        override fun value(value: String?): JsonSink = apply { w.value(value) }
        override fun value(value: Long): JsonSink = apply { w.value(value) }
        override fun value(value: Boolean): JsonSink = apply { w.value(value) }
        override fun value(value: Number): JsonSink = apply { w.value(value) }
        override fun nullValue(): JsonSink = apply { w.nullValue() }
        override fun flush() = w.flush()
    }

    private fun writeHeaders(w: JsonSink, name: String, raw: String) {
        w.name(name)
        // The omission marker is not JSON: emit it as a plain
        // string so the export shows the headers were dropped
        // for size, instead of looking like there were none.
        if (raw == HEADERS_OMITTED) {
            w.value(raw)
            return
        }
        val parsed = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            null
        }
        if (parsed != null) {
            writeJsonValue(w, parsed)
        } else {
            w.value(HEADERS_INVALID)
        }
    }

    /** Walk one parsed JSON value into the writer (see writeRequestsJson). */
    private fun writeJsonValue(w: JsonSink, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> w.nullValue()
            is JSONObject -> {
                w.beginObject()
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    w.name(k)
                    writeJsonValue(w, v.get(k))
                }
                w.endObject()
            }
            is JSONArray -> {
                w.beginArray()
                for (i in 0 until v.length()) {
                    writeJsonValue(w, v.get(i))
                }
                w.endArray()
            }
            is String -> w.value(v)
            is Boolean -> w.value(v)
            is Number -> w.value(v)
            else -> w.value(v.toString())
        }
    }

    fun toRequestsText(
        list: List<NetReq>,
        droppedRequests: Int,
        tapDropped: Int = 0,
        tapFailed: Boolean = false
    ): String {
        val sb = StringBuilder()
        if (droppedRequests > 0) {
            sb.append("（記録上限のため、古いリクエスト記録 ${droppedRequests} 件を破棄しました）\n")
        }
        // Same tap-loss notes as the console text: a requests-only
        // export must not look complete either.
        sb.append(tapLossNotes(tapDropped, tapFailed))
        for (r in list) {
            sb.append(textFormat(r.startWallMs)).append(" ")
            r.type?.let { sb.append("[").append(DebugLog.escapeLine(it)).append("] ") }
            sb.append(r.method?.let { DebugLog.escapeLine(it) } ?: "?").append(" ")
            .append(r.url?.let { DebugLog.escapeLine(it) } ?: "")
            r.status?.let { sb.append(" -> ").append(it) }
            r.errorText?.let { sb.append(" -> ").append(DebugLog.escapeLine(it)) }
            val meta = mutableListOf<String>()
            r.durationMs?.let { meta.add("${it}ms") }
            r.encodedBytes?.let { meta.add("${it}B") }
            r.mimeType?.let { meta.add(DebugLog.escapeLine(it)) }
            if (meta.isNotEmpty()) sb.append(" (").append(meta.joinToString(", ")).append(")")
            sb.append("\n")
            r.initiator?.let { sb.append("    initiator: ").append(DebugLog.escapeLine(it)).append("\n") }
            r.postData?.let {
                sb.append("    postData: ").append(DebugLog.escapeLine(it)).append("\n")
            }
        }
        return sb.toString()
    }

    companion object {
        private const val MAX_ENTRIES = 5000
        private const val MAX_NET_REQS = 5000
        private const val MAX_FIELD_CHARS = 32 * 1024
        private const val MAX_RAW_CHARS = 64 * 1024
        private const val MAX_FRAMES = 32
        private const val MAX_TOTAL_ENTRY_CHARS = 8 * 1024 * 1024
        private const val MAX_TOTAL_REQ_CHARS = 8 * 1024 * 1024
        private const val HEADERS_OMITTED = "<headers omitted: over size cap>"
        private const val HEADERS_INVALID = "<headers omitted: invalid JSON>"

        /** The only CDP methods worth a full parse. */
        private val HANDLED_METHODS = setOf(
            "Log.entryAdded",
            "Network.requestWillBeSent",
            "Network.responseReceived",
            "Network.loadingFailed",
            "Network.loadingFinished",
            "Runtime.consoleAPICalled",
            "Runtime.exceptionThrown",
            "Target.receivedMessageFromTarget"
        )

        // SimpleDateFormat is not thread-safe and creating one per
        // entry would be pure waste: one per thread, reused.
        private val isoFormatter = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
        }
        private val textFormatter = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
        }

        private fun isoFormat(ms: Long): String = isoFormatter.get()!!.format(Date(ms))

        private fun textFormat(ms: Long): String = textFormatter.get()!!.format(Date(ms))
    }
}
