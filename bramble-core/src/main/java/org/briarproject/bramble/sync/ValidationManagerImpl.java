package org.briarproject.bramble.sync;

import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.NoSuchGroupException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.Service;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageContext;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.ValidationManager;
import org.briarproject.bramble.api.sync.event.MessageAddedEvent;
import org.briarproject.bramble.api.versioning.ClientMajorVersion;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.briarproject.bramble.api.sync.ValidationManager.State.DELIVERED;
import static org.briarproject.bramble.api.sync.ValidationManager.State.INVALID;
import static org.briarproject.bramble.api.sync.ValidationManager.State.PENDING;
import static org.briarproject.bramble.util.LogUtils.logException;

@ThreadSafe
@NotNullByDefault
class ValidationManagerImpl implements ValidationManager, Service,
		EventListener {

	private static final Logger LOG =
			Logger.getLogger(ValidationManagerImpl.class.getName());

	private final DatabaseComponent db;
	private final Executor dbExecutor, validationExecutor;
	private final Map<ClientMajorVersion, MessageValidator> validators;
	private final Map<ClientMajorVersion, IncomingMessageHook> hooks;
	private final AtomicBoolean used = new AtomicBoolean(false);

	@Inject
	ValidationManagerImpl(DatabaseComponent db,
			@DatabaseExecutor Executor dbExecutor,
			@ValidationExecutor Executor validationExecutor) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.validationExecutor = validationExecutor;
		validators = new ConcurrentHashMap<>();
		hooks = new ConcurrentHashMap<>();
	}

	@Override
	public void startService() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		validateOutstandingMessagesAsync();
		deliverOutstandingMessagesAsync();
		shareOutstandingMessagesAsync();
	}

	@Override
	public void stopService() {
	}

	@Override
	public void registerMessageValidator(ClientId c, int majorVersion,
			MessageValidator v) {
		validators.put(new ClientMajorVersion(c, majorVersion), v);
	}

	@Override
	public void registerIncomingMessageHook(ClientId c, int majorVersion,
			IncomingMessageHook hook) {
		hooks.put(new ClientMajorVersion(c, majorVersion), hook);
	}

	private void validateOutstandingMessagesAsync() {
		dbExecutor.execute(this::validateOutstandingMessages);
	}

	@DatabaseExecutor
	private void validateOutstandingMessages() {
		try {
			Queue<MessageId> unvalidated = new LinkedList<>();
			Transaction txn = db.startTransaction(true);
			try {
				unvalidated.addAll(db.getMessagesToValidate(txn));
				db.commitTransaction(txn);
			} finally {
				db.endTransaction(txn);
			}
			validateNextMessageAsync(unvalidated);
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
	}

	private void validateNextMessageAsync(Queue<MessageId> unvalidated) {
		if (unvalidated.isEmpty()) return;
		dbExecutor.execute(() -> validateNextMessage(unvalidated));
	}

	@DatabaseExecutor
	private void validateNextMessage(Queue<MessageId> unvalidated) {
		try {
			Message m;
			Group g;
			Transaction txn = db.startTransaction(true);
			try {
				MessageId id = unvalidated.poll();
				m = db.getMessage(txn, id);
				g = db.getGroup(txn, m.getGroupId());
				db.commitTransaction(txn);
			} finally {
				db.endTransaction(txn);
			}
			validateMessageAsync(m, g);
			validateNextMessageAsync(unvalidated);
		} catch (NoSuchMessageException e) {
			LOG.info("Message removed before validation");
			validateNextMessageAsync(unvalidated);
		} catch (NoSuchGroupException e) {
			LOG.info("Group removed before validation");
			validateNextMessageAsync(unvalidated);
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
	}

	private void deliverOutstandingMessagesAsync() {
		dbExecutor.execute(this::deliverOutstandingMessages);
	}

	@DatabaseExecutor
	private void deliverOutstandingMessages() {
		try {
			Queue<MessageId> pending = new LinkedList<>();
			Transaction txn = db.startTransaction(true);
			try {
				pending.addAll(db.getPendingMessages(txn));
				db.commitTransaction(txn);
			} finally {
				db.endTransaction(txn);
			}
			deliverNextPendingMessageAsync(pending);
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
	}

	private void deliverNextPendingMessageAsync(Queue<MessageId> pending) {
		if (pending.isEmpty()) return;
		dbExecutor.execute(() -> deliverNextPendingMessage(pending));
	}

	@DatabaseExecutor
	private void deliverNextPendingMessage(Queue<MessageId> pending) {
		try {
			boolean anyInvalid = false, allDelivered = true;
			Queue<MessageId> toShare = null;
			Queue<MessageId> invalidate = null;
			Transaction txn = db.startTransaction(false);
			try {
				MessageId id = pending.poll();
				// Check if message is still pending
				if (db.getMessageState(txn, id) == PENDING) {
					// Check if dependencies are valid and delivered
					Map<MessageId, State> states =
							db.getMessageDependencies(txn, id);
					for (Entry<MessageId, State> e : states.entrySet()) {
						if (e.getValue() == INVALID) anyInvalid = true;
						if (e.getValue() != DELIVERED) allDelivered = false;
					}
					if (anyInvalid) {
						invalidateMessage(txn, id);
						invalidate = getDependentsToInvalidate(txn, id);
					} else if (allDelivered) {
						Message m = db.getMessage(txn, id);
						Group g = db.getGroup(txn, m.getGroupId());
						ClientId c = g.getClientId();
						int majorVersion = g.getMajorVersion();
						Metadata meta =
								db.getMessageMetadataForValidator(txn, id);
						DeliveryResult result =
								deliverMessage(txn, m, c, majorVersion, meta);
						if (result.valid) {
							pending.addAll(getPendingDependents(txn, id));
							if (result.share) {
								db.setMessageShared(txn, id);
								toShare = new LinkedList<>(states.keySet());
							}
						} else {
							invalidate = getDependentsToInvalidate(txn, id);
						}
					}
				}
				db.commitTransaction(txn);
			} finally {
				db.endTransaction(txn);
			}
			if (invalidate != null) invalidateNextMessageAsync(invalidate);
			if (toShare != null) shareNextMessageAsync(toShare);
			deliverNextPendingMessageAsync(pending);
		} catch (NoSuchMessageException e) {
			LOG.info("Message removed before delivery");
			deliverNextPendingMessageAsync(pending);
		} catch (NoSuchGroupException e) {
			LOG.info("Group removed before delivery");
			deliverNextPendingMessageAsync(pending);
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
	}

	private void validateMessageAsync(Message m, Group g) {
		validationExecutor.execute(() -> validateMessage(m, g));
	}

	@ValidationExecutor
	private void validateMessage(Message m, Group g) {
		ClientMajorVersion cv =
				new ClientMajorVersion(g.getClientId(), g.getMajorVersion());
		MessageValidator v = validators.get(cv);
		if (v == null) {
			if (LOG.isLoggable(WARNING)) LOG.warning("No validator for " + cv);
		} else {
			try {
				MessageContext context = v.validateMessage(m, g);
				storeMessageContextAsync(m, g.getClientId(),
						g.getMajorVersion(), context);
			} catch (InvalidMessageException e) {
				logException(LOG, INFO, e);
				Queue<MessageId> invalidate = new LinkedList<>();
				invalidate.add(m.getId());
				invalidateNextMessageAsync(invalidate);
			}
		}
	}

	private void storeMessageContextAsync(Message m, ClientId c,
			int majorVersion, MessageContext result) {
		dbExecutor.execute(() ->
				storeMessageContext(m, c, majorVersion, result));
	}

	@DatabaseExecutor
	private void storeMessageContext(Message m, ClientId c, int majorVersion,
			MessageContext context) {
		try {
			MessageId id = m.getId();
			boolean anyInvalid = false, allDelivered = true;
			Queue<MessageId> invalidate = null;
			Queue<MessageId> pending = null;
			Queue<MessageId> toShare = null;
			Transaction txn = db.startTransaction(false);
			try {
				// Check if message has any dependencies
				Collection<MessageId> dependencies = context.getDependencies();
				if (!dependencies.isEmpty()) {
					db.addMessageDependencies(txn, m, dependencies);
					// Check if dependencies are valid and delivered
					Map<MessageId, State> states =
							db.getMessageDependencies(txn, id);
					for (Entry<MessageId, State> e : states.entrySet()) {
						if (e.getValue() == INVALID) anyInvalid = true;
						if (e.getValue() != DELIVERED) allDelivered = false;
					}
				}
				if (anyInvalid) {
					if (db.getMessageState(txn, id) != INVALID) {
						invalidateMessage(txn, id);
						invalidate = getDependentsToInvalidate(txn, id);
					}
				} else {
					Metadata meta = context.getMetadata();
					db.mergeMessageMetadata(txn, id, meta);
					if (allDelivered) {
						DeliveryResult result =
								deliverMessage(txn, m, c, majorVersion, meta);
						if (result.valid) {
							pending = getPendingDependents(txn, id);
							if (result.share) {
								db.setMessageShared(txn, id);
								toShare = new LinkedList<>(dependencies);
							}
						} else {
							invalidate = getDependentsToInvalidate(txn, id);
						}
					} else {
						db.setMessageState(txn, id, PENDING);
					}
				}
				db.commitTransaction(txn);
			} finally {
				db.endTransaction(txn);
			}
			if (invalidate != null) invalidateNextMessageAsync(invalidate);
			if (pending != null) deliverNextPendingMessageAsync(pending);
			if (toShare != null) shareNextMessageAsync(toShare);
		} catch (NoSuchMessageException e) {
			LOG.info("Message removed during validation");
		} catch (NoSuchGroupException e) {
			LOG.info("Group removed during validation");
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
	}

	@DatabaseExecutor
	private DeliveryResult deliverMessage(Transaction txn, Message m,
			ClientId c, int majorVersion, Metadata meta) throws DbException {
		// Deliver the message to the client if it's registered a hook
		boolean shareMsg = false;
		ClientMajorVersion cv = new ClientMajorVersion(c, majorVersion);
		IncomingMessageHook hook = hooks.get(cv);
		if (hook != null) {
			try {
				shareMsg = hook.incomingMessage(txn, m, meta);
			} catch (InvalidMessageException e) {
				invalidateMessage(txn, m.getId());
				return new DeliveryResult(false, false);
			}
		}
		db.setMessageState(txn, m.getId(), DELIVERED);
		return new DeliveryResult(true, shareMsg);
	}

	@DatabaseExecutor
	private Queue<MessageId> getPendingDependents(Transaction txn, MessageId m)
			throws DbException {
		Queue<MessageId> pending = new LinkedList<>();
		Map<MessageId, State> states = db.getMessageDependents(txn, m);
		for (Entry<MessageId, State> e : states.entrySet()) {
			if (e.getValue() == PENDING) pending.add(e.getKey());
		}
		return pending;
	}

	private void shareOutstandingMessagesAsync() {
		dbExecutor.execute(this::shareOutstandingMessages);
	}

	@DatabaseExecutor
	private void shareOutstandingMessages() {
		try {
			Queue<MessageId> toShare = new LinkedList<>();
			Transaction txn = db.startTransaction(true);
			try {
				toShare.addAll(db.getMessagesToShare(txn));
				db.commitTransaction(txn);
			} finally {
				db.endTransaction(txn);
			}
			shareNextMessageAsync(toShare);
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
	}

	/**
	 * Shares the next message from the toShare queue asynchronously.
	 * <p>
	 * This method should only be called for messages that have all their
	 * dependencies delivered and have been delivered themselves.
	 */
	private void shareNextMessageAsync(Queue<MessageId> toShare) {
		if (toShare.isEmpty()) return;
		dbExecutor.execute(() -> shareNextMessage(toShare));
	}

	@DatabaseExecutor
	private void shareNextMessage(Queue<MessageId> toShare) {
		try {
			Transaction txn = db.startTransaction(false);
			try {
				MessageId id = toShare.poll();
				db.setMessageShared(txn, id);
				toShare.addAll(db.getMessageDependencies(txn, id).keySet());
				db.commitTransaction(txn);
			} finally {
				db.endTransaction(txn);
			}
			shareNextMessageAsync(toShare);
		} catch (NoSuchMessageException e) {
			LOG.info("Message removed before sharing");
			shareNextMessageAsync(toShare);
		} catch (NoSuchGroupException e) {
			LOG.info("Group removed before sharing");
			shareNextMessageAsync(toShare);
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
	}

	private void invalidateNextMessageAsync(Queue<MessageId> invalidate) {
		if (invalidate.isEmpty()) return;
		dbExecutor.execute(() -> invalidateNextMessage(invalidate));
	}

	@DatabaseExecutor
	private void invalidateNextMessage(Queue<MessageId> invalidate) {
		try {
			Transaction txn = db.startTransaction(false);
			try {
				MessageId id = invalidate.poll();
				if (db.getMessageState(txn, id) != INVALID) {
					invalidateMessage(txn, id);
					invalidate.addAll(getDependentsToInvalidate(txn, id));
				}
				db.commitTransaction(txn);
			} finally {
				db.endTransaction(txn);
			}
			invalidateNextMessageAsync(invalidate);
		} catch (NoSuchMessageException e) {
			LOG.info("Message removed before invalidation");
			invalidateNextMessageAsync(invalidate);
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
	}

	@DatabaseExecutor
	private void invalidateMessage(Transaction txn, MessageId m)
			throws DbException {
		db.setMessageState(txn, m, INVALID);
		db.deleteMessage(txn, m);
		db.deleteMessageMetadata(txn, m);
	}

	@DatabaseExecutor
	private Queue<MessageId> getDependentsToInvalidate(Transaction txn,
			MessageId m) throws DbException {
		Queue<MessageId> invalidate = new LinkedList<>();
		Map<MessageId, State> states = db.getMessageDependents(txn, m);
		for (Entry<MessageId, State> e : states.entrySet()) {
			if (e.getValue() != INVALID) invalidate.add(e.getKey());
		}
		return invalidate;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof MessageAddedEvent) {
			// Validate the message if it wasn't created locally
			MessageAddedEvent m = (MessageAddedEvent) e;
			if (m.getContactId() != null)
				loadGroupAndValidateAsync(m.getMessage());
		}
	}

	private void loadGroupAndValidateAsync(Message m) {
		dbExecutor.execute(() -> loadGroupAndValidate(m));
	}

	@DatabaseExecutor
	private void loadGroupAndValidate(Message m) {
		try {
			Group g;
			Transaction txn = db.startTransaction(true);
			try {
				g = db.getGroup(txn, m.getGroupId());
				db.commitTransaction(txn);
			} finally {
				db.endTransaction(txn);
			}
			validateMessageAsync(m, g);
		} catch (NoSuchGroupException e) {
			LOG.info("Group removed before validation");
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
	}

	private static class DeliveryResult {

		private final boolean valid, share;

		private DeliveryResult(boolean valid, boolean share) {
			this.valid = valid;
			this.share = share;
		}
	}
}
