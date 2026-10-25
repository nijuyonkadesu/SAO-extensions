package one.njk.sao.extension.findthatmeme

import android.content.Context
import one.njk.sao.source.*

internal object FindThatMemeCategories {
    fun values(context: Context): List<SourceCategory> = listOf(R.string.category_this_is_fine to "this is fine", R.string.category_no_idea to "i have no idea what i am doing",
            R.string.category_distracted_boyfriend to "distracted boyfriend", R.string.category_please_stop to "please stop",
            R.string.category_trolley_problem to "trolley problem", R.string.category_cats to "cat").mapIndexed { index, (label, text) ->
            SourceCategory("meme-$index", context.getString(label), MediaQuery(text, mode = SearchMode.DESCRIPTION))
        }
}
