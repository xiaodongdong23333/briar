package org.briarproject.bramble.db;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.event.ContactAddedEvent;
import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.contact.event.ContactStatusChangedEvent;
import org.briarproject.bramble.api.contact.event.ContactVerifiedEvent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.ContactExistsException;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbCallable;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.DbRunnable;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.MigrationListener;
import org.briarproject.bramble.api.db.NoSuchContactException;
import org.briarproject.bramble.api.db.NoSuchGroupException;
import org.briarproject.bramble.api.db.NoSuchLocalAuthorException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.NoSuchTransportException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.identity.event.LocalAuthorAddedEvent;
import org.briarproject.bramble.api.identity.event.LocalAuthorRemovedEvent;
import org.briarproject.bramble.api.lifecycle.ShutdownManager;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.event.SettingsUpdatedEvent;
import org.briarproject.bramble.api.sync.Ack;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.Group.Visibility;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.MessageStatus;
import org.briarproject.bramble.api.sync.Offer;
import org.briarproject.bramble.api.sync.Request;
import org.briarproject.bramble.api.sync.ValidationManager.State;
import org.briarproject.bramble.api.sync.event.GroupAddedEvent;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import org.briarproject.bramble.api.sync.event.GroupVisibilityUpdatedEvent;
import org.briarproject.bramble.api.sync.event.MessageAddedEvent;
import org.briarproject.bramble.api.sync.event.MessageRequestedEvent;
import org.briarproject.bramble.api.sync.event.MessageSharedEvent;
import org.briarproject.bramble.api.sync.event.MessageStateChangedEvent;
import org.briarproject.bramble.api.sync.event.MessageToAckEvent;
import org.briarproject.bramble.api.sync.event.MessageToRequestEvent;
import org.briarproject.bramble.api.sync.event.MessagesAckedEvent;
import org.briarproject.bramble.api.sync.event.MessagesSentEvent;
import org.briarproject.bramble.api.transport.KeySet;
import org.briarproject.bramble.api.transport.KeySetId;
import org.briarproject.bramble.api.transport.TransportKeys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.logging.Level.WARNING;
import static org.briarproject.bramble.api.sync.Group.Visibility.INVISIBLE;
import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.bramble.api.sync.ValidationManager.State.DELIVERED;
import static org.briarproject.bramble.api.sync.ValidationManager.State.UNKNOWN;
import static org.briarproject.bramble.db.DatabaseConstants.MAX_OFFERED_MESSAGES;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.LogUtils.now;

@ThreadSafe
@NotNullByDefault
class DatabaseComponentImpl<T> implements DatabaseComponent {

	private static final Logger LOG =
			Logger.getLogger(DatabaseComponentImpl.class.getName());

	private final Database<T> db;
	private final Class<T> txnClass;
	private final EventBus eventBus;
	private final ShutdownManager shutdown;
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private final ReentrantReadWriteLock lock =
			new ReentrantReadWriteLock(true);

	@Inject
	DatabaseComponentImpl(Database<T> db, Class<T> txnClass, EventBus eventBus,
			ShutdownManager shutdown) {
		this.db = db;
		this.txnClass = txnClass;
		this.eventBus = eventBus;
		this.shutdown = shutdown;
	}

