package org.briarproject.bramble.api.db;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.settings.Settings;
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
import org.briarproject.bramble.api.transport.KeySet;
import org.briarproject.bramble.api.transport.KeySetId;
import org.briarproject.bramble.api.transport.TransportKeys;

import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

import static org.briarproject.bramble.api.sync.ValidationManager.State;

/**
 * Encapsulates the database implementation and exposes high-level operations
 * to other components.
 */
@NotNullByDefault
public interface DatabaseComponent {

	/**
	 * Opens the database and returns true if the database already existed.
	 *
	 * @throws DataTooNewException if the data uses a newer schema than the
	 * current code
	 * @throws DataTooOldException if the data uses an older schema than the
	 * current code and cannot be migrated
	 */
	boolean open(SecretKey key, @Nullable MigrationListener listener)
			throws DbException;

	/**
	 * Waits for any open transactions to finish and closes the database.
	 */
	void close() throws DbException;

	/**
	 * Starts a new transaction and returns an object representing it.
	 * <p/>
	 * This method acquires locks, so it must not be called while holding a
	 * lock.
	 *
	 * @param readOnly true if the transaction will only be used for reading.
	 */
	Transaction startTransaction(boolean readOnly) throws DbException;

	/**
	 * Commits a transaction to the database.
	 */
	void commitTransaction(Transaction txn) throws DbException;

	/**
	 * Ends a transaction. If the transaction has not been committed,
	 * it will be aborted. If the transaction has been committed,
	 * any events attached to the transaction are broadcast.
	 * The database lock will be released in either case.
	 */
	void endTransaction(Transaction txn);

	/**
	 * Runs the given task within a transaction.
	 */
	<E extends Exception> void transaction(boolean readOnly,
			DbRunnable<E> task) throws DbException, E;

	/**
	 * Runs the given task within a transaction and returns the result of the
	 * task.
	 */
	<R, E extends Exception> R transactionWithResult(boolean readOnly,
			DbCallable<R, E> task) throws DbException, E;

	/**
	 * Stores a contact associated with the given local and remote pseudonyms,
	 * and returns an ID for the contact.
	 */
	ContactId addContact(Transaction txn, Author remote, AuthorId local,
			boolean verified, boolean active) throws DbException;

	/**
	 * Stores a group.
	 */
	void addGroup(Transaction txn, Group g) throws DbException;

	/**
	 * Stores a local pseudonym.
	 */
	void addLocalAuthor(Transaction txn, LocalAuthor a) throws DbException;

	/**
	 * Stores a local message.
	 */
	void addLocalMessage(Transaction txn, Message m, Metadata meta,
			boolean shared) throws DbException;

	/**
	 * Stores a transport.
	 */
	void addTransport(Transaction txn, TransportId t, int maxLatency)
			throws DbException;

	/**
	 * Stores the given transport keys for the given contact and returns a
	 * key set ID.
	 */
	KeySetId addTransportKeys(Transaction txn, ContactId c,
			TransportKeys k) throws DbException;

	/**
	 * Returns true if the database contains the given contact for the given
	 * local pseudonym.
	 */
	boolean containsContact(Transaction txn, AuthorId remote, AuthorId local)
			throws DbException;

	/**
	 * Returns true if the database contains the given group.
	 */
	boolean containsGroup(Transaction txn, GroupId g) throws DbException;

	/**
	 * Returns true if the database contains the given local author.
	 */
	boolean containsLocalAuthor(Transaction txn, AuthorId local)
			throws DbException;

	/**
	 * Deletes the message with the given ID. Unlike
	 * {@link #removeMessage(Transaction, MessageId)}, the message ID,
	 * dependencies, metadata, and any other associated state are not deleted.
	 */
	void deleteMessage(Transaction txn, MessageId m) throws DbException;

	/**
	 * Deletes any metadata associated with the given message.
	 */
	void deleteMessageMetadata(Transaction txn, MessageId m) throws DbException;

