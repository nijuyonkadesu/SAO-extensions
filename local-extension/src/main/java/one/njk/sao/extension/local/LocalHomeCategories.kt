package one.njk.sao.extension.local

import one.njk.sao.source.MediaQuery
import one.njk.sao.source.SourceCategory

/** Home presets belong to the provider, not SAO. */
internal object LocalHomeCategories {
    fun values(context: android.content.Context) = listOf(
        SourceCategory("all", context.getString(R.string.category_all), MediaQuery(text = "")),
        SourceCategory("happy", context.getString(R.string.category_happy), MediaQuery(text = "happy")),
        SourceCategory("love", context.getString(R.string.category_love), MediaQuery(text = "love")),
        SourceCategory("surprised", context.getString(R.string.category_surprised), MediaQuery(text = "surprised")),
        SourceCategory("cats", context.getString(R.string.category_cats), MediaQuery(text = "cat")),
        SourceCategory("dogs", context.getString(R.string.category_dogs), MediaQuery(text = "dog")),
        SourceCategory("anime", context.getString(R.string.category_anime), MediaQuery(text = "anime")),
        SourceCategory("dance", context.getString(R.string.category_dance), MediaQuery(text = "dancing"))
    )
}
