package org.hello.reader.core.engine.smil

import java.io.File
import java.util.zip.ZipFile

/**
 * A block element (p, h1, h2, h3, blockquote) extracted from XHTML.
 */
data class ParsedHtmlBlock(
    val tag: String,
    val id: String?,
    val text: String,
)

/**
 * A single synchronized or standalone sentence/paragraph extracted from an EPUB 3 archive.
 */
data class EpubSentenceSegment(
    val elementId: String,
    val textHref: String,
    val text: String,
    val audioSrc: String,
    val clipBeginMs: Long,
    val clipEndMs: Long,
    val isChapterStart: Boolean = false,
    val chapterTitle: String? = null,
    val rawHtmlId: String? = null,
)

/**
 * A single audio track referenced in an EPUB 3 SMIL overlay, along with its start offset
 * and duration on the unified playback timeline.
 */
data class AudioTrackItem(
    val file: File,
    val audioSrc: String,
    val startOffsetMs: Long,
    val durationMs: Long,
)

/**
 * Complete overlay & text payload extracted from a local `.epub` container.
 */
data class ExtractedEpubContent(
    val smilNodes: List<SmilParNode>,
    val sentences: List<EpubSentenceSegment>,
    val extractedAudioFile: File? = null,
    /** Table-of-contents labels by spine document path (fragment stripped), from the EPUB 3 nav or the NCX. */
    val tocTitles: Map<String, String> = emptyMap(),
    /** All extracted audio tracks across spine chapters in playback order. */
    val audioTracks: List<AudioTrackItem> = emptyList(),
)

/**
 * Pure-Kotlin EPUB 3 Media Overlay (`.smil`) and XHTML chapter text extractor.
 *
 * - Extracts `.smil` `<par>` timing nodes and resolves relative `textHref` / `audioSrc` paths
 *   against the `.smil` entry directory inside the EPUB zip.
 * - Extracts the actual human-readable sentence text for each DOM `fragmentId` (`id="..."`)
 *   from the corresponding `.xhtml` / `.html` spine files inside the EPUB zip.
 * - For `EBOOK_ONLY` EPUBs (without `.smil` overlays), extracts paragraphs (`<p>`, `<h1>`–`<h3>`)
 *   directly from the XHTML spine documents so pure e-books display real chapter text.
 * - Orders content by the OPF spine (falling back to file names) and reads chapter titles from the
 *   EPUB 3 navigation document or the EPUB 2 NCX.
 * - Optionally extracts the primary embedded audio track from a `READALOUD` EPUB container
 *   to [audioOutputDir] for playback via Media3 `ExoPlayer`.
 */
object EpubOverlayExtractor {

