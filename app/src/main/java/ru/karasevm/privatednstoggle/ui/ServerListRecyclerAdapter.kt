package ru.karasevm.privatednstoggle.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.color.MaterialColors
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.dns.ProbeResult
import ru.karasevm.privatednstoggle.dns.ServerStatus
import ru.karasevm.privatednstoggle.dns.ServerStatusTracker
import ru.karasevm.privatednstoggle.model.DnsServer


/**
 * The server list, in the main screen (with drag handles and status icons) and in the tile's
 * server picker.
 */
class ServerListRecyclerAdapter(
    private val showDragHandle: Boolean,
    private val showStatus: Boolean = false,
) :
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

    /** Called with a server when its status icon is tapped */
    var onStatusClick: ((DnsServer) -> Unit)? = null

    private var statuses: Map<String, ServerStatus> = emptyMap()

    /** Shows [statuses] (see [ServerStatusTracker.statuses]) next to the servers */
    fun setStatuses(statuses: Map<String, ServerStatus>) {
        if (!showStatus || statuses == this.statuses) return
        this.statuses = statuses
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, PAYLOAD_STATUS)
    }

    /**
     * Called to move a server by one position without dragging, for accessibility services
     * (TalkBack, switch access). Gets the old and new position and the server id.
     */
    var onMove: ((fromPosition: Int, toPosition: Int, id: Int) -> Unit)? = null
    private var items: MutableList<DnsServer> = mutableListOf()

    override fun onBindViewHolder(holder: DnsServerViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_MOVE_ACTIONS || it == PAYLOAD_STATUS }) {
            // Only the position (see refreshMoveActions) or the status changed
            if (PAYLOAD_MOVE_ACTIONS in payloads && showDragHandle) updateMoveActions(holder, position)
            if (PAYLOAD_STATUS in payloads) bindStatus(holder, items[position])
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
        bindStatus(holder, item)
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
        val statusIcon: ImageView = itemView.findViewById(R.id.statusIcon)
        var id = 0
        val moveActionIds = mutableListOf<Int>()

        init {
            view.setOnClickListener {
                onItemClick?.invoke(id)
            }
        }
    }

    /** Shows the status icon of [item], with its description for accessibility services */
    private fun bindStatus(holder: DnsServerViewHolder, item: DnsServer) {
        val icon = holder.statusIcon
        if (!showStatus) {
            icon.visibility = View.GONE
            return
        }
        val status = statuses[ServerStatusTracker.key(item.server)]
        val (drawable, color) = when {
            status is ServerStatus.Tested && status.result is ProbeResult.Working ->
                R.drawable.ic_status_working_24 to ContextCompat.getColor(icon.context, R.color.status_working)

            status is ServerStatus.Tested ->
                R.drawable.ic_status_failed_24 to
                    MaterialColors.getColor(icon, androidx.appcompat.R.attr.colorError)

            // Testing, or not tested yet
            else -> R.drawable.ic_status_testing_24 to ContextCompat.getColor(icon.context, R.color.status_testing)
        }
        icon.visibility = View.VISIBLE
        icon.setImageResource(drawable)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
        icon.contentDescription = describeStatus(icon.context, status)
        icon.setOnClickListener { onStatusClick?.invoke(item) }
    }

    private companion object {
        const val PAYLOAD_MOVE_ACTIONS = "move_actions"
        const val PAYLOAD_STATUS = "status"
    }
}
