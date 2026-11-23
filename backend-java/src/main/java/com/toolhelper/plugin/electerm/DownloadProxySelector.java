package com.toolhelper.plugin.electerm;

import com.toolhelper.support.ApiException;
import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 下载专用的代理观察层；遵循系统的首选路由，不探测端口或擅自切换线路。 */
final class DownloadProxySelector extends ProxySelector {
    private static final Logger LOG = LoggerFactory.getLogger(DownloadProxySelector.class);
    private final ProxySelector system;
    private final Map<String, Proxy> reportedRoutes = new ConcurrentHashMap<>();

    DownloadProxySelector(ProxySelector system) {
        this.system = system;
    }

    @Override public List<Proxy> select(URI uri) {
        Objects.requireNonNull(uri, "uri");
        List<Proxy> candidates = system == null ? List.of(Proxy.NO_PROXY) : system.select(uri);
        if (candidates == null || candidates.isEmpty() || candidates.getFirst() == null) {
            throw new ApiException(503, "PROXY_CONFIGURATION_INVALID", "系统代理未返回有效网络路由，请检查本机代理配置", true);
        }
        Proxy selected = candidates.getFirst();
        // JDK HttpClient 仅支持 HTTP 代理；SOCKS 会被当作直连，必须显式拒绝这种隐式绕过。
        if (selected.type() == Proxy.Type.SOCKS) {
            throw new ApiException(503, "PROXY_TYPE_UNSUPPORTED", "下载需要 HTTP 代理，请将系统代理配置为 HTTP 或 Mixed 监听端口", true);
        }
        // 每个目标首次选择或线路变化时记录一次；不记录带签名的下载 URL 或下载循环。
        String destination = uri.getScheme() + "://" + uri.getHost();
        if (!selected.equals(reportedRoutes.put(destination, selected))) {
            if (selected.type() == Proxy.Type.DIRECT) {
                LOG.info("官方下载网络路由 destination={} mode=DIRECT", destination);
            } else {
                InetSocketAddress address = (InetSocketAddress) selected.address();
                LOG.info("官方下载网络路由 destination={} mode=HTTP_PROXY proxyHost={} proxyPort={}",
                        destination, address.getHostString(), address.getPort());
            }
        }
        return List.of(selected);
    }

    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
        if (system != null) system.connectFailed(uri, address, error);
    }
}
