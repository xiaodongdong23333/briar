package org.briarproject.bramble.sync;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.NoSuchGroupException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageContext;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.ValidationManager.IncomingMessageHook;
import org.briarproject.bramble.api.sync.ValidationManager.MessageValidator;
import org.briarproject.bramble.api.sync.ValidationManager.State;
import org.briarproject.bramble.api.sync.event.MessageAddedEvent;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.ImmediateExecutor;
import org.jmock.Expectations;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.briarproject.bramble.api.sync.ValidationManager.State.DELIVERED;
import static org.briarproject.bramble.api.sync.ValidationManager.State.INVALID;
import static org.briarproject.bramble.api.sync.ValidationManager.State.PENDING;
import static org.briarproject.bramble.api.sync.ValidationManager.State.UNKNOWN;
import static org.briarproject.bramble.test.TestUtils.getClientId;
import static org.briarproject.bramble.test.TestUtils.getGroup;
import static org.briarproject.bramble.test.TestUtils.getMessage;
import static org.briarproject.bramble.test.TestUtils.getRandomId;

public class ValidationManagerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final MessageValidator validator =
			context.mock(MessageValidator.class);
	private final IncomingMessageHook hook =
			context.mock(IncomingMessageHook.class);

	private final Executor dbExecutor = new ImmediateExecutor();
	private final Executor validationExecutor = new ImmediateExecutor();
	private final ClientId clientId = getClientId();
	private final int majorVersion = 123;
	private final Group group = getGroup(clientId, majorVersion);
	private final GroupId groupId = group.getId();
	private final Message message = getMessage(groupId);
	private final Message message1 = getMessage(groupId);
	private final Message message2 = getMessage(groupId);
	private final MessageId messageId = message.getId();
	private final MessageId messageId1 = message1.getId();
	private final MessageId messageId2 = message2.getId();

	private final Metadata metadata = new Metadata();
	private final MessageContext validResult = new MessageContext(metadata);
	private final ContactId contactId = new ContactId(234);
	private final MessageContext validResultWithDependencies =
			new MessageContext(metadata, singletonList(messageId1));

	private ValidationManagerImpl vm;

	@Before
	public void setUp() {
		vm = new ValidationManagerImpl(db, dbExecutor, validationExecutor);
		vm.registerMessageValidator(clientId, majorVersion, validator);
		vm.registerIncomingMessageHook(clientId, majorVersion, hook);
	}

	@Test
	public void testStartAndStop() throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, true);
		Transaction txn2 = new Transaction(null, true);

		context.checking(new Expectations() {{
			// validateOutstandingMessages()
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getMessagesToValidate(txn);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// deliverOutstandingMessages()
			oneOf(db).startTransaction(true);
			will(returnValue(txn1));
			oneOf(db).getPendingMessages(txn1);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
			// shareOutstandingMessages()
			oneOf(db).startTransaction(true);
			will(returnValue(txn2));
			oneOf(db).getMessagesToShare(txn2);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
		}});

		vm.startService();
		vm.stopService();
	}

	@Test
	public void testMessagesAreValidatedAtStartup() throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, true);
		Transaction txn2 = new Transaction(null, false);
		Transaction txn3 = new Transaction(null, true);
		Transaction txn4 = new Transaction(null, false);
		Transaction txn5 = new Transaction(null, true);
		Transaction txn6 = new Transaction(null, true);

		context.checking(new Expectations() {{
			// Get messages to validate
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getMessagesToValidate(txn);
			will(returnValue(Arrays.asList(messageId, messageId1)));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Load the first raw message and group
			oneOf(db).startTransaction(true);
			will(returnValue(txn1));
			oneOf(db).getMessage(txn1, messageId);
			will(returnValue(message));
			oneOf(db).getGroup(txn1, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
			// Validate the first message: valid
			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResult));
			// Store the validation result for the first message
			oneOf(db).startTransaction(false);
			will(returnValue(txn2));
			oneOf(db).mergeMessageMetadata(txn2, messageId, metadata);
			// Deliver the first message
			oneOf(hook).incomingMessage(txn2, message, metadata);
			will(returnValue(false));
			oneOf(db).setMessageState(txn2, messageId, DELIVERED);
			// Get any pending dependents
			oneOf(db).getMessageDependents(txn2, messageId);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
			// Load the second raw message and group
			oneOf(db).startTransaction(true);
			will(returnValue(txn3));
			oneOf(db).getMessage(txn3, messageId1);
			will(returnValue(message1));
			oneOf(db).getGroup(txn3, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn3);
			oneOf(db).endTransaction(txn3);
			// Validate the second message: invalid
			oneOf(validator).validateMessage(message1, group);
			will(throwException(new InvalidMessageException()));
			// Store the validation result for the second message
			oneOf(db).startTransaction(false);
			will(returnValue(txn4));
			oneOf(db).getMessageState(txn4, messageId1);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn4, messageId1, INVALID);
			oneOf(db).deleteMessage(txn4, messageId1);
			oneOf(db).deleteMessageMetadata(txn4, messageId1);
			// Recursively invalidate any dependents
			oneOf(db).getMessageDependents(txn4, messageId1);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn4);
			oneOf(db).endTransaction(txn4);
			// Get pending messages to deliver
			oneOf(db).startTransaction(true);
			will(returnValue(txn5));
			oneOf(db).getPendingMessages(txn5);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn5);
			oneOf(db).endTransaction(txn5);
			// Get messages to share
			oneOf(db).startTransaction(true);
			will(returnValue(txn6));
			oneOf(db).getMessagesToShare(txn6);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn6);
			oneOf(db).endTransaction(txn6);
		}});

		vm.startService();
	}

	@Test
	public void testPendingMessagesAreDeliveredAtStartup() throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, true);
		Transaction txn2 = new Transaction(null, false);
		Transaction txn3 = new Transaction(null, false);
		Transaction txn4 = new Transaction(null, true);

		context.checking(new Expectations() {{
			// Get messages to validate
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getMessagesToValidate(txn);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Get pending messages to deliver
			oneOf(db).startTransaction(true);
			will(returnValue(txn1));
			oneOf(db).getPendingMessages(txn1);
			will(returnValue(singletonList(messageId)));
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
			// Check whether the message is ready to deliver
			oneOf(db).startTransaction(false);
			will(returnValue(txn2));
			oneOf(db).getMessageState(txn2, messageId);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn2, messageId);
			will(returnValue(singletonMap(messageId1, DELIVERED)));
			// Get the message and its metadata to deliver
			oneOf(db).getMessage(txn2, messageId);
			will(returnValue(message));
			oneOf(db).getGroup(txn2, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn2, messageId);
			will(returnValue(new Metadata()));
			// Deliver the message
			oneOf(hook).incomingMessage(txn2, message, metadata);
			will(returnValue(false));
			oneOf(db).setMessageState(txn2, messageId, DELIVERED);
			// Get any pending dependents
			oneOf(db).getMessageDependents(txn2, messageId);
			will(returnValue(singletonMap(messageId2, PENDING)));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
			// Check whether the dependent is ready to deliver
			oneOf(db).startTransaction(false);
			will(returnValue(txn3));
			oneOf(db).getMessageState(txn3, messageId2);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn3, messageId2);
			will(returnValue(singletonMap(messageId1, DELIVERED)));
			// Get the dependent and its metadata to deliver
			oneOf(db).getMessage(txn3, messageId2);
			will(returnValue(message2));
			oneOf(db).getGroup(txn3, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn3, messageId2);
			will(returnValue(metadata));
			// Deliver the dependent
			oneOf(hook).incomingMessage(txn3, message2, metadata);
			will(returnValue(false));
			oneOf(db).setMessageState(txn3, messageId2, DELIVERED);
			// Get any pending dependents
			oneOf(db).getMessageDependents(txn3, messageId2);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn3);
			oneOf(db).endTransaction(txn3);

			// Get messages to share
			oneOf(db).startTransaction(true);
			will(returnValue(txn4));
			oneOf(db).getMessagesToShare(txn4);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn4);
			oneOf(db).endTransaction(txn4);
		}});

		vm.startService();
	}

	@Test
	public void testMessagesAreSharedAtStartup() throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, true);
		Transaction txn2 = new Transaction(null, true);
		Transaction txn3 = new Transaction(null, false);
		Transaction txn4 = new Transaction(null, false);

		context.checking(new Expectations() {{
			// No messages to validate
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getMessagesToValidate(txn);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// No pending messages to deliver
			oneOf(db).startTransaction(true);
			will(returnValue(txn1));
			oneOf(db).getPendingMessages(txn1);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);

			// Get messages to share
			oneOf(db).startTransaction(true);
			will(returnValue(txn2));
			oneOf(db).getMessagesToShare(txn2);
			will(returnValue(singletonList(messageId)));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
			// Share message and get dependencies
			oneOf(db).startTransaction(false);
			will(returnValue(txn3));
			oneOf(db).setMessageShared(txn3, messageId);
			oneOf(db).getMessageDependencies(txn3, messageId);
			will(returnValue(singletonMap(messageId2, DELIVERED)));
			oneOf(db).commitTransaction(txn3);
			oneOf(db).endTransaction(txn3);
			// Share dependency
			oneOf(db).startTransaction(false);
			will(returnValue(txn4));
			oneOf(db).setMessageShared(txn4, messageId2);
			oneOf(db).getMessageDependencies(txn4, messageId2);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn4);
			oneOf(db).endTransaction(txn4);
		}});

		vm.startService();
	}

	@Test
	public void testIncomingMessagesAreShared() throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, false);

		context.checking(new Expectations() {{
			// Load the group
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Validate the message: valid
			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResultWithDependencies));
			// Store the validation result
			oneOf(db).startTransaction(false);
			will(returnValue(txn1));
			oneOf(db).addMessageDependencies(txn1, message,
					validResultWithDependencies.getDependencies());
			oneOf(db).getMessageDependencies(txn1, messageId);
			will(returnValue(singletonMap(messageId1, DELIVERED)));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);
			// Deliver the message
			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(true));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);
			// Get any pending dependents
			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(emptyMap()));
			// Share message
			oneOf(db).setMessageShared(txn1, messageId);
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
			// Share dependencies
			oneOf(db).startTransaction(false);
			will(returnValue(txn2));
			oneOf(db).setMessageShared(txn2, messageId1);
			oneOf(db).getMessageDependencies(txn2, messageId1);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testValidationContinuesAfterNoSuchMessageException()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, true);
		Transaction txn2 = new Transaction(null, true);
		Transaction txn3 = new Transaction(null, false);
		Transaction txn4 = new Transaction(null, true);
		Transaction txn5 = new Transaction(null, true);

		context.checking(new Expectations() {{
			// Get messages to validate
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getMessagesToValidate(txn);
			will(returnValue(Arrays.asList(messageId, messageId1)));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Load the first raw message - *gasp* it's gone!
			oneOf(db).startTransaction(true);
			will(returnValue(txn1));
			oneOf(db).getMessage(txn1, messageId);
			will(throwException(new NoSuchMessageException()));
			never(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
			// Load the second raw message and group
			oneOf(db).startTransaction(true);
			will(returnValue(txn2));
			oneOf(db).getMessage(txn2, messageId1);
			will(returnValue(message1));
			oneOf(db).getGroup(txn2, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
			// Validate the second message: invalid
			oneOf(validator).validateMessage(message1, group);
			will(throwException(new InvalidMessageException()));
			// Invalidate the second message
			oneOf(db).startTransaction(false);
			will(returnValue(txn3));
			oneOf(db).getMessageState(txn3, messageId1);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn3, messageId1, INVALID);
			oneOf(db).deleteMessage(txn3, messageId1);
			oneOf(db).deleteMessageMetadata(txn3, messageId1);
			// Recursively invalidate dependents
			oneOf(db).getMessageDependents(txn3, messageId1);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn3);
			oneOf(db).endTransaction(txn3);
			// Get pending messages to deliver
			oneOf(db).startTransaction(true);
			will(returnValue(txn4));
			oneOf(db).getPendingMessages(txn4);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn4);
			oneOf(db).endTransaction(txn4);
			// Get messages to share
			oneOf(db).startTransaction(true);
			will(returnValue(txn5));
			oneOf(db).getMessagesToShare(txn5);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn5);
			oneOf(db).endTransaction(txn5);
		}});

		vm.startService();
	}

	@Test
	public void testValidationContinuesAfterNoSuchGroupException()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, true);
		Transaction txn2 = new Transaction(null, true);
		Transaction txn3 = new Transaction(null, false);
		Transaction txn4 = new Transaction(null, true);
		Transaction txn5 = new Transaction(null, true);

		context.checking(new Expectations() {{
			// Get messages to validate
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getMessagesToValidate(txn);
			will(returnValue(Arrays.asList(messageId, messageId1)));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Load the first raw message
			oneOf(db).startTransaction(true);
			will(returnValue(txn1));
			oneOf(db).getMessage(txn1, messageId);
			will(returnValue(message));
			// Load the group - *gasp* it's gone!
			oneOf(db).getGroup(txn1, groupId);
			will(throwException(new NoSuchGroupException()));
			never(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
			// Load the second raw message and group
			oneOf(db).startTransaction(true);
			will(returnValue(txn2));
			oneOf(db).getMessage(txn2, messageId1);
			will(returnValue(message1));
			oneOf(db).getGroup(txn2, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
			// Validate the second message: invalid
			oneOf(validator).validateMessage(message1, group);
			will(throwException(new InvalidMessageException()));
			// Store the validation result for the second message
			oneOf(db).startTransaction(false);
			will(returnValue(txn3));
			oneOf(db).getMessageState(txn3, messageId1);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn3, messageId1, INVALID);
			oneOf(db).deleteMessage(txn3, messageId1);
			oneOf(db).deleteMessageMetadata(txn3, messageId1);
			// Recursively invalidate dependents
			oneOf(db).getMessageDependents(txn3, messageId1);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn3);
			oneOf(db).endTransaction(txn3);
			// Get pending messages to deliver
			oneOf(db).startTransaction(true);
			will(returnValue(txn4));
			oneOf(db).getPendingMessages(txn4);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn4);
			oneOf(db).endTransaction(txn4);
			// Get messages to share
			oneOf(db).startTransaction(true);
			will(returnValue(txn5));
			oneOf(db).getMessagesToShare(txn5);
			will(returnValue(emptyList()));
			oneOf(db).commitTransaction(txn5);
			oneOf(db).endTransaction(txn5);
		}});

		vm.startService();
	}

	@Test
	public void testNonLocalMessagesAreValidatedWhenAdded() throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);

		context.checking(new Expectations() {{
			// Load the group
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Validate the message: valid
			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResult));
			// Store the validation result
			oneOf(db).startTransaction(false);
			will(returnValue(txn1));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);
			// Deliver the message
			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(false));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);
			// Get any pending dependents
			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testLocalMessagesAreNotValidatedWhenAdded() {
		vm.eventOccurred(new MessageAddedEvent(message, null));
	}

	@Test
	public void testMessagesWithUndeliveredDependenciesArePending()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);

		context.checking(new Expectations() {{
			// Load the group
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Validate the message: valid
			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResultWithDependencies));
			// Store the validation result
			oneOf(db).startTransaction(false);
			will(returnValue(txn1));
			oneOf(db).addMessageDependencies(txn1, message,
					validResultWithDependencies.getDependencies());
			oneOf(db).getMessageDependencies(txn1, messageId);
			will(returnValue(singletonMap(messageId1, UNKNOWN)));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);
			oneOf(db).setMessageState(txn1, messageId, PENDING);
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testMessagesWithDeliveredDependenciesGetDelivered()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);

		context.checking(new Expectations() {{
			// Load the group
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Validate the message: valid
			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResultWithDependencies));
			// Store the validation result
			oneOf(db).startTransaction(false);
			will(returnValue(txn1));
			oneOf(db).addMessageDependencies(txn1, message,
					validResultWithDependencies.getDependencies());
			oneOf(db).getMessageDependencies(txn1, messageId);
			will(returnValue(singletonMap(messageId1, DELIVERED)));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);
			// Deliver the message
			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(false));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);
			// Get any pending dependents
			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testMessagesWithInvalidDependenciesAreInvalid()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, false);

		context.checking(new Expectations() {{
			// Load the group
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Validate the message: valid
			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResultWithDependencies));
			// Store the validation result
			oneOf(db).startTransaction(false);
			will(returnValue(txn1));
			oneOf(db).addMessageDependencies(txn1, message,
					validResultWithDependencies.getDependencies());
			// Check for invalid dependencies
			oneOf(db).getMessageDependencies(txn1, messageId);
			will(returnValue(singletonMap(messageId1, INVALID)));
			// Invalidate message
			oneOf(db).getMessageState(txn1, messageId);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn1, messageId, INVALID);
			oneOf(db).deleteMessage(txn1, messageId);
			oneOf(db).deleteMessageMetadata(txn1, messageId);
			// Recursively invalidate dependents
			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(singletonMap(messageId2, UNKNOWN)));
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
			// Invalidate dependent in a new transaction
			oneOf(db).startTransaction(false);
			will(returnValue(txn2));
			oneOf(db).getMessageState(txn2, messageId2);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn2, messageId2, INVALID);
			oneOf(db).deleteMessage(txn2, messageId2);
			oneOf(db).deleteMessageMetadata(txn2, messageId2);
			oneOf(db).getMessageDependents(txn2, messageId2);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testRecursiveInvalidation() throws Exception {
		MessageId messageId3 = new MessageId(getRandomId());
		MessageId messageId4 = new MessageId(getRandomId());
		Map<MessageId, State> twoDependents = new LinkedHashMap<>();
		twoDependents.put(messageId1, PENDING);
		twoDependents.put(messageId2, PENDING);
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, false);
		Transaction txn3 = new Transaction(null, false);
		Transaction txn4 = new Transaction(null, false);
		Transaction txn5 = new Transaction(null, false);
		Transaction txn6 = new Transaction(null, false);

		context.checking(new Expectations() {{
			// Load the group
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Validate the message: invalid
			oneOf(validator).validateMessage(message, group);
			will(throwException(new InvalidMessageException()));
			// Invalidate the message
			oneOf(db).startTransaction(false);
			will(returnValue(txn1));
			oneOf(db).getMessageState(txn1, messageId);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn1, messageId, INVALID);
			oneOf(db).deleteMessage(txn1, messageId);
			oneOf(db).deleteMessageMetadata(txn1, messageId);
			// The message has two dependents: 1 and 2
			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(twoDependents));
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
			// Invalidate message 1
			oneOf(db).startTransaction(false);
			will(returnValue(txn2));
			oneOf(db).getMessageState(txn2, messageId1);
			will(returnValue(PENDING));
			oneOf(db).setMessageState(txn2, messageId1, INVALID);
			oneOf(db).deleteMessage(txn2, messageId1);
			oneOf(db).deleteMessageMetadata(txn2, messageId1);
			// Message 1 has one dependent: 3
			oneOf(db).getMessageDependents(txn2, messageId1);
			will(returnValue(singletonMap(messageId3, PENDING)));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
			// Invalidate message 2
			oneOf(db).startTransaction(false);
			will(returnValue(txn3));
			oneOf(db).getMessageState(txn3, messageId2);
			will(returnValue(PENDING));
			oneOf(db).setMessageState(txn3, messageId2, INVALID);
			oneOf(db).deleteMessage(txn3, messageId2);
			oneOf(db).deleteMessageMetadata(txn3, messageId2);
			// Message 2 has one dependent: 3 (same dependent as 1)
			oneOf(db).getMessageDependents(txn3, messageId2);
			will(returnValue(singletonMap(messageId3, PENDING)));
			oneOf(db).commitTransaction(txn3);
			oneOf(db).endTransaction(txn3);
			// Invalidate message 3 (via 1)
			oneOf(db).startTransaction(false);
			will(returnValue(txn4));
			oneOf(db).getMessageState(txn4, messageId3);
			will(returnValue(PENDING));
			oneOf(db).setMessageState(txn4, messageId3, INVALID);
			oneOf(db).deleteMessage(txn4, messageId3);
			oneOf(db).deleteMessageMetadata(txn4, messageId3);
			// Message 3 has one dependent: 4
			oneOf(db).getMessageDependents(txn4, messageId3);
			will(returnValue(singletonMap(messageId4, PENDING)));
			oneOf(db).commitTransaction(txn4);
			oneOf(db).endTransaction(txn4);
			// Invalidate message 3 (again, via 2)
			oneOf(db).startTransaction(false);
			will(returnValue(txn5));
			oneOf(db).getMessageState(txn5, messageId3);
			will(returnValue(INVALID)); // Already invalidated
			oneOf(db).commitTransaction(txn5);
			oneOf(db).endTransaction(txn5);
			// Invalidate message 4 (via 1 and 3)
			oneOf(db).startTransaction(false);
			will(returnValue(txn6));
			oneOf(db).getMessageState(txn6, messageId4);
			will(returnValue(PENDING));
			oneOf(db).setMessageState(txn6, messageId4, INVALID);
			oneOf(db).deleteMessage(txn6, messageId4);
			oneOf(db).deleteMessageMetadata(txn6, messageId4);
			// Message 4 has no dependents
			oneOf(db).getMessageDependents(txn6, messageId4);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn6);
			oneOf(db).endTransaction(txn6);
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testPendingDependentsGetDelivered() throws Exception {
		Message message3 = getMessage(groupId);
		Message message4 = getMessage(groupId);
		MessageId messageId3 = message3.getId();
		MessageId messageId4 = message4.getId();
		Map<MessageId, State> twoDependents = new LinkedHashMap<>();
		twoDependents.put(messageId1, PENDING);
		twoDependents.put(messageId2, PENDING);
		Map<MessageId, State> twoDependencies = new LinkedHashMap<>();
		twoDependencies.put(messageId1, DELIVERED);
		twoDependencies.put(messageId2, DELIVERED);
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, false);
		Transaction txn3 = new Transaction(null, false);
		Transaction txn4 = new Transaction(null, false);
		Transaction txn5 = new Transaction(null, false);
		Transaction txn6 = new Transaction(null, false);

		context.checking(new Expectations() {{
			// Load the group
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Validate the message: valid
			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResult));
			// Store the validation result
			oneOf(db).startTransaction(false);
			will(returnValue(txn1));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);
			// Deliver the message
			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(false));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);
			// The message has two pending dependents: 1 and 2
			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(twoDependents));
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
			// Check whether message 1 is ready to be delivered
			oneOf(db).startTransaction(false);
			will(returnValue(txn2));
			oneOf(db).getMessageState(txn2, messageId1);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn2, messageId1);
			will(returnValue(singletonMap(messageId, DELIVERED)));
			// Get message 1 and its metadata
			oneOf(db).getMessage(txn2, messageId1);
			will(returnValue(message1));
			oneOf(db).getGroup(txn2, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn2, messageId1);
			will(returnValue(metadata));
			// Deliver message 1
			oneOf(hook).incomingMessage(txn2, message1, metadata);
			will(returnValue(false));
			oneOf(db).setMessageState(txn2, messageId1, DELIVERED);
			// Message 1 has one pending dependent: 3
			oneOf(db).getMessageDependents(txn2, messageId1);
			will(returnValue(singletonMap(messageId3, PENDING)));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
			// Check whether message 2 is ready to be delivered
			oneOf(db).startTransaction(false);
			will(returnValue(txn3));
			oneOf(db).getMessageState(txn3, messageId2);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn3, messageId2);
			will(returnValue(singletonMap(messageId, DELIVERED)));
			// Get message 2 and its metadata
			oneOf(db).getMessage(txn3, messageId2);
			will(returnValue(message2));
			oneOf(db).getGroup(txn3, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn3, messageId2);
			will(returnValue(metadata));
			// Deliver message 2
			oneOf(hook).incomingMessage(txn3, message2, metadata);
			will(returnValue(false));
			oneOf(db).setMessageState(txn3, messageId2, DELIVERED);
			// Message 2 has one pending dependent: 3 (same dependent as 1)
			oneOf(db).getMessageDependents(txn3, messageId2);
			will(returnValue(singletonMap(messageId3, PENDING)));
			oneOf(db).commitTransaction(txn3);
			oneOf(db).endTransaction(txn3);
			// Check whether message 3 is ready to be delivered (via 1)
			oneOf(db).startTransaction(false);
			will(returnValue(txn4));
			oneOf(db).getMessageState(txn4, messageId3);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn4, messageId3);
			will(returnValue(twoDependencies));
			// Get message 3 and its metadata
			oneOf(db).getMessage(txn4, messageId3);
			will(returnValue(message3));
			oneOf(db).getGroup(txn4, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn4, messageId3);
			will(returnValue(metadata));
			// Deliver message 3
			oneOf(hook).incomingMessage(txn4, message3, metadata);
			oneOf(db).setMessageState(txn4, messageId3, DELIVERED);
			// Message 3 has one pending dependent: 4
			oneOf(db).getMessageDependents(txn4, messageId3);
			will(returnValue(singletonMap(messageId4, PENDING)));
			oneOf(db).commitTransaction(txn4);
			oneOf(db).endTransaction(txn4);
			// Check whether message 3 is ready to be delivered (again, via 2)
			oneOf(db).startTransaction(false);
			will(returnValue(txn5));
			oneOf(db).getMessageState(txn5, messageId3);
			will(returnValue(DELIVERED)); // Already delivered
			oneOf(db).commitTransaction(txn5);
			oneOf(db).endTransaction(txn5);
			// Check whether message 4 is ready to be delivered (via 1 and 3)
			oneOf(db).startTransaction(false);
			will(returnValue(txn6));
			oneOf(db).getMessageState(txn6, messageId4);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn6, messageId4);
			will(returnValue(singletonMap(messageId3, DELIVERED)));
			// Get message 4 and its metadata
			oneOf(db).getMessage(txn6, messageId4);
			will(returnValue(message4));
			oneOf(db).getGroup(txn6, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn6, messageId4);
			will(returnValue(metadata));
			// Deliver message 4
			oneOf(hook).incomingMessage(txn6, message4, metadata);
			will(returnValue(false));
			oneOf(db).setMessageState(txn6, messageId4, DELIVERED);
			// Message 4 has no pending dependents
			oneOf(db).getMessageDependents(txn6, messageId4);
			will(returnValue(emptyMap()));
			oneOf(db).commitTransaction(txn6);
			oneOf(db).endTransaction(txn6);
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testOnlyReadyPendingDependentsGetDelivered() throws Exception {
		Map<MessageId, State> twoDependencies = new LinkedHashMap<>();
		twoDependencies.put(messageId, DELIVERED);
		twoDependencies.put(messageId2, UNKNOWN);
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, false);

		context.checking(new Expectations() {{
			// Load the group
			oneOf(db).startTransaction(true);
			will(returnValue(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));
			oneOf(db).commitTransaction(txn);
			oneOf(db).endTransaction(txn);
			// Validate the message: valid
			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResult));
			// Store the validation result
			oneOf(db).startTransaction(false);
			will(returnValue(txn1));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);
			// Deliver the message
			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(false));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);
			// Get any pending dependents
			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(singletonMap(messageId1, PENDING)));
			oneOf(db).commitTransaction(txn1);
			oneOf(db).endTransaction(txn1);
			// Check whether the pending dependent is ready to be delivered
			oneOf(db).startTransaction(false);
			will(returnValue(txn2));
			oneOf(db).getMessageState(txn2, messageId1);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn2, messageId1);
			will(returnValue(twoDependencies));
			oneOf(db).commitTransaction(txn2);
			oneOf(db).endTransaction(txn2);
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}
}
