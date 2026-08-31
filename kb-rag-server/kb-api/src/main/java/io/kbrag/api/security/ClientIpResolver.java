package io.kbrag.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 在显式可信代理边界内解析客户端地址。
 *
 * <p>未命中可信代理时只认 socket peer；命中后从 X-Forwarded-For 右向左剥离可信代理，
 * 取第一个不可信地址。这样既支持官方 Vite 反代，也不允许直连调用方伪造转发头。
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ClientIpResolver {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_FORWARDED_HEADER_LENGTH = 1_024;
    private static final int MAX_FORWARDED_HOPS = 10;
    private static final int IPV4_BITS = 32;
    private static final int IPV6_BITS = 128;

    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver(
            @Value("${kb.web.trusted-proxy-cidrs:127.0.0.1/32,::1/128}") String trustedProxyCidrs) {
        this.trustedProxies = parseTrustedProxies(trustedProxyCidrs);
    }

    /**
     * 解析当前请求的可信客户端地址。
     *
     * @param request servlet 请求
     * @return 规范化 IP；容器未提供合法 peer 时退回原始值
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        InetAddress directPeer = parseIpLiteral(remoteAddress);
        if (directPeer == null || !isTrustedProxy(directPeer)) {
            return directPeer == null ? safeFallback(remoteAddress) : canonical(directPeer);
        }

        String forwarded = request.getHeader(HEADER_FORWARDED_FOR);
        if (forwarded == null || forwarded.isBlank()
                || forwarded.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return canonical(directPeer);
        }
        String[] hops = forwarded.split(",", -1);
        if (hops.length > MAX_FORWARDED_HOPS) {
            return canonical(directPeer);
        }
        for (int index = hops.length - 1; index >= 0; index--) {
            InetAddress hop = parseIpLiteral(hops[index].trim());
            if (hop == null) {
                return canonical(directPeer);
            }
            if (!isTrustedProxy(hop)) {
                return canonical(hop);
            }
        }
        return canonical(directPeer);
    }

    private List<CidrBlock> parseTrustedProxies(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<CidrBlock> blocks = new ArrayList<>();
        for (String raw : configured.split(",")) {
            String value = raw.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("trusted proxy CIDR must not contain empty entries");
            }
            blocks.add(CidrBlock.parse(value));
        }
        return List.copyOf(blocks);
    }

    private boolean isTrustedProxy(InetAddress address) {
        return trustedProxies.stream().anyMatch(block -> block.contains(address));
    }

    private static InetAddress parseIpLiteral(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return null;
        }
        String candidate = value.trim();
        if (!looksLikeIpv4(candidate) && !looksLikeIpv6(candidate)) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate);
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    private static boolean looksLikeIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            int number = 0;
            for (int index = 0; index < octet.length(); index++) {
                char character = octet.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
                number = number * 10 + character - '0';
            }
            if (number > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeIpv6(String value) {
        if (!value.contains(":")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = character == ':' || character == '.'
                    || character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static String canonical(InetAddress address) {
        return address.getHostAddress();
    }

    private static String safeFallback(String value) {
        return value == null || value.length() > 64 ? "" : value;
    }

    private record CidrBlock(byte[] network, int prefixLength) {

        private static CidrBlock parse(String value) {
            int separator = value.lastIndexOf('/');
            String addressPart = separator < 0 ? value : value.substring(0, separator);
            InetAddress address = parseIpLiteral(addressPart);
            if (address == null) {
                throw new IllegalArgumentException("invalid trusted proxy CIDR: " + value);
            }
            int maxBits = address.getAddress().length == 4 ? IPV4_BITS : IPV6_BITS;
            int prefix = separator < 0 ? maxBits : parsePrefix(value.substring(separator + 1), value);
            if (prefix <= 0 || prefix > maxBits) {
                throw new IllegalArgumentException("trusted proxy CIDR prefix must be within 1.." + maxBits);
            }
            byte[] network = address.getAddress().clone();
            clearHostBits(network, prefix);
            return new CidrBlock(network, prefix);
        }

        private boolean contains(InetAddress candidate) {
            byte[] address = candidate.getAddress();
            if (address.length != network.length) {
                return false;
            }
            int wholeBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < wholeBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (Byte.SIZE - remainingBits);
            return (address[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }

        private static int parsePrefix(String raw, String source) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid trusted proxy CIDR: " + source, e);
            }
        }

        private static void clearHostBits(byte[] address, int prefix) {
            int wholeBytes = prefix / Byte.SIZE;
            int remainingBits = prefix % Byte.SIZE;
            if (remainingBits != 0) {
                int mask = 0xFF << (Byte.SIZE - remainingBits);
                address[wholeBytes] = (byte) (address[wholeBytes] & mask);
                wholeBytes++;
            }
            for (int index = wholeBytes; index < address.length; index++) {
                address[index] = 0;
            }
        }
    }
}
