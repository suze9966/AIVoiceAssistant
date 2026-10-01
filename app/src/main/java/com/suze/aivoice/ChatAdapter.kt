package com.suze.aivoice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
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
    /** 角色聊天可覆盖 AI 头像；为空时沿用小沫全局头像。 */
    var bindAiAvatar: ((ImageView) -> Unit)? = null
    /** 群聊按发言人切换头像。 */
    var bindMessageAvatar: ((ImageView, ChatMessage) -> Unit)? = null
    /** 气泡样式是否用主人自定义（口令改过的）。默认走运行时自定义。 */
    var useCustomBubbleStyle: Boolean = true

    /** 角色专属样式提供器：返回非空时优先用该样式（角色聊天里按角色区分）。 */
    var styleProvider: (() -> BubbleStyleStore.Style?)? = null

    /** 取当前应生效的样式：角色专属优先，否则全局；未启用自定义时为 null。 */
    private fun currentStyle(ctx: android.content.Context): BubbleStyleStore.Style? {
        if (!useCustomBubbleStyle) return null
        styleProvider?.invoke()?.let { return it }
        return BubbleStyleStore.load(ctx)
    }

    /** 一次性出现动效：仅对这些下标播一次（避免每次 bind 都重复播）。 */
    private val animOnce = mutableSetOf<Int>()

    /** 标记某条为「新插入」，下次绑定时播一次动效。 */
    private fun markAnim(position: Int) {
        animOnce.add(position)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvMsg: TextView = view.findViewById(R.id.tvMsg)
        val tvSpeaker: TextView? = view.findViewById(R.id.tvSpeaker)
        val ivSticker: ImageView = view.findViewById(R.id.ivSticker)
        val ivAvatar: ImageView? = view.findViewById(R.id.ivAvatar)
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
        holder.ivAvatar?.let { view ->
            bindMessageAvatar?.invoke(view, msg)
                ?: bindAiAvatar?.invoke(view)
                ?: ChatStyleStore.applyAvatar(view)
        }
        val speaker = msg.speakerName.trim()
        holder.tvSpeaker?.let { label ->
            if (!msg.isMe && speaker.isNotEmpty()) {
                label.visibility = View.VISIBLE
                label.text = speaker
            } else {
                label.visibility = View.GONE
            }
        }
        when (msg.type) {
            ChatMessage.TYPE_IMAGE -> {
                holder.ivSticker.visibility = View.VISIBLE
                holder.tvMsg.visibility = View.GONE
                ImageLoader.load(msg.content, holder.ivSticker)
            }
            else -> {
                holder.ivSticker.visibility = View.GONE
                holder.tvMsg.visibility = View.VISIBLE
                val style = if (useCustomBubbleStyle) currentStyle(holder.itemView.context) else null
                val raw = msg.content
                holder.tvMsg.text = if (style?.trimQuotes == true) trimWrappingQuotes(raw) else raw
                val pureEmoji = msg.type == ChatMessage.TYPE_EMOJI ||
                        ChatMessage.isPureEmoji(msg.content)
                holder.tvMsg.textSize = if (pureEmoji) EMOJI_SIZE_SP else TEXT_SIZE_SP
            }
        }
        // 气泡形状与颜色：主人用口令改过就按自定义画，否则保留原 drawable
        if (useCustomBubbleStyle) {
            val ctx = holder.itemView.context
            val style = currentStyle(ctx)
            if (style == null || style.isDefault) {
                // 默认样式：清掉可能残留的自定义背景，交回布局原 drawable
                holder.tvMsg.background = ContextCompat.getDrawable(
                    ctx,
                    if (msg.isMe) R.drawable.bubble_me_bg else R.drawable.bubble_ai_bg
                )
            } else {
                holder.tvMsg.background = BubbleStyleStore.drawableFor(ctx, msg.isMe, style)
                holder.tvMsg.setTextColor(BubbleStyleStore.textColorFor(msg.isMe, style))
                // 阴影：用 elevation 做浮起感
                holder.tvMsg.elevation = if (style.shadow) {
                    ctx.resources.displayMetrics.density * 4f
                } else 0f
                // 字号 / 宽度 / 内边距
                val pureEmoji = msg.type == ChatMessage.TYPE_EMOJI ||
                        ChatMessage.isPureEmoji(msg.content)
                if (!pureEmoji) holder.tvMsg.textSize = style.fontSp
                holder.tvMsg.maxWidth = (ctx.resources.displayMetrics.widthPixels *
                        style.maxWidthPercent / 100).coerceAtLeast(200)
                holder.tvMsg.setPadding(
                    BubbleStyleStore.padHpx(ctx, style),
                    BubbleStyleStore.padVpx(ctx, style),
                    BubbleStyleStore.padHpx(ctx, style),
                    BubbleStyleStore.padVpx(ctx, style)
                )
                // 头像大小
                holder.ivAvatar?.let { av ->
                    val size = BubbleStyleStore.avatarPx(ctx, style)
                    val lp = av.layoutParams
                    lp.width = size
                    lp.height = size
                    av.layoutParams = lp
                }
            }
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemLongClick?.invoke(pos)
            true
        }
        // 出现动效：只对新插入的条目播一次
        if (animOnce.remove(position)) playEnterAnim(holder.itemView, position)
    }

    /** 按当前样式播放一次气泡出现动效。 */
    private fun playEnterAnim(view: View, position: Int) {
        val ctx = view.context
        val anim = if (useCustomBubbleStyle) currentStyle(ctx)?.anim ?: BubbleStyleStore.ANIM_NONE else BubbleStyleStore.ANIM_NONE
        if (anim == BubbleStyleStore.ANIM_NONE) return
        val density = ctx.resources.displayMetrics.density
        when (anim) {
            BubbleStyleStore.ANIM_FADE -> {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(220).start()
            }
            BubbleStyleStore.ANIM_POP -> {
                view.alpha = 0f
                view.scaleX = 0.85f
                view.scaleY = 0.85f
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
            }
            BubbleStyleStore.ANIM_SLIDE -> {
                view.alpha = 0f
                val from = if (items.getOrNull(position)?.isMe == true) 64f * density else -64f * density
                view.translationX = from
                view.animate().alpha(1f).translationX(0f).setDuration(240).start()
            }
        }
    }

    /** 去掉文本首尾成对的引号（中英文双引号 / 单引号）。 */
    private fun trimWrappingQuotes(text: String): String {
        if (text.length < 2) return text
        val pairs = listOf(
            '\u201C' to '\u201D',   // “ ”
            '\u300C' to '\u300D',   // 「 」
            '\u2018' to '\u2019',   // ‘ ’
            '"' to '"',
            '\'' to '\''
        )
        for ((open, close) in pairs) {
            if (text.first() == open && text.last() == close) {
                return text.substring(1, text.length - 1).trim()
            }
        }
        return text
    }

    override fun getItemCount(): Int = items.size

    fun add(msg: ChatMessage) {
        items.add(msg)
        markAnim(items.size - 1)
        notifyItemInserted(items.size - 1)
    }

    /** 更新最后一条（打字机增量更新时保持原有类型） */
    fun updateLast(content: String) {
        if (items.isEmpty()) return
        val last = items.last()
        if (last.content == content) return
        items[items.size - 1] = last.copy(content = content)
        notifyItemChanged(items.size - 1)
    }

    /** 取下标的文案 */
    fun contentAt(position: Int): String =
        if (position in items.indices) items[position].content else ""

    fun messageAt(position: Int): ChatMessage? =
        items.getOrNull(position)

    fun isMeAt(position: Int): Boolean =
        items.getOrNull(position)?.isMe == true

    /** 按下标删除 */
    fun removeAt(position: Int) {
        if (position !in items.indices) return
        items.removeAt(position)
        // 同步收缩 animOnce 里的索引：删除位置之后的整体前移一位，并移除被删位置
        val shifted = mutableSetOf<Int>()
        for (p in animOnce) {
            when {
                p == position -> { /* 被删条目，直接丢弃 */ }
                p > position -> shifted.add(p - 1)
                else -> shifted.add(p)
            }
        }
        animOnce.clear()
        animOnce.addAll(shifted)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, items.size - position)
    }

    fun replaceAll(newItems: List<ChatMessage>) {
        val copy = if (newItems === items) newItems.toList() else newItems
        items.clear()
        items.addAll(copy)
        animOnce.clear()
        notifyDataSetChanged()
    }
}
