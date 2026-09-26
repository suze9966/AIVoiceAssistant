package com.suze.aivoice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val items: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    companion object {
        private const val TYPE_ME = 1
        private const val TYPE_AI = 2

        /** emoji 表情气泡的大字号 / 普通字号 */
        private const val EMOJI_SIZE_SP = 40f
        private const val TEXT_SIZE_SP = 16f
    }

    /** 长按某条消息的回调：position 为条目下标 */
    var onItemLongClick: ((Int) -> Unit)? = null

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvMsg: TextView = view.findViewById(R.id.tvMsg)
        val ivSticker: ImageView = view.findViewById(R.id.ivSticker)
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].isMe) TYPE_ME else TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_ME) R.layout.item_chat_me else R.layout.item_chat_ai
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        when (msg.type) {
            ChatMessage.TYPE_IMAGE -> {
                // 图片表情包：显示图片，隐藏文本
                holder.ivSticker.visibility = View.VISIBLE
                holder.tvMsg.visibility = View.GONE
                ImageLoader.load(msg.content, holder.ivSticker)
            }
            else -> {
                holder.ivSticker.visibility = View.GONE
                holder.tvMsg.visibility = View.VISIBLE
                holder.tvMsg.text = msg.content
                // 纯 emoji 用超大字号，形成表情包效果
                val pureEmoji = msg.type == ChatMessage.TYPE_EMOJI ||
                        ChatMessage.isPureEmoji(msg.content)
                holder.tvMsg.textSize = if (pureEmoji) EMOJI_SIZE_SP else TEXT_SIZE_SP
            }
        }
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

    /** 更新最后一条（打字机增量更新时保持原有类型） */
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