package org.briarproject.briar.android.contact;

import android.arch.lifecycle.MutableLiveData;
import android.arch.lifecycle.Observer;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.ActionMenuView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.Toolbar;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchContactException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.bramble.api.plugin.ConnectionRegistry;
import org.briarproject.bramble.api.plugin.event.ContactConnectedEvent;
import org.briarproject.bramble.api.plugin.event.ContactDisconnectedEvent;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.event.MessagesAckedEvent;
import org.briarproject.bramble.api.sync.event.MessagesSentEvent;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.android.blog.BlogActivity;
import org.briarproject.briar.android.contact.ConversationAdapter.ConversationListener;
import org.briarproject.briar.android.contact.ConversationVisitor.TextCache;
import org.briarproject.briar.android.forum.ForumActivity;
import org.briarproject.briar.android.introduction.IntroductionActivity;
import org.briarproject.briar.android.privategroup.conversation.GroupActivity;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.android.view.TextInputView;
import org.briarproject.briar.android.view.TextInputView.TextInputListener;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.blog.BlogSharingManager;
import org.briarproject.briar.api.client.ProtocolStateException;
import org.briarproject.briar.api.client.SessionId;
import org.briarproject.briar.api.forum.ForumSharingManager;
import org.briarproject.briar.api.introduction.IntroductionManager;
import org.briarproject.briar.api.messaging.ConversationManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessage;
import org.briarproject.briar.api.messaging.PrivateMessageFactory;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.messaging.PrivateRequest;
import org.briarproject.briar.api.messaging.PrivateResponse;
import org.briarproject.briar.api.messaging.event.PrivateMessageReceivedEvent;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import de.hdodenhof.circleimageview.CircleImageView;
import im.delight.android.identicons.IdenticonDrawable;
import uk.co.samuelwall.materialtaptargetprompt.MaterialTapTargetPrompt;
import uk.co.samuelwall.materialtaptargetprompt.MaterialTapTargetPrompt.PromptStateChangeListener;

