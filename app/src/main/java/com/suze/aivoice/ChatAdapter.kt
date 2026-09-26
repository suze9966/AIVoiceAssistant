package com.suze.aivoice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val items: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    companion object {
        private const val TYPE_ME = 1
        private const val TYPE_AI = 2
    }

    /** 长按某条消息的回调：position 为条目下标 */
    var onItemLongClick: ((Int) -> Unit)? = null

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvMsg: TextView = view.findViewById(R.id.tvMsg)
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].isMe) TYPE_ME else TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_ME) R.layout.item_chat_me else R.layout.item_chat_ai
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tvMsg.text = items[position].content
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemLongClick?.invoke(pos)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    fun add(msg: ChatMessage) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
    }

    fun updateLast(content: String) {
        if (items.isEmpty()) return
        val last = items.last()
        items[items.size - 1] = last.copy(content = content)
        notifyItemChanged(items.size - 1)
    }

    /** 取下标的文案 */
    fun contentAt(position: Int): String =
        if (position in items.indices) items[position].content else ""

    /** 按下标删除 */
    fun removeAt(position: Int) {
        if (position !in items.indices) return
        items.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, items.size - position)
    }
}
