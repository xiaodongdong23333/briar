package org.briarproject.briar.sharing;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager.ContactHook;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.sync.Client;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.Group.Visibility;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.MessageStatus;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.api.versioning.ClientVersioningManager.ClientVersioningHook;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.client.SessionId;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.messaging.PrivateRequest;
import org.briarproject.briar.api.sharing.InvitationResponse;
import org.briarproject.briar.api.sharing.Shareable;
import org.briarproject.briar.api.sharing.SharingInvitationItem;
import org.briarproject.briar.api.sharing.SharingManager;
import org.briarproject.briar.client.ConversationClientImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.briar.sharing.MessageType.ABORT;
import static org.briarproject.briar.sharing.MessageType.ACCEPT;
import static org.briarproject.briar.sharing.MessageType.DECLINE;
import static org.briarproject.briar.sharing.MessageType.INVITE;
import static org.briarproject.briar.sharing.MessageType.LEAVE;
import static org.briarproject.briar.sharing.SharingConstants.GROUP_KEY_CONTACT_ID;
import static org.briarproject.briar.sharing.State.SHARING;

@NotNullByDefault
abstract class SharingManagerImpl<S extends Shareable>
		extends ConversationClientImpl
		implements SharingManager<S>, Client, ContactHook,
		ClientVersioningHook {

	private final ClientVersioningManager clientVersioningManager;
	private final MessageParser<S> messageParser;
	private final SessionEncoder sessionEncoder;
	private final SessionParser sessionParser;
	private final ContactGroupFactory contactGroupFactory;
	private final ProtocolEngine<S> engine;
	private final InvitationFactory<S, ?> invitationFactory;

	SharingManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			ClientVersioningManager clientVersioningManager,
			MetadataParser metadataParser, MessageParser<S> messageParser,
			SessionEncoder sessionEncoder, SessionParser sessionParser,
			MessageTracker messageTracker,
			ContactGroupFactory contactGroupFactory, ProtocolEngine<S> engine,
			InvitationFactory<S, ?> invitationFactory) {
		super(db, clientHelper, metadataParser, messageTracker);
		this.clientVersioningManager = clientVersioningManager;
		this.messageParser = messageParser;
		this.sessionEncoder = sessionEncoder;
		this.sessionParser = sessionParser;
		this.contactGroupFactory = contactGroupFactory;
		this.engine = engine;
		this.invitationFactory = invitationFactory;
	}

	protected abstract ClientId getClientId();

	protected abstract int getMajorVersion();

	protected abstract ClientId getShareableClientId();

	protected abstract int getShareableMajorVersion();

	@Override
	public void createLocalState(Transaction txn) throws DbException {
		// Create a local group to indicate that we've set this client up
		Group localGroup = contactGroupFactory.createLocalGroup(getClientId(),
				getMajorVersion());
		if (db.containsGroup(txn, localGroup.getId())) return;
		db.addGroup(txn, localGroup);
		// Set things up for any pre-existing contacts
		for (Contact c : db.getContacts(txn)) addingContact(txn, c);
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		// Create a group to share with the contact
		Group g = getContactGroup(c);
		db.addGroup(txn, g);
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), getClientId(), getMajorVersion());
		db.setGroupVisibility(txn, c.getId(), g.getId(), client);
		// Attach the contact ID to the group
		BdfDictionary meta = new BdfDictionary();
		meta.put(GROUP_KEY_CONTACT_ID, c.getId().getInt());
		try {
			clientHelper.mergeGroupMetadata(txn, g.getId(), meta);
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
	}

	@Override
	public void removingContact(Transaction txn, Contact c) throws DbException {
		// Remove the contact group (all messages will be removed with it)
		db.removeGroup(txn, getContactGroup(c));
	}

	@Override
	public Group getContactGroup(Contact c) {
		return contactGroupFactory.createContactGroup(getClientId(),
				getMajorVersion(), c);
	}

	@Override
	protected boolean incomingMessage(Transaction txn, Message m, BdfList body,
			BdfDictionary d) throws DbException, FormatException {
		// Parse the metadata
		MessageMetadata meta = messageParser.parseMetadata(d);
		// Look up the session, if there is one
		SessionId sessionId = getSessionId(meta.getShareableId());
		StoredSession ss = getSession(txn, m.getGroupId(), sessionId);
		// Handle the message
		Session session;
		MessageId storageId;
		if (ss == null) {
			session = handleFirstMessage(txn, m, body, meta);
			storageId = createStorageId(txn, m.getGroupId());
		} else {
			session = handleMessage(txn, m, body, meta, ss.bdfSession);
			storageId = ss.storageId;
		}
		// Store the updated session
		storeSession(txn, storageId, session);
		return false;
	}

	/**
	 * Adds the given Group and initializes a session between us
	 * and the Contact c in state SHARING.
	 * If a session already exists, this does nothing.
	 */
	void preShareGroup(Transaction txn, Contact c, Group g)
			throws DbException, FormatException {
		// Return if a session already exists with the contact
		GroupId contactGroupId = getContactGroup(c).getId();
		StoredSession existingSession = getSession(txn, contactGroupId,
				getSessionId(g.getId()));
		if (existingSession != null) return;

		// Add the shareable's group
		db.addGroup(txn, g);

		// Apply the client's visibility
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), getShareableClientId(), getShareableMajorVersion());
		db.setGroupVisibility(txn, c.getId(), g.getId(), client);

		// Initialize session in sharing state
		Session session = new Session(SHARING, contactGroupId, g.getId(),
				null, null, 0, 0);
		MessageId storageId = createStorageId(txn, contactGroupId);
		storeSession(txn, storageId, session);
	}

	private SessionId getSessionId(GroupId shareableId) {
		return new SessionId(shareableId.getBytes());
	}

	@Nullable
	private StoredSession getSession(Transaction txn, GroupId contactGroupId,
			SessionId sessionId) throws DbException, FormatException {
		BdfDictionary query = sessionParser.getSessionQuery(sessionId);
		Map<MessageId, BdfDictionary> results = clientHelper
				.getMessageMetadataAsDictionary(txn, contactGroupId, query);
		if (results.size() > 1) throw new DbException();
		if (results.isEmpty()) return null;
		return new StoredSession(results.keySet().iterator().next(),
				results.values().iterator().next());
	}

	private Session handleFirstMessage(Transaction txn, Message m, BdfList body,
			MessageMetadata meta) throws DbException, FormatException {
		GroupId shareableId = meta.getShareableId();
		MessageType type = meta.getMessageType();
		if (type == INVITE) {
			Session session = new Session(m.getGroupId(), shareableId);
			BdfDictionary d = sessionEncoder.encodeSession(session);
			return handleMessage(txn, m, body, meta, d);
		} else {
			throw new FormatException(); // Invalid first message
		}
	}

	private Session handleMessage(Transaction txn, Message m, BdfList body,
			MessageMetadata meta, BdfDictionary d)
			throws DbException, FormatException {
		MessageType type = meta.getMessageType();
		Session session = sessionParser.parseSession(m.getGroupId(), d);
		if (type == INVITE) {
			InviteMessage<S> invite = messageParser.parseInviteMessage(m, body);
			return engine.onInviteMessage(txn, session, invite);
		} else if (type == ACCEPT) {
			AcceptMessage accept = messageParser.parseAcceptMessage(m, body);
			return engine.onAcceptMessage(txn, session, accept);
		} else if (type == DECLINE) {
			DeclineMessage decline = messageParser.parseDeclineMessage(m, body);
			return engine.onDeclineMessage(txn, session, decline);
		} else if (type == LEAVE) {
			LeaveMessage leave = messageParser.parseLeaveMessage(m, body);
			return engine.onLeaveMessage(txn, session, leave);
		} else if (type == ABORT) {
			AbortMessage abort = messageParser.parseAbortMessage(m, body);
			return engine.onAbortMessage(txn, session, abort);
		} else {
			throw new AssertionError();
		}
	}

	private MessageId createStorageId(Transaction txn, GroupId g)
			throws DbException {
		Message m = clientHelper.createMessageForStoringMetadata(g);
		db.addLocalMessage(txn, m, new Metadata(), false);
		return m.getId();
	}

	private void storeSession(Transaction txn, MessageId storageId,
			Session session) throws DbException, FormatException {
		BdfDictionary d = sessionEncoder.encodeSession(session);
		clientHelper.mergeMessageMetadata(txn, storageId, d);
	}

	@Override
	public void sendInvitation(GroupId shareableId, ContactId contactId,
			@Nullable String text, long timestamp) throws DbException {
		SessionId sessionId = getSessionId(shareableId);
		Transaction txn = db.startTransaction(false);
		try {
			Contact contact = db.getContact(txn, contactId);
			if (!canBeShared(txn, shareableId, contact))
				// we might have received an invitation in the meantime
				return;
			// Look up the session, if there is one
			GroupId contactGroupId = getContactGroup(contact).getId();
			StoredSession ss = getSession(txn, contactGroupId, sessionId);
			// Create or parse the session
			Session session;
			MessageId storageId;
			if (ss == null) {
				// This is the first invite - create a new session
				session = new Session(contactGroupId, shareableId);
				storageId = createStorageId(txn, contactGroupId);
			} else {
				// We already have a session
				session = sessionParser
						.parseSession(contactGroupId, ss.bdfSession);
				storageId = ss.storageId;
			}
			// Handle the invite action
			session = engine.onInviteAction(txn, session, text, timestamp);
			// Store the updated session
			storeSession(txn, storageId, session);
			db.commitTransaction(txn);
		} catch (FormatException e) {
			throw new DbException(e);
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public void respondToInvitation(S s, Contact c, boolean accept)
			throws DbException {
		respondToInvitation(c.getId(), getSessionId(s.getId()), accept);
	}

	@Override
	public void respondToInvitation(ContactId c, SessionId id, boolean accept)
			throws DbException {
		Transaction txn = db.startTransaction(false);
		try {
			// Look up the session
			Contact contact = db.getContact(txn, c);
			GroupId contactGroupId = getContactGroup(contact).getId();
			StoredSession ss = getSession(txn, contactGroupId, id);
			if (ss == null) throw new IllegalArgumentException();
			// Parse the session
			Session session =
					sessionParser.parseSession(contactGroupId, ss.bdfSession);
			// Handle the accept or decline action
			if (accept) session = engine.onAcceptAction(txn, session);
			else session = engine.onDeclineAction(txn, session);
			// Store the updated session
			storeSession(txn, ss.storageId, session);
			db.commitTransaction(txn);
		} catch (FormatException e) {
			throw new DbException(e);
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public Collection<PrivateMessageHeader> getMessageHeaders(Transaction txn,
			ContactId c) throws DbException {
		try {
			Contact contact = db.getContact(txn, c);
			GroupId contactGroupId = getContactGroup(contact).getId();
			BdfDictionary query = messageParser.getMessagesVisibleInUiQuery();
			Map<MessageId, BdfDictionary> results = clientHelper
					.getMessageMetadataAsDictionary(txn, contactGroupId, query);
			Collection<PrivateMessageHeader> messages =
					new ArrayList<>(results.size());
			for (Entry<MessageId, BdfDictionary> e : results.entrySet()) {
				MessageId m = e.getKey();
				MessageMetadata meta =
						messageParser.parseMetadata(e.getValue());
				MessageStatus status = db.getMessageStatus(txn, c, m);
				MessageType type = meta.getMessageType();
				if (type == INVITE) {
					messages.add(parseInvitationRequest(txn, c, m,
							meta, status));
				} else if (type == ACCEPT) {
					messages.add(parseInvitationResponse(contactGroupId, m,
							meta, status, true));
				} else if (type == DECLINE) {
					messages.add(parseInvitationResponse(contactGroupId, m,
							meta, status, false));
				}
			}
			return messages;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private PrivateRequest<S> parseInvitationRequest(Transaction txn,
			ContactId c, MessageId m, MessageMetadata meta,
			MessageStatus status) throws DbException, FormatException {
		// Look up the invite message to get the details of the private group
		InviteMessage<S> invite = messageParser.getInviteMessage(txn, m);
		// Find out whether the shareable can be opened
		boolean canBeOpened = meta.wasAccepted() &&
				db.containsGroup(txn, invite.getShareableId());
		return invitationFactory
				.createInvitationRequest(meta.isLocal(), status.isSent(),
						status.isSeen(), meta.isRead(), invite, c,
						meta.isAvailableToAnswer(), canBeOpened);
	}

	private InvitationResponse parseInvitationResponse(GroupId contactGroupId,
			MessageId m, MessageMetadata meta, MessageStatus status,
			boolean accept) {
		return invitationFactory.createInvitationResponse(m, contactGroupId,
				meta.getTimestamp(), meta.isLocal(), status.isSent(),
				status.isSeen(), meta.isRead(), accept, meta.getShareableId());
	}

	@Override
	public Collection<SharingInvitationItem> getInvitations()
			throws DbException {
		List<SharingInvitationItem> items = new ArrayList<>();
		BdfDictionary query = messageParser.getInvitesAvailableToAnswerQuery();
		Map<S, Collection<Contact>> sharers = new HashMap<>();
		Transaction txn = db.startTransaction(true);
		try {
			// get invitations from each contact
			for (Contact c : db.getContacts(txn)) {
				GroupId contactGroupId = getContactGroup(c).getId();
				Map<MessageId, BdfDictionary> results =
						clientHelper.getMessageMetadataAsDictionary(txn,
								contactGroupId, query);
				for (MessageId m : results.keySet()) {
					InviteMessage<S> invite =
							messageParser.getInviteMessage(txn, m);
					S s = invite.getShareable();
					if (sharers.containsKey(s)) {
						sharers.get(s).add(c);
					} else {
						Collection<Contact> contacts = new ArrayList<>();
						contacts.add(c);
						sharers.put(s, contacts);
					}
				}
			}
			// construct the invitation items
			for (Entry<S, Collection<Contact>> e : sharers.entrySet()) {
				S s = e.getKey();
				Collection<Contact> contacts = e.getValue();
				boolean subscribed = db.containsGroup(txn, s.getId());
				SharingInvitationItem invitation =
						new SharingInvitationItem(s, subscribed, contacts);
				items.add(invitation);
			}
			db.commitTransaction(txn);
			return items;
		} catch (FormatException e) {
			throw new DbException(e);
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public Collection<Contact> getSharedWith(GroupId g) throws DbException {
		// TODO report also pending invitations
		Collection<Contact> contacts = new ArrayList<>();
		Transaction txn = db.startTransaction(true);
		try {
			for (Contact c : db.getContacts(txn)) {
				if (db.getGroupVisibility(txn, c.getId(), g) == SHARED)
					contacts.add(c);
			}
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
		return contacts;
	}

	@Override
	public boolean canBeShared(GroupId g, Contact c) throws DbException {
		Transaction txn = db.startTransaction(true);
		try {
			boolean canBeShared = canBeShared(txn, g, c);
			db.commitTransaction(txn);
			return canBeShared;
		} finally {
			db.endTransaction(txn);
		}
	}

	private boolean canBeShared(Transaction txn, GroupId g, Contact c)
			throws DbException {
		// The group can't be shared unless the contact supports the client
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), getShareableClientId(), getShareableMajorVersion());
		if (client != SHARED) return false;
		GroupId contactGroupId = getContactGroup(c).getId();
		SessionId sessionId = getSessionId(g);
		try {
			StoredSession ss = getSession(txn, contactGroupId, sessionId);
			// If there's no session, we can share the group with the contact
			if (ss == null) return true;
			// If the session's in the right state, the contact can be invited
			Session session =
					sessionParser.parseSession(contactGroupId, ss.bdfSession);
			return session.getState().canInvite();
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	void removingShareable(Transaction txn, S shareable) throws DbException {
		SessionId sessionId = getSessionId(shareable.getId());
		// If we have any sessions in progress, tell the contacts we're leaving
		try {
			for (Contact c : db.getContacts(txn)) {
				// Look up the session for the contact, if there is one
				GroupId contactGroupId = getContactGroup(c).getId();
				StoredSession ss = getSession(txn, contactGroupId, sessionId);
				if (ss == null) continue; // No session for this contact
				// Let the engine perform a LEAVE action
				Session session = sessionParser
						.parseSession(contactGroupId, ss.bdfSession);
				session = engine.onLeaveAction(txn, session);
				// Store the updated session
				storeSession(txn, ss.storageId, session);
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void onClientVisibilityChanging(Transaction txn, Contact c,
			Visibility v) throws DbException {
		// Apply the client's visibility to the contact group
		Group g = getContactGroup(c);
		db.setGroupVisibility(txn, c.getId(), g.getId(), v);
	}

	ClientVersioningHook getShareableClientVersioningHook() {
		return this::onShareableClientVisibilityChanging;
	}

	// Versioning hook for the shareable client
	private void onShareableClientVisibilityChanging(Transaction txn, Contact c,
			Visibility client) throws DbException {
		try {
			Collection<Group> shareables = db.getGroups(txn,
					getShareableClientId(), getShareableMajorVersion());
			Map<GroupId, Visibility> m = getPreferredVisibilities(txn, c);
			for (Group g : shareables) {
				Visibility preferred = m.get(g.getId());
				if (preferred == null) continue; // No session for this group
				// Apply min of preferred visibility and client's visibility
				Visibility min = Visibility.min(preferred, client);
				db.setGroupVisibility(txn, c.getId(), g.getId(), min);
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private Map<GroupId, Visibility> getPreferredVisibilities(Transaction txn,
			Contact c) throws DbException, FormatException {
		GroupId contactGroupId = getContactGroup(c).getId();
		BdfDictionary query = sessionParser.getAllSessionsQuery();
		Map<MessageId, BdfDictionary> results = clientHelper
				.getMessageMetadataAsDictionary(txn, contactGroupId, query);
		Map<GroupId, Visibility> m = new HashMap<>();
		for (BdfDictionary d : results.values()) {
			Session s = sessionParser.parseSession(contactGroupId, d);
			m.put(s.getShareableId(), s.getState().getVisibility());
		}
		return m;
	}

	private static class StoredSession {

		private final MessageId storageId;
		private final BdfDictionary bdfSession;

		private StoredSession(MessageId storageId, BdfDictionary bdfSession) {
			this.storageId = storageId;
			this.bdfSession = bdfSession;
		}
	}

}
