package com.toolhelper.plugin.electerm;

import com.toolhelper.support.ApiException;
import java.io.IOException;
import java.net.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DownloadProxySelectorTest {
    private static final URI GITHUB = URI.create("https://github.com/electerm/electerm/releases/download/v5.12.6/package.tar.gz");
    private static final Proxy HTTP = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 18765));

    @Test void noSystemProxyUsesDirectConnection() {
        assertEquals(List.of(Proxy.NO_PROXY), new DownloadProxySelector(null).select(GITHUB));
        assertEquals(List.of(Proxy.NO_PROXY), new DownloadProxySelector(new Routes(List.of(Proxy.NO_PROXY))).select(GITHUB));
    }

    @Test void followsSystemRouteForEachDestinationIncludingBypassAndAssetRedirects() {
        ProxySelector system = new Routes(List.of(HTTP)) {
            @Override public List<Proxy> select(URI uri) {
                return List.of(uri.getHost().equals("api.github.com") ? Proxy.NO_PROXY : HTTP);
            }
        };
        DownloadProxySelector selector = new DownloadProxySelector(system);
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("https://api.github.com/repos/electerm/electerm/releases/latest")));
        assertEquals(List.of(HTTP), selector.select(GITHUB));
        assertEquals(List.of(HTTP), selector.select(URI.create("https://release-assets.githubusercontent.com/file?sig=secret")));
    }

    @Test void observesRouteChangesWithoutCachingThePreviousDecision() {
        Routes system = new Routes(List.of(Proxy.NO_PROXY));
        DownloadProxySelector selector = new DownloadProxySelector(system);
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(GITHUB));
        system.routes = List.of(HTTP);
        assertEquals(List.of(HTTP), selector.select(GITHUB));
        system.routes = List.of(Proxy.NO_PROXY);
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(GITHUB));
    }

    @Test void keepsPreferredRouteInsteadOfFallingBackToDirectOrOverridingBypass() {
        assertEquals(List.of(HTTP), new DownloadProxySelector(new Routes(List.of(HTTP, Proxy.NO_PROXY))).select(GITHUB));
        assertEquals(List.of(Proxy.NO_PROXY), new DownloadProxySelector(new Routes(List.of(Proxy.NO_PROXY, HTTP))).select(GITHUB));
    }

    @Test void unsupportedSocksAndInvalidSelectionsFailInsteadOfSilentlyConnectingDirectly() {
        Proxy socks = new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 18766));
        ApiException error = assertThrows(ApiException.class,
                () -> new DownloadProxySelector(new Routes(List.of(socks, Proxy.NO_PROXY))).select(GITHUB));
        assertEquals("PROXY_TYPE_UNSUPPORTED", error.code());
        assertEquals("PROXY_CONFIGURATION_INVALID", assertThrows(ApiException.class,
                () -> new DownloadProxySelector(new Routes(List.of())).select(GITHUB)).code());
        assertEquals("PROXY_CONFIGURATION_INVALID", assertThrows(ApiException.class,
                () -> new DownloadProxySelector(new Routes(null)).select(GITHUB)).code());
    }

    @Test void notifiesSystemSelectorOfConnectionFailure() {
        Routes system = new Routes(List.of(HTTP));
        IOException failure = new IOException("proxy unavailable");
        new DownloadProxySelector(system).connectFailed(GITHUB, HTTP.address(), failure);
        assertSame(failure, system.failure);
    }

    private static class Routes extends ProxySelector {
        List<Proxy> routes;
        IOException failure;
        Routes(List<Proxy> routes) { this.routes = routes; }
        @Override public List<Proxy> select(URI uri) { return routes; }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { failure = error; }
    }
}
