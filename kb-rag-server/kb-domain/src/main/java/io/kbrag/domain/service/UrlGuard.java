package io.kbrag.domain.service;

import io.kbrag.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * The SSRF gate of URL import, the M12 contract section 3.2.
 *
 * <p>URL import is the first place in the system where an outbound request goes to an address a
 * user typed rather than one an operator configured, so this guard is what keeps "index this page"
 * from becoming "read the cloud metadata endpoint". Every address the host resolves to has to be
 * public: a name with one public and one private A record is exactly the classic bypass.
 *
 * <p>The fetcher re-runs the guard on every redirect hop and on every sync (the registration may be
 * days old and DNS may have moved), which is why validation lives in a stateless domain service
 * both kb-app and the kb-infrastructure fetcher can share.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class UrlGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Validates one outbound URL, rejecting anything that could reach an internal address.
     *
     * @param url address as the user or a redirect supplied it
     * @return parsed URI, ready for the fetcher
     */
    public URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw BizException.invalidParam("URL 不能为空");
        }
        URI uri = parse(url.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw BizException.invalidParam("仅支持 http/https 地址");
        }
        // Credentials in a URL are a smuggling vector ("http://internal@evil/") and no legitimate
        // public page needs them; anonymous fetch is a scope decision of the contract.
        if (uri.getUserInfo() != null) {
            throw BizException.invalidParam("URL 不能携带用户名密码");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw BizException.invalidParam("URL 缺少主机名");
        }
        for (InetAddress address : resolve(host)) {
            if (isInternal(address)) {
                log.info("url rejected by ssrf guard, host={}, address={}", host, address.getHostAddress());
                throw BizException.invalidParam("该地址指向内网或本机，禁止抓取");
            }
        }
        return uri;
    }

    private URI parse(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("URL 格式不正确");
        }
    }

    private InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw BizException.invalidParam("无法解析该域名");
        }
    }

    /**
     * Whether an address is unreachable-from-outside territory: loopback, RFC1918, link local
     * (which covers 169.254.169.254, the metadata endpoint), multicast or the wildcard.
     */
    private boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isAnyLocalAddress();
    }
}
