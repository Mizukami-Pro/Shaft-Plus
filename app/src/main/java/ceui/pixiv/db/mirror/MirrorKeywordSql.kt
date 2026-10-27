package ceui.pixiv.db.mirror

import java.util.Locale

/**
 * 「作品内搜索」的共用 SQL 片段：把用户输入的关键词翻译成逐词 AND 的 LIKE 条件。
 *
 * 单书架（[BookmarkMirrorQuery]）与跨书架（[AllBookmarkMirrorQuery]）两个查询出口
 * 必须给出**完全相同**的检索语义 —— 用户在「我的插画收藏」里搜「东方 灵梦」得到的结果，
 * 不该在「全收藏分类」页里换成另一套规则（否则同一份数据换个入口就换个答案，用户只会
 * 觉得搜索坏了）。所以这段逻辑抽出来共用，而不是两边各写一份、日后各自漂移。
 *
 * 安全性：条件片段全部是代码里的常量，用户输入只以 `?` 绑定值出现。
 */
internal object MirrorKeywordSql {

    /** LIKE 的转义符。标题/标签里真出现 `%` `_` 的时候不该当通配符。 */
    private const val LIKE_ESCAPE = '\\'

    /** 关键词分词的上限：多到这个数已经不是检索而是误输入，再多只是白白拖慢 LIKE。 */
    private const val MAX_KEYWORD_TERMS = 8

    private val WHITESPACE = Regex("\\s+")

    /**
     * 把 [keyword] 按空白分词，逐词 AND 追加条件到 [clauses]，绑定值按出现顺序追加到 [args]。
     *
     * 分词而不是整串匹配，是因为用户脑子里的检索是「东方 + 灵梦」而不是一个连续子串；
     * 而逐词 AND 而不是 OR，是因为多打一个词的意图永远是「再缩小一点」。
     *
     * 纯数字词同时按作品 id / 作者 uid 命中（用户往往就是把一串数字贴进来），
     * 文本匹配保留：标题/标签里本来就带数字的作品照样命中。
     */
    fun append(keyword: String, clauses: MutableList<String>, args: MutableList<Any?>) {
        tokenize(keyword).forEach { term ->
            val asId = term.toLongOrNull()?.takeIf { it > 0L }
            if (asId != null) {
                clauses += "(searchText LIKE ? ESCAPE '$LIKE_ESCAPE' OR targetId = ? OR authorId = ?)"
                args += "%${escapeLike(term)}%"
                args += asId
                args += asId
            } else {
                clauses += "searchText LIKE ? ESCAPE '$LIKE_ESCAPE'"
                args += "%${escapeLike(term)}%"
            }
        }
    }

    /**
     * 分词结果：去首尾空白、小写归一（[BookmarkMirrorEntity.searchText] 入库时就是这个口径）、
     * 去重、截断到 [MAX_KEYWORD_TERMS]。
     */
    fun tokenize(keyword: String): List<String> = keyword.trim().lowercase(Locale.ROOT)
        .split(WHITESPACE)
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_KEYWORD_TERMS)

    /** 转义 LIKE 的通配符（`%` / `_` / 转义符自身）。 */
    fun escapeLike(raw: String): String = buildString(raw.length + 4) {
        raw.forEach { ch ->
            if (ch == '%' || ch == '_' || ch == LIKE_ESCAPE) append(LIKE_ESCAPE)
            append(ch)
        }
    }
}
