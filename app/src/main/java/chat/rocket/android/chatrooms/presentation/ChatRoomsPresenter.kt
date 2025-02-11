package chat.rocket.android.chatrooms.presentation

import android.util.Log
import chat.rocket.android.R
import chat.rocket.android.chatrooms.adapter.model.RoomUiModel
import chat.rocket.android.chatrooms.domain.FetchChatRoomsInteractor
import chat.rocket.android.core.lifecycle.CancelStrategy
import chat.rocket.android.db.DatabaseManager
import chat.rocket.android.db.model.ChatRoomEntity
import chat.rocket.android.helper.UserHelper
import chat.rocket.android.infrastructure.LocalRepository
import chat.rocket.android.main.presentation.MainNavigator
import chat.rocket.android.server.domain.SettingsRepository
import chat.rocket.android.server.domain.SortingAndGroupingInteractor
import chat.rocket.android.server.domain.siteName
import chat.rocket.android.server.domain.useRealName
import chat.rocket.android.server.domain.useSpecialCharsOnRoom
import chat.rocket.android.server.infrastructure.ConnectionManager
import chat.rocket.android.util.extension.launchUI
import chat.rocket.android.util.retryDB
import chat.rocket.android.util.retryIO
import chat.rocket.common.RocketChatException
import chat.rocket.common.model.RoomType
import chat.rocket.common.model.User
import chat.rocket.common.model.roomTypeOf
import chat.rocket.core.internal.rest.createDirectMessage
import chat.rocket.core.internal.rest.me
import chat.rocket.core.internal.rest.show
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named

class ChatRoomsPresenter @Inject constructor(
    private val view: ChatRoomsView,
    private val strategy: CancelStrategy,
    private val navigator: MainNavigator,
    @Named("currentServer") private val currentServer: String?,
    private val sortingAndGroupingInteractor: SortingAndGroupingInteractor,
    private val dbManager: DatabaseManager,
    manager: ConnectionManager,
    private val localRepository: LocalRepository,
    private val userHelper: UserHelper,
    settingsRepository: SettingsRepository
) {
    private val client = manager.client
    private val settings = currentServer?.let { settingsRepository.get(it) }

    fun toCreateChannel() = navigator.toCreateChannel()

    fun toSettings() = navigator.toSettings()

    fun toDirectory() = navigator.toDirectory()

    fun getCurrentServerName() = currentServer?.let {
        view.setupToolbar(settings?.siteName() ?: it)
    }

    fun getSortingAndGroupingPreferences() {
        with(sortingAndGroupingInteractor) {
            currentServer?.let {
                view.setupSortingAndGrouping(
                    getSortByName(it),
                    getUnreadOnTop(it),
                    getGroupByType(it),
                    getGroupByFavorites(it)
                )
            }
        }
    }

    fun loadChatRoom(roomId: String) {
        launchUI(strategy) {
            try {
                val room = dbManager.getRoom(roomId)
                if (room != null) {
                    loadChatRoom(room.chatRoom, true)
                } else {
                    Timber.e("Error loading channel")
                    view.showGenericErrorMessage()
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error loading channel")
                view.showGenericErrorMessage()
            }
        }
    }

    fun loadChatRoom(chatRoom: RoomUiModel) {
        launchUI(strategy) {
            try {
                Log.i("App Flow" ,"loadChatRoom - ChatRoom "+chatRoom);
                val room = retryDB("getRoom(${chatRoom.id}") { dbManager.getRoom(chatRoom.id) }
                if (room != null) {
                    loadChatRoom(room.chatRoom, true)
                } else {
                    with(chatRoom) {
                        val entity = ChatRoomEntity(
                            id = id,
                            subscriptionId = "",
                            parentId = null,
                            type = type.toString(),
                            name = username ?: name.toString(),
                            fullname = name.toString(),
                            open = open,
                            muted = muted
                        )
                        loadChatRoom(entity, false)
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error loading channel")
                view.showGenericErrorMessage()
            }
        }
    }

    suspend fun loadChatRoom(chatRoom: ChatRoomEntity, local: Boolean = false) {
        Log.i("App Flow" ,"loadChatRoom local - ChatRoom "+chatRoom);
        with(chatRoom) {
            val isDirectMessage = roomTypeOf(type) is RoomType.DirectMessage
            val roomName =
                if ((settings?.useSpecialCharsOnRoom() == true) ||
                    (isDirectMessage && settings?.useRealName() == true)) {
                    fullname ?: name
                } else {
                    name
                }

            val myself = getCurrentUser()
            if (myself?.username == null) {
                view.showMessage(R.string.msg_generic_error)
            } else {
                val id = if (isDirectMessage && open == false) {
                    // If from local database, we already have the roomId, no need to concatenate
                    if (local) {
                        retryIO {
                            client.show(id, roomTypeOf(RoomType.DIRECT_MESSAGE))
                        }
                        id
                    } else {
                        retryIO("createDirectMessage($name)") {
                            withTimeout(10000) {
                                try {
                                    client.createDirectMessage(name)
                                } catch (ex: Exception) {
                                    Timber.e(ex)
                                }
                            }
                        }
                        val fromTo = mutableListOf(myself.id, id).apply {
                            sort()
                        }
                        fromTo.joinToString("")
                    }
                } else {
                    id
                }

                FetchChatRoomsInteractor(client, dbManager).refreshChatRooms()

                navigator.toChatRoom(
                    chatRoomId = id,
                    chatRoomName = roomName,
                    chatRoomType = type,
                    isReadOnly = readonly ?: false,
                    chatRoomLastSeen = lastSeen ?: -1,
                    isSubscribed = open,
                    isCreator = ownerId == myself.id || isDirectMessage,
                    isFavorite = favorite ?: false
                )
            }
        }
    }

    private suspend fun getCurrentUser(): User? {
        userHelper.user()?.let {
            return it
        }
        try {
            val myself = retryIO { client.me() }
            val user = User(
                id = myself.id,
                username = myself.username,
                name = myself.name,
                status = myself.status,
                utcOffset = myself.utcOffset,
                emails = null,
                roles = myself.roles
            )
            currentServer?.let {
                localRepository.saveCurrentUser(url = it, user = user)
            }
        } catch (ex: RocketChatException) {
            Timber.e(ex)
        }
        return null
    }
}