package org.briarproject.bramble.plugin.tor;

import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.network.NetworkManager;
import org.briarproject.bramble.api.plugin.BackoffFactory;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.system.LocationUtils;
import org.briarproject.bramble.api.system.ResourceProvider;
import org.briarproject.bramble.test.BrambleJavaIntegrationTestComponent;
import org.briarproject.bramble.test.BrambleTestCase;
import org.briarproject.bramble.test.DaggerBrambleJavaIntegrationTestComponent;
import org.briarproject.bramble.util.OsUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.net.SocketFactory;

import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.briarproject.bramble.test.TestUtils.deleteTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.briarproject.bramble.test.TestUtils.isOptionalTestEnabled;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

@RunWith(Parameterized.class)
public class BridgeTest extends BrambleTestCase {

	@Parameters
	public static Iterable<String> data() {
		BrambleJavaIntegrationTestComponent component =
				DaggerBrambleJavaIntegrationTestComponent.builder().build();
		return component.getCircumventionProvider().getBridges();
	}

	private final static long TIMEOUT = SECONDS.toMillis(30);

	private final static Logger LOG =
			Logger.getLogger(BridgeTest.class.getName());

	@Inject
	NetworkManager networkManager;
	@Inject
	ResourceProvider resourceProvider;
	@Inject
	CircumventionProvider circumventionProvider;
	@Inject
	EventBus eventBus;
	@Inject
	BackoffFactory backoffFactory;
	@Inject
	Clock clock;

	private final File torDir = getTestDirectory();
	private final String bridge;

	private LinuxTorPluginFactory factory;

	public BridgeTest(String bridge) {
		this.bridge = bridge;
	}

	@Before
	public void setUp() {
		// Skip this test unless it's explicitly enabled in the environment
		assumeTrue(isOptionalTestEnabled(BridgeTest.class));

		// TODO: Remove this assumption when the plugin supports other platforms
		assumeTrue(OsUtils.isLinux());

		BrambleJavaIntegrationTestComponent component =
				DaggerBrambleJavaIntegrationTestComponent.builder().build();
		component.inject(this);

		Executor ioExecutor = Executors.newCachedThreadPool();
		LocationUtils locationUtils = () -> "US";
		SocketFactory torSocketFactory = SocketFactory.getDefault();

		CircumventionProvider bridgeProvider = new CircumventionProvider() {
			@Override
			public boolean isTorProbablyBlocked(String countryCode) {
				return true;
			}

			@Override
			public boolean doBridgesWork(String countryCode) {
				return true;
			}

			@Override
			public List<String> getBridges() {
				return singletonList(bridge);
			}
		};
		factory = new LinuxTorPluginFactory(ioExecutor, networkManager,
				locationUtils, eventBus, torSocketFactory, backoffFactory,
				resourceProvider, bridgeProvider, clock, torDir);
	}

	@After
	public void tearDown() {
		deleteTestDirectory(torDir);
	}

	@Test
	public void testBridges() throws Exception {
		DuplexPlugin duplexPlugin =
				factory.createPlugin(new TorPluginCallBack());
		assertNotNull(duplexPlugin);
		LinuxTorPlugin plugin = (LinuxTorPlugin) duplexPlugin;

		LOG.warning("Testing " + bridge);
		try {
			plugin.start();
			long start = clock.currentTimeMillis();
			while (clock.currentTimeMillis() - start < TIMEOUT) {
				if (plugin.isRunning()) return;
				clock.sleep(500);
			}
			if (!plugin.isRunning()) {
				fail("Could not connect to Tor within timeout.");
			}
		} finally {
			plugin.stop();
		}
	}

}
