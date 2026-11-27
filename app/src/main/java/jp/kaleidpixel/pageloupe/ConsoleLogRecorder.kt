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
        fun rawOf(): String? = if (rawLength <= MAX_RAW_CHARS) {
            // The raw event quotes the same URLs the
            // structured fields redact (review round 3,
            // SEC-1), so the raw copy must not keep what
            // the fields withheld. The redaction runs on
            // the PARSED values, not the serialization
            // (review round 5, SEN-1/SEC-1): serializers
            // escape differently (the device org.json
            // writes "\/" for "/"), which broke every
            // pattern that assumed a plain "://" in the
            // serialized text. In memory the strings are
            // unescaped on both platforms, so a deep copy
            // with each string run through the text
            // redactor is serializer-proof.
            try {
                redactDeep(obj).toString()
            } catch (_: Error) {
                // A pathological nest must not take the
                // relay down with the raw copy (round 6,
                // SEN-5): no raw is the fail-closed answer.
                null
            }
        } else {
            null
        }
        when (method) {
            "Log.entryAdded" -> {
                val e = obj.optJSONObject("params")?.optJSONObject("entry") ?: return
                val frames = parseFrames(e.optJSONObject("stackTrace"))
                val top = frames.firstOrNull()
                add(
                    Entry(
                        timeMs = normalizeTs(e.optDouble("timestamp", Double.NaN)),
                        level = e.optString("level", "log"),
                        text = redactSecretsInText(e.optString("text")),
                        source = e.optString("source").ifEmpty { null },
                        url = capUrl(e.optString("url").ifEmpty { null }) ?: top?.url,
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
                    nr.url = capText(redactUrlQuery(req?.optString("url")?.ifEmpty { null }))
                    nr.requestHeaders =
                        capField(redactHeaders(req?.optJSONObject("headers")?.toString()))
                    nr.postData = capText(
                        redactPostData(
                            req?.optString("postData")?.ifEmpty { null },
                            req?.optJSONObject("headers")
                        )
                    )
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
                    nr.url = capText(redactUrlQuery(resp.optString("url").ifEmpty { null })) ?: nr.url
                    // Replace when a headers object was actually
                    // sent: keeping the previous hop's headers when
                    // the new ones are oversize would misattribute
                    // them.
                    val respHeaders = resp.optJSONObject("headers")
                    if (respHeaders != null) {
                        nr.responseHeaders = capField(redactHeaders(respHeaders.toString()))
                    }
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
                        text = redactSecretsInText(parts.joinToString(" ")),
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
                        text = redactSecretsInText(text),
                        source = "javascript",
                        url = capUrl(d.optString("url").ifEmpty { null }) ?: top?.url,
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

    // --- Credential redaction (FULL-SEC-Q1) ---
    //
    // The recordings are plain text by design (SEC-127), but
    // credential VALUES are not diagnostic content: an
    // Authorization header or a login form's password field
    // in an exported file is a live secret in a shareable
    // artifact. Redaction happens at ingestion, so no
    // consumer — export today, anything tomorrow — can
    // receive the value. Header NAMES, body structure and
    // non-sensitive values are untouched: the recording
    // still shows what kind of request was made. (The
    // owner's call, recorded in the ledger: if full-fidelity
    // capture is ever wanted back, this block is the one
    // place to lift.)

    /** Redact credential header values in a stored headers
     * JSON string. An unparseable string passes through
     * unchanged: the export substitutes its invalid marker
     * for those, so nothing readable leaks that way either. */
    private fun redactHeaders(raw: String?): String? {
        if (raw == null) return null
        val obj = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return raw
        }
        var changed = false
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (isSensitiveHeader(k)) {
                obj.put(k, REDACTED_VALUE)
                changed = true
            } else if (isPluralCredentialName(k) &&
                k.lowercase() !in CORS_HEADERS &&
                !isCounterValue(obj.optString(k))
            ) {
                // A compound-plural header with a non-numeric
                // value is a credential list; a numeric one
                // is a rate-limit counter (round 6, PER-2:
                // x-ratelimit-remaining-tokens).
                obj.put(k, REDACTED_VALUE)
                changed = true
            } else if (k.lowercase() in URL_VALUED_HEADERS) {
                // A URL in a header value (Referer, Location)
                // carries the same query credentials as a
                // request URL (review round 4, SEC-6).
                val v = obj.optString(k)
                val r = redactUrlQuery(v)
                if (r != null && r != v) {
                    obj.put(k, r)
                    changed = true
                }
            }
        }
        return if (changed) obj.toString() else raw
    }

    /** A headers object's one value, case-insensitively. */
    private fun headerValue(headers: JSONObject?, name: String): String? {
        if (headers == null) return null
        val keys = headers.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.equals(name, ignoreCase = true)) {
                return headers.optString(k).ifEmpty { null }
            }
        }
        return null
    }

    /** Redact credential fields in a request body, by its
     * declared content type: form pairs and JSON keys whose
     * names are credential-shaped lose their values. Any
     * other body passes through unchanged — free-form
     * payloads are the recording's diagnostic content. */
    private fun redactPostData(postData: String?, headers: JSONObject?): String? {
        if (postData == null) return null
        val contentType = headerValue(headers, "Content-Type")
            ?.lowercase().orEmpty()
        val trimmed = postData.trimStart()
        return when {
            contentType.contains("application/x-www-form-urlencoded") ->
                redactFormBody(postData)
            contentType.contains("json") ||
                trimmed.startsWith("{") || trimmed.startsWith("[") ->
                // The declared type is not trusted as the
                // only signal (review round 2, PER-7): a
                // JSON body sent as text/plain (sendBeacon
                // and kin) is still JSON, and redacting it
                // only when the label says so left it raw.
                redactJsonBody(postData)
            contentType.contains("multipart/") ->
                // Multipart bodies are NOT parsed for
                // redaction: their parts are left as recorded.
                // Recorded as an accepted residual in the
                // design records (review round 2, SEN-9).
                postData
            isFormShaped(postData) ->
                // A form-shaped body under a non-form label
                // (a fetch with a string body is text/plain)
                // carries the same credentials (review
                // round 3, SEC-6): the shape decides, not the
                // declared type. Form redaction only rewrites
                // credential-named pairs, so innocent pairs
                // keep their diagnostic value.
                redactFormBody(postData)
            else -> postData
        }
    }

    /** Redact credential query parameters in a recorded
     * URL (review round 2, SEN-9): a token in a request
     * URL's query is the same secret as one in a header.
     * Only the values of credential-named parameters are
     * replaced; the URL's shape stays readable. */
    private fun redactUrlQuery(url: String?): String? {
        if (url == null) return null
        var u = url
        // Userinfo credentials (user:password@host): a
        // password in plain sight (review round 3, SEN-6
        // question). Only the password half is replaced.
        val schemeEnd = u.indexOf("://")
        if (schemeEnd >= 0) {
            val authStart = schemeEnd + 3
            // The authority ends at the first of '/', '?',
            // '#' (review round 4, SEN-6): a pathless URL
            // whose query holds ':' and '@' is not userinfo.
            var authEnd = u.length
            for (j in authStart until u.length) {
                val ch = u[j]
                if (ch == '/' || ch == '?' || ch == '#') {
                    authEnd = j
                    break
                }
            }
            if (authEnd > authStart) {
                val at = u.lastIndexOf('@', authEnd - 1)
                val colon = u.indexOf(':', authStart)
                if (at > authStart && colon >= authStart && colon < at) {
                    u = u.substring(0, colon + 1) + REDACTED_VALUE + u.substring(at)
                }
            }
        }
        val q = u.indexOf('?')
        if (q < 0) return redactFragmentPairs(u)
        val frag = u.indexOf('#', q)
        val queryEnd = if (frag >= 0) frag else u.length
        val query = u.substring(q + 1, queryEnd)
        if (query.isEmpty()) return redactFragmentPairs(u)
        var changed = false
        val redacted = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) {
                pair
            } else {
                val key = pair.substring(0, eq)
                val decoded = try {
                    java.net.URLDecoder.decode(key, "UTF-8")
                } catch (_: Exception) {
                    key
                }
                if (isSensitivePairKey(decoded)) {
                    changed = true
                    "$key=$REDACTED_VALUE"
                } else {
                    pair
                }
            }
        }
        val withQuery = if (changed) {
            u.substring(0, q + 1) + redacted + u.substring(queryEnd)
        } else {
            u
        }
        return redactFragmentPairs(withQuery)
    }

    /**
     * OAuth implicit flow puts tokens in the fragment
     * (…#access_token=…) (review round 3, SEN-6 question):
     * when the fragment is pair-shaped, redact it like a
     * query. A plain fragment identifier is left alone.
     */
    private fun redactFragmentPairs(url: String): String {
        val hash = url.indexOf('#')
        if (hash < 0) return url
        val frag = url.substring(hash + 1)
        if (!frag.contains('=')) return url
        return url.substring(0, hash + 1) + redactFormBody(frag)
    }

    /**
     * Redact credentials inside free text (review round 3,
     * SEC-1/PER-9; round 4, SEN-5/SEC-1): console text and
     * the raw event JSON quote URLs the structured fields
     * already redacted, in every form a URL carries a
     * credential — query pairs, pair-shaped fragments and
     * userinfo passwords. Runs are escape-aware (round 4):
     * the device org.json serializer writes '/' as "\/",
     * so a backslash inside a run is an escape carrier and
     * is skipped together with the character it escapes,
     * never a run terminator — otherwise a query value
     * containing '/' cut the run and left every parameter
     * after it unredacted in the raw copy.
     */
    private fun redactSecretsInText(text: String): String {
        if (!text.contains('?') && !text.contains('#') &&
            !text.contains(':')
        ) {
            return text
        }
        // The userinfo scan only runs when a scheme form
        // is present (round 6, PER-1): most strings carry
        // a ':' (times, "key: value") without any URL.
        val src = if (text.contains("://") || text.contains(":\\/\\/")) {
            redactUserinfoInText(text)
        } else {
            text
        }
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c != '?' && c != '#') {
                sb.append(c)
                i++
                continue
            }
            var end = i + 1
            while (end < src.length) {
                val e = src[end]
                if (e == '\\' && end + 1 < src.length) {
                    val n = src[end + 1]
                    // Escaped separators and quotes are part
                    // of the run; an escaped LINE BREAK ends
                    // it (review round 5, PER-1) — in the raw
                    // copy "\n" is how a real newline inside
                    // the quoted text is written, and a pair
                    // must not swallow the next line's start.
                    if (n == 'n' || n == 't' || n == 'r') break
                    if (n == '/' || n == '"' || n == '\\') {
                        end += 2
                        continue
                    }
                }
                if (e.isWhitespace() || e == '"' || e == '\'' ||
                    e == '<' || e == '>'
                ) {
                    break
                }
                end++
            }
            val run = src.substring(i + 1, end)
            sb.append(c)
            if (c == '?') {
                val hash = run.indexOf('#')
                val head = if (hash >= 0) run.substring(0, hash) else run
                sb.append(redactFormBody(head))
                if (hash >= 0) {
                    val tail = run.substring(hash + 1)
                    sb.append('#')
                    sb.append(
                        if (tail.contains('=')) redactFormBody(tail) else tail
                    )
                }
            } else {
                // A '#' with no '?' before it: only a
                // pair-shaped fragment is touched, so plain
                // fragments and prose keep their text.
                sb.append(if (run.contains('=')) redactFormBody(run) else run)
            }
            i = end
        }
        return sb.toString()
    }

    /**
     * A deep copy of a parsed event with every string
     * value run through the text redactor (round 5): the
     * raw copy's redaction basis. Non-string values pass
     * through unchanged.
     */
    private fun redactDeep(v: Any?): Any? = when (v) {
        is JSONObject -> {
            val copy = JSONObject()
            val keys = v.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                copy.put(k, redactDeep(v.get(k)))
            }
            copy
        }
        is JSONArray -> {
            val copy = JSONArray()
            for (i in 0 until v.length()) {
                copy.put(redactDeep(v.get(i)))
            }
            copy
        }
        is String -> redactSecretsInText(v)
        else -> v
    }

    /**
     * Redact userinfo passwords in URLs quoted in text.
     * Both scheme forms are recognized (review round 5,
     * SEN-1/SEC-1): the plain "://" of parsed text and the
     * ":\\/\\/" the device org.json serializer writes into
     * the raw copy — round 4 recognized only the plain
     * form, so the raw copy kept the password.
     */
    private fun redactUserinfoInText(text: String): String {
        if (!text.contains(':')) return text
        val sb = StringBuilder(text)
        var searchFrom = 0
        while (true) {
            val colon = sb.indexOf(":", searchFrom)
            if (colon < 0) break
            val authStart = when {
                sb.startsWith("//", colon + 1) -> colon + 3
                sb.startsWith("\\/\\/", colon + 1) -> colon + 5
                else -> -1
            }
            if (authStart < 0) {
                searchFrom = colon + 1
                continue
            }
            var authEnd = sb.length
            for (j in authStart until sb.length) {
                val ch = sb[j]
                if (ch == '/' || ch == '\\' || ch == '?' ||
                    ch == '#' || ch.isWhitespace() || ch == '"' ||
                    ch == '\''
                ) {
                    authEnd = j
                    break
                }
            }
            var at = -1
            var pwColon = -1
            for (j in authStart until authEnd) {
                if (sb[j] == '@') at = j
                if (sb[j] == ':' && pwColon < 0) pwColon = j
            }
            if (at > authStart && pwColon >= authStart && pwColon < at) {
                sb.replace(pwColon + 1, at, REDACTED_VALUE)
                searchFrom = pwColon + 1 + REDACTED_VALUE.length + 1
            } else {
                searchFrom =
                    if (authEnd > searchFrom) authEnd else searchFrom + 1
            }
        }
        return sb.toString()
    }

    /** A URL on its way into a record: capped and redacted
     * in one place, so no ingestion path can forget the
     * redaction (review round 3, SEC-1). */
    private fun capUrl(s: String?): String? = redactUrlQuery(capText(s))

    /**
     * Whether a body is shaped like form data: every
     * '&'-separated segment is a key=value pair whose key
     * carries no whitespace. A single pair qualifies — a
     * one-field form is still a form.
     */
    private fun isFormShaped(body: String): Boolean {
        if (body.isEmpty()) return false
        return body.split('&').all { pair ->
            val eq = pair.indexOf('=')
            eq > 0 && pair.substring(0, eq).none { it.isWhitespace() }
        }
    }

    private fun redactFormBody(body: String): String =
        body.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) {
                pair
            } else {
                val key = pair.substring(0, eq)
                val decoded = try {
                    java.net.URLDecoder.decode(key, "UTF-8")
                } catch (_: Exception) {
                    key
                }
                if (isSensitivePairKey(decoded)) {
                    "$key=$REDACTED_VALUE"
                } else {
                    pair
                }
            }
        }

    private fun redactJsonBody(body: String): String {
        // Trailing whitespace is not trailing content
        // (review round 4, SEN-7): tokener.more() does not
        // skip whitespace, so a body ending in a newline —
        // a common encoder habit — read as "more content"
        // and the whole body was withheld. Parse the
        // trimmed text; the verbatim return below still
        // uses the original.
        val tokener = org.json.JSONTokener(body.trimEnd())
        val root = try {
            tokener.nextValue()
        } catch (_: Exception) {
            // Fail closed (review round 2, SEN-8): a JSON
            // body that does not parse — CDP truncates
            // postData at its cap, so a cut body is exactly
            // the case that occurs — was stored raw,
            // password and all. Withhold the body instead.
            return REDACTED_UNPARSEABLE
        } catch (_: Error) {
            // A pathologically nested body exhausts the
            // stack inside the recursive parser (review
            // round 3, PER-8): fail closed the same way
            // instead of letting the Error reach the relay.
            return REDACTED_UNPARSEABLE
        }
        // nextValue reads ONE value (review round 3, SEN-4):
        // trailing content — NDJSON batches, concatenated
        // documents — was never inspected, so a credential
        // on a later line sailed through, and when the first
        // value did change, the tail was silently dropped.
        // Anything past the first value but whitespace makes
        // the whole body unparseable here.
        val hasTrailing = try {
            tokener.more()
        } catch (_: Exception) {
            true
        }
        if (hasTrailing) return REDACTED_UNPARSEABLE
        if (root !is JSONObject && root !is JSONArray) return body
        // Unchanged bodies keep their original text: re-
        // serializing rewrites number spellings and key
        // order, which is diagnostic content (SEN-8).
        val changed = try {
            redactJsonValue(root)
        } catch (_: Error) {
            return REDACTED_UNPARSEABLE
        }
        return if (changed) root.toString() else body
    }

    /** A header value that is a counter, not a
     * credential: digits and dots, or a duration built
     * from them ("6m0s", "1.5s", "20ms") — rate-limit
     * headers use both forms (round 7, SEN-5). */
    private fun isCounterValue(v: String): Boolean =
        v.isNotEmpty() && v.any { it.isDigit() } &&
            v.all { it.isDigit() || it == '.' || it in "msunh" }

    /**
     * Redact every string in a credential container's
     * subtree, whatever the inner key names are (round
     * 7, SEC-1): a plural-named map or list of tokens is
     * a name-to-secret mapping, and its inner names are
     * site-chosen ids, not credential names. Non-string
     * scalars keep their values.
     */
    private fun redactCredentialSubtree(v: Any?): Boolean {
        var changed = false
        when (v) {
            is JSONObject -> {
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val child = v.get(k)
                    if (isSensitiveBodyKey(k)) {
                        // An explicit credential name keeps
                        // its any-type rule even inside a
                        // plural container (round 8, PER-1:
                        // a numeric PIN under "pin").
                        v.put(k, REDACTED_VALUE)
                        changed = true
                    } else if (child is String) {
                        v.put(k, REDACTED_VALUE)
                        changed = true
                    } else if (redactCredentialSubtree(child)) {
                        changed = true
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until v.length()) {
                    val el = v.get(i)
                    if (el is String) {
                        v.put(i, REDACTED_VALUE)
                        changed = true
                    } else if (redactCredentialSubtree(el)) {
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    /**
     * For an ambiguous name's array value (round 7,
     * SEN-3): string elements redact individually,
     * non-strings keep their values, and object elements
     * are recursed into by key name as usual.
     */
    private fun redactStringElements(arr: JSONArray): Boolean {
        var changed = false
        for (i in 0 until arr.length()) {
            val el = arr.get(i)
            if (el is String) {
                arr.put(i, REDACTED_VALUE)
                changed = true
            } else if (el is JSONArray) {
                // A nested array keeps the element rule:
                // its strings are this name's values too
                // (round 8, SEN-1).
                if (redactStringElements(el)) changed = true
            } else if (redactJsonValue(el)) {
                changed = true
            }
        }
        return changed
    }

    /**
     * The ambiguous-name and compound-plural dispatch
     * (round 7): returns whether anything changed, and
     * calls [putWhole] when the member's whole value is
     * the credential. Numbers, booleans and null never
     * reach here (the caller keeps them untouched), which
     * is what preserves usage counts (PER-2/PER-3).
     */
    private fun redactAmbiguousOrPlural(
        k: String,
        child: Any,
        putWhole: () -> Unit
    ): Boolean {
        if (k.lowercase() in SENSITIVE_EXACT_SCALAR) {
            return when (child) {
                is String -> {
                    putWhole()
                    true
                }
                // String elements redact individually;
                // objects inside are recursed by name.
                is JSONArray -> redactStringElements(child)
                // "auth" the object: recurse by name, as
                // since round 1.
                else -> redactJsonValue(child)
            }
        }
        if (isPluralCredentialName(k)) {
            return when (child) {
                is String -> {
                    putWhole()
                    true
                }
                // A name-to-secret mapping or a mixed
                // list: every string inside is a
                // credential, whatever its inner name
                // (round 7, SEC-1).
                is JSONObject, is JSONArray ->
                    redactCredentialSubtree(child)
                else -> false
            }
        }
        return redactJsonValue(child)
    }

    /** Redact in place; returns whether anything changed. */
    private fun redactJsonValue(v: Any?): Boolean {
        var changed = false
        when (v) {
            is JSONObject -> {
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val child = v.get(k)
                    if (isSensitiveBodyKey(k)) {
                        v.put(k, REDACTED_VALUE)
                        changed = true
                    } else if (child is JSONObject || child is JSONArray ||
                        child is String
                    ) {
                        if (redactAmbiguousOrPlural(k, child) {
                                v.put(k, REDACTED_VALUE)
                            }
                        ) {
                            changed = true
                        }
                    } else if (redactJsonValue(child)) {
                        changed = true
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until v.length()) {
                    if (redactJsonValue(v.get(i))) changed = true
                }
            }
        }
        return changed
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
                    url = capUrl(f.optString("url").ifEmpty { null }),
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
        val url = redactUrlQuery(f.optString("url").ifEmpty { null })
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

    /**
     * Stream the console export into [out] with a JsonWriter,
     * the same shape as the requests export (PER-9): building
     * the whole JSONArray tree plus the rendered string first
     * put a transient heap peak next to the resident store at
     * the caps, and an OOM there would lose the recording to
     * the clear() that follows the export. The export format
     * does not change; the cosmetic properties are the ones
     * listed on writeRequestsJson. The writer is NOT closed
     * here: the caller owns [out].
     */
    fun writeConsoleJson(list: List<Entry>, net: Map<String, NetReq>, out: java.io.Writer) {
        val jw = android.util.JsonWriter(out)
        jw.setIndent("  ")
        writeConsoleJson(list, net, AndroidJsonSink(jw))
    }

    internal fun writeConsoleJson(list: List<Entry>, net: Map<String, NetReq>, w: JsonSink) {
        w.beginArray()
        for (e in list) {
            w.beginObject()
            w.name("time").value(isoFormat(e.timeMs))
            w.name("level").value(e.level)
            w.name("text").value(e.text)
            e.source?.let { w.name("source").value(it) }
            e.url?.let { w.name("url").value(it) }
            e.line?.let { w.name("line").value(it) }
            e.column?.let { w.name("column").value(it) }
            e.scriptId?.let { w.name("scriptId").value(it) }
            if (e.frames.isNotEmpty()) {
                w.name("stackTrace")
                w.beginArray()
                for (f in e.frames) {
                    w.beginObject()
                    f.functionName?.let { w.name("functionName").value(it) }
                    f.url?.let { w.name("url").value(it) }
                    f.scriptId?.let { w.name("scriptId").value(it) }
                    f.line?.let { w.name("line").value(it) }
                    f.column?.let { w.name("column").value(it) }
                    w.endObject()
                }
                w.endArray()
            }
            val nr = e.networkRequestId?.let { net[it] }
            if (e.networkRequestId != null || nr != null) {
                w.name("network")
                w.beginObject()
                e.networkRequestId?.let { w.name("requestId").value(it) }
                if (nr != null) {
                    nr.method?.let { w.name("method").value(it) }
                    nr.url?.let { w.name("url").value(it) }
                    nr.status?.let { w.name("status").value(it) }
                    nr.mimeType?.let { w.name("mimeType").value(it) }
                    nr.errorText?.let { w.name("errorText").value(it) }
                    nr.initiator?.let { w.name("initiator").value(it) }
                }
                w.endObject()
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
            e.raw?.let { w.name("raw").value(it) }
            w.endObject()
        }
        w.endArray()
        w.flush()
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

        /** Replacement for a credential value (FULL-SEC-Q1). */
        private const val REDACTED_VALUE = "<redacted>"

        /** Header names whose values are credentials by
         * definition, matched case-insensitively. */
        private val SENSITIVE_HEADERS = setOf(
            "authorization", "cookie", "set-cookie", "proxy-authorization"
        )

        /** Headers whose value is a URL: the value gets
         * query redaction instead of passing through
         * (review round 4, SEC-6). */
        private val URL_VALUED_HEADERS = setOf(
            "referer", "location", "content-location"
        )

        /** Body field names whose values are credentials,
         * matched case-insensitively. */
        private val SENSITIVE_BODY_KEYS = setOf(
            "password", "passwd", "pwd", "passphrase",
            "token", "access_token", "refresh_token", "id_token",
            "secret", "client_secret", "api_key", "apikey",
            "authorization", "credential", "credentials"
        )

        /** Credential words matched as whole name TOKENS:
         * a name is split into tokens (separators, camelCase
         * and letter/digit boundaries), and a token equal to
         * one of these words marks the name. */
        private val SENSITIVE_NAME_TOKENS = setOf(
            "password", "passwd", "passphrase", "token", "secret",
            "credential", "credentials", "authorization",
            "authentication", "csrf", "xsrf", "otp", "apikey",
            // Round 5 (SEC-3): bearer values under their own
            // names — SAS and CloudFront signatures, JWTs,
            // session ids, SAML responses.
            "signature", "sig", "jwt", "session", "bearer",
            "samlresponse"
        )

        /** Short or ambiguous names matched only as a WHOLE
         * name and only for string-shaped values (round 5,
         * SEC-2; round 6): query and form pairs are always
         * strings; a JSON member is redacted when its value
         * is a string or a string array, recursed into when
         * it is an object ("auth" the container keeps the
         * round-1 nested behaviour), and left alone when
         * it is a number or boolean. */
        private val SENSITIVE_EXACT_SCALAR = setOf(
            "auth", "cookie", "key", "pass", "pw", "sid", "ticket"
        )

        /** First tokens that make a trailing "tokens" a
         * quantity, not a credential list: used by the pair
         * matcher, whose values are always strings (round
         * 6). JSON and header plurals are gated on the
         * value's shape instead (isPluralCredentialName). */
        private val QUANTIFIER_TOKENS = setOf(
            "max", "min", "prompt", "completion", "input",
            "output", "total", "context"
        )

        /** The standard CORS header names: policy, never
         * credentials. */
        private val CORS_HEADERS = setOf(
            "access-control-allow-origin",
            "access-control-allow-credentials",
            "access-control-allow-methods",
            "access-control-allow-headers",
            "access-control-expose-headers",
            "access-control-max-age",
            "access-control-request-method",
            "access-control-request-headers",
            "access-control-allow-private-network"
        )

        /** Extra token words for HEADERS only: a header
         * named with "auth" or "cookie" carries a credential
         * by construction. A body field of the same name is
         * handled by the scalar rule below instead. */
        private val SENSITIVE_HEADER_ONLY_TOKENS = setOf("auth", "cookie")

        /** Long, unambiguous credential words also matched
         * as a token SUFFIX (review round 4, SEN-4): real
         * names concatenate without separators — accesstoken,
         * newpassword, clientsecret, csrftoken,
         * csrfmiddlewaretoken — and token equality alone let
         * them through. The suffix rule cannot fire on the
         * pinned lookalikes: footprint ends in none of these,
         * max_tokens' token is "tokens" (the plural does not
         * end in "token"), and tokenizer ends in "ize"-side
         * letters, not the word. Bare plurals (tokens,
         * secrets, passwords as whole names) deliberately do
         * NOT match: a "tokens" field in an API payload is
         * ordinarily a count or a list of public ids, and
         * max_tokens is a request parameter, not a
         * credential — the trade-off is recorded in
         * REVIEW.md. */
        private val SENSITIVE_NAME_SUFFIXES = listOf(
            "password", "passwd", "passphrase", "secret",
            "credential", "credentials", "authorization",
            "authentication", "token", "csrf", "xsrf"
        )

        /** Exact names too short to token-match safely. */
        private val SENSITIVE_BODY_KEYS_EXACT = setOf(
            "pwd", "pin", "passcode"
        )

        /** Split a field name into lowercase word tokens:
         * separators and brackets are boundaries, and so are
         * camelCase transitions — including the end of an
         * uppercase run (CSRFToken -> csrf, token) — and
         * letter/digit transitions (password1 -> password,
         * 1). */
        private fun keyTokens(key: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            fun flush() {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
            }
            var prev: Char? = null
            for (i in key.indices) {
                val c = key[i]
                if (c.isLetterOrDigit()) {
                    val next = key.getOrNull(i + 1)
                    val pv = prev
                    val boundary = pv != null && (
                        (pv.isLowerCase() && c.isUpperCase()) ||
                            (pv.isDigit() != c.isDigit() && pv.isLetterOrDigit()) ||
                            (pv.isUpperCase() && c.isUpperCase() &&
                                next != null && next.isLowerCase())
                        )
                    if (boundary) flush()
                    cur.append(c.lowercaseChar())
                    prev = c
                } else {
                    flush()
                    prev = null
                }
            }
            flush()
            return out
        }

        /** Whether a field or header name marks its value
         * as a credential: the exact sets, a credential word
         * as a whole token, a credential word as a token
         * suffix, or "api" + "key" as adjacent tokens. */
        private fun isSensitiveName(key: String): Boolean {
            val n = key.lowercase()
            if (n in SENSITIVE_BODY_KEYS || n in SENSITIVE_BODY_KEYS_EXACT) {
                return true
            }
            val tokens = keyTokens(key)
            for (t in tokens) {
                if (t in SENSITIVE_NAME_TOKENS ||
                    t in SENSITIVE_BODY_KEYS_EXACT
                ) {
                    return true
                }
                if (SENSITIVE_NAME_SUFFIXES.any { t.endsWith(it) }) {
                    return true
                }
            }
            // "<modifier>_key" spelled as two tokens
            // (api_key, private_key, signing_key, ...).
            return tokens.zipWithNext().any { (a, b) ->
                b == "key" && a in KEY_MODIFIERS
            }
        }

        /** Modifiers that make a following "key" token a
         * credential compound (round 6, SEN-10). */
        private val KEY_MODIFIERS = setOf(
            "api", "private", "access", "client", "master",
            "signing", "consumer", "encryption", "license",
            "app", "auth", "secret"
        )

        /**
         * Whether a name has the SHAPE of a compound plural
         * credential (round 6, SEN-1/PER-2): a plural
         * credential word at the end (trailing numeric and
         * container tokens aside), or a concatenated plural
         * (accesstokens). Shape alone does not redact: the
         * callers decide by the value — numbers, booleans
         * and null are kept (the same shape with a number
         * is a usage count, cache_read_input_tokens),
         * strings redact, and containers redact their
         * strings wholesale. A bare plural name (tokens,
         * secrets) is not compound and never matches.
         */
        private fun isPluralCredentialName(key: String): Boolean {
            val tokens = keyTokens(key).dropLastWhile {
                it.all { c -> c.isDigit() } || it in CONTAINER_TOKENS
            }
            if (tokens.size >= 2) {
                val last = tokens.last()
                val singular = last.removeSuffix("s")
                if (singular == last) return false
                if (singular in SENSITIVE_NAME_TOKENS ||
                    SENSITIVE_NAME_SUFFIXES.any { singular.endsWith(it) }
                ) {
                    return true
                }
                // api_keys / private_keys: the "key" compound
                // in plural form (round 6, SEC-2).
                return singular == "key" &&
                    tokens[tokens.size - 2] in KEY_MODIFIERS
            }
            if (tokens.size == 1) {
                val t = tokens[0]
                return SENSITIVE_NAME_SUFFIXES.any { w ->
                    t.length > w.length + 1 && t.endsWith(w + "s")
                }
            }
            return false
        }

        /** Trailing words that name a container, not the
         * credential itself (refresh_tokens_list). */
        private val CONTAINER_TOKENS = setOf(
            "list", "json", "hash", "map", "set", "array"
        )

        /** Whether a query/form pair name marks its value
         * as a credential: the body matcher, plus the
         * scalar-only exact names (pairs are always
         * scalar). */
        private fun isSensitivePairKey(key: String): Boolean {
            if (key.lowercase() in SENSITIVE_EXACT_SCALAR) return true
            if (isSensitiveName(key)) return true
            // Pair values are always strings, so a compound
            // plural here is a credential list — unless a
            // quantifier leads (a form field named
            // max_tokens is a request parameter).
            if (isPluralCredentialName(key)) {
                val first = keyTokens(key).firstOrNull()
                return first !in QUANTIFIER_TOKENS
            }
            return false
        }

        /** Whether a body field name marks its value as a
         * credential. */
        private fun isSensitiveBodyKey(key: String): Boolean =
            isSensitiveName(key)

        /** Whether a header name marks its value as a
         * credential: the shared name matcher, with the
         * header-specific exclusions — pseudo-headers carry
         * addressing, not credentials, and
         * www-authenticate is a challenge, not a credential. */
        private fun isSensitiveHeader(name: String): Boolean {
            val n = name.lowercase()
            if (n.startsWith(":")) return false
            if (n == "www-authenticate") return false
            // The standard CORS headers describe policy,
            // not credentials (round 5, SEC-4; round 6,
            // SEN-8: an allowlist, not the prefix — a page
            // can send any Access-Control-* name it likes).
            if (n in CORS_HEADERS) return false
            if (n in SENSITIVE_HEADERS) return true
            if (keyTokens(name).any { it in SENSITIVE_HEADER_ONLY_TOKENS }) {
                return true
            }
            return isSensitiveName(name)
        }

        /** Replacement for a body that could not be parsed
         * for redaction (review round 2, SEN-8): the whole
         * body is withheld rather than stored raw. */
        private const val REDACTED_UNPARSEABLE = "<redacted: unparseable body>"

        /** The only CDP methods worth a full parse. */
        val HANDLED_METHODS = setOf(
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
