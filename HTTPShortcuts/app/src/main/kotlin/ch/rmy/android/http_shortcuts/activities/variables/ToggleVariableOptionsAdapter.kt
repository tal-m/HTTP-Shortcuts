package ch.rmy.android.http_shortcuts.activities.variables

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ch.rmy.android.http_shortcuts.activities.SimpleListAdapter
import ch.rmy.android.http_shortcuts.data.models.Option
import ch.rmy.android.http_shortcuts.databinding.ToggleOptionBinding
import ch.rmy.android.http_shortcuts.utils.UUIDUtils
import ch.rmy.android.http_shortcuts.variables.VariablePlaceholderProvider
import ch.rmy.android.http_shortcuts.variables.Variables

class ToggleVariableOptionsAdapter : SimpleListAdapter<Option, ToggleVariableOptionsAdapter.SelectOptionViewHolder>() {

    lateinit var variablePlaceholderProvider: VariablePlaceholderProvider

    var options: List<Option>
        get() = items
        set(value) {
            items = value
        }
    var clickListener: ((Option) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        SelectOptionViewHolder(ToggleOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemId(item: Option) = UUIDUtils.toLong(item.id)

    override fun onBindViewHolder(holder: ToggleVariableOptionsAdapter.SelectOptionViewHolder, position: Int) {
        holder.updateViews(items[position])
    }

    inner class SelectOptionViewHolder(
        private val binding: ToggleOptionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun updateViews(item: Option) {
            binding.toggleOptionValue.text = Variables.rawPlaceholdersToVariableSpans(item.value, variablePlaceholderProvider, 0)
            itemView.setOnClickListener { clickListener?.invoke(item) }
        }

    }
}