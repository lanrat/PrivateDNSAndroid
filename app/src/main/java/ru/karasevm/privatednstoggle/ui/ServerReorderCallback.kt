package ru.karasevm.privatednstoggle.ui

import android.graphics.Color
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Drag and drop reordering of the server list. Rows move on screen while dragging; [onMoved]
 * gets the start and end position and the server id once the row is dropped. Positions match
 * the servers' sortOrder.
 */
class ServerReorderCallback(
    private val adapter: ServerListRecyclerAdapter,
    private val onMoved: (fromPosition: Int, toPosition: Int, id: Int) -> Unit,
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

    private var dragFrom = NO_POSITION
    private var dragTo = NO_POSITION

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        if (dragFrom == NO_POSITION) dragFrom = from
        dragTo = to
        adapter.onItemMove(from, to)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            viewHolder?.itemView?.apply {
                elevation = 8f
                alpha = 0.5f
                setBackgroundColor(Color.GRAY)
            }
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.apply {
            elevation = 0f
            alpha = 1.0f
            setBackgroundColor(Color.TRANSPARENT)
        }
        if (dragFrom != NO_POSITION && dragTo != NO_POSITION && dragFrom != dragTo) {
            onMoved(dragFrom, dragTo, (viewHolder as ServerListRecyclerAdapter.DnsServerViewHolder).id)
        }
        dragFrom = NO_POSITION
        dragTo = NO_POSITION
        // Positions changed without a rebind
        adapter.refreshMoveActions()
    }

    private companion object {
        const val NO_POSITION = -1
    }
}
