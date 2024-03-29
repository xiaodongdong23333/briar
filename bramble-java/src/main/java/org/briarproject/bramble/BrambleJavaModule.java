package org.briarproject.bramble;

import org.briarproject.bramble.network.JavaNetworkModule;
import org.briarproject.bramble.plugin.tor.CircumventionModule;
import org.briarproject.bramble.system.JavaSystemModule;

import dagger.Module;

@Module(includes = {
		JavaNetworkModule.class,
		JavaSystemModule.class,
		CircumventionModule.class
})
public class BrambleJavaModule {

}
