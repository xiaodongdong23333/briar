package org.briarproject.bramble.api.plugin;

public interface TorConstants {

	TransportId ID = new TransportId("org.briarproject.bramble.tor");

	String PROP_ONION_V2 = "onion";
	String PROP_ONION_V3 = "onion3";

	int SOCKS_PORT = 59050;
	int CONTROL_PORT = 59051;

	int CONNECT_TO_PROXY_TIMEOUT = 5000; // Milliseconds
	int EXTRA_SOCKET_TIMEOUT = 30000; // Milliseconds

	String PREF_TOR_NETWORK = "network2";
	String PREF_TOR_PORT = "port";
	String PREF_TOR_MOBILE = "useMobileData";

	int PREF_TOR_NETWORK_AUTOMATIC = 0;
	int PREF_TOR_NETWORK_WITHOUT_BRIDGES = 1;
	int PREF_TOR_NETWORK_WITH_BRIDGES = 2;
	int PREF_TOR_NETWORK_NEVER = 3;

}
