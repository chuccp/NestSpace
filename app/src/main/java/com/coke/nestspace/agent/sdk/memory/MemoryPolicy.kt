package com.coke.nestspace.agent.sdk.memory

object MemoryPolicy {
    const val MAX_CONTENT = 500
    const val MAX_SOURCE = 300
    const val MAX_CONTEXT_CHARS = 4_000
    private val WHITESPACE = Regex("\\s+")
    private val SENSITIVE = listOf(
        Regex(
            "(?:支付密码|密码|口令|验证码|校验码|动态码|安全码|安全问题|" +
                "(?<![A-Za-z])(?:password|passcode|passwd|cvv|cvc|verification code|" +
                "security code|one-time code)(?![A-Za-z]))" +
                "[\\s:：=,，是为叫]{0,4}(?:(?:is|was|are)\\s+)?[A-Za-z0-9]{4,}",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "(?<![A-Za-z0-9])[A-Za-z0-9]{4,}\\s*(?:就是|是|为)" +
                "(?:我的|我|用户的)?\\s*(?:支付密码|密码|口令|验证码|校验码|动态码|安全码|安全问题|" +
                "(?<![A-Za-z])(?:password|passcode|passwd|cvv|cvc)(?![A-Za-z]))",
            RegexOption.IGNORE_CASE
        ),
        Regex("(?i)sk-[a-z0-9]{12,}"),
        Regex(
            "(?i)(?:api[_ -]?key|secret[_ -]?key|access[_ -]?token)\\s*[:：=]\\s*" +
                "[A-Za-z0-9_\\-]{8,}"
        ),
        Regex("(?<![0-9])[1-9][0-9]{16}[0-9Xx](?![0-9A-Za-z])"),
        Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])")
    )

    fun normalize(text: String): String = WHITESPACE.replace(text.trim(), " ")

    fun sensitiveReason(text: String): String? =
        if (SENSITIVE.any { it.containsMatchIn(text) }) "sensitive" else null

    fun quoteSupported(quote: String, utterances: List<String>): Boolean {
        val needle = normalize(quote)
            .trim('"', '\'', '“', '”', '‘', '’')
            .lowercase()
        if (needle.isBlank()) return false
        return utterances.any { normalize(it).lowercase().contains(needle) }
    }

    fun normalizeKeywords(raw: List<String>, content: String): List<String> {
        val values = raw
            .flatMap { it.split(',', '，', ';', '；', ' ') }
            .map { normalize(it).lowercase() }
            .filter { it.length in 2..40 }
            .distinct()
            .take(10)
        if (values.isNotEmpty()) return values
        return Regex("[\\p{IsHan}]{2,6}|[A-Za-z0-9_-]{2,24}")
            .findAll(content)
            .map { it.value.lowercase() }
            .distinct()
            .take(6)
            .toList()
    }
}

internal object MemoryRanking {
    private val TOKEN = Regex("[\\p{IsHan}]+|[A-Za-z0-9]+")

    private val STOP = setOf(
        "的", "了", "是", "我", "你", "他", "她", "它", "在", "有", "和", "与",
        "就", "都", "也", "不", "吗", "呢", "吧", "啊", "要", "想", "请",
        "帮我", "给我", "一下", "我的", "这个", "那个", "可以", "一个", "用户",
        "今天", "这次", "现在", "什么", "怎么", "我们", "一些", "看看"
    )

    fun search(items: List<MemoryItem>, query: String, limit: Int): List<MemoryItem> {
        val terms = queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        return items.asSequence()
            .map { it to score(it, terms) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryItem, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .map { it.first }
            .distinctBy { it.id }
            .take(limit.coerceIn(1, 20))
            .toList()
    }

    fun select(items: List<MemoryItem>, query: String, limit: Int): List<MemoryItem> {
        val cap = limit.coerceIn(1, 20)
        val terms = queryTerms(query)
        val pinned = items.filter { it.pinned }
            .sortedByDescending { it.updatedAt }
            .take(MAX_PINNED)
        val scored = items.asSequence()
            .filterNot { it.pinned }
            .map { it to score(it, terms) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryItem, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .map { it.first }
            .toList()
        val used = (pinned + scored).map { it.id }.toSet()
        val rest = items.filterNot { it.id in used }
            .sortedByDescending { it.updatedAt }
        return (pinned + scored + rest)
            .distinctBy { it.id }
            .take(cap)
    }

    internal fun queryTerms(query: String): List<String> {
        val terms = mutableListOf<String>()
        TOKEN.findAll(query.lowercase()).forEach { match ->
            val value = match.value
            if (value.first().isHan()) {
                if (value.length == 1) {
                    terms += value
                } else {
                    for (i in 0 until value.length - 1) {
                        terms += value.substring(i, i + 2)
                    }
                }
            } else {
                terms += value
            }
        }
        return terms.filter {
            (it.length >= 2 || it.first().isHan()) && it !in STOP
        }.distinct()
    }

    internal fun score(item: MemoryItem, terms: List<String>): Int {
        if (terms.isEmpty()) return 0
        val content = item.content.lowercase()
        val keywords = item.keywords.joinToString(" ").lowercase()
        var score = 0
        for (term in terms) {
            if (content.contains(term)) score += CONTENT_HIT
            if (keywords.contains(term)) score += KEYWORD_HIT
        }
        return score
    }

    private fun Char.isHan(): Boolean =
        Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN

    private const val CONTENT_HIT = 2
    private const val KEYWORD_HIT = 4
    private const val MAX_PINNED = 5
}

object MemoryContextFormatter {
    fun render(
        items: List<MemoryItem>,
        maxChars: Int = MemoryPolicy.MAX_CONTEXT_CHARS
    ): String {
        val builder = StringBuilder()
        for (item in items) {
            val line = "- [${item.kind.api}]${if (item.pinned) " (pinned)" else ""} " +
                item.content
            if (builder.length + line.length + 1 > maxChars) break
            builder.appendLine(line)
        }
        return builder.toString().trim()
    }
}
