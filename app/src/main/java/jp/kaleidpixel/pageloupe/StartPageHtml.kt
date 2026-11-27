package jp.kaleidpixel.pageloupe

/**
 * The start page's HTML, built from the palette's CSS colour
 * strings and a per-load nonce (embedded as an HTML comment so
 * the load's data URL is unique to the load; see
 * BrowserTab.homeDataUrl).
 *
 * INVARIANT: this page must never reference a subresource —
 * no scripts, images, stylesheets, frames, fonts or CSS url()
 * loads. The DevTools recorder excludes only the start page's
 * own Document load and records everything else on the tab's
 * stream, on the premise that the start page emits nothing
 * else (see isInternalDocumentUrl and REVIEW.md). Adding an
 * external reference here would silently put the start page's
 * own traffic into the recordings. The JVM tests
 * (tests/StartPageHtmlTest.kt) pin the absence of
 * subresource-bearing syntax.
 *
 * Pure and top-level so those tests can build the page.
 */
fun startPageHtml(
    ink: String,
    sub: String,
    brand: String,
    line: String,
    bg: String,
    nonce: String,
): String = """
    <!DOCTYPE html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>PageLoupe</title>
    <style>
      body{font-family:sans-serif;margin:0;padding:48px 24px;color:$ink;background:$bg}
      h1{font-size:28px;color:$brand;margin:0 0 8px}
      p{color:$sub;margin:0 0 32px}
      a{display:block;padding:14px 16px;margin:0 0 10px;border:1px solid $line;border-radius:12px;
        color:$ink;text-decoration:none;font-size:16px}
      a span{color:$sub;font-size:13px;display:block;margin-top:2px}
    </style></head><body>
    <h1>PageLoupe</h1>
    <p>DevTools 搭載ブラウザ。下の &lt;/&gt; ボタンで DevTools が開きます。</p>
    <a href="https://www.google.com">Google<span>google.com</span></a>
    <a href="https://github.com">GitHub<span>github.com</span></a>
    <a href="https://developer.mozilla.org">MDN Web Docs<span>developer.mozilla.org</span></a>
    <a href="https://example.com">Example<span>example.com</span></a>
    <!-- pageloupe-home $nonce -->
    </body></html>
""".trimIndent()