	/**
	 * Returns an acknowledgement for the given contact, or null if there are
	 * no messages to acknowledge.
	 */
	@Nullable
	Ack generateAck(Transaction txn, ContactId c, int maxMessages)
			throws DbException;

	/**
	 * Returns a batch of messages for the given contact, with a total length
	 * less than or equal to the given length, for transmission over a
	 * transport with the given maximum latency. Returns null if there are no
	 * sendable messages that fit in the given length.
	 */
	@Nullable
	Collection<Message> generateBatch(Transaction txn, ContactId c,
			int maxLength, int maxLatency) throws DbException;

	/**
	 * Returns an offer for the given contact for transmission over a
	 * transport with the given maximum latency, or null if there are no
	 * messages to offer.
	 */
	@Nullable
	Offer generateOffer(Transaction txn, ContactId c, int maxMessages,
			int maxLatency) throws DbException;

	/**
	 * Returns a request for the given contact, or null if there are no
	 * messages to request.
	 */
	@Nullable
	Request generateRequest(Transaction txn, ContactId c, int maxMessages)
			throws DbException;

	/**
	 * Returns a batch of messages for the given contact, with a total length
	 * less than or equal to the given length, for transmission over a
	 * transport with the given maximum latency. Only messages that have been
	 * requested by the contact are returned. Returns null if there are no
	 * sendable messages that fit in the given length.
	 */
	@Nullable
	Collection<Message> generateRequestedBatch(Transaction txn, ContactId c,
			int maxLength, int maxLatency) throws DbException;

	/**
	 * Returns the contact with the given ID.
	 * <p/>
	 * Read-only.
	 */
	Contact getContact(Transaction txn, ContactId c) throws DbException;

	/**
	 * Returns all contacts.
	 * <p/>
	 * Read-only.
	 */
	Collection<Contact> getContacts(Transaction txn) throws DbException;

	/**
	 * Returns a possibly empty collection of contacts with the given author ID.
	 * <p/>
	 * Read-only.
	 */
	Collection<Contact> getContactsByAuthorId(Transaction txn, AuthorId remote)
			throws DbException;

	/**
	 * Returns all contacts associated with the given local pseudonym.
	 * <p/>
	 * Read-only.
	 */
	Collection<ContactId> getContacts(Transaction txn, AuthorId a)
			throws DbException;

	/**
	 * Returns the group with the given ID.
	 * <p/>
	 * Read-only.
	 */
	Group getGroup(Transaction txn, GroupId g) throws DbException;

	/**
	 * Returns the metadata for the given group.
	 * <p/>
	 * Read-only.
	 */
	Metadata getGroupMetadata(Transaction txn, GroupId g) throws DbException;

	/**
	 * Returns all groups belonging to the given client.
	 * <p/>
	 * Read-only.
	 */
	Collection<Group> getGroups(Transaction txn, ClientId c, int majorVersion)
			throws DbException;

	/**
	 * Returns the given group's visibility to the given contact, or
	 * {@link Visibility INVISIBLE} if the group is not in the database.
	 * <p/>
	 * Read-only.
	 */
	Visibility getGroupVisibility(Transaction txn, ContactId c, GroupId g)
			throws DbException;

	/**
	 * Returns the local pseudonym with the given ID.
	 * <p/>
	 * Read-only.
	 */
	LocalAuthor getLocalAuthor(Transaction txn, AuthorId a) throws DbException;

	/**
	 * Returns all local pseudonyms.
	 * <p/>
	 * Read-only.
	 */
	Collection<LocalAuthor> getLocalAuthors(Transaction txn) throws DbException;

	/**
	 * Returns the message with the given ID.
	 * <p/>
	 * Read-only.
	 *
	 * @throws MessageDeletedException if the message has been deleted
	 */
	Message getMessage(Transaction txn, MessageId m) throws DbException;

