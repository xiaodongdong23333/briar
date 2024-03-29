package org.briarproject.briar.test;

import net.jodah.concurrentunit.Waiter;

import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.AuthorFactory;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.bramble.api.sync.MessageFactory;
import org.briarproject.bramble.api.sync.SyncSession;
import org.briarproject.bramble.api.sync.SyncSessionFactory;
import org.briarproject.bramble.api.sync.event.MessageStateChangedEvent;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.bramble.contact.ContactModule;
import org.briarproject.bramble.crypto.CryptoExecutorModule;
import org.briarproject.bramble.identity.IdentityModule;
import org.briarproject.bramble.lifecycle.LifecycleModule;
import org.briarproject.bramble.properties.PropertiesModule;
import org.briarproject.bramble.sync.SyncModule;
import org.briarproject.bramble.system.SystemModule;
import org.briarproject.bramble.test.TestUtils;
import org.briarproject.bramble.transport.TransportModule;
import org.briarproject.bramble.versioning.VersioningModule;
import org.briarproject.briar.api.blog.BlogFactory;
import org.briarproject.briar.api.blog.BlogPostFactory;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.forum.ForumPostFactory;
import org.briarproject.briar.api.privategroup.GroupMessageFactory;
import org.briarproject.briar.api.privategroup.PrivateGroupFactory;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory;
import org.briarproject.briar.blog.BlogModule;
import org.briarproject.briar.forum.ForumModule;
import org.briarproject.briar.introduction.IntroductionModule;
import org.briarproject.briar.messaging.MessagingModule;
import org.briarproject.briar.privategroup.PrivateGroupModule;
import org.briarproject.briar.privategroup.invitation.GroupInvitationModule;
import org.briarproject.briar.sharing.SharingModule;
import org.junit.After;
import org.junit.Before;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static junit.framework.Assert.assertNotNull;
import static org.briarproject.bramble.api.sync.ValidationManager.State.DELIVERED;
import static org.briarproject.bramble.api.sync.ValidationManager.State.INVALID;
import static org.briarproject.bramble.api.sync.ValidationManager.State.PENDING;
import static org.briarproject.bramble.test.TestPluginConfigModule.MAX_LATENCY;
import static org.briarproject.bramble.test.TestUtils.getSecretKey;
import static org.junit.Assert.assertTrue;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public abstract class BriarIntegrationTest<C extends BriarIntegrationTestComponent>
		extends BriarTestCase {

	private static final Logger LOG =
			Logger.getLogger(BriarIntegrationTest.class.getName());

	@Nullable
	protected ContactId contactId1From2, contactId2From1;
	protected ContactId contactId0From1, contactId0From2, contactId1From0,
			contactId2From0;
	protected Contact contact0From1, contact0From2, contact1From0,
			contact2From0;
	protected LocalAuthor author0, author1, author2;
	protected ContactManager contactManager0, contactManager1, contactManager2;
	protected IdentityManager identityManager0, identityManager1,
			identityManager2;
	protected DatabaseComponent db0, db1, db2;
	protected MessageTracker messageTracker0, messageTracker1, messageTracker2;

	private LifecycleManager lifecycleManager0, lifecycleManager1,
			lifecycleManager2;
	private SyncSessionFactory sync0, sync1, sync2;

	@Inject
	protected Clock clock;
	@Inject
	protected CryptoComponent crypto;
	@Inject
	protected ClientHelper clientHelper;
	@Inject
	protected AuthorFactory authorFactory;
	@Inject
	protected MessageFactory messageFactory;
	@Inject
	protected ContactGroupFactory contactGroupFactory;
	@Inject
	protected PrivateGroupFactory privateGroupFactory;
	@Inject
	protected GroupMessageFactory groupMessageFactory;
	@Inject
	protected GroupInvitationFactory groupInvitationFactory;
	@Inject
	protected BlogFactory blogFactory;
	@Inject
	protected BlogPostFactory blogPostFactory;
	@Inject
	protected ForumPostFactory forumPostFactory;

	// objects accessed from background threads need to be volatile
	private volatile Waiter validationWaiter;
	private volatile Waiter deliveryWaiter;

	protected final static int TIMEOUT = 15000;
	protected C c0, c1, c2;

	private final File testDir = TestUtils.getTestDirectory();
	private final String AUTHOR0 = "Author 0";
	private final String AUTHOR1 = "Author 1";
	private final String AUTHOR2 = "Author 2";

	protected File t0Dir = new File(testDir, AUTHOR0);
	protected File t1Dir = new File(testDir, AUTHOR1);
	protected File t2Dir = new File(testDir, AUTHOR2);

	@Before
	public void setUp() throws Exception {
		assertTrue(testDir.mkdirs());
		createComponents();

		identityManager0 = c0.getIdentityManager();
		identityManager1 = c1.getIdentityManager();
		identityManager2 = c2.getIdentityManager();
		contactManager0 = c0.getContactManager();
		contactManager1 = c1.getContactManager();
		contactManager2 = c2.getContactManager();
		messageTracker0 = c0.getMessageTracker();
		messageTracker1 = c1.getMessageTracker();
		messageTracker2 = c2.getMessageTracker();
		db0 = c0.getDatabaseComponent();
		db1 = c1.getDatabaseComponent();
		db2 = c2.getDatabaseComponent();
		sync0 = c0.getSyncSessionFactory();
		sync1 = c1.getSyncSessionFactory();
		sync2 = c2.getSyncSessionFactory();

		// initialize waiters fresh for each test
		validationWaiter = new Waiter();
		deliveryWaiter = new Waiter();

		createAndRegisterIdentities();
		startLifecycles();
		listenToEvents();
		addDefaultContacts();
	}

	abstract protected void createComponents();

	protected void injectEagerSingletons(
			BriarIntegrationTestComponent component) {
		component.inject(new BlogModule.EagerSingletons());
		component.inject(new ContactModule.EagerSingletons());
		component.inject(new CryptoExecutorModule.EagerSingletons());
		component.inject(new ForumModule.EagerSingletons());
		component.inject(new GroupInvitationModule.EagerSingletons());
		component.inject(new IdentityModule.EagerSingletons());
		component.inject(new IntroductionModule.EagerSingletons());
		component.inject(new LifecycleModule.EagerSingletons());
		component.inject(new MessagingModule.EagerSingletons());
		component.inject(new PrivateGroupModule.EagerSingletons());
		component.inject(new PropertiesModule.EagerSingletons());
		component.inject(new SharingModule.EagerSingletons());
		component.inject(new SyncModule.EagerSingletons());
		component.inject(new SystemModule.EagerSingletons());
		component.inject(new TransportModule.EagerSingletons());
		component.inject(new VersioningModule.EagerSingletons());
	}

	private void startLifecycles() throws InterruptedException {
		// Start the lifecycle manager and wait for it to finish starting
		lifecycleManager0 = c0.getLifecycleManager();
		lifecycleManager1 = c1.getLifecycleManager();
		lifecycleManager2 = c2.getLifecycleManager();
		lifecycleManager0.startServices(getSecretKey());
		lifecycleManager1.startServices(getSecretKey());
		lifecycleManager2.startServices(getSecretKey());
		lifecycleManager0.waitForStartup();
		lifecycleManager1.waitForStartup();
		lifecycleManager2.waitForStartup();
	}

	private void listenToEvents() {
		Listener listener0 = new Listener();
		c0.getEventBus().addListener(listener0);
		Listener listener1 = new Listener();
		c1.getEventBus().addListener(listener1);
		Listener listener2 = new Listener();
		c2.getEventBus().addListener(listener2);
	}

	private class Listener implements EventListener {
		@Override
		public void eventOccurred(Event e) {
			if (e instanceof MessageStateChangedEvent) {
				MessageStateChangedEvent event = (MessageStateChangedEvent) e;
				if (!event.isLocal()) {
					if (event.getState() == DELIVERED) {
						LOG.info("Delivered new message");
						deliveryWaiter.resume();
					} else if (event.getState() == INVALID ||
							event.getState() == PENDING) {
						LOG.info("Validated new " + event.getState().name() +
								" message");
						validationWaiter.resume();
					}
				}
			}
		}
	}

	private void createAndRegisterIdentities() {
		author0 = identityManager0.createLocalAuthor(AUTHOR0);
		identityManager0.registerLocalAuthor(author0);
		author1 = identityManager1.createLocalAuthor(AUTHOR1);
		identityManager1.registerLocalAuthor(author1);
		author2 = identityManager2.createLocalAuthor(AUTHOR2);
		identityManager2.registerLocalAuthor(author2);
	}

	protected void addDefaultContacts() throws Exception {
		contactId1From0 = contactManager0
				.addContact(author1, author0.getId(), getSecretKey(),
						clock.currentTimeMillis(), true, true, true);
		contact1From0 = contactManager0.getContact(contactId1From0);
		contactId0From1 = contactManager1
				.addContact(author0, author1.getId(), getSecretKey(),
						clock.currentTimeMillis(), true, true, true);
		contact0From1 = contactManager1.getContact(contactId0From1);
		contactId2From0 = contactManager0
				.addContact(author2, author0.getId(), getSecretKey(),
						clock.currentTimeMillis(), true, true, true);
		contact2From0 = contactManager0.getContact(contactId2From0);
		contactId0From2 = contactManager2
				.addContact(author0, author2.getId(), getSecretKey(),
						clock.currentTimeMillis(), true, true, true);
		contact0From2 = contactManager2.getContact(contactId0From2);

		// Sync initial client versioning updates
		sync0To1(1, true);
		sync0To2(1, true);
		sync1To0(1, true);
		sync2To0(1, true);
		sync0To1(1, true);
		sync0To2(1, true);
	}

	protected void addContacts1And2() throws Exception {
		contactId2From1 = contactManager1
				.addContact(author2, author1.getId(), getSecretKey(),
						clock.currentTimeMillis(), true, true, true);
		contactId1From2 = contactManager2
				.addContact(author1, author2.getId(), getSecretKey(),
						clock.currentTimeMillis(), true, true, true);

		// Sync initial client versioning updates
		sync1To2(1, true);
		sync2To1(1, true);
		sync1To2(1, true);
	}

	@After
	public void tearDown() throws Exception {
		stopLifecycles();
		TestUtils.deleteTestDirectory(testDir);
	}

	private void stopLifecycles() throws InterruptedException {
		// Clean up
		lifecycleManager0.stopServices();
		lifecycleManager1.stopServices();
		lifecycleManager2.stopServices();
		lifecycleManager0.waitForShutdown();
		lifecycleManager1.waitForShutdown();
		lifecycleManager2.waitForShutdown();
	}

	protected void sync0To1(int num, boolean valid)
			throws IOException, TimeoutException {
		syncMessage(sync0, contactId0From1, sync1, contactId1From0, num, valid);
	}

	protected void sync0To2(int num, boolean valid)
			throws IOException, TimeoutException {
		syncMessage(sync0, contactId0From2, sync2, contactId2From0, num, valid);
	}

	protected void sync1To0(int num, boolean valid)
			throws IOException, TimeoutException {
		syncMessage(sync1, contactId1From0, sync0, contactId0From1, num, valid);
	}

	protected void sync2To0(int num, boolean valid)
			throws IOException, TimeoutException {
		syncMessage(sync2, contactId2From0, sync0, contactId0From2, num, valid);
	}

	protected void sync2To1(int num, boolean valid)
			throws IOException, TimeoutException {
		assertNotNull(contactId2From1);
		assertNotNull(contactId1From2);
		syncMessage(sync2, contactId2From1, sync1, contactId1From2, num, valid);
	}

	protected void sync1To2(int num, boolean valid)
			throws IOException, TimeoutException {
		assertNotNull(contactId2From1);
		assertNotNull(contactId1From2);
		syncMessage(sync1, contactId1From2, sync2, contactId2From1, num, valid);
	}

	private void syncMessage(SyncSessionFactory fromSync, ContactId fromId,
			SyncSessionFactory toSync, ContactId toId, int num, boolean valid)
			throws IOException, TimeoutException {

		// Debug output
		String from = "0";
		if (fromSync == sync1) from = "1";
		else if (fromSync == sync2) from = "2";
		String to = "0";
		if (toSync == sync1) to = "1";
		else if (toSync == sync2) to = "2";
		LOG.info("TEST: Sending message from " + from + " to " + to);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamWriter streamWriter = new TestStreamWriter(out);
		// Create an outgoing sync session
		SyncSession sessionFrom = fromSync.createSimplexOutgoingSession(toId,
				MAX_LATENCY, streamWriter);
		// Write whatever needs to be written
		sessionFrom.run();
		out.close();

		ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
		// Create an incoming sync session
		SyncSession sessionTo = toSync.createIncomingSession(fromId, in);
		// Read whatever needs to be read
		sessionTo.run();
		in.close();

		if (valid) {
			deliveryWaiter.await(TIMEOUT, num);
		} else {
			validationWaiter.await(TIMEOUT, num);
		}
	}

	protected void removeAllContacts() throws DbException {
		contactManager0.removeContact(contactId1From0);
		contactManager0.removeContact(contactId2From0);
		contactManager1.removeContact(contactId0From1);
		contactManager2.removeContact(contactId0From2);
		assertNotNull(contactId2From1);
		contactManager1.removeContact(contactId2From1);
		assertNotNull(contactId1From2);
		contactManager2.removeContact(contactId1From2);
	}

}
