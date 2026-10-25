package one.njk.sao.extension.googlephotos

import android.content.Context
import one.njk.sao.source.*

internal object GooglePhotosCategories {
    fun values(context: Context): List<SourceCategory> = listOf(
            R.string.category_memes to "memes", R.string.category_screenshots to "screenshots", R.string.category_cats to "cats", R.string.category_dogs to "dogs",
            R.string.category_documents to "documents", R.string.category_food to "food").map { (label, text) ->
            SourceCategory(text, context.getString(label), MediaQuery(text))
        }
}
