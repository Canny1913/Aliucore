package com.aliucord.coreplugins

import android.content.Context
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.entities.CorePlugin
import com.aliucord.patcher.*
import com.aliucord.updater.ManagerBuild
import com.aliucord.utils.*
import com.aliucord.wrappers.ChannelWrapper.Companion.guildId
import com.aliucord.wrappers.ChannelWrapper.Companion.id
import com.aliucord.wrappers.ChannelWrapper.Companion.parentId
import com.aliucord.wrappers.ChannelWrapper.Companion.type
import com.discord.api.channel.Channel
import com.discord.models.domain.*
import com.discord.stores.*
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.message.MessageUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.guilds.contextmenu.FolderContextMenuViewModel
import com.discord.widgets.guilds.contextmenu.GuildContextMenuViewModel
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheetViewModel
import com.lytefast.flexinput.R

internal class MessageAckFix : CorePlugin(Manifest("MessageAckFix")) {
    val GuildContextMenuViewModel.guildId by accessField<Long>()
    val FolderContextMenuViewModel.guildIds by accessField<List<Long>>()
    val FolderContextMenuViewModel.unreadGuildIds by accessField<Set<Long>>()
    var ModelReadState.manual by accessField<Boolean>()

    init {
        manifest.description = "Fixes forum message and manual message ack."
    }