	/**
	 * Returns the IDs of all delivered messages in the given group.
	 * <p/>
	 * Read-only.
	 */
	Collection<MessageId> getMessageIds(Transaction txn, GroupId g)
			throws DbException;

	/**
	 * Returns the IDs of any messages that need to be validated.
	 * <p/>
	 * Read-only.
	 */
	Collection<MessageId> getMessagesToValidate(Transaction txn)
			throws DbException;

	/**
	 * Returns the IDs of any messages that are pending delivery due to
	 * dependencies on other messages.
	 * <p/>
	 * Read-only.
	 */
	Collection<MessageId> getPendingMessages(Transaction txn)
			throws DbException;

	/**
	 * Returns the IDs of any messages that have shared dependents but have
	 * not yet been shared themselves.
	 * <p/>
	 * Read-only.
	 */
	Collection<MessageId> getMessagesToShare(Transaction txn)
			throws DbException;

	/**
	 * Returns the metadata for all delivered messages in the given group.
	 * <p/>
	 * Read-only.
	 */
	Map<MessageId, Metadata> getMessageMetadata(Transaction txn, GroupId g)
			throws DbException;

	/**
	 * Returns the metadata for any delivered messages in the given group with
	 * metadata that matches all entries in the given query. If the query is
	 * empty, the metadata for all delivered messages is returned.
	 * <p/>
	 * Read-only.
	 */
	Map<MessageId, Metadata> getMessageMetadata(Transaction txn, GroupId g,
			Metadata query) throws DbException;

	/**
	 * Returns the metadata for the given delivered message.
	 * <p/>
	 * Read-only.
	 */
	Metadata getMessageMetadata(Transaction txn, MessageId m)
			throws DbException;

	/**
	 * Returns the metadata for the given delivered or pending message.
	 * This is only meant to be used by the ValidationManager.
	 * <p/>
	 * Read-only.
	 */
	Metadata getMessageMetadataForValidator(Transaction txn, MessageId m)
			throws DbException;

	/**
	 * Returns the status of all delivered messages in the given group with
	 * respect to the given contact.
	 * <p/>
	 * Read-only.
	 */
	Collection<MessageStatus> getMessageStatus(Transaction txn, ContactId c,
			GroupId g) throws DbException;

	/**
	 * Returns the IDs and states of all dependencies of the given message.
	 * For missing dependencies and dependencies in other groups, the state
	 * {@link State UNKNOWN} is returned.
	 * <p/>
	 * Read-only.
	 */
	Map<MessageId, State> getMessageDependencies(Transaction txn, MessageId m)
			throws DbException;

	/**
	 * Returns the IDs and states of all dependents of the given message.
	 * Dependents in other groups are not returned. If the given message is
	 * missing, no dependents are returned.
	 * <p/>
	 * Read-only.
	 */
	Map<MessageId, State> getMessageDependents(Transaction txn, MessageId m)
			throws DbException;

	/**
	 * Gets the validation and delivery state of the given message.
	 * <p/>
	 * Read-only.
	 */
	State getMessageState(Transaction txn, MessageId m) throws DbException;

	/**
	 * Returns the status of the given delivered message with respect to the
	 * given contact.
	 * <p/>
	 * Read-only.
	 */
	MessageStatus getMessageStatus(Transaction txn, ContactId c, MessageId m)
			throws DbException;

	/*
	 * Returns the next time (in milliseconds since the Unix epoch) when a
	 * message is due to be sent to the given contact. The returned value may
	 * be zero if a message is due to be sent immediately, or Long.MAX_VALUE if
	 * no messages are scheduled to be sent.
	 * <p/>
	 * Read-only.
	 */
	long getNextSendTime(Transaction txn, ContactId c) throws DbException;

	/**
	 * Returns all settings in the given namespace.
	 * <p/>
	 * Read-only.
	 */
	Settings getSettings(Transaction txn, String namespace) throws DbException;

