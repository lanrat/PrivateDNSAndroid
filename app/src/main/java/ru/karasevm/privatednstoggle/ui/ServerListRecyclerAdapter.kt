package ru.karasevm.privatednstoggle.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.model.DnsServer


class ServerListRecyclerAdapter(private val showDragHandle: Boolean) :
    RecyclerView.Adapter<ServerListRecyclerAdapter.DnsServerViewHolder>() {


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DnsServerViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_row, parent, false)
        val vh = DnsServerViewHolder(view)
        return vh
    }

    override fun getItemCount(): Int {
        return items.size
    }

    var onItemClick: ((Int) -> Unit)? = null
    var onDragStart: ((DnsServerViewHolder) -> Unit)? = null

    /**
     * Called to move a server by one position without dragging, for accessibility services
     * (TalkBack, switch access). Gets the old and new position and the server id.
     */
    var onMove: ((fromPosition: Int, toPosition: Int, id: Int) -> Unit)? = null
    private var items: MutableList<DnsServer> = mutableListOf()

    override fun onBindViewHolder(holder: DnsServerViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_MOVE_ACTIONS }) {
            // Only the position changed, see refreshMoveActions
            if (showDragHandle) updateMoveActions(holder, position)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    /**
     * Updates the rows' "move up" / "move down" accessibility actions, which depend on their
     * positions, after rows were moved, added or removed. Rows are not rebound otherwise.
     */
    fun refreshMoveActions() {
        if (showDragHandle && items.isNotEmpty()) {
            notifyItemRangeChanged(0, items.size, PAYLOAD_MOVE_ACTIONS)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: DnsServerViewHolder, position: Int) {
        val item = items[position]
        if (item.label.isNotEmpty()) {
            holder.labelTextView.text = item.label
            holder.labelTextView.visibility = View.VISIBLE
        } else {
            holder.labelTextView.visibility = View.GONE
        }
        holder.serverTextView.text = item.server
        holder.id = item.id
        if (item.enabled) {
            holder.labelTextView.alpha = 1f
            holder.serverTextView.alpha = 1f
        } else {
            holder.labelTextView.alpha = 0.5f
            holder.serverTextView.alpha = 0.5f
        }
        if (showDragHandle) {
            holder.dragHandle.visibility = View.VISIBLE
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onDragStart?.invoke(holder)
                }
                return@setOnTouchListener true
            }
            updateMoveActions(holder, position)
        }
    }

    /**
     * Replaces the row's "move up" / "move down" accessibility actions to match [position]
     */
    private fun updateMoveActions(holder: DnsServerViewHolder, position: Int) {
        val view = holder.itemView
        holder.moveActionIds.forEach { ViewCompat.removeAccessibilityAction(view, it) }
        holder.moveActionIds.clear()
        val context = view.context
        if (position > 0) {
            holder.moveActionIds += ViewCompat.addAccessibilityAction(
                view, context.getString(R.string.a11y_move_up)
            ) { _, _ -> moveBy(holder, -1) }
        }
        if (position < items.size - 1) {
            holder.moveActionIds += ViewCompat.addAccessibilityAction(
                view, context.getString(R.string.a11y_move_down)
            ) { _, _ -> moveBy(holder, 1) }
        }
    }

    private fun moveBy(holder: DnsServerViewHolder, offset: Int): Boolean {
        val from = holder.bindingAdapterPosition
        val to = from + offset
        if (from == RecyclerView.NO_POSITION || to !in items.indices) return false
        onMove?.invoke(from, to, holder.id)
        return true
    }

    /** Gets the server with [id] as currently shown, or null */
    fun getItem(id: Int): DnsServer? = items.firstOrNull { it.id == id }

    /**
     *  Update server position in memory
     *  @param fromPosition old position
     *  @param toPosition new position
     */
    fun onItemMove(fromPosition: Int, toPosition: Int) {
        items.add(toPosition, items.removeAt(fromPosition))
        notifyItemMoved(fromPosition, toPosition)
    }

    class DiffCallback(
        private val oldList: List<DnsServer>, private var newList: List<DnsServer>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int {
            return oldList.size
        }

        override fun getNewListSize(): Int {
            return newList.size
        }

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return oldItem.server == newItem.server && oldItem.label == newItem.label && oldItem.enabled == newItem.enabled
        }
    }

    /**
     *  Submit list to adapter
     *  @param list list to submit
     */
    fun submitList(list: List<DnsServer>) {
        val diffCallback = DiffCallback(items, list)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items.clear()
        items.addAll(list)
        diffResult.dispatchUpdatesTo(this)
        refreshMoveActions()
    }

    inner class DnsServerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val labelTextView: TextView = view.findViewById(R.id.labelTextView)
        val serverTextView: TextView = view.findViewById(R.id.textView)
        val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
        var id = 0
        val moveActionIds = mutableListOf<Int>()

        init {
            view.setOnClickListener {
                onItemClick?.invoke(id)
            }
        }
    }

    private companion object {
        const val PAYLOAD_MOVE_ACTIONS = "move_actions"
    }
}