    override fun start(context: Context) {
        // Fixes false-positive unread detection for forum posts
        @Suppress("UNCHECKED_CAST")
        patcher.before<StoreReadStates>(
            "computeUnreadIds",
            Map::class.java,
            Map::class.java,
            Map::class.java,
            Map::class.java,
            Map::class.java,
            Map::class.java,
            Long::class.javaPrimitiveType!!,
            Map::class.java,
            Map::class.java,
        ) { param -> // Only check joined forum posts instead of all public posts
            val forumThreadsMap = param.args[7] as Map<Long, Channel>
            val activeJoinedThreads = param.args[8] as Map<Long, StoreThreadsActiveJoined.ActiveJoinedThread>
            val joinedForumThreadsMap = forumThreadsMap.filter {
                it.value.id in activeJoinedThreads
            }
            param.args[7] = joinedForumThreadsMap
        }

        if (!ManagerBuild.hasPatches("1.5.1")) {
            logger.warn("Base app outdated, cannot patch message ack")
            return
        }
        patcher.instead<WidgetGuildProfileSheetViewModel>(
            "onClickMarkAsRead",
            Long::class.java
        ) { (_, guildId: Long) ->
            bulkAck(guildId) {
                WidgetGuildProfileSheetViewModel.`access$getEventSubject$p`(this)
                    .k
                    .onNext(WidgetGuildProfileSheetViewModel.Event.DismissAndShowToast(R.h.marked_as_read))
            }
        }
        patcher.instead<GuildContextMenuViewModel>("onMarkAsReadClicked") {
            bulkAck(guildId) {
                GuildContextMenuViewModel.`access$getEventSubject$p`(this).k.onNext(GuildContextMenuViewModel.Event.Dismiss.INSTANCE)
            }
        }
        patcher.instead<FolderContextMenuViewModel>("onMarkAsReadClicked") {
            guildIds.intersect(unreadGuildIds).forEach { bulkAck(it) }
        }
        patcher.instead<ModelReadState.Parser>(
            "parse",
            Model.JsonReader::class.java
        ) { (_, reader: Model.JsonReader) ->
            var channelId = 0L
            var messageId = 0L
            var lastMessageId = 0L
            var id = 0L
            var version = 0
            var mentionCount = 0
            var manual = false
            reader.nextObject { key ->
                when (key) {
                    "channel_id" -> channelId = reader.nextLong(0L)
                    "message_id" -> messageId = reader.nextLong(0L)
                    "last_message_id" -> lastMessageId = reader.nextLong(0L)
                    "id" -> id = reader.nextLong(0L)
                    "version" -> version = reader.nextInt(0)
                    "mention_count" -> mentionCount = reader.nextInt(0)
                    "manual" -> manual = reader.nextBoolean(false)
                    else -> reader.skipValue()
                }
            }
            return@instead ModelReadState(id, channelId, lastMessageId, messageId, mentionCount, version, null).apply { this.manual = manual }
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()

    fun isChannelMuted(
        channel: Channel?,
        guildSettings: Map<Long, ModelNotificationSettings>,
        thread: StoreThreadsActiveJoined.ActiveJoinedThread? = null
    ): Boolean {
        if (channel == null) return false

        if (channel.type in 10..12 && thread != null) {
            return thread.muted
        }
        return guildSettings[channel.guildId]?.getChannelOverride(channel.id)?.isMuted ?: false
    }

    /**
     * Computes and returns all unread guilds and unread channels (with latest message) in snowflake format.
     */
    fun computeUnread(
        perms: Map<Long, Long>,
        channels: Map<Long, Channel>,
        guildJoinedAt: Map<Long, Long>,
        guildSettings: Map<Long, ModelNotificationSettings>,
        acks: Map<Long, StoreMessageAck.Ack>,
        mostRecent: Map<Long, Long>,
        vcSelectedId: Long,
        activeForumThreads: Map<Long, Channel>,
        activeAndJoinedThreads: Map<Long, StoreThreadsActiveJoined.ActiveJoinedThread>,
    ): Pair<HashSet<Long>, HashMap<Long, Long>> {
        val guildsIds: HashSet<Long> = hashSetOf()
        val channelIdsWithMessage: HashMap<Long, Long> = hashMapOf()

        mostRecent.forEach { (channelId, messageId) ->

            var joinedThread: StoreThreadsActiveJoined.ActiveJoinedThread? = null
            var forumPost: Channel? = null
            var channel = channels[channelId]
            if (channel == null) {
                joinedThread = activeAndJoinedThreads[channelId]
                forumPost = activeForumThreads[channelId]
                channel = joinedThread?.channel ?: forumPost ?: return@forEach
            }

            val isForumPost = channels[channel.parentId]?.type == 15
            val isPrivate = channel.type == 1 || channel.type == 3
            val isThread = channel.type in 10..12
            val isVoice = channel.type == 2
            if (!isPrivate && (!isThread || isForumPost || joinedThread != null) && (!isThread || !isForumPost || forumPost != null)) {
                if (PermissionUtils.INSTANCE.hasAccess(channel, perms[channelId]) && !isChannelMuted(channel,
                        guildSettings,
                        joinedThread) && ((!isVoice || channel.id == vcSelectedId) && (!isVoice || channel.id != vcSelectedId || SnowflakeUtils.toTimestamp(
                        messageId) > StoreStream.Companion!!.voiceChannelSelected.timeSelectedMs))
                ) {
                    val ack = acks[channelId]
                    if (ack != null || !isThread) {
                        val ackTime = ack?.messageId ?: guildJoinedAt[channelId] ?: SnowflakeUtils.fromTimestamp(System.currentTimeMillis())
                        if (MessageUtils.isNewer(ackTime, messageId)) {
                            channelIdsWithMessage[channelId] = messageId // check if parent channel is muted
                            val parentChannel = channels[channel.parentId]
                            var parentChannelOrCategoryMuted = isChannelMuted(parentChannel, guildSettings) // check category as well
                            if (!parentChannelOrCategoryMuted) {
                                val categoryChannel = channels[parentChannel?.parentId]
                                parentChannelOrCategoryMuted = isChannelMuted(categoryChannel, guildSettings)
                            }
                            if (!parentChannelOrCategoryMuted) {
                                guildsIds.add(channel.guildId)
                            }
                        }
                    }
                }
            }
        }
        return Pair(guildsIds, channelIdsWithMessage)
    }

    /**
     * Acknowledges all messages in a guild.
     *
     * @param guildId Target guild.
     * @param onSuccess Callback to invoke upon successful request.
     */
    fun bulkAck(guildId: Long, onSuccess: (() -> Unit)? = null) {
        val storeStream = StoreStream.Companion!!
        storeStream.dispatcherYesThisIsIntentional.schedule {
            val channels = storeStream.channels.getChannelsForGuild(guildId)
            val forumChannels = channels.filterValues { it.type == 15 }
            val activeJoinedThreads = storeStream.threadsActiveJoined.`activeJoinedThreadsInternal$app_productionGoogleRelease`
            val channelActiveThreadEntries = activeJoinedThreads.filter { it.value.channel.guildId == guildId }
            val forumPosts = channelActiveThreadEntries.values.associate { it.channel.id to it.channel }
                .filterValues { channel -> channel.parentId in forumChannels }
            val permissions =
                storeStream.permissions.`getPermissionsByChannelInternal$app_productionGoogleRelease`(guildId) // this will check all channels and threads
            val computed = computeUnread(permissions,
                channels,
                storeStream.guilds.guildsJoinedAt,
                storeStream.userGuildSettings.guildSettings,
                storeStream.messageAck.all,
                storeStream.messagesMostRecent.mostRecentIds,
                storeStream.voiceChannelSelected.selectedVoiceChannelId,
                forumPosts,
                channelActiveThreadEntries)
            val updatePayloads = computed.second.map {
                ReadStateUpdatePayload(channelId = it.key, messageId = it.value)
            }
            val req = Http.Request.newDiscordRNRequest("/read-states/ack-bulk", "POST")
            val response = req.executeWithJson(GsonUtils.gsonRestApi, ReadStatePayload(updatePayloads))
            response.assertOk()
            Utils.mainThread.post { onSuccess?.invoke() }
        }
    }

    companion object {
        data class ReadStateUpdatePayload(
            @SerializedName("read_state_type") val readStateType: Int = 0, // CHANNEL
            @SerializedName("channel_id") val channelId: Long,
            @SerializedName("message_id") val messageId: Long
        )

        data class ReadStatePayload(
            @SerializedName("read_states") val readStates: List<ReadStateUpdatePayload>
        )
    }
}