	/**
	 * Returns all transport keys for the given transport.
	 * <p/>
	 * Read-only.
	 */
	Collection<KeySet> getTransportKeys(Transaction txn, TransportId t)
			throws DbException;

	/**
	 * Increments the outgoing stream counter for the given transport keys.
	 */
	void incrementStreamCounter(Transaction txn, TransportId t, KeySetId k)
			throws DbException;

	/**
	 * Merges the given metadata with the existing metadata for the given
	 * group.
	 */
	void mergeGroupMetadata(Transaction txn, GroupId g, Metadata meta)
			throws DbException;

	/**
	 * Merges the given metadata with the existing metadata for the given
	 * message.
	 */
	void mergeMessageMetadata(Transaction txn, MessageId m, Metadata meta)
			throws DbException;

	/**
	 * Merges the given settings with the existing settings in the given
	 * namespace.
	 */
	void mergeSettings(Transaction txn, Settings s, String namespace)
			throws DbException;

	/**
	 * Processes an ack from the given contact.
	 */
	void receiveAck(Transaction txn, ContactId c, Ack a) throws DbException;

	/**
	 * Processes a message from the given contact.
	 */
	void receiveMessage(Transaction txn, ContactId c, Message m)
			throws DbException;

	/**
	 * Processes an offer from the given contact.
	 */
	void receiveOffer(Transaction txn, ContactId c, Offer o) throws DbException;

	/**
	 * Processes a request from the given contact.
	 */
	void receiveRequest(Transaction txn, ContactId c, Request r)
			throws DbException;

	/**
	 * Removes a contact (and all associated state) from the database.
	 */
	void removeContact(Transaction txn, ContactId c) throws DbException;

	/**
	 * Removes a group (and all associated state) from the database.
	 */
	void removeGroup(Transaction txn, Group g) throws DbException;

	/**
	 * Removes a local pseudonym (and all associated state) from the database.
	 */
	void removeLocalAuthor(Transaction txn, AuthorId a) throws DbException;

	/**
	 * Removes a message (and all associated state) from the database.
	 */
	void removeMessage(Transaction txn, MessageId m) throws DbException;

	/**
	 * Removes a transport (and all associated state) from the database.
	 */
	void removeTransport(Transaction txn, TransportId t) throws DbException;

	/**
	 * Removes the given transport keys from the database.
	 */
	void removeTransportKeys(Transaction txn, TransportId t, KeySetId k)
			throws DbException;

	/**
	 * Marks the given contact as verified.
	 */
	void setContactVerified(Transaction txn, ContactId c) throws DbException;

	/**
	 * Marks the given contact as active or inactive.
	 */
	void setContactActive(Transaction txn, ContactId c, boolean active)
			throws DbException;

	/**
	 * Sets the given group's visibility to the given contact.
	 */
	void setGroupVisibility(Transaction txn, ContactId c, GroupId g,
			Visibility v) throws DbException;

	/**
	 * Marks the given message as shared.
	 */
	void setMessageShared(Transaction txn, MessageId m) throws DbException;

	/**
	 * Sets the validation and delivery state of the given message.
	 */
	void setMessageState(Transaction txn, MessageId m, State state)
			throws DbException;

	/**
	 * Adds dependencies for a message
	 */
	void addMessageDependencies(Transaction txn, Message dependent,
			Collection<MessageId> dependencies) throws DbException;

	/**
	 * Sets the reordering window for the given key set and transport in the
	 * given rotation period.
	 */
	void setReorderingWindow(Transaction txn, KeySetId k, TransportId t,
			long rotationPeriod, long base, byte[] bitmap) throws DbException;

	/**
	 * Marks the given transport keys as usable for outgoing streams.
	 */
	void setTransportKeysActive(Transaction txn, TransportId t, KeySetId k)
			throws DbException;

	/**
	 * Stores the given transport keys, deleting any keys they have replaced.
	 */
	void updateTransportKeys(Transaction txn, Collection<KeySet> keys)
			throws DbException;
}
