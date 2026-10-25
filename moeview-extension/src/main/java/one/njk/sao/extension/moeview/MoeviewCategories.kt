package one.njk.sao.extension.moeview

import one.njk.sao.moeview.data.Board
import one.njk.sao.source.*

/** Reviewed vocabulary, not waifu.pics endpoint names. See docs/moeview-category-tags.md. */
internal object MoeviewCategories {
    private val shared=listOf(
        R.string.category_school_uniform to "school_uniform", R.string.category_blush to "blush", R.string.category_ponytail to "ponytail",
        R.string.category_waifu to "1girl", R.string.category_neko to "cat_ears", R.string.category_shinobu to "oshino_shinobu", R.string.category_megumin to "megumin",
        R.string.category_bully to "bullying", R.string.category_cuddle to "cuddling", R.string.category_cry to "crying", R.string.category_hug to "hug",
        R.string.category_kiss to "kiss", R.string.category_lick to "licking", R.string.category_pat to "headpat", R.string.category_smug to "smug",
        R.string.category_yeet to "throwing", R.string.category_smile to "smile", R.string.category_wave to "waving", R.string.category_handhold to "holding_hands",
        R.string.category_nom to "eating", R.string.category_bite to "biting", R.string.category_glomp to "glomp", R.string.category_slap to "slapping",
        R.string.category_kick to "kicking", R.string.category_happy to "happy", R.string.category_wink to "one_eye_closed", R.string.category_poke to "poking",
        R.string.category_dance to "dancing", R.string.category_wince to "wince")
    private val boards=mapOf(
        // Yande.re removes expression/action tags. Some familiar names instead identify
        // artists/circles/characters: e.g. ponytail, hug, happy. Never substitute those.
        Board.YANDERE to listOf(R.string.category_school_uniform to "seifuku", R.string.category_neko to "nekomimi",
            R.string.category_shinobu to "oshino_shinobu", R.string.category_megumin to "megumin"),
        Board.KONACHAN to listOf(R.string.category_school_uniform to "school_uniform", R.string.category_blush to "blush", R.string.category_ponytail to "ponytail",
            R.string.category_neko to "catgirl", R.string.category_shinobu to "oshino_shinobu", R.string.category_megumin to "megumin",
            R.string.category_cry to "crying", R.string.category_hug to "hug", R.string.category_kiss to "kiss", R.string.category_cat_smile to "cat_smile",
            R.string.category_kick to "kick", R.string.category_wink to "wink"),
        Board.GELBOORU to shared+listOf(R.string.category_howling to "howling", R.string.category_bonk to "bonk", R.string.category_high_five to "high_five", R.string.category_kill to "murder"),
        // Danbooru uses the same modern general tags for these concepts. The published
        // vocabulary confirms these choices; live API verification is Cloudflare-blocked.
        Board.DANBOORU to shared+listOf(R.string.category_head_bump to "head_bump", R.string.category_death to "death"))

    fun forBoard(board: Board, context: android.content.Context): List<SourceCategory> = (listOf(R.string.category_all to "")+boards.getValue(board)).map { (label,tag) ->
        SourceCategory(tag.ifEmpty { "all" },context.getString(label),MediaQuery(text=tag,mode=SearchMode.DESCRIPTION))
    }
}
