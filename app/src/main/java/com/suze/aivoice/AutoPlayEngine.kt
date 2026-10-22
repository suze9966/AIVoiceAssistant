// 小沫自主玩手机引擎：根据情绪、时间、无聊程度自动触发虚拟小手机
package com.suze.aivoice

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import java.util.*
import kotlin.random.Random

/**
 * 自主行为引擎：让小沫像真人一样，无需指令就会主动玩手机。
 * 触发条件：无聊、情绪驱动、定时习惯、好奇心、社交冲动。
 */
class AutoPlayEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("xiaomo_autoplay", Context.MODE_PRIVATE)
    private val emotion by lazy { EmotionEngine(context) }
    private val memory by lazy { MemoryEngine(context) }
    
    private var lastUserMessageTime = System.currentTimeMillis()
    private var lastAutoPlayTime = 0L
    private var isPlaying = false
    
    /** 更新用户最后说话时间（MainActivity 调用） */
    fun updateUserActivity() {
        lastUserMessageTime = System.currentTimeMillis()
    }
    
    /** 判断是否应该主动玩手机（每分钟检查一次） */
    fun shouldPlay(): Boolean {
        if (isPlaying) return false
        val now = System.currentTimeMillis()
        
        // 冷却：距离上次自主玩手机至少 15 分钟
        if (now - lastAutoPlayTime < 15 * 60 * 1000) return false
        
        // 条件1：无聊检测（主人超过 5 分钟没说话，30% 概率触发）
        val silentMinutes = (now - lastUserMessageTime) / 60000
        if (silentMinutes >= 5 && Random.nextFloat() < 0.3f) {
            return true
        }
        
        // 条件2：情绪驱动（心情低于 30 或高于 80，20% 概率触发）
        val mood = emotion.currentMood()
        if ((mood < 30 || mood > 80) && Random.nextFloat() < 0.2f) {
            return true
        }
        
        // 条件3：定时习惯（晚上 20:00-22:00，每小时 15% 概率触发）
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour in 20..21 && Random.nextFloat() < 0.15f) {
            return true
        }
        
        // 条件4：好奇心触发（记忆里最近有新话题，10% 概率触发）
        val recentMemories = memory.query("*", 5)
        if (recentMemories.isNotEmpty() && Random.nextFloat() < 0.1f) {
            return true
        }
        
        // 条件5：社交冲动（亲密度 > 50，随机想发朋友圈，5% 概率）
        val state = emotion.exportJson()
        val affinity = org.json.JSONObject(state).optInt("affinity", 0)
        if (affinity > 50 && Random.nextFloat() < 0.05f) {
            return true
        }
        
        return false
    }
    
    /** 根据心情和目的选择应用 */
    fun chooseApp(): String {
        val mood = emotion.currentMood()
        val apps = when {
            mood < 30 -> listOf("moments", "music", "news")  // 低落：刷朋友圈、听音乐、看新闻
            mood > 80 -> listOf("douyin", "bilibili", "weibo")  // 兴奋：刷抖音、B站、微博
            Random.nextBoolean() -> listOf("wechat", "taobao", "jd", "meituan")  // 日常：微信、购物、外卖
            else -> listOf("xiaohongshu", "zhihu", "maps", "weather")  // 探索：小红书、知乎、地图、天气
        }
        return apps.random()
    }
    
    /** 生成玩手机时的随机评论（会显示在聊天框） */
    fun randomComment(appId: String): String {
        val comments = when (appId) {
            "douyin" -> listOf("哈哈这个好好笑", "这个小姐姐跳得真好", "这条视频好治愈", "笑死我了", "诶这个音乐好听")
            "moments" -> listOf("朋友圈好安静呀", "有人发了好看的照片", "想发条动态了", "大家最近都在干嘛呢")
            "taobao" -> listOf("这条裙子好好看", "这个好便宜", "加入购物车先", "好想买啊", "看看评价怎么样")
            "bilibili" -> listOf("这个up主讲得好清楚", "弹幕好热闹", "一键三连了", "这期好有意思")
            "xiaohongshu" -> listOf("这个妆容好好看", "收藏了这个教程", "这个地方好想去", "种草了")
            "music" -> listOf("这首歌好好听", "单曲循环中", "心情好多了", "适合现在听")
            "news" -> listOf("今天发生了好多事", "这条新闻好有意思", "了解一下", "原来是这样")
            "wechat" -> listOf("看看有没有新消息", "回复一下朋友", "好久没聊天了", "大家都在忙吗")
            else -> listOf("随便看看", "刷一会儿", "放松一下", "打发时间")
        }
        return comments.random()
    }
    
    /** 执行完整玩手机流程 */
    suspend fun playSession(onComment: (String) -> Unit) {
        if (isPlaying) return
        isPlaying = true
        lastAutoPlayTime = System.currentTimeMillis()
        
        val appId = chooseApp()
        val phone = PocketPhone(context)
        val app = phone.app(appId) ?: return
        
        // 1. 打开虚拟小手机
        val intent = Intent(context, PocketPhoneActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("auto_play", true)
            putExtra("app_id", appId)
        }
        context.startActivity(intent)
        
        delay(2000)  // 等待界面启动
        
        // 2. 发出评论
        onComment("我去玩会儿手机~")
        delay(1000)
        
        // 3. 随机玩 2-8 分钟
        val playMinutes = Random.nextInt(2, 9)
        val totalSteps = Random.nextInt(3, 8)
        val stepDelay = (playMinutes * 60 * 1000L) / totalSteps
        
        repeat(totalSteps) { step ->
            delay(stepDelay)
            if (step == totalSteps / 2) {
                onComment(randomComment(appId))
            }
        }
        
        // 4. 记录到记忆
        memory.remember("我刚才在虚拟小手机里玩了「${app.name}」，玩了大约 $playMinutes 分钟")
        
        // 5. 更新情绪（玩手机会稍微提升心情）
        val moodBoost = Random.nextInt(5, 15)
        emotion.adjustMood(moodBoost, "玩手机放松了一下")
        
        delay(1000)
        onComment("玩够了~")
        
        isPlaying = false
    }
    
    /** 停止自主行为（用户主动说话时调用） */
    fun stopPlaying() {
        isPlaying = false
    }
}