import static android.support.v4.view.ViewCompat.setTransitionName;
import static android.support.v7.util.SortedList.INVALID_POSITION;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.LogUtils.now;
import static org.briarproject.briar.android.activity.RequestCodes.REQUEST_INTRODUCTION;
import static org.briarproject.briar.android.settings.SettingsFragment.SETTINGS_NAMESPACE;
import static org.briarproject.briar.android.util.UiUtils.getAvatarTransitionName;
import static org.briarproject.briar.android.util.UiUtils.getBulbTransitionName;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;
import static uk.co.samuelwall.materialtaptargetprompt.MaterialTapTargetPrompt.STATE_DISMISSED;
import static uk.co.samuelwall.materialtaptargetprompt.MaterialTapTargetPrompt.STATE_FINISHED;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ConversationActivity extends BriarActivity
		implements EventListener, ConversationListener, TextInputListener,
		TextCache {

	public static final String CONTACT_ID = "briar.CONTACT_ID";

	private static final Logger LOG =
			Logger.getLogger(ConversationActivity.class.getName());
	private static final String SHOW_ONBOARDING_INTRODUCTION =
			"showOnboardingIntroduction";

	@Inject
	AndroidNotificationManager notificationManager;
	@Inject
	ConnectionRegistry connectionRegistry;
	@Inject
	@CryptoExecutor
	Executor cryptoExecutor;

	private final Map<MessageId, String> textCache = new ConcurrentHashMap<>();
	private final MutableLiveData<String> contactName = new MutableLiveData<>();

	private ConversationVisitor visitor;
	private ConversationAdapter adapter;
	private Toolbar toolbar;
	private CircleImageView toolbarAvatar;
	private ImageView toolbarStatus;
	private TextView toolbarTitle;
	private BriarRecyclerView list;
	private TextInputView textInputView;

	// Fields that are accessed from background threads must be volatile
	@Inject
	volatile ContactManager contactManager;
	@Inject
	volatile MessagingManager messagingManager;
	@Inject
	volatile ConversationManager conversationManager;
	@Inject
	volatile EventBus eventBus;
	@Inject
	volatile SettingsManager settingsManager;
	@Inject
	volatile PrivateMessageFactory privateMessageFactory;
	@Inject
	volatile IntroductionManager introductionManager;
	@Inject
	volatile ForumSharingManager forumSharingManager;
	@Inject
	volatile BlogSharingManager blogSharingManager;
	@Inject
	volatile GroupInvitationManager groupInvitationManager;

	private volatile ContactId contactId;
	@Nullable
	private volatile AuthorId contactAuthorId;
	@Nullable
	private volatile GroupId messagingGroupId;

	@SuppressWarnings("ConstantConditions")
	@Override
	public void onCreate(@Nullable Bundle state) {
		setSceneTransitionAnimation();
		super.onCreate(state);

		Intent i = getIntent();
		int id = i.getIntExtra(CONTACT_ID, -1);
		if (id == -1) throw new IllegalStateException();
		contactId = new ContactId(id);

		setContentView(R.layout.activity_conversation);

		// Custom Toolbar
		toolbar = setUpCustomToolbar(true);
		if (toolbar != null) {
			toolbarAvatar = toolbar.findViewById(R.id.contactAvatar);
			toolbarStatus = toolbar.findViewById(R.id.contactStatus);
			toolbarTitle = toolbar.findViewById(R.id.contactName);
		}

		setTransitionName(toolbarAvatar, getAvatarTransitionName(contactId));
		setTransitionName(toolbarStatus, getBulbTransitionName(contactId));

		visitor = new ConversationVisitor(this, this, contactName);
		adapter = new ConversationAdapter(this, this);
		list = findViewById(R.id.conversationView);
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		list.setEmptyText(getString(R.string.no_private_messages));

		textInputView = findViewById(R.id.text_input_container);
		textInputView.setListener(this);
	}

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	protected void onActivityResult(int request, int result, Intent data) {
		super.onActivityResult(request, result, data);

		if (request == REQUEST_INTRODUCTION && result == RESULT_OK) {
			Snackbar snackbar = Snackbar.make(list, R.string.introduction_sent,
					Snackbar.LENGTH_SHORT);
			snackbar.getView().setBackgroundResource(R.color.briar_primary);
			snackbar.show();
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		notificationManager.blockContactNotification(contactId);
		notificationManager.clearContactNotification(contactId);
		displayContactOnlineStatus();
		loadContactDetailsAndMessages();
		list.startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
		notificationManager.unblockContactNotification(contactId);
		list.stopPeriodicUpdate();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu items for use in the action bar
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.conversation_actions, menu);

		enableIntroductionActionIfAvailable(
				menu.findItem(R.id.action_introduction));

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle presses on the action bar items
		switch (item.getItemId()) {
			case android.R.id.home:
				onBackPressed();
				return true;
			case R.id.action_introduction:
				if (contactId == null) return false;
				Intent intent = new Intent(this, IntroductionActivity.class);
				intent.putExtra(CONTACT_ID, contactId.getInt());
				startActivityForResult(intent, REQUEST_INTRODUCTION);
				return true;
			case R.id.action_social_remove_person:
				askToRemoveContact();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	private void loadContactDetailsAndMessages() {
		runOnDbThread(() -> {
			try {
				long start = now();
				if (contactAuthorId == null) {
					Contact contact = contactManager.getContact(contactId);
					contactName.postValue(contact.getAuthor().getName());
					contactAuthorId = contact.getAuthor().getId();
				}
				logDuration(LOG, "Loading contact", start);
				loadMessages();
				displayContactDetails();
			} catch (NoSuchContactException e) {
				finishOnUiThread();
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	// contactAuthorId and contactName are expected to be set
	private void displayContactDetails() {
		runOnUiThreadUnlessDestroyed(() -> {
			//noinspection ConstantConditions
			toolbarAvatar.setImageDrawable(
					new IdenticonDrawable(contactAuthorId.getBytes()));
			toolbarTitle.setText(contactName.getValue());
		});
	}

	private void displayContactOnlineStatus() {
		runOnUiThreadUnlessDestroyed(() -> {
			if (connectionRegistry.isConnected(contactId)) {
				toolbarStatus.setImageDrawable(ContextCompat
						.getDrawable(ConversationActivity.this,
								R.drawable.contact_online));
				toolbarStatus
						.setContentDescription(getString(R.string.online));
			} else {
				toolbarStatus.setImageDrawable(ContextCompat
						.getDrawable(ConversationActivity.this,
								R.drawable.contact_offline));
				toolbarStatus
						.setContentDescription(getString(R.string.offline));
			}
		});
	}

	private void loadMessages() {
		int revision = adapter.getRevision();
		runOnDbThread(() -> {
			try {
				long start = now();
				Collection<PrivateMessageHeader> headers =
						conversationManager.getMessageHeaders(contactId);
				logDuration(LOG, "Loading messages", start);
				displayMessages(revision, headers);
			} catch (NoSuchContactException e) {
				finishOnUiThread();
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	private void displayMessages(int revision,
			Collection<PrivateMessageHeader> headers) {
		runOnUiThreadUnlessDestroyed(() -> {
			if (revision == adapter.getRevision()) {
				adapter.incrementRevision();
				textInputView.setSendButtonEnabled(true);
				List<ConversationItem> items = createItems(headers);
				adapter.addAll(items);
				list.showData();
				// Scroll to the bottom
				list.scrollToPosition(adapter.getItemCount() - 1);
			} else {
				LOG.info("Concurrent update, reloading");
				loadMessages();
			}
		});
	}

	/**
	 * Creates ConversationItems from headers loaded from the database.
	 * <p>
	 * Attention: Call this only after contactName has been initialized.
	 */
	@SuppressWarnings("ConstantConditions")
	private List<ConversationItem> createItems(
			Collection<PrivateMessageHeader> headers) {
		List<ConversationItem> items = new ArrayList<>(headers.size());
		for (PrivateMessageHeader h : headers) items.add(h.accept(visitor));
		return items;
	}

	private void loadMessageText(MessageId m) {
		runOnDbThread(() -> {
			try {
				long start = now();
				String text = messagingManager.getMessageText(m);
				logDuration(LOG, "Loading text", start);
				displayMessageText(m, text);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	private void displayMessageText(MessageId m, String text) {
		runOnUiThreadUnlessDestroyed(() -> {
			textCache.put(m, text);
			SparseArray<ConversationItem> messages =
					adapter.getPrivateMessages();
			for (int i = 0; i < messages.size(); i++) {
				ConversationItem item = messages.valueAt(i);
				if (item.getId().equals(m)) {
					item.setText(text);
					adapter.notifyItemChanged(messages.keyAt(i));
					list.scrollToPosition(adapter.getItemCount() - 1);
					return;
				}
			}
		});
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			if (c.getContactId().equals(contactId)) {
				LOG.info("Contact removed");
				finishOnUiThread();
			}
		} else if (e instanceof PrivateMessageReceivedEvent) {
			PrivateMessageReceivedEvent p = (PrivateMessageReceivedEvent) e;
			if (p.getContactId().equals(contactId)) {
				LOG.info("Message received, adding");
				onNewPrivateMessage(p.getMessageHeader());
			}
		} else if (e instanceof MessagesSentEvent) {
			MessagesSentEvent m = (MessagesSentEvent) e;
			if (m.getContactId().equals(contactId)) {
				LOG.info("Messages sent");
				markMessages(m.getMessageIds(), true, false);
			}
		} else if (e instanceof MessagesAckedEvent) {
			MessagesAckedEvent m = (MessagesAckedEvent) e;
			if (m.getContactId().equals(contactId)) {
				LOG.info("Messages acked");
				markMessages(m.getMessageIds(), true, true);
			}
		} else if (e instanceof ContactConnectedEvent) {
			ContactConnectedEvent c = (ContactConnectedEvent) e;
			if (c.getContactId().equals(contactId)) {
				LOG.info("Contact connected");
				displayContactOnlineStatus();
			}
		} else if (e instanceof ContactDisconnectedEvent) {
			ContactDisconnectedEvent c = (ContactDisconnectedEvent) e;
			if (c.getContactId().equals(contactId)) {
				LOG.info("Contact disconnected");
				displayContactOnlineStatus();
			}
		}
	}

	private void addConversationItem(ConversationItem item) {
		runOnUiThreadUnlessDestroyed(() -> {
			adapter.incrementRevision();
			adapter.add(item);
			// Scroll to the bottom
			list.scrollToPosition(adapter.getItemCount() - 1);
		});
	}

	private void onNewPrivateMessage(PrivateMessageHeader h) {
		runOnUiThreadUnlessDestroyed(() -> {
			if (h instanceof PrivateRequest || h instanceof PrivateResponse) {
				String cName = contactName.getValue();
				if (cName == null) {
					// Wait for the contact name to be loaded
					contactName.observe(this, new Observer<String>() {
						@Override
						public void onChanged(@Nullable String cName) {
							if (cName != null) {
								addConversationItem(h.accept(visitor));
								contactName.removeObserver(this);
							}
						}
					});
				} else {
					addConversationItem(h.accept(visitor));
				}
			} else {
				addConversationItem(h.accept(visitor));
				loadMessageText(h.getId());
			}
		});
	}

	private void markMessages(Collection<MessageId> messageIds, boolean sent,
			boolean seen) {
		runOnUiThreadUnlessDestroyed(() -> {
			adapter.incrementRevision();
			Set<MessageId> messages = new HashSet<>(messageIds);
			SparseArray<ConversationOutItem> list =
					adapter.getOutgoingMessages();
			for (int i = 0; i < list.size(); i++) {
				ConversationOutItem item = list.valueAt(i);
				if (messages.contains(item.getId())) {
					item.setSent(sent);
					item.setSeen(seen);
					adapter.notifyItemChanged(list.keyAt(i));
				}
			}
		});
	}

	@Override
	public void onSendClick(String text) {
		if (text.isEmpty()) return;
		text = StringUtils.truncateUtf8(text, MAX_PRIVATE_MESSAGE_TEXT_LENGTH);
		long timestamp = System.currentTimeMillis();
		timestamp = Math.max(timestamp, getMinTimestampForNewMessage());
		if (messagingGroupId == null) loadGroupId(text, timestamp);
		else createMessage(text, timestamp);
		textInputView.setText("");
	}

	private long getMinTimestampForNewMessage() {
		// Don't use an earlier timestamp than the newest message
		ConversationItem item = adapter.getLastItem();
		return item == null ? 0 : item.getTime() + 1;
	}

	private void loadGroupId(String text, long timestamp) {
		runOnDbThread(() -> {
			try {
				messagingGroupId =
						messagingManager.getConversationId(contactId);
				createMessage(text, timestamp);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}

		});
	}

	private void createMessage(String text, long timestamp) {
		cryptoExecutor.execute(() -> {
			try {
				//noinspection ConstantConditions init in loadGroupId()
				storeMessage(privateMessageFactory.createPrivateMessage(
						messagingGroupId, timestamp, text), text);
			} catch (FormatException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private void storeMessage(PrivateMessage m, String text) {
		runOnDbThread(() -> {
			try {
				long start = now();
				messagingManager.addLocalMessage(m);
				logDuration(LOG, "Storing message", start);
				Message message = m.getMessage();
				PrivateMessageHeader h = new PrivateMessageHeader(
						message.getId(), message.getGroupId(),
						message.getTimestamp(), true, false, false, false);
				textCache.put(message.getId(), text);
				addConversationItem(h.accept(visitor));
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	private void askToRemoveContact() {
		DialogInterface.OnClickListener okListener =
				(dialog, which) -> removeContact();
		AlertDialog.Builder builder =
				new AlertDialog.Builder(ConversationActivity.this,
						R.style.BriarDialogTheme);
		builder.setTitle(getString(R.string.dialog_title_delete_contact));
		builder.setMessage(getString(R.string.dialog_message_delete_contact));
		builder.setNegativeButton(R.string.delete, okListener);
		builder.setPositiveButton(R.string.cancel, null);
		builder.show();
	}

	private void removeContact() {
		runOnDbThread(() -> {
			try {
				contactManager.removeContact(contactId);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			} finally {
				finishAfterContactRemoved();
			}
		});
	}

	private void finishAfterContactRemoved() {
		runOnUiThreadUnlessDestroyed(() -> {
			String deleted = getString(R.string.contact_deleted_toast);
			Toast.makeText(ConversationActivity.this, deleted, LENGTH_SHORT)
					.show();
			supportFinishAfterTransition();
		});
	}

	private void enableIntroductionActionIfAvailable(MenuItem item) {
		runOnDbThread(() -> {
			try {
				if (contactManager.getActiveContacts().size() > 1) {
					enableIntroductionAction(item);
					Settings settings =
							settingsManager.getSettings(SETTINGS_NAMESPACE);
					if (settings.getBoolean(SHOW_ONBOARDING_INTRODUCTION,
							true)) {
						showIntroductionOnboarding();
					}
				}
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	private void enableIntroductionAction(MenuItem item) {
		runOnUiThreadUnlessDestroyed(() -> item.setEnabled(true));
	}

	private void showIntroductionOnboarding() {
		runOnUiThreadUnlessDestroyed(() -> {
			// find view of overflow icon
			View target = null;
			for (int i = 0; i < toolbar.getChildCount(); i++) {
				if (toolbar.getChildAt(i) instanceof ActionMenuView) {
					ActionMenuView menu =
							(ActionMenuView) toolbar.getChildAt(i);
					target = menu.getChildAt(menu.getChildCount() - 1);
					break;
				}
			}
			if (target == null) {
				LOG.warning("No Overflow Icon found!");
				return;
			}

			PromptStateChangeListener listener = (prompt, state) -> {
				if (state == STATE_DISMISSED || state == STATE_FINISHED) {
					introductionOnboardingSeen();
				}
			};
			new MaterialTapTargetPrompt.Builder(ConversationActivity.this,
					R.style.OnboardingDialogTheme).setTarget(target)
					.setPrimaryText(R.string.introduction_onboarding_title)
					.setSecondaryText(R.string.introduction_onboarding_text)
					.setIcon(R.drawable.ic_more_vert_accent)
					.setPromptStateChangeListener(listener)
					.show();
		});
	}

	private void introductionOnboardingSeen() {
		runOnDbThread(() -> {
			try {
				Settings settings = new Settings();
				settings.putBoolean(SHOW_ONBOARDING_INTRODUCTION, false);
				settingsManager.mergeSettings(settings, SETTINGS_NAMESPACE);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	@Override
	public void onItemVisible(ConversationItem item) {
		if (!item.isRead()) markMessageRead(item.getGroupId(), item.getId());
	}

	private void markMessageRead(GroupId g, MessageId m) {
		runOnDbThread(() -> {
			try {
				long start = now();
				messagingManager.setReadFlag(g, m, true);
				logDuration(LOG, "Marking read", start);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	@UiThread
	@Override
	public void respondToRequest(ConversationRequestItem item, boolean accept) {
		item.setAnswered();
		int position = adapter.findItemPosition(item);
		if (position != INVALID_POSITION) {
			adapter.notifyItemChanged(position, item);
		}
		runOnDbThread(() -> {
			long timestamp = System.currentTimeMillis();
			timestamp = Math.max(timestamp, getMinTimestampForNewMessage());
			try {
				switch (item.getRequestType()) {
					case INTRODUCTION:
						respondToIntroductionRequest(item.getSessionId(),
								accept, timestamp);
						break;
					case FORUM:
						respondToForumRequest(item.getSessionId(), accept);
						break;
					case BLOG:
						respondToBlogRequest(item.getSessionId(), accept);
						break;
					case GROUP:
						respondToGroupRequest(item.getSessionId(), accept);
						break;
					default:
						throw new IllegalArgumentException(
								"Unknown Request Type");
				}
				loadMessages();
			} catch (ProtocolStateException e) {
				// Action is no longer valid - reloading should solve the issue
				logException(LOG, INFO, e);
			} catch (DbException e) {
				// TODO show an error message
				logException(LOG, WARNING, e);
			}
		});
	}

	@UiThread
	@Override
	public void openRequestedShareable(ConversationRequestItem item) {
		if (item.getRequestedGroupId() == null)
			throw new IllegalArgumentException();
		Intent i;
		switch (item.getRequestType()) {
			case FORUM:
				i = new Intent(this, ForumActivity.class);
				break;
			case BLOG:
				i = new Intent(this, BlogActivity.class);
				break;
			case GROUP:
				i = new Intent(this, GroupActivity.class);
				break;
			default:
				throw new IllegalArgumentException("Unknown Request Type");
		}
		i.putExtra(GROUP_ID, item.getRequestedGroupId().getBytes());
		startActivity(i);
	}

	@DatabaseExecutor
	private void respondToIntroductionRequest(SessionId sessionId,
			boolean accept, long time) throws DbException {
		introductionManager.respondToIntroduction(contactId, sessionId, time,
				accept);
	}

	@DatabaseExecutor
	private void respondToForumRequest(SessionId id, boolean accept)
			throws DbException {
		forumSharingManager.respondToInvitation(contactId, id, accept);
	}

	@DatabaseExecutor
	private void respondToBlogRequest(SessionId id, boolean accept)
			throws DbException {
		blogSharingManager.respondToInvitation(contactId, id, accept);
	}

	@DatabaseExecutor
	private void respondToGroupRequest(SessionId id, boolean accept)
			throws DbException {
		groupInvitationManager.respondToInvitation(contactId, id, accept);
	}

	@Nullable
	@Override
	public String getText(MessageId m) {
		String text = textCache.get(m);
		if (text == null) loadMessageText(m);
		return text;
	}
}