    private val blockParagraphRegex = Regex(
        pattern = """<(p|h1|h2|h3|h4|blockquote)\b([^>]*)>(.*?)</\1>""",
        options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val idAttrRegex = Regex("""\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private val attrRegex = Regex("""([a-zA-Z0-9_:-]+)\s*=\s*["']([^"']*)["']""")
    private val rootfileRegex = Regex("""<rootfile\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val manifestItemRegex = Regex("""<item\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val itemrefRegex = Regex("""<itemref\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val tocNavRegex = Regex(
        pattern = """<nav\b[^>]*epub:type\s*=\s*["'][^"']*\btoc\b[^"']*["'][^>]*>(.*?)</nav>""",
        options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val anchorRegex = Regex(
        pattern = """<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val ncxPointRegex = Regex(
        pattern = """<navLabel\b[^>]*>\s*<text\b[^>]*>(.*?)</text>\s*</navLabel>\s*<content\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""",
        options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val percentEscapeRegex = Regex("""(%[0-9A-Fa-f]{2})+""")

    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")

    fun extract(epubFile: File, audioOutputDir: File? = null): ExtractedEpubContent? {
        if (!epubFile.isFile) return null
        return runCatching {
            ZipFile(epubFile).use { zip ->
                val entriesByName = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .associateBy { normalizeEntryPath(it.name) }

                val smilEntries = entriesByName.keys
                    .filter { it.endsWith(".smil", ignoreCase = true) }
                    .sorted()

                fun readEntry(path: String): String? =
                    entriesByName[path]?.let { entry -> zip.getInputStream(entry).bufferedReader().use { it.readText() } }

                val packageDoc = readPackage(entriesByName.keys, ::readEntry)
                val spineIndex = packageDoc.spine.withIndex().associate { (i, href) -> href to i }
                fun spinePosition(href: String): Int = spineIndex[href] ?: Int.MAX_VALUE
                val tocTitles = readTocTitles(entriesByName.keys, packageDoc, ::readEntry)
                val tocAnchors = readTocFragmentAnchors(entriesByName.keys, packageDoc, ::readEntry)

                val xhtmlFragmentsByEntry = mutableMapOf<String, Map<String, String>>()
                val globalFragments = mutableMapOf<String, String>()

                fun loadFragmentsForXhtml(entryPath: String): Map<String, String> {
                    xhtmlFragmentsByEntry[entryPath]?.let { return it }
                    val zipEntry = entriesByName[entryPath] ?: return emptyMap()
                    val rawXml = zip.getInputStream(zipEntry).bufferedReader().use { it.readText() }
                    val map = parseIdFragments(rawXml)
                    xhtmlFragmentsByEntry[entryPath] = map
                    for ((k, v) in map) {
                        globalFragments.putIfAbsent(k, v)
                    }
                    return map
                }

                if (smilEntries.isNotEmpty()) {
                    val unorderedNodes = mutableListOf<SmilParNode>()
                    for (smilPath in smilEntries) {
                        val smilEntry = entriesByName[smilPath] ?: continue
                        val smilXml = zip.getInputStream(smilEntry).bufferedReader().use { it.readText() }
                        val parsed = SmilParser.parse(smilXml)
                        for (node in parsed) {
                            val resolvedTextHref = resolveRelativePath(smilPath, node.textHref)
                            val resolvedAudioSrc = resolveRelativePath(smilPath, node.audioSrc)
                            unorderedNodes += node.copy(
                                textHref = resolvedTextHref,
                                audioSrc = resolvedAudioSrc,
                            )
                        }
                    }
                    // Stable: clips of one document keep their SMIL order.
                    val resolvedNodes = unorderedNodes.sortedBy { spinePosition(it.textHref) }

                    // Preload referenced XHTML files
                    resolvedNodes.map { it.textHref }.distinct().forEach { href ->
                        if (href in entriesByName) {
                            loadFragmentsForXhtml(href)
                        } else {
                            // Try matching by basename if SMIL had non-standard relative path
                            val fallback = entriesByName.keys.firstOrNull {
                                it.endsWith("/${href.substringAfterLast('/')}", ignoreCase = true) ||
                                    it.equals(href.substringAfterLast('/'), ignoreCase = true)
                            }
                            if (fallback != null) loadFragmentsForXhtml(fallback)
                        }
                    }

                    // Map multiple audio tracks to a continuous timeline
                    val distinctAudioSources = resolvedNodes.map { it.audioSrc }.distinct()
                    val audioTracks = mutableListOf<AudioTrackItem>()
                    val trackOffsetsByAudioSrc = mutableMapOf<String, Long>()
                    var runningOffsetMs = 0L

                    audioOutputDir?.mkdirs()

                    for ((index, audioSrc) in distinctAudioSources.withIndex()) {
                        val zipEntryPath = resolveAudioZipEntry(entriesByName.keys, audioSrc)
                        val zipEntry = zipEntryPath?.let { zip.getEntry(it) }
                        val extractedFile = if (zipEntry != null && audioOutputDir != null) {
                            val fileName = zipEntryPath.substringAfterLast('/')
                            val targetFile = File(audioOutputDir, "audio_${index}_$fileName")
                            if (!targetFile.isFile || targetFile.length() != zipEntry.size) {
                                zip.getInputStream(zipEntry).use { input ->
                                    targetFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                            targetFile
                        } else {
                            null
                        }

                        val trackNodes = resolvedNodes.filter { it.audioSrc == audioSrc }
                        val smilMaxMs = trackNodes.maxOfOrNull { it.clipEndMs } ?: 0L
                        val smilMinMs = trackNodes.minOfOrNull { it.clipBeginMs } ?: 0L
                        val fileDurationMs = extractedFile?.let { extractAudioDuration(it) } ?: smilMaxMs
                        val trackDurationMs = maxOf(fileDurationMs, smilMaxMs)

                        // Check if this track restarts at 0 in its file while earlier tracks preceded it
                        val needsOffset = (runningOffsetMs > 0L) && (smilMinMs < runningOffsetMs - 1_000L)
                        val effectiveOffset = if (needsOffset) runningOffsetMs else 0L

                        if (extractedFile != null) {
                            audioTracks += AudioTrackItem(
                                file = extractedFile,
                                audioSrc = audioSrc,
                                startOffsetMs = effectiveOffset,
                                durationMs = trackDurationMs,
                            )
                        }

                        trackOffsetsByAudioSrc[audioSrc] = effectiveOffset
                        runningOffsetMs = maxOf(runningOffsetMs + trackDurationMs, effectiveOffset + trackDurationMs)
                    }

                    val timelineNodes = resolvedNodes.map { node ->
                        val offset = trackOffsetsByAudioSrc[node.audioSrc] ?: 0L
                        if (offset != 0L) {
                            node.copy(
                                clipBeginMs = node.clipBeginMs + offset,
                                clipEndMs = node.clipEndMs + offset,
                            )
                        } else {
                            node
                        }
                    }

                    var previousTimelineHref: String? = null
                    val sentences = timelineNodes.mapIndexed { index, node ->
                        val text = xhtmlFragmentsByEntry[node.textHref]?.get(node.fragmentId)
                            ?: globalFragments[node.fragmentId]
                            ?: ""
                        val targetHref = "${node.textHref}#${node.fragmentId}"
                        val matchedTocTitle = tocAnchors[targetHref] ?: tocAnchors[node.fragmentId]
                        val isTocTarget = matchedTocTitle != null
                        val isNewChapter = previousTimelineHref == null || node.textHref != previousTimelineHref || isTocTarget
                        previousTimelineHref = node.textHref
                        val chapterTitle = matchedTocTitle ?: if (isNewChapter) tocTitles[node.textHref] else null
                        EpubSentenceSegment(
                            elementId = node.fragmentId,
                            textHref = node.textHref,
                            text = text,
                            audioSrc = node.audioSrc,
                            clipBeginMs = node.clipBeginMs,
                            clipEndMs = node.clipEndMs,
                            isChapterStart = isNewChapter,
                            chapterTitle = if (isNewChapter) chapterTitle else null,
                        )
                    }

                    ExtractedEpubContent(
                        smilNodes = timelineNodes,
                        sentences = sentences,
                        extractedAudioFile = audioTracks.firstOrNull()?.file,
                        tocTitles = tocTitles,
                        audioTracks = audioTracks,
                    )
                } else {
                    // EBOOK_ONLY fallback: extract readable paragraphs from XHTML/HTML spine files
                    val xhtmlEntries = entriesByName.keys
                        .filter {
                            it.endsWith(".xhtml", ignoreCase = true) ||
                                it.endsWith(".html", ignoreCase = true) ||
                                it.endsWith(".htm", ignoreCase = true)
                        }
                        .filterNot { it.contains("nav", ignoreCase = true) || it.contains("toc", ignoreCase = true) }
                        .sortedWith(compareBy<String> { spinePosition(it) }.thenBy { it })

                    val paragraphs = mutableListOf<EpubSentenceSegment>()
                    val syntheticNodes = mutableListOf<SmilParNode>()
                    var counter = 0
                    val segmentDurationMs = 5_000L

                    for (xhtmlPath in xhtmlEntries) {
                        val entry = entriesByName[xhtmlPath] ?: continue
                        val rawHtml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                        val blocks = extractReadableBlocks(rawHtml)
                        val docTitle = tocTitles[xhtmlPath]
                        var previousBlockWasHeading = false
                        for ((bIdx, block) in blocks.withIndex()) {
                            val idx = counter++
                            val elemId = "p_${idx + 1}"
                            val startMs = idx * segmentDurationMs
                            val endMs = startMs + segmentDurationMs
                            syntheticNodes += SmilParNode(
                                id = "par_${idx + 1}",
                                textHref = xhtmlPath,
                                fragmentId = elemId,
                                audioSrc = "",
                                clipBeginMs = startMs,
                                clipEndMs = endMs,
                            )
                            val targetHref = if (!block.id.isNullOrBlank()) "$xhtmlPath#${block.id}" else null
                            val matchedTocTitle = targetHref?.let { tocAnchors[it] } ?: block.id?.let { tocAnchors[it] }
                            val isHeadingTag = block.tag in setOf("h1", "h2")
                            val isHeadingText = isChapterHeadingText(block.text)
                            val isHeadingBlock = isHeadingTag || isHeadingText

                            val isStart = if (bIdx == 0) {
                                true
                            } else if (previousBlockWasHeading && isHeadingBlock && matchedTocTitle == null) {
                                false
                            } else {
                                matchedTocTitle != null || isHeadingBlock
                            }

                            val chapterTitle = matchedTocTitle
                                ?: (if (bIdx == 0) docTitle else null)
                                ?: (if (isHeadingBlock) block.text else null)

                            previousBlockWasHeading = isHeadingBlock

                            paragraphs += EpubSentenceSegment(
                                elementId = elemId,
                                textHref = xhtmlPath,
                                text = block.text,
                                audioSrc = "",
                                clipBeginMs = startMs,
                                clipEndMs = endMs,
                                isChapterStart = isStart,
                                chapterTitle = if (isStart) chapterTitle else null,
                                rawHtmlId = block.id?.ifBlank { null },
                            )
                        }
                    }

                    ExtractedEpubContent(
                        smilNodes = syntheticNodes,
                        sentences = paragraphs,
                        extractedAudioFile = null,
                        tocTitles = tocTitles,
                    )
                }
            }
        }.getOrNull()
    }

    /** The OPF's spine (resolved entry paths, in reading order) and where its navigation documents are. */
    internal data class PackageDocument(
        val spine: List<String>,
        val navPath: String?,
        val ncxPath: String?,
    )

    internal fun readPackage(entryNames: Set<String>, read: (String) -> String?): PackageDocument {
        val opfPath = read("META-INF/container.xml")
            ?.let { container -> rootfileRegex.find(container)?.let { parseAttributes(it.groupValues[1])["full-path"] } }
            ?.let(::normalizeEntryPath)
            ?: entryNames.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: return PackageDocument(emptyList(), null, null)
        val opf = read(opfPath) ?: return PackageDocument(emptyList(), null, null)

        data class Item(val href: String, val mediaType: String, val properties: String)
        val items = manifestItemRegex.findAll(opf).mapNotNull { match ->
            val attrs = parseAttributes(match.groupValues[1])
            val id = attrs["id"] ?: return@mapNotNull null
            val href = attrs["href"]?.let { resolveRelativePath(opfPath, decodePercent(it.substringBefore('#'))) } ?: return@mapNotNull null
            id to Item(href, attrs["media-type"].orEmpty(), attrs["properties"].orEmpty())
        }.toMap()

        val spine = itemrefRegex.findAll(opf)
            .mapNotNull { items[parseAttributes(it.groupValues[1])["idref"]]?.href }
            .toList()
        return PackageDocument(
            spine = spine,
            navPath = items.values.firstOrNull { "nav" in it.properties.split(' ') }?.href,
            ncxPath = items.values.firstOrNull { it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) }?.href
                ?: entryNames.firstOrNull { it.endsWith(".ncx", ignoreCase = true) },
        )
    }

    /** First TOC label per spine document, preferring the EPUB 3 nav over the NCX. */
    internal fun readTocTitles(
        entryNames: Set<String>,
        packageDoc: PackageDocument,
        read: (String) -> String?,
    ): Map<String, String> {
        val titles = linkedMapOf<String, String>()

        val navPath = packageDoc.navPath ?: entryNames.firstOrNull { path ->
            (path.endsWith(".xhtml", ignoreCase = true) || path.endsWith(".html", ignoreCase = true)) &&
                path.substringAfterLast('/').contains("nav", ignoreCase = true)
        }
        navPath?.let(read)?.let { navDoc ->
            tocNavRegex.find(navDoc)?.groupValues?.get(1)?.let { toc ->
                for (anchor in anchorRegex.findAll(toc)) {
                    val rawHref = anchor.groupValues[1]
                    val pathPart = rawHref.substringBefore('#')
                    val label = cleanHtmlText(anchor.groupValues[2])
                    if (pathPart.isNotBlank() && label.isNotEmpty()) {
                        val resolvedPath = resolveRelativePath(navPath, decodePercent(pathPart))
                        titles.putIfAbsent(resolvedPath, label)
                    }
                }
            }
        }
        if (titles.isNotEmpty()) return titles

        val ncxPath = packageDoc.ncxPath ?: return titles
        read(ncxPath)?.let { ncx ->
            for (point in ncxPointRegex.findAll(ncx)) {
                val label = cleanHtmlText(point.groupValues[1])
                val rawHref = point.groupValues[2]
                val pathPart = rawHref.substringBefore('#')
                if (pathPart.isNotBlank() && label.isNotEmpty()) {
                    val resolvedPath = resolveRelativePath(ncxPath, decodePercent(pathPart))
                    titles.putIfAbsent(resolvedPath, label)
                }
            }
        }
        return titles
    }

    /** Explicit anchor targets (#id) in the TOC, for detecting chapter starts inside a document. */
    internal fun readTocFragmentAnchors(
        entryNames: Set<String>,
        packageDoc: PackageDocument,
        read: (String) -> String?,
    ): Map<String, String> {
        val anchors = linkedMapOf<String, String>()

        val navPath = packageDoc.navPath ?: entryNames.firstOrNull { path ->
            (path.endsWith(".xhtml", ignoreCase = true) || path.endsWith(".html", ignoreCase = true)) &&
                path.substringAfterLast('/').contains("nav", ignoreCase = true)
        }
        navPath?.let(read)?.let { navDoc ->
            tocNavRegex.find(navDoc)?.groupValues?.get(1)?.let { toc ->
                for (anchor in anchorRegex.findAll(toc)) {
                    val rawHref = anchor.groupValues[1]
                    val pathPart = rawHref.substringBefore('#')
                    val fragment = rawHref.substringAfter('#', "").takeIf { it.isNotEmpty() }
                    val label = cleanHtmlText(anchor.groupValues[2])
                    if (pathPart.isNotBlank() && fragment != null && label.isNotEmpty()) {
                        val resolvedPath = resolveRelativePath(navPath, decodePercent(pathPart))
                        anchors.putIfAbsent("$resolvedPath#$fragment", label)
                        anchors.putIfAbsent(fragment, label)
                    }
                }
            }
        }
        if (anchors.isNotEmpty()) return anchors

        val ncxPath = packageDoc.ncxPath ?: return anchors
        read(ncxPath)?.let { ncx ->
            for (point in ncxPointRegex.findAll(ncx)) {
                val label = cleanHtmlText(point.groupValues[1])
                val rawHref = point.groupValues[2]
                val pathPart = rawHref.substringBefore('#')
                val fragment = rawHref.substringAfter('#', "").takeIf { it.isNotEmpty() }
                if (pathPart.isNotBlank() && fragment != null && label.isNotEmpty()) {
                    val resolvedPath = resolveRelativePath(ncxPath, decodePercent(pathPart))
                    anchors.putIfAbsent("$resolvedPath#$fragment", label)
                    anchors.putIfAbsent(fragment, label)
                }
            }
        }
        return anchors
    }

    private fun parseAttributes(raw: String): Map<String, String> =
        attrRegex.findAll(raw).associate { it.groupValues[1] to it.groupValues[2] }

    /** `%20`-style escapes in OPF and nav hrefs; a literal `+` stays a plus. */
    internal fun decodePercent(href: String): String =
        percentEscapeRegex.replace(href) { match ->
            val bytes = match.value.split('%').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
            String(bytes, Charsets.UTF_8)
        }

    /**
     * Extracts a map of `id -> cleaned plain text` from an XHTML/HTML string.
     * Recursively traverses any nested elements (such as Storyteller inline span segments,
     * ruby text, headers, and paragraphs) so any element with an `id` or `xml:id` is captured.
     */
    internal fun parseIdFragments(xhtml: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val tagRegex = Regex("""<(/?)(\w[\w:.-]*)([^>]*?)>""", RegexOption.DOT_MATCHES_ALL)
        val attrRegex = Regex("""([a-zA-Z0-9_:-]+)\s*=\s*["']([^"']*)["']""")

        class OpenElement(
            val tagName: String,
            val id: String?,
            val textBuilder: StringBuilder = StringBuilder(),
        )

        val stack = mutableListOf<OpenElement>()
        var lastIndex = 0

        for (match in tagRegex.findAll(xhtml)) {
            val textBetween = xhtml.substring(lastIndex, match.range.first)
            if (textBetween.isNotEmpty() && stack.isNotEmpty()) {
                for (elem in stack) {
                    elem.textBuilder.append(textBetween)
                }
            }
            lastIndex = match.range.last + 1

            val isClosing = match.groupValues[1] == "/"
            val tagName = match.groupValues[2].lowercase()
            val rawAttrs = match.groupValues[3]
            val isSelfClosing = match.value.endsWith("/>") || isVoidTag(tagName)

            if (isClosing) {
                val targetIndex = stack.indexOfLast { it.tagName == tagName }
                if (targetIndex >= 0) {
                    while (stack.size > targetIndex) {
                        val popped = stack.removeAt(stack.size - 1)
                        if (!popped.id.isNullOrBlank()) {
                            val cleaned = cleanHtmlText(popped.textBuilder.toString())
                            if (cleaned.isNotEmpty()) {
                                result.putIfAbsent(popped.id, cleaned)
                            }
                        }
                    }
                }
            } else if (isSelfClosing) {
                val id = attrRegex.findAll(rawAttrs)
                    .firstOrNull { it.groupValues[1].equals("id", ignoreCase = true) || it.groupValues[1].equals("xml:id", ignoreCase = true) }
                    ?.groupValues?.get(2)?.trim()
                if (!id.isNullOrBlank()) {
                    result.putIfAbsent(id, "")
                }
            } else {
                val id = attrRegex.findAll(rawAttrs)
                    .firstOrNull { it.groupValues[1].equals("id", ignoreCase = true) || it.groupValues[1].equals("xml:id", ignoreCase = true) }
                    ?.groupValues?.get(2)?.trim()
                stack.add(OpenElement(tagName = tagName, id = id))
            }
        }

        while (stack.isNotEmpty()) {
            val popped = stack.removeAt(stack.size - 1)
            if (!popped.id.isNullOrBlank()) {
                val cleaned = cleanHtmlText(popped.textBuilder.toString())
                if (cleaned.isNotEmpty()) {
                    result.putIfAbsent(popped.id, cleaned)
                }
            }
        }

        return result
    }

    private fun isVoidTag(tagName: String): Boolean =
        tagName in setOf("br", "hr", "img", "input", "meta", "link", "col", "base", "area", "wbr")

    internal fun extractReadableBlocks(xhtml: String): List<ParsedHtmlBlock> {
        val list = mutableListOf<ParsedHtmlBlock>()
        for (match in blockParagraphRegex.findAll(xhtml)) {
            val tag = match.groupValues[1].lowercase()
            val attrs = match.groupValues[2]
            val id = idAttrRegex.find(attrs)?.groupValues?.get(1)?.trim()
            val cleaned = cleanHtmlText(match.groupValues[3])
            if (cleaned.isNotEmpty()) {
                list += ParsedHtmlBlock(tag, id, cleaned)
            }
        }
        return list
    }

    internal fun cleanHtmlText(rawHtml: String): String {
        val withoutTags = htmlTagRegex.replace(rawHtml, " ")
        val decoded = decodeHtmlEntities(withoutTags)
        return whitespaceRegex.replace(decoded, " ").trim()
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#8217;", "’")
            .replace("&#8216;", "‘")
            .replace("&#8220;", "“")
            .replace("&#8221;", "”")
            .replace("&#8212;", "—")
            .replace("&#8211;", "–")
    }

    internal fun resolveRelativePath(baseEntryPath: String, relativeHref: String): String {
        val decoded = decodePercent(relativeHref)
        val cleanHref = decoded.trim().replace('\\', '/')
        if (cleanHref.isEmpty()) return ""
        if (cleanHref.startsWith("/")) return normalizeEntryPath(cleanHref)

        val baseDir = baseEntryPath.substringBeforeLast('/', missingDelimiterValue = "")
        val combined = if (baseDir.isEmpty()) cleanHref else "$baseDir/$cleanHref"
        return normalizeEntryPath(combined)
    }

    internal fun normalizeEntryPath(path: String): String {
        val segments = path.trim().replace('\\', '/').split('/')
        val stack = ArrayDeque<String>()
        for (segment in segments) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return stack.joinToString("/")
    }

    private fun extractAudioDuration(file: File): Long? {
        return runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val timeString = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            timeString?.toLongOrNull()
        }.getOrNull()
    }

    private fun resolveAudioZipEntry(entryKeys: Set<String>, src: String): String? {
        if (src in entryKeys) return src
        val cleanSrc = src.trimStart('.', '/').replace('\\', '/')
        if (cleanSrc.isNotBlank()) {
            val pathMatch = entryKeys.firstOrNull {
                it.equals(cleanSrc, ignoreCase = true) || it.endsWith("/$cleanSrc", ignoreCase = true)
            }
            if (pathMatch != null) return pathMatch
        }
        val candidate = entryKeys.firstOrNull {
            it.endsWith("/${src.substringAfterLast('/')}", ignoreCase = true) ||
                it.equals(src.substringAfterLast('/'), ignoreCase = true)
        }
        if (candidate != null) return candidate
        return entryKeys.firstOrNull {
            it.endsWith(".mp4", ignoreCase = true) ||
                it.endsWith(".m4a", ignoreCase = true) ||
                it.endsWith(".m4b", ignoreCase = true) ||
                it.endsWith(".mp3", ignoreCase = true) ||
                it.endsWith(".ogg", ignoreCase = true)
        }
    }
}
