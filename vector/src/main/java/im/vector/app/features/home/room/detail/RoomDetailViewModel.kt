/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.home.room.detail

import android.net.Uri
import androidx.annotation.IdRes
import androidx.lifecycle.asFlow
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.BuildConfig
import im.vector.app.R
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.flow.chunk
import im.vector.app.core.mvrx.runCatchingToAsync
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.BehaviorDataSource
import im.vector.app.features.call.conference.ConferenceEvent
import im.vector.app.features.call.conference.JitsiActiveConferenceHolder
import im.vector.app.features.call.conference.JitsiService
import im.vector.app.features.call.lookup.CallProtocolsChecker
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.createdirect.DirectRoomHelper
import im.vector.app.features.crypto.keysrequest.OutboundSessionKeySharingStrategy
import im.vector.app.features.crypto.verification.SupportedVerificationMethodsProvider
import im.vector.app.features.home.room.detail.sticker.StickerPickerActionHandler
import im.vector.app.features.home.room.detail.timeline.factory.TimelineFactory
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.home.room.typing.TypingHelper
import im.vector.app.features.powerlevel.PowerLevelsFlowFactory
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorDataStore
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.isAttachmentMessage
import org.matrix.android.sdk.api.session.events.model.isTextMessage
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.session.initsync.SyncStatusService
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.tombstone.RoomTombstoneContent
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.widgets.model.WidgetType
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap
import org.matrix.android.sdk.internal.crypto.model.event.WithHeldCode
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class RoomDetailViewModel @AssistedInject constructor(
        @Assisted private val initialState: RoomDetailViewState,
        private val vectorPreferences: VectorPreferences,
        private val vectorDataStore: VectorDataStore,
        private val stringProvider: StringProvider,
        private val session: Session,
        private val supportedVerificationMethodsProvider: SupportedVerificationMethodsProvider,
        private val stickerPickerActionHandler: StickerPickerActionHandler,
        private val typingHelper: TypingHelper,
        private val callManager: WebRtcCallManager,
        private val chatEffectManager: ChatEffectManager,
        private val directRoomHelper: DirectRoomHelper,
        private val jitsiService: JitsiService,
        private val activeConferenceHolder: JitsiActiveConferenceHolder,
        timelineFactory: TimelineFactory
) : VectorViewModel<RoomDetailViewState, RoomDetailAction, RoomDetailViewEvents>(initialState),
        Timeline.Listener, ChatEffectManager.Delegate, CallProtocolsChecker.Listener {

    private val room = session.getRoom(initialState.roomId)!!
    private val eventId = initialState.eventId
    private val invisibleEventsSource = BehaviorDataSource<RoomDetailAction.TimelineEventTurnsInvisible>()
    private val visibleEventsSource = BehaviorDataSource<RoomDetailAction.TimelineEventTurnsVisible>()
    private var timelineEvents = MutableSharedFlow<List<TimelineEvent>>(0)
    val timeline = timelineFactory.createTimeline(viewModelScope, room, eventId)

    // Same lifecycle than the ViewModel (survive to screen rotation)
    val previewUrlRetriever = PreviewUrlRetriever(session, viewModelScope)

    // Slot to keep a pending action during permission request
    var pendingAction: RoomDetailAction? = null

    // Slot to keep a pending event during permission request
    var pendingEvent: RoomDetailViewEvents? = null

    private var trackUnreadMessages = AtomicBoolean(false)
    private var mostRecentDisplayedEvent: TimelineEvent? = null

    private var prepareToEncrypt: Async<Unit> = Uninitialized

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomDetailViewModel, RoomDetailViewState> {
        override fun create(initialState: RoomDetailViewState): RoomDetailViewModel
    }

    companion object : MavericksViewModelFactory<RoomDetailViewModel, RoomDetailViewState> by hiltMavericksViewModelFactory() {
        const val PAGINATION_COUNT = 50
    }

    init {
        timeline.start()
        timeline.addListener(this)
        observeRoomSummary()
        observeMembershipChanges()
        observeSummaryState()
        getUnreadState()
        observeSyncState()
        observeDataStore()
        observeEventDisplayedActions()
        observeUnreadState()
        observeMyRoomMember()
        observeActiveRoomWidgets()
        observePowerLevel()
        room.getRoomSummaryLive()
        viewModelScope.launch(Dispatchers.IO) {
            tryOrNull { room.markAsRead(ReadService.MarkAsReadParams.READ_RECEIPT) }
        }
        // Inform the SDK that the room is displayed
        viewModelScope.launch(Dispatchers.IO) {
            tryOrNull { session.onRoomDisplayed(initialState.roomId) }
        }
        callManager.addProtocolsCheckerListener(this)
        callManager.checkForProtocolsSupportIfNeeded()
        chatEffectManager.delegate = this

        // Ensure to share the outbound session keys with all members
        if (OutboundSessionKeySharingStrategy.WhenEnteringRoom == BuildConfig.outboundSessionKeySharingStrategy && room.isEncrypted()) {
            prepareForEncryption()
        }
    }

    private fun observeDataStore() {
        vectorDataStore.pushCounterFlow.setOnEach { nbOfPush ->
            copy(
                    pushCounter = nbOfPush
            )
        }
    }

    private fun prepareForEncryption() {
        // check if there is not already a call made, or if there has been an error
        if (prepareToEncrypt.shouldLoad) {
            prepareToEncrypt = Loading()
            viewModelScope.launch {
                runCatching {
                    room.prepareToEncrypt()
                }.fold({
                    prepareToEncrypt = Success(Unit)
                }, {
                    prepareToEncrypt = Fail(it)
                })
            }
        }
    }

    private fun observePowerLevel() {
        PowerLevelsFlowFactory(room).createFlow()
                .onEach {
                    val canInvite = PowerLevelsHelper(it).isUserAbleToInvite(session.myUserId)
                    val isAllowedToManageWidgets = session.widgetService().hasPermissionsToHandleWidgets(room.roomId)
                    val isAllowedToStartWebRTCCall = PowerLevelsHelper(it).isUserAllowedToSend(session.myUserId, false, EventType.CALL_INVITE)
                    setState {
                        copy(
                                canInvite = canInvite,
                                isAllowedToManageWidgets = isAllowedToManageWidgets,
                                isAllowedToStartWebRTCCall = isAllowedToStartWebRTCCall
                        )
                    }
                }.launchIn(viewModelScope)
    }

    private fun observeActiveRoomWidgets() {
        session.flow()
                .liveRoomWidgets(
                        roomId = initialState.roomId,
                        widgetId = QueryStringValue.NoCondition
                )
                .map { widgets ->
                    widgets.filter { it.isActive }
                }
                .execute { widgets ->
                    copy(activeRoomWidgets = widgets)
                }

        onAsync(RoomDetailViewState::activeRoomWidgets) { widgets ->
            setState {
                val jitsiWidget = widgets.firstOrNull { it.type == WidgetType.Jitsi }
                val jitsiConfId = jitsiWidget?.let {
                    jitsiService.extractJitsiWidgetData(it)?.confId
                }
                copy(
                        jitsiState = jitsiState.copy(
                                confId = jitsiConfId,
                                widgetId = jitsiWidget?.widgetId,
                                hasJoined = activeConferenceHolder.isJoined(jitsiConfId)
                        )
                )
            }
        }
    }

    private fun observeMyRoomMember() {
        val queryParams = roomMemberQueryParams {
            this.userId = QueryStringValue.Equals(session.myUserId, QueryStringValue.Case.SENSITIVE)
        }
        room.flow()
                .liveRoomMembers(queryParams)
                .map {
                    it.firstOrNull().toOptional()
                }
                .unwrap()
                .execute {
                    copy(myRoomMember = it)
                }
    }

    fun getOtherUserIds() = room.roomSummary()?.otherMemberIds

    override fun handle(action: RoomDetailAction) {
        when (action) {
            is RoomDetailAction.ComposerFocusChange              -> handleComposerFocusChange(action)
            is RoomDetailAction.SendMedia                        -> handleSendMedia(action)
            is RoomDetailAction.SendSticker                      -> handleSendSticker(action)
            is RoomDetailAction.TimelineEventTurnsVisible        -> handleEventVisible(action)
            is RoomDetailAction.TimelineEventTurnsInvisible      -> handleEventInvisible(action)
            is RoomDetailAction.LoadMoreTimelineEvents           -> handleLoadMore(action)
            is RoomDetailAction.SendReaction                     -> handleSendReaction(action)
            is RoomDetailAction.AcceptInvite                     -> handleAcceptInvite()
            is RoomDetailAction.RejectInvite                     -> handleRejectInvite()
            is RoomDetailAction.RedactAction                     -> handleRedactEvent(action)
            is RoomDetailAction.UndoReaction                     -> handleUndoReact(action)
            is RoomDetailAction.UpdateQuickReactAction           -> handleUpdateQuickReaction(action)
            is RoomDetailAction.DownloadOrOpen                   -> handleOpenOrDownloadFile(action)
            is RoomDetailAction.NavigateToEvent                  -> handleNavigateToEvent(action)
            is RoomDetailAction.JoinAndOpenReplacementRoom       -> handleJoinAndOpenReplacementRoom()
            is RoomDetailAction.ResendMessage                    -> handleResendEvent(action)
            is RoomDetailAction.RemoveFailedEcho                 -> handleRemove(action)
            is RoomDetailAction.MarkAllAsRead                    -> handleMarkAllAsRead()
            is RoomDetailAction.ReportContent                    -> handleReportContent(action)
            is RoomDetailAction.IgnoreUser                       -> handleIgnoreUser(action)
            is RoomDetailAction.EnterTrackingUnreadMessagesState -> startTrackingUnreadMessages()
            is RoomDetailAction.ExitTrackingUnreadMessagesState  -> stopTrackingUnreadMessages()
            is RoomDetailAction.VoteToPoll                       -> handleVoteToPoll(action)
            is RoomDetailAction.AcceptVerificationRequest        -> handleAcceptVerification(action)
            is RoomDetailAction.DeclineVerificationRequest       -> handleDeclineVerification(action)
            is RoomDetailAction.RequestVerification              -> handleRequestVerification(action)
            is RoomDetailAction.ResumeVerification               -> handleResumeRequestVerification(action)
            is RoomDetailAction.ReRequestKeys                    -> handleReRequestKeys(action)
            is RoomDetailAction.TapOnFailedToDecrypt             -> handleTapOnFailedToDecrypt(action)
            is RoomDetailAction.SelectStickerAttachment          -> handleSelectStickerAttachment()
            is RoomDetailAction.OpenIntegrationManager           -> handleOpenIntegrationManager()
            is RoomDetailAction.StartCall                        -> handleStartCall(action)
            is RoomDetailAction.AcceptCall                       -> handleAcceptCall(action)
            is RoomDetailAction.EndCall                          -> handleEndCall()
            is RoomDetailAction.ManageIntegrations               -> handleManageIntegrations()
            is RoomDetailAction.AddJitsiWidget                   -> handleAddJitsiConference(action)
            is RoomDetailAction.UpdateJoinJitsiCallStatus        -> handleJitsiCallJoinStatus(action)
            is RoomDetailAction.JoinJitsiCall                    -> handleJoinJitsiCall()
            is RoomDetailAction.LeaveJitsiCall                   -> handleLeaveJitsiCall()
            is RoomDetailAction.RemoveWidget                     -> handleDeleteWidget(action.widgetId)
            is RoomDetailAction.EnsureNativeWidgetAllowed        -> handleCheckWidgetAllowed(action)
            is RoomDetailAction.CancelSend                       -> handleCancel(action)
            is RoomDetailAction.OpenOrCreateDm                   -> handleOpenOrCreateDm(action)
            is RoomDetailAction.JumpToReadReceipt                -> handleJumpToReadReceipt(action)
            RoomDetailAction.QuickActionInvitePeople             -> handleInvitePeople()
            RoomDetailAction.QuickActionSetAvatar                -> handleQuickSetAvatar()
            is RoomDetailAction.SetAvatarAction                  -> handleSetNewAvatar(action)
            RoomDetailAction.QuickActionSetTopic                 -> _viewEvents.post(RoomDetailViewEvents.OpenRoomSettings)
            is RoomDetailAction.ShowRoomAvatarFullScreen         -> {
                _viewEvents.post(
                        RoomDetailViewEvents.ShowRoomAvatarFullScreen(action.matrixItem, action.transitionView)
                )
            }
            is RoomDetailAction.DoNotShowPreviewUrlFor           -> handleDoNotShowPreviewUrlFor(action)
            RoomDetailAction.RemoveAllFailedMessages             -> handleRemoveAllFailedMessages()
            RoomDetailAction.ResendAll                           -> handleResendAll()
            is RoomDetailAction.RoomUpgradeSuccess               -> {
                setState {
                    copy(joinUpgradedRoomAsync = Success(action.replacementRoomId))
                }
                _viewEvents.post(RoomDetailViewEvents.OpenRoom(action.replacementRoomId, closeCurrentRoom = true))
            }
            is RoomDetailAction.EndPoll                          -> handleEndPoll(action.eventId)
        }.exhaustive
    }

    private fun handleJitsiCallJoinStatus(action: RoomDetailAction.UpdateJoinJitsiCallStatus) = withState { state ->
        if (state.jitsiState.confId == null) {
            // If jitsi widget is removed while on the call
            if (state.jitsiState.hasJoined) {
                setState { copy(jitsiState = jitsiState.copy(hasJoined = false)) }
            }
            return@withState
        }
        when (action.conferenceEvent) {
            is ConferenceEvent.Joined,
            is ConferenceEvent.Terminated -> {
                setState { copy(jitsiState = jitsiState.copy(hasJoined = activeConferenceHolder.isJoined(jitsiState.confId))) }
            }
            else                          -> Unit
        }
    }

    private fun handleLeaveJitsiCall() {
        _viewEvents.post(RoomDetailViewEvents.LeaveJitsiConference)
    }

    private fun handleJoinJitsiCall() = withState { state ->
        val jitsiWidget = state.activeRoomWidgets()?.firstOrNull { it.widgetId == state.jitsiState.widgetId } ?: return@withState
        val action = RoomDetailAction.EnsureNativeWidgetAllowed(jitsiWidget, false, RoomDetailViewEvents.JoinJitsiConference(jitsiWidget, true))
        handleCheckWidgetAllowed(action)
    }

    private fun handleAcceptCall(action: RoomDetailAction.AcceptCall) {
        callManager.getCallById(action.callId)?.also {
            _viewEvents.post(RoomDetailViewEvents.DisplayAndAcceptCall(it))
        }
    }

    private fun handleDoNotShowPreviewUrlFor(action: RoomDetailAction.DoNotShowPreviewUrlFor) {
        previewUrlRetriever.doNotShowPreviewUrlFor(action.eventId, action.url)
    }

    private fun handleSetNewAvatar(action: RoomDetailAction.SetAvatarAction) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                room.updateAvatar(action.newAvatarUri, action.newAvatarFileName)
                _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ActionFailure(action, failure))
            }
        }
    }

    private fun handleInvitePeople() {
        _viewEvents.post(RoomDetailViewEvents.OpenInvitePeople)
    }

    private fun handleQuickSetAvatar() {
        _viewEvents.post(RoomDetailViewEvents.OpenSetRoomAvatarDialog)
    }

    private fun handleOpenOrCreateDm(action: RoomDetailAction.OpenOrCreateDm) {
        viewModelScope.launch {
            val roomId = try {
                directRoomHelper.ensureDMExists(action.userId)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ActionFailure(action, failure))
                return@launch
            }
            if (roomId != initialState.roomId) {
                _viewEvents.post(RoomDetailViewEvents.OpenRoom(roomId = roomId))
            }
        }
    }

    private fun handleJumpToReadReceipt(action: RoomDetailAction.JumpToReadReceipt) {
        room.getUserReadReceipt(action.userId)
                ?.let { handleNavigateToEvent(RoomDetailAction.NavigateToEvent(it, true)) }
    }

    private fun handleSendSticker(action: RoomDetailAction.SendSticker) {
        room.sendEvent(EventType.STICKER, action.stickerContent.toContent())
    }

    private fun handleStartCall(action: RoomDetailAction.StartCall) {
        viewModelScope.launch {
            room.roomSummary()?.otherMemberIds?.firstOrNull()?.let {
                callManager.startOutgoingCall(room.roomId, it, action.isVideo)
            }
        }
    }

    private fun handleEndCall() {
        callManager.endCallForRoom(initialState.roomId)
    }

    private fun handleSelectStickerAttachment() {
        viewModelScope.launch {
            val viewEvent = stickerPickerActionHandler.handle()
            _viewEvents.post(viewEvent)
        }
    }

    private fun handleOpenIntegrationManager() {
        viewModelScope.launch {
            val viewEvent = withContext(Dispatchers.Default) {
                if (isIntegrationEnabled()) {
                    RoomDetailViewEvents.OpenIntegrationManager
                } else {
                    RoomDetailViewEvents.DisplayEnableIntegrationsWarning
                }
            }
            _viewEvents.post(viewEvent)
        }
    }

    private fun handleManageIntegrations() = withState { state ->
        if (state.activeRoomWidgets().isNullOrEmpty()) {
            // Directly open integration manager screen
            handleOpenIntegrationManager()
        } else {
            // Display bottomsheet with widget list
            _viewEvents.post(RoomDetailViewEvents.OpenActiveWidgetBottomSheet)
        }
    }

    private fun handleAddJitsiConference(action: RoomDetailAction.AddJitsiWidget) {
        _viewEvents.post(RoomDetailViewEvents.ShowWaitingView)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val widget = jitsiService.createJitsiWidget(room.roomId, action.withVideo)
                _viewEvents.post(RoomDetailViewEvents.JoinJitsiConference(widget, action.withVideo))
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ShowMessage(stringProvider.getString(R.string.failed_to_add_widget)))
            } finally {
                _viewEvents.post(RoomDetailViewEvents.HideWaitingView)
            }
        }
    }

    private fun handleDeleteWidget(widgetId: String) = withState { state ->
        val isJitsiWidget = state.jitsiState.widgetId == widgetId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isJitsiWidget) {
                    setState { copy(jitsiState = jitsiState.copy(deleteWidgetInProgress = true)) }
                } else {
                    _viewEvents.post(RoomDetailViewEvents.ShowWaitingView)
                }
                session.widgetService().destroyRoomWidget(room.roomId, widgetId)
                // local echo
                setState {
                    copy(
                            activeRoomWidgets = when (activeRoomWidgets) {
                                is Success -> {
                                    Success(activeRoomWidgets.invoke().filter { it.widgetId != widgetId })
                                }
                                else       -> activeRoomWidgets
                            }
                    )
                }
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ShowMessage(stringProvider.getString(R.string.failed_to_remove_widget)))
            } finally {
                if (isJitsiWidget) {
                    setState { copy(jitsiState = jitsiState.copy(deleteWidgetInProgress = false)) }
                } else {
                    _viewEvents.post(RoomDetailViewEvents.HideWaitingView)
                }
            }
        }
    }

    private fun handleCheckWidgetAllowed(action: RoomDetailAction.EnsureNativeWidgetAllowed) {
        val widget = action.widget
        val domain = action.widget.widgetContent.data["domain"] as? String ?: ""
        val isAllowed = action.userJustAccepted || if (widget.type == WidgetType.Jitsi) {
            widget.senderInfo?.userId == session.myUserId ||
                    session.integrationManagerService().isNativeWidgetDomainAllowed(
                            action.widget.type.preferred,
                            domain
                    )
        } else false

        if (isAllowed) {
            _viewEvents.post(action.grantedEvents)
        } else {
            // we need to request permission
            _viewEvents.post(RoomDetailViewEvents.RequestNativeWidgetPermission(widget, domain, action.grantedEvents))
        }
    }

    private fun startTrackingUnreadMessages() {
        trackUnreadMessages.set(true)
        setState { copy(canShowJumpToReadMarker = false) }
    }

    private fun stopTrackingUnreadMessages() {
        if (trackUnreadMessages.getAndSet(false)) {
            mostRecentDisplayedEvent?.root?.eventId?.also {
                session.coroutineScope.launch {
                    tryOrNull { room.setReadMarker(it) }
                }
            }
            mostRecentDisplayedEvent = null
        }
        setState { copy(canShowJumpToReadMarker = true) }
    }

    private fun handleEventInvisible(action: RoomDetailAction.TimelineEventTurnsInvisible) {
        invisibleEventsSource.post(action)
    }

    fun getMember(userId: String): RoomMemberSummary? {
        return room.getRoomMember(userId)
    }

    private fun handleComposerFocusChange(action: RoomDetailAction.ComposerFocusChange) {
        // Ensure outbound session keys
        if (OutboundSessionKeySharingStrategy.WhenTyping == BuildConfig.outboundSessionKeySharingStrategy && room.isEncrypted()) {
            if (action.focused) {
                // Should we add some rate limit here, or do it only once per model lifecycle?
                prepareForEncryption()
            }
        }
    }

    private fun handleJoinAndOpenReplacementRoom() = withState { state ->
        val tombstoneContent = state.tombstoneEvent?.getClearContent()?.toModel<RoomTombstoneContent>() ?: return@withState

        val roomId = tombstoneContent.replacementRoomId ?: ""
        val isRoomJoined = session.getRoom(roomId)?.roomSummary()?.membership == Membership.JOIN
        if (isRoomJoined) {
            setState { copy(joinUpgradedRoomAsync = Success(roomId)) }
            _viewEvents.post(RoomDetailViewEvents.OpenRoom(roomId, closeCurrentRoom = true))
        } else {
            val viaServers = MatrixPatterns.extractServerNameFromId(state.tombstoneEvent.senderId)
                    ?.let { listOf(it) }
                    .orEmpty()
            // need to provide feedback as joining could take some time
            _viewEvents.post(RoomDetailViewEvents.RoomReplacementStarted)
            setState {
                copy(joinUpgradedRoomAsync = Loading())
            }
            viewModelScope.launch {
                val result = runCatchingToAsync {
                    session.joinRoom(roomId, viaServers = viaServers)
                    roomId
                }
                setState {
                    copy(joinUpgradedRoomAsync = result)
                }
                if (result is Success) {
                    _viewEvents.post(RoomDetailViewEvents.OpenRoom(roomId, closeCurrentRoom = true))
                }
            }
        }
    }

    private fun isIntegrationEnabled() = session.integrationManagerService().isIntegrationEnabled()

    fun isMenuItemVisible(@IdRes itemId: Int): Boolean = com.airbnb.mvrx.withState(this) { state ->
        if (state.asyncRoomSummary()?.membership != Membership.JOIN) {
            return@withState false
        }
        when (itemId) {
            R.id.timeline_setting -> true
            R.id.invite           -> state.canInvite
            R.id.open_matrix_apps -> true
            R.id.voice_call       -> state.isWebRTCCallOptionAvailable()
            R.id.video_call       -> state.isWebRTCCallOptionAvailable() || state.jitsiState.confId == null || state.jitsiState.hasJoined
            // Show Join conference button only if there is an active conf id not joined. Otherwise fallback to default video disabled. ^
            R.id.join_conference  -> !state.isWebRTCCallOptionAvailable() && state.jitsiState.confId != null && !state.jitsiState.hasJoined
            R.id.search           -> true
            R.id.dev_tools        -> vectorPreferences.developerMode()
            else                  -> false
        }
    }

    // PRIVATE METHODS *****************************************************************************

    private fun handleSendReaction(action: RoomDetailAction.SendReaction) {
        room.sendReaction(action.targetEventId, action.reaction)
    }

    private fun handleRedactEvent(action: RoomDetailAction.RedactAction) {
        val event = room.getTimeLineEvent(action.targetEventId) ?: return
        room.redactEvent(event.root, action.reason)
    }

    private fun handleUndoReact(action: RoomDetailAction.UndoReaction) {
        room.undoReaction(action.targetEventId, action.reaction)
    }

    private fun handleUpdateQuickReaction(action: RoomDetailAction.UpdateQuickReactAction) {
        if (action.add) {
            room.sendReaction(action.targetEventId, action.selectedReaction)
        } else {
            room.undoReaction(action.targetEventId, action.selectedReaction)
        }
    }

    private fun handleSendMedia(action: RoomDetailAction.SendMedia) {
        room.sendMedias(action.attachments, action.compressBeforeSending, emptySet())
    }

    private fun handleEventVisible(action: RoomDetailAction.TimelineEventTurnsVisible) {
        viewModelScope.launch(Dispatchers.Default) {
            if (action.event.root.sendState.isSent()) { // ignore pending/local events
                visibleEventsSource.post(action)
            }
            // We need to update this with the related m.replace also (to move read receipt)
            action.event.annotations?.editSummary?.sourceEvents?.forEach {
                room.getTimeLineEvent(it)?.let { event ->
                    visibleEventsSource.post(RoomDetailAction.TimelineEventTurnsVisible(event))
                }
            }

            // handle chat effects here
            if (vectorPreferences.chatEffectsEnabled()) {
                chatEffectManager.checkForEffect(action.event)
            }
        }
    }

    override fun shouldStartEffect(effect: ChatEffect) {
        _viewEvents.post(RoomDetailViewEvents.StartChatEffect(effect))
    }

    override fun stopEffects() {
        _viewEvents.post(RoomDetailViewEvents.StopChatEffects)
    }

    private fun handleLoadMore(action: RoomDetailAction.LoadMoreTimelineEvents) {
        timeline.paginate(action.direction, PAGINATION_COUNT)
    }

    private fun handleRejectInvite() {
        viewModelScope.launch {
            tryOrNull { room.leave(null) }
        }
    }

    private fun handleAcceptInvite() {
        viewModelScope.launch {
            tryOrNull { room.join() }
        }
    }

    private fun handleOpenOrDownloadFile(action: RoomDetailAction.DownloadOrOpen) {
        val mxcUrl = action.messageFileContent.getFileUrl() ?: return
        val isLocalSendingFile = action.senderId == session.myUserId &&
                mxcUrl.startsWith("content://")
        if (isLocalSendingFile) {
            tryOrNull { Uri.parse(mxcUrl) }?.let {
                _viewEvents.post(RoomDetailViewEvents.OpenFile(
                        it,
                        action.messageFileContent.mimeType
                ))
            }
        } else {
            viewModelScope.launch {
                val fileState = session.fileService().fileState(action.messageFileContent)
                var canOpen = fileState is FileService.FileState.InCache && fileState.decryptedFileInCache
                if (!canOpen) {
                    // First download, or download and decrypt, or decrypt from cache
                    val result = runCatching {
                        session.fileService().downloadFile(messageContent = action.messageFileContent)
                    }

                    _viewEvents.post(RoomDetailViewEvents.DownloadFileState(
                            action.messageFileContent.mimeType,
                            result.getOrNull(),
                            result.exceptionOrNull()
                    ))
                    canOpen = result.isSuccess
                }

                if (canOpen) {
                    // We can now open the file
                    session.fileService().getTemporarySharableURI(action.messageFileContent)?.let { uri ->
                        _viewEvents.post(RoomDetailViewEvents.OpenFile(
                                uri,
                                action.messageFileContent.mimeType
                        ))
                    }
                }
            }
        }
    }

    private fun handleNavigateToEvent(action: RoomDetailAction.NavigateToEvent) {
        stopTrackingUnreadMessages()
        val targetEventId: String = action.eventId
        val indexOfEvent = timeline.getIndexOfEvent(targetEventId)
        if (indexOfEvent == null) {
            // Event is not already in RAM
            timeline.restartWithEventId(targetEventId)
        }
        if (action.highlight) {
            setState { copy(highlightedEventId = targetEventId) }
        }
        _viewEvents.post(RoomDetailViewEvents.NavigateToEvent(targetEventId))
    }

    private fun handleResendEvent(action: RoomDetailAction.ResendMessage) {
        val targetEventId = action.eventId
        room.getTimeLineEvent(targetEventId)?.let {
            // State must be UNDELIVERED or Failed
            if (!it.root.sendState.hasFailed()) {
                Timber.e("Cannot resend message, it is not failed, Cancel first")
                return
            }
            when {
                it.root.isTextMessage()       -> room.resendTextMessage(it)
                it.root.isAttachmentMessage() -> room.resendMediaMessage(it)
                else                          -> {
                    // TODO
                }
            }
        }
    }

    private fun handleRemove(action: RoomDetailAction.RemoveFailedEcho) {
        val targetEventId = action.eventId
        room.getTimeLineEvent(targetEventId)?.let {
            // State must be UNDELIVERED or Failed
            if (!it.root.sendState.hasFailed()) {
                Timber.e("Cannot resend message, it is not failed, Cancel first")
                return
            }
            room.deleteFailedEcho(it)
        }
    }

    private fun handleCancel(action: RoomDetailAction.CancelSend) {
        if (action.force) {
            room.cancelSend(action.eventId)
            return
        }
        val targetEventId = action.eventId
        room.getTimeLineEvent(targetEventId)?.let {
            // State must be in one of the sending states
            if (!it.root.sendState.isSending()) {
                Timber.e("Cannot cancel message, it is not sending")
                return
            }
            room.cancelSend(targetEventId)
        }
    }

    private fun handleResendAll() {
        room.resendAllFailedMessages()
    }

    private fun handleRemoveAllFailedMessages() {
        room.cancelAllFailedMessages()
    }

    private fun observeEventDisplayedActions() {
        // We are buffering scroll events for one second
        // and keep the most recent one to set the read receipt on.

        visibleEventsSource
                .stream()
                .chunk(1000)
                .filter { it.isNotEmpty() }
                .onEach { actions ->
                    val bufferedMostRecentDisplayedEvent = actions.maxByOrNull { it.event.displayIndex }?.event ?: return@onEach
                    val globalMostRecentDisplayedEvent = mostRecentDisplayedEvent
                    if (trackUnreadMessages.get()) {
                        if (globalMostRecentDisplayedEvent == null) {
                            mostRecentDisplayedEvent = bufferedMostRecentDisplayedEvent
                        } else if (bufferedMostRecentDisplayedEvent.displayIndex > globalMostRecentDisplayedEvent.displayIndex) {
                            mostRecentDisplayedEvent = bufferedMostRecentDisplayedEvent
                        }
                    }
                    bufferedMostRecentDisplayedEvent.root.eventId?.let { eventId ->
                        session.coroutineScope.launch {
                            tryOrNull { room.setReadReceipt(eventId) }
                        }
                    }
                }
                .flowOn(Dispatchers.Default)
                .launchIn(viewModelScope)
    }

    private fun handleMarkAllAsRead() {
        setState { copy(unreadState = UnreadState.HasNoUnread) }
        viewModelScope.launch {
            tryOrNull { room.markAsRead(ReadService.MarkAsReadParams.BOTH) }
        }
    }

    private fun handleReportContent(action: RoomDetailAction.ReportContent) {
        viewModelScope.launch {
            val event = try {
                room.reportContent(action.eventId, -100, action.reason)
                RoomDetailViewEvents.ActionSuccess(action)
            } catch (failure: Exception) {
                RoomDetailViewEvents.ActionFailure(action, failure)
            }
            _viewEvents.post(event)
        }
    }

    private fun handleIgnoreUser(action: RoomDetailAction.IgnoreUser) {
        if (action.userId.isNullOrEmpty()) {
            return
        }

        viewModelScope.launch {
            val event = try {
                session.ignoreUserIds(listOf(action.userId))
                RoomDetailViewEvents.ActionSuccess(action)
            } catch (failure: Throwable) {
                RoomDetailViewEvents.ActionFailure(action, failure)
            }
            _viewEvents.post(event)
        }
    }

    private fun handleAcceptVerification(action: RoomDetailAction.AcceptVerificationRequest) {
        Timber.v("## SAS handleAcceptVerification ${action.otherUserId},  roomId:${room.roomId}, txId:${action.transactionId}")
        if (session.cryptoService().verificationService().readyPendingVerificationInDMs(
                        supportedVerificationMethodsProvider.provide(),
                        action.otherUserId,
                        room.roomId,
                        action.transactionId)) {
            _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
        } else {
            // TODO
        }
    }

    private fun handleDeclineVerification(action: RoomDetailAction.DeclineVerificationRequest) {
        session.cryptoService().verificationService().declineVerificationRequestInDMs(
                action.otherUserId,
                action.transactionId,
                room.roomId)
    }

    private fun handleRequestVerification(action: RoomDetailAction.RequestVerification) {
        if (action.userId == session.myUserId) return
        _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
    }

    private fun handleResumeRequestVerification(action: RoomDetailAction.ResumeVerification) {
        // Check if this request is still active and handled by me
        session.cryptoService().verificationService().getExistingVerificationRequestInRoom(room.roomId, action.transactionId)?.let {
            if (it.handledByOtherSession) return
            if (!it.isFinished) {
                _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action.copy(
                        otherUserId = it.otherUserId
                )))
            }
        }
    }

    private fun handleReRequestKeys(action: RoomDetailAction.ReRequestKeys) {
        // Check if this request is still active and handled by me
        room.getTimeLineEvent(action.eventId)?.let {
            session.cryptoService().reRequestRoomKeyForEvent(it.root)
            _viewEvents.post(RoomDetailViewEvents.ShowMessage(stringProvider.getString(R.string.e2e_re_request_encryption_key_dialog_content)))
        }
    }

    private fun handleTapOnFailedToDecrypt(action: RoomDetailAction.TapOnFailedToDecrypt) {
        room.getTimeLineEvent(action.eventId)?.let {
            val code = when (it.root.mCryptoError) {
                MXCryptoError.ErrorType.KEYS_WITHHELD -> {
                    WithHeldCode.fromCode(it.root.mCryptoErrorReason)
                }
                else                                  -> null
            }

            _viewEvents.post(RoomDetailViewEvents.ShowE2EErrorMessage(code))
        }
    }

    private fun handleVoteToPoll(action: RoomDetailAction.VoteToPoll) {
        // Do not allow to vote unsent local echo of the poll event
        if (LocalEcho.isLocalEchoId(action.eventId)) return
        // Do not allow to vote the same option twice
        room.getTimeLineEvent(action.eventId)?.let { pollTimelineEvent ->
            val currentVote = pollTimelineEvent.annotations?.pollResponseSummary?.aggregatedContent?.myVote
            if (currentVote != action.optionKey) {
                room.voteToPoll(action.eventId, action.optionKey)
            }
        }
    }

    private fun handleEndPoll(eventId: String) {
        room.endPoll(eventId)
    }

    private fun observeSyncState() {
        session.flow()
                .liveSyncState()
                .setOnEach { syncState ->
                    copy(syncState = syncState)
                }

        session.getSyncStatusLive()
                .asFlow()
                .filterIsInstance<SyncStatusService.Status.IncrementalSyncStatus>()
                .setOnEach {
                    copy(incrementalSyncStatus = it)
                }
    }

    private fun observeRoomSummary() {
        room.flow().liveRoomSummary()
                .unwrap()
                .execute { async ->
                    copy(
                            asyncRoomSummary = async
                    )
                }
    }

    private fun getUnreadState() {
        combine(
                timelineEvents,
                room.flow().liveRoomSummary().unwrap()
        ) { timelineEvents, roomSummary ->
            computeUnreadState(timelineEvents, roomSummary)
        }
                // We don't want live update of unread so we skip when we already had a HasUnread or HasNoUnread
                .distinctUntilChanged { previous, current ->
                    when {
                        previous is UnreadState.Unknown || previous is UnreadState.ReadMarkerNotLoaded -> false
                        current is UnreadState.HasUnread || current is UnreadState.HasNoUnread         -> true
                        else                                                                           -> false
                    }
                }
                .setOnEach {
                    copy(unreadState = it)
                }
    }

    private fun computeUnreadState(events: List<TimelineEvent>, roomSummary: RoomSummary): UnreadState {
        if (events.isEmpty()) return UnreadState.Unknown
        val readMarkerIdSnapshot = roomSummary.readMarkerId ?: return UnreadState.Unknown
        val firstDisplayableEventIndex = timeline.getIndexOfEvent(readMarkerIdSnapshot)
                ?: return if (timeline.isLive) {
                    UnreadState.ReadMarkerNotLoaded(readMarkerIdSnapshot)
                } else {
                    UnreadState.Unknown
                }
        for (i in (firstDisplayableEventIndex - 1) downTo 0) {
            val timelineEvent = events.getOrNull(i) ?: return UnreadState.Unknown
            val eventId = timelineEvent.root.eventId ?: return UnreadState.Unknown
            val isFromMe = timelineEvent.root.senderId == session.myUserId
            if (!isFromMe) {
                return UnreadState.HasUnread(eventId)
            }
        }
        return UnreadState.HasNoUnread
    }

    private fun observeUnreadState() {
        onEach(RoomDetailViewState::unreadState) {
            Timber.v("Unread state: $it")
            if (it is UnreadState.HasNoUnread) {
                startTrackingUnreadMessages()
            }
        }
    }

    private fun observeMembershipChanges() {
        session.flow()
                .liveRoomChangeMembershipState()
                .map {
                    it[initialState.roomId] ?: ChangeMembershipState.Unknown
                }
                .distinctUntilChanged()
                .setOnEach {
                    copy(changeMembershipState = it)
                }
    }

    private fun observeSummaryState() {
        onAsync(RoomDetailViewState::asyncRoomSummary) { summary ->
            setState {
                val typingMessage = typingHelper.getTypingMessage(summary.typingUsers)
                copy(
                        formattedTypingUsers = typingMessage,
                        hasFailedSending = summary.hasFailedSending
                )
            }
            if (summary.membership == Membership.INVITE) {
                summary.inviterId?.let { inviterId ->
                    session.getRoomMember(inviterId, summary.roomId)
                }?.also {
                    setState { copy(asyncInviter = Success(it)) }
                }
            }
            room.getStateEvent(EventType.STATE_ROOM_TOMBSTONE)?.also {
                setState { copy(tombstoneEvent = it) }
            }
        }
    }

    override fun onTimelineUpdated(snapshot: List<TimelineEvent>) {
        viewModelScope.launch {
            // tryEmit doesn't work with SharedFlow without cache
            timelineEvents.emit(snapshot)
        }
        // PreviewUrl
        if (vectorPreferences.showUrlPreviews()) {
            withState { state ->
                snapshot
                        .takeIf { state.asyncRoomSummary.invoke()?.isEncrypted == false }
                        ?.forEach {
                            previewUrlRetriever.getPreviewUrl(it)
                        }
            }
        }
    }

    override fun onTimelineFailure(throwable: Throwable) {
        // If we have a critical timeline issue, we get back to live.
        timeline.restartWithEventId(null)
        _viewEvents.post(RoomDetailViewEvents.Failure(throwable))
    }

    override fun onNewTimelineEvents(eventIds: List<String>) {
        Timber.v("On new timeline events: $eventIds")
        _viewEvents.post(RoomDetailViewEvents.OnNewTimelineEvents(eventIds))
    }

    override fun onCleared() {
        timeline.dispose()
        timeline.removeAllListeners()
        if (vectorPreferences.sendTypingNotifs()) {
            room.userStopsTyping()
        }
        chatEffectManager.delegate = null
        chatEffectManager.dispose()
        callManager.removeProtocolsCheckerListener(this)
        super.onCleared()
    }
}