	@Override
	public boolean open(SecretKey key, @Nullable MigrationListener listener)
			throws DbException {
		boolean reopened = db.open(key, listener);
		shutdown.addShutdownHook(() -> {
			try {
				close();
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
		return reopened;
	}

	@Override
	public void close() throws DbException {
		if (closed.getAndSet(true)) return;
		db.close();
	}

	@Override
	public Transaction startTransaction(boolean readOnly) throws DbException {
		// Don't allow reentrant locking
		if (lock.getReadHoldCount() > 0) throw new IllegalStateException();
		if (lock.getWriteHoldCount() > 0) throw new IllegalStateException();
		long start = now();
		if (readOnly) {
			lock.readLock().lock();
			logDuration(LOG, "Waiting for read lock", start);
		} else {
			lock.writeLock().lock();
			logDuration(LOG, "Waiting for write lock", start);
		}
		try {
			return new Transaction(db.startTransaction(), readOnly);
		} catch (DbException | RuntimeException e) {
			if (readOnly) lock.readLock().unlock();
			else lock.writeLock().unlock();
			throw e;
		}
	}

	@Override
	public void commitTransaction(Transaction transaction) throws DbException {
		T txn = txnClass.cast(transaction.unbox());
		if (transaction.isCommitted()) throw new IllegalStateException();
		transaction.setCommitted();
		db.commitTransaction(txn);
	}

	@Override
	public void endTransaction(Transaction transaction) {
		try {
			T txn = txnClass.cast(transaction.unbox());
			if (!transaction.isCommitted()) db.abortTransaction(txn);
		} finally {
			if (transaction.isReadOnly()) lock.readLock().unlock();
			else lock.writeLock().unlock();
		}
		if (transaction.isCommitted())
			for (Event e : transaction.getEvents()) eventBus.broadcast(e);
	}

	@Override
	public <E extends Exception> void transaction(boolean readOnly,
			DbRunnable<E> task) throws DbException, E {
		Transaction txn = startTransaction(readOnly);
		try {
			task.run(txn);
			commitTransaction(txn);
		} finally {
			endTransaction(txn);
		}
	}

	@Override
	public <R, E extends Exception> R transactionWithResult(boolean readOnly,
			DbCallable<R, E> task) throws DbException, E {
		Transaction txn = startTransaction(readOnly);
		try {
			R result = task.call(txn);
			commitTransaction(txn);
			return result;
		} finally {
			endTransaction(txn);
		}
	}

	private T unbox(Transaction transaction) {
		if (transaction.isCommitted()) throw new IllegalStateException();
		return txnClass.cast(transaction.unbox());
	}

	@Override
	public ContactId addContact(Transaction transaction, Author remote,
			AuthorId local, boolean verified, boolean active)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsLocalAuthor(txn, local))
			throw new NoSuchLocalAuthorException();
		if (db.containsLocalAuthor(txn, remote.getId()))
			throw new ContactExistsException();
		if (db.containsContact(txn, remote.getId(), local))
			throw new ContactExistsException();
		ContactId c = db.addContact(txn, remote, local, verified, active);
		transaction.attach(new ContactAddedEvent(c, active));
		if (active) transaction.attach(new ContactStatusChangedEvent(c, true));
		return c;
	}

	@Override
	public void addGroup(Transaction transaction, Group g) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsGroup(txn, g.getId())) {
			db.addGroup(txn, g);
			transaction.attach(new GroupAddedEvent(g));
		}
	}

	@Override
	public void addLocalAuthor(Transaction transaction, LocalAuthor a)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsLocalAuthor(txn, a.getId())) {
			db.addLocalAuthor(txn, a);
			transaction.attach(new LocalAuthorAddedEvent(a.getId()));
		}
	}

	@Override
	public void addLocalMessage(Transaction transaction, Message m,
			Metadata meta, boolean shared) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsGroup(txn, m.getGroupId()))
			throw new NoSuchGroupException();
		if (!db.containsMessage(txn, m.getId())) {
			db.addMessage(txn, m, DELIVERED, shared, null);
			transaction.attach(new MessageAddedEvent(m, null));
			transaction.attach(new MessageStateChangedEvent(m.getId(), true,
					DELIVERED));
			if (shared) transaction.attach(new MessageSharedEvent(m.getId()));
		}
		db.mergeMessageMetadata(txn, m.getId(), meta);
	}

	@Override
	public void addTransport(Transaction transaction, TransportId t,
			int maxLatency) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsTransport(txn, t))
			db.addTransport(txn, t, maxLatency);
	}

	@Override
	public KeySetId addTransportKeys(Transaction transaction, ContactId c,
			TransportKeys k) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		if (!db.containsTransport(txn, k.getTransportId()))
			throw new NoSuchTransportException();
		return db.addTransportKeys(txn, c, k);
	}

	@Override
	public boolean containsContact(Transaction transaction, AuthorId remote,
			AuthorId local) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsLocalAuthor(txn, local))
			throw new NoSuchLocalAuthorException();
		return db.containsContact(txn, remote, local);
	}

	@Override
	public boolean containsGroup(Transaction transaction, GroupId g)
			throws DbException {
		T txn = unbox(transaction);
		return db.containsGroup(txn, g);
	}

	@Override
	public boolean containsLocalAuthor(Transaction transaction, AuthorId local)
			throws DbException {
		T txn = unbox(transaction);
		return db.containsLocalAuthor(txn, local);
	}

	@Override
	public void deleteMessage(Transaction transaction, MessageId m)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		db.deleteMessage(txn, m);
	}

	@Override
	public void deleteMessageMetadata(Transaction transaction, MessageId m)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		db.deleteMessageMetadata(txn, m);
	}

	@Nullable
	@Override
	public Ack generateAck(Transaction transaction, ContactId c,
			int maxMessages) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		Collection<MessageId> ids = db.getMessagesToAck(txn, c, maxMessages);
		if (ids.isEmpty()) return null;
		db.lowerAckFlag(txn, c, ids);
		return new Ack(ids);
	}

	@Nullable
	@Override
	public Collection<Message> generateBatch(Transaction transaction,
			ContactId c, int maxLength, int maxLatency) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		Collection<MessageId> ids =
				db.getMessagesToSend(txn, c, maxLength, maxLatency);
		List<Message> messages = new ArrayList<>(ids.size());
		for (MessageId m : ids) {
			messages.add(db.getMessage(txn, m));
			db.updateExpiryTimeAndEta(txn, c, m, maxLatency);
		}
		if (ids.isEmpty()) return null;
		db.lowerRequestedFlag(txn, c, ids);
		transaction.attach(new MessagesSentEvent(c, ids));
		return messages;
	}

	@Nullable
	@Override
	public Offer generateOffer(Transaction transaction, ContactId c,
			int maxMessages, int maxLatency) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		Collection<MessageId> ids =
				db.getMessagesToOffer(txn, c, maxMessages, maxLatency);
		if (ids.isEmpty()) return null;
		for (MessageId m : ids)
			db.updateExpiryTimeAndEta(txn, c, m, maxLatency);
		return new Offer(ids);
	}

	@Nullable
	@Override
	public Request generateRequest(Transaction transaction, ContactId c,
			int maxMessages) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		Collection<MessageId> ids = db.getMessagesToRequest(txn, c,
				maxMessages);
		if (ids.isEmpty()) return null;
		db.removeOfferedMessages(txn, c, ids);
		return new Request(ids);
	}

	@Nullable
	@Override
	public Collection<Message> generateRequestedBatch(Transaction transaction,
			ContactId c, int maxLength, int maxLatency) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		Collection<MessageId> ids =
				db.getRequestedMessagesToSend(txn, c, maxLength, maxLatency);
		List<Message> messages = new ArrayList<>(ids.size());
		for (MessageId m : ids) {
			messages.add(db.getMessage(txn, m));
			db.updateExpiryTimeAndEta(txn, c, m, maxLatency);
		}
		if (ids.isEmpty()) return null;
		db.lowerRequestedFlag(txn, c, ids);
		transaction.attach(new MessagesSentEvent(c, ids));
		return messages;
	}

	@Override
	public Contact getContact(Transaction transaction, ContactId c)
			throws DbException {
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		return db.getContact(txn, c);
	}

	@Override
	public Collection<Contact> getContacts(Transaction transaction)
			throws DbException {
		T txn = unbox(transaction);
		return db.getContacts(txn);
	}

	@Override
	public Collection<Contact> getContactsByAuthorId(Transaction transaction,
			AuthorId remote) throws DbException {
		T txn = unbox(transaction);
		return db.getContactsByAuthorId(txn, remote);
	}

	@Override
	public Collection<ContactId> getContacts(Transaction transaction,
			AuthorId a) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsLocalAuthor(txn, a))
			throw new NoSuchLocalAuthorException();
		return db.getContacts(txn, a);
	}

	@Override
	public Group getGroup(Transaction transaction, GroupId g)
			throws DbException {
		T txn = unbox(transaction);
		if (!db.containsGroup(txn, g))
			throw new NoSuchGroupException();
		return db.getGroup(txn, g);
	}

	@Override
	public Metadata getGroupMetadata(Transaction transaction, GroupId g)
			throws DbException {
		T txn = unbox(transaction);
		if (!db.containsGroup(txn, g))
			throw new NoSuchGroupException();
		return db.getGroupMetadata(txn, g);
	}

	@Override
	public Collection<Group> getGroups(Transaction transaction, ClientId c,
			int majorVersion) throws DbException {
		T txn = unbox(transaction);
		return db.getGroups(txn, c, majorVersion);
	}

	@Override
	public Visibility getGroupVisibility(Transaction transaction, ContactId c,
			GroupId g) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		return db.getGroupVisibility(txn, c, g);
	}

	@Override
	public LocalAuthor getLocalAuthor(Transaction transaction, AuthorId a)
			throws DbException {
		T txn = unbox(transaction);
		if (!db.containsLocalAuthor(txn, a))
			throw new NoSuchLocalAuthorException();
		return db.getLocalAuthor(txn, a);
	}

	@Override
	public Collection<LocalAuthor> getLocalAuthors(Transaction transaction)
			throws DbException {
		T txn = unbox(transaction);
		return db.getLocalAuthors(txn);
	}

	@Override
	public Message getMessage(Transaction transaction, MessageId m)
			throws DbException {
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		return db.getMessage(txn, m);
	}

	@Override
	public Collection<MessageId> getMessageIds(Transaction transaction,
			GroupId g) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsGroup(txn, g))
			throw new NoSuchGroupException();
		return db.getMessageIds(txn, g);
	}

	@Override
	public Collection<MessageId> getMessagesToValidate(Transaction transaction)
			throws DbException {
		T txn = unbox(transaction);
		return db.getMessagesToValidate(txn);
	}

	@Override
	public Collection<MessageId> getPendingMessages(Transaction transaction)
			throws DbException {
		T txn = unbox(transaction);
		return db.getPendingMessages(txn);
	}

	@Override
	public Collection<MessageId> getMessagesToShare(Transaction transaction)
			throws DbException {
		T txn = unbox(transaction);
		return db.getMessagesToShare(txn);
	}

	@Override
	public Map<MessageId, Metadata> getMessageMetadata(Transaction transaction,
			GroupId g) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsGroup(txn, g))
			throw new NoSuchGroupException();
		return db.getMessageMetadata(txn, g);
	}

	@Override
	public Map<MessageId, Metadata> getMessageMetadata(Transaction transaction,
			GroupId g, Metadata query) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsGroup(txn, g))
			throw new NoSuchGroupException();
		return db.getMessageMetadata(txn, g, query);
	}

	@Override
	public Metadata getMessageMetadata(Transaction transaction, MessageId m)
			throws DbException {
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		return db.getMessageMetadata(txn, m);
	}

	@Override
	public Metadata getMessageMetadataForValidator(Transaction transaction,
			MessageId m)
			throws DbException {
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		return db.getMessageMetadataForValidator(txn, m);
	}

	@Override
	public State getMessageState(Transaction transaction, MessageId m)
			throws DbException {
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		return db.getMessageState(txn, m);
	}

	@Override
	public Collection<MessageStatus> getMessageStatus(Transaction transaction,
			ContactId c, GroupId g) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		if (!db.containsGroup(txn, g))
			throw new NoSuchGroupException();
		if (db.getGroupVisibility(txn, c, g) == INVISIBLE) {
			// No status rows exist - return default statuses
			Collection<MessageStatus> statuses = new ArrayList<>();
			for (MessageId m : db.getMessageIds(txn, g))
				statuses.add(new MessageStatus(m, c, false, false));
			return statuses;
		}
		return db.getMessageStatus(txn, c, g);
	}

	@Override
	public MessageStatus getMessageStatus(Transaction transaction, ContactId c,
			MessageId m) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		MessageStatus status = db.getMessageStatus(txn, c, m);
		if (status == null) return new MessageStatus(m, c, false, false);
		return status;
	}

	@Override
	public Map<MessageId, State> getMessageDependencies(Transaction transaction,
			MessageId m) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		return db.getMessageDependencies(txn, m);
	}

	@Override
	public Map<MessageId, State> getMessageDependents(Transaction transaction,
			MessageId m) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		return db.getMessageDependents(txn, m);
	}

	@Override
	public long getNextSendTime(Transaction transaction, ContactId c)
			throws DbException {
		T txn = unbox(transaction);
		return db.getNextSendTime(txn, c);
	}

	@Override
	public Settings getSettings(Transaction transaction, String namespace)
			throws DbException {
		T txn = unbox(transaction);
		return db.getSettings(txn, namespace);
	}

	@Override
	public Collection<KeySet> getTransportKeys(Transaction transaction,
			TransportId t) throws DbException {
		T txn = unbox(transaction);
		if (!db.containsTransport(txn, t))
			throw new NoSuchTransportException();
		return db.getTransportKeys(txn, t);
	}

	@Override
	public void incrementStreamCounter(Transaction transaction, TransportId t,
			KeySetId k) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsTransport(txn, t))
			throw new NoSuchTransportException();
		db.incrementStreamCounter(txn, t, k);
	}

	@Override
	public void mergeGroupMetadata(Transaction transaction, GroupId g,
			Metadata meta) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsGroup(txn, g))
			throw new NoSuchGroupException();
		db.mergeGroupMetadata(txn, g, meta);
	}

	@Override
	public void mergeMessageMetadata(Transaction transaction, MessageId m,
			Metadata meta) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		db.mergeMessageMetadata(txn, m, meta);
	}

	@Override
	public void mergeSettings(Transaction transaction, Settings s,
			String namespace) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		Settings old = db.getSettings(txn, namespace);
		Settings merged = new Settings();
		merged.putAll(old);
		merged.putAll(s);
		if (!merged.equals(old)) {
			db.mergeSettings(txn, s, namespace);
			transaction.attach(new SettingsUpdatedEvent(namespace, merged));
		}
	}

	@Override
	public void receiveAck(Transaction transaction, ContactId c, Ack a)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		Collection<MessageId> acked = new ArrayList<>();
		for (MessageId m : a.getMessageIds()) {
			if (db.containsVisibleMessage(txn, c, m)) {
				db.raiseSeenFlag(txn, c, m);
				acked.add(m);
			}
		}
		if (acked.size() > 0) {
			transaction.attach(new MessagesAckedEvent(c, acked));
		}
	}

	@Override
	public void receiveMessage(Transaction transaction, ContactId c, Message m)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		if (db.getGroupVisibility(txn, c, m.getGroupId()) != INVISIBLE) {
			if (db.containsMessage(txn, m.getId())) {
				db.raiseSeenFlag(txn, c, m.getId());
				db.raiseAckFlag(txn, c, m.getId());
			} else {
				db.addMessage(txn, m, UNKNOWN, false, c);
				transaction.attach(new MessageAddedEvent(m, c));
			}
			transaction.attach(new MessageToAckEvent(c));
		}
	}

	@Override
	public void receiveOffer(Transaction transaction, ContactId c, Offer o)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		boolean ack = false, request = false;
		int count = db.countOfferedMessages(txn, c);
		for (MessageId m : o.getMessageIds()) {
			if (db.containsVisibleMessage(txn, c, m)) {
				db.raiseSeenFlag(txn, c, m);
				db.raiseAckFlag(txn, c, m);
				ack = true;
			} else if (count < MAX_OFFERED_MESSAGES) {
				db.addOfferedMessage(txn, c, m);
				request = true;
				count++;
			}
		}
		if (ack) transaction.attach(new MessageToAckEvent(c));
		if (request) transaction.attach(new MessageToRequestEvent(c));
	}

	@Override
	public void receiveRequest(Transaction transaction, ContactId c, Request r)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		boolean requested = false;
		for (MessageId m : r.getMessageIds()) {
			if (db.containsVisibleMessage(txn, c, m)) {
				db.raiseRequestedFlag(txn, c, m);
				db.resetExpiryTime(txn, c, m);
				requested = true;
			}
		}
		if (requested) transaction.attach(new MessageRequestedEvent(c));
	}

	@Override
	public void removeContact(Transaction transaction, ContactId c)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		db.removeContact(txn, c);
		transaction.attach(new ContactRemovedEvent(c));
	}

	@Override
	public void removeGroup(Transaction transaction, Group g)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		GroupId id = g.getId();
		if (!db.containsGroup(txn, id))
			throw new NoSuchGroupException();
		Collection<ContactId> affected =
				db.getGroupVisibility(txn, id).keySet();
		db.removeGroup(txn, id);
		transaction.attach(new GroupRemovedEvent(g));
		transaction.attach(new GroupVisibilityUpdatedEvent(affected));
	}

	@Override
	public void removeLocalAuthor(Transaction transaction, AuthorId a)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsLocalAuthor(txn, a))
			throw new NoSuchLocalAuthorException();
		db.removeLocalAuthor(txn, a);
		transaction.attach(new LocalAuthorRemovedEvent(a));
	}

	@Override
	public void removeMessage(Transaction transaction, MessageId m)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		// TODO: Don't allow messages with dependents to be removed
		db.removeMessage(txn, m);
	}

	@Override
	public void removeTransport(Transaction transaction, TransportId t)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsTransport(txn, t))
			throw new NoSuchTransportException();
		db.removeTransport(txn, t);
	}

	@Override
	public void removeTransportKeys(Transaction transaction,
			TransportId t, KeySetId k) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsTransport(txn, t))
			throw new NoSuchTransportException();
		db.removeTransportKeys(txn, t, k);
	}

	@Override
	public void setContactVerified(Transaction transaction, ContactId c)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		db.setContactVerified(txn, c);
		transaction.attach(new ContactVerifiedEvent(c));
	}

	@Override
	public void setContactActive(Transaction transaction, ContactId c,
			boolean active) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		db.setContactActive(txn, c, active);
		transaction.attach(new ContactStatusChangedEvent(c, active));
	}

	@Override
	public void setGroupVisibility(Transaction transaction, ContactId c,
			GroupId g, Visibility v) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsContact(txn, c))
			throw new NoSuchContactException();
		if (!db.containsGroup(txn, g))
			throw new NoSuchGroupException();
		Visibility old = db.getGroupVisibility(txn, c, g);
		if (old == v) return;
		if (old == INVISIBLE) db.addGroupVisibility(txn, c, g, v == SHARED);
		else if (v == INVISIBLE) db.removeGroupVisibility(txn, c, g);
		else db.setGroupVisibility(txn, c, g, v == SHARED);
		List<ContactId> affected = Collections.singletonList(c);
		transaction.attach(new GroupVisibilityUpdatedEvent(affected));
	}

	@Override
	public void setMessageShared(Transaction transaction, MessageId m)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		if (db.getMessageState(txn, m) != DELIVERED)
			throw new IllegalArgumentException(
					"Shared undelivered message");
		db.setMessageShared(txn, m);
		transaction.attach(new MessageSharedEvent(m));
	}

	@Override
	public void setMessageState(Transaction transaction, MessageId m,
			State state) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, m))
			throw new NoSuchMessageException();
		db.setMessageState(txn, m, state);
		transaction.attach(new MessageStateChangedEvent(m, false, state));
	}

	@Override
	public void addMessageDependencies(Transaction transaction,
			Message dependent, Collection<MessageId> dependencies)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsMessage(txn, dependent.getId()))
			throw new NoSuchMessageException();
		State dependentState = db.getMessageState(txn, dependent.getId());
		for (MessageId dependency : dependencies) {
			db.addMessageDependency(txn, dependent, dependency,
					dependentState);
		}
	}

	@Override
	public void setReorderingWindow(Transaction transaction, KeySetId k,
			TransportId t, long rotationPeriod, long base, byte[] bitmap)
			throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsTransport(txn, t))
			throw new NoSuchTransportException();
		db.setReorderingWindow(txn, k, t, rotationPeriod, base, bitmap);
	}

	@Override
	public void setTransportKeysActive(Transaction transaction, TransportId t,
			KeySetId k) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		if (!db.containsTransport(txn, t))
			throw new NoSuchTransportException();
		db.setTransportKeysActive(txn, t, k);
	}

	@Override
	public void updateTransportKeys(Transaction transaction,
			Collection<KeySet> keys) throws DbException {
		if (transaction.isReadOnly()) throw new IllegalArgumentException();
		T txn = unbox(transaction);
		for (KeySet ks : keys) {
			TransportId t = ks.getTransportKeys().getTransportId();
			if (db.containsTransport(txn, t))
				db.updateTransportKeys(txn, ks);
		}
	}
}
