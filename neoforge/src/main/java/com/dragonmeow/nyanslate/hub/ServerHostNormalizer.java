package com.dragonmeow.nyanslate.hub;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Collapses a Minecraft server address down to its registrable domain (for example
 * {@code mc.hypixel.net} -&gt; {@code hypixel.net}), so the shared repository can key
 * server translations by ownership rather than by every subdomain/port variant a
 * server operator happens to use.
 *
 * <p>Deliberately conservative: anything that is not an ordinary public hostname — a
 * bare IP (v4 or v6), {@code localhost}, a {@code .local} mDNS name, or a single-label
 * host with no dot at all — normalizes to {@link Optional#empty()}. Singleplayer/LAN/
 * Realms sessions have no server address at all; the caller passes {@code null} for
 * those instead of calling this method.</p>
 */
public final class ServerHostNormalizer {
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    private static final Pattern PORT_SUFFIX = Pattern.compile("\\d{1,5}");
    /** A3: final whitelist the computed registrable domain must satisfy before it is ever
     *  used to build a hub repository path/URL. {@code registrableDomain}'s naive
     *  {@code split("\\.")}/rejoin does not itself validate character content, so without
     *  this check a crafted address like {@code "evil.com/x/y.ext"} would leave its '/'
     *  untouched and flow straight into {@link HubPaths#serverPath}. Every label must be a
     *  non-empty run of ASCII letters/digits/hyphens (no leading/trailing hyphen, no empty
     *  label from a stray "..").*/
    private static final Pattern VALID_REGISTRABLE_DOMAIN = Pattern.compile(
            "^[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.[a-z0-9]+(?:-[a-z0-9]+)*)+$");
    /** Second-level public suffixes this normalizer knows about. Anything else keeps
     *  the ordinary "last two labels" eTLD+1 approximation. */
    private static final Set<String> MULTI_PART_SUFFIXES = Set.of(
            "co.uk", "org.uk", "gov.uk", "ac.uk", "com.au", "net.au", "org.au",
            "com.br", "co.jp", "co.kr", "com.cn", "com.tw", "co.nz", "co.za",
            "co.in", "com.mx", "com.ar", "com.sg", "com.hk");

    private ServerHostNormalizer() {
    }

    public static Optional<String> normalize(String rawAddr) {
        if (rawAddr == null) return Optional.empty();
        String trimmed = rawAddr.strip();
        if (trimmed.isEmpty()) return Optional.empty();

        String host = stripPort(trimmed);
        if (host == null) return Optional.empty(); // bracketed/raw IPv6 literal

        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return Optional.empty();
        if (host.indexOf(':') >= 0) return Optional.empty(); // defensive: IPv6
        if (IPV4.matcher(host).matches()) return Optional.empty();
        if (host.equals("localhost") || host.endsWith(".local")) return Optional.empty();
        if (host.indexOf('.') < 0) return Optional.empty(); // single label, no registrable domain

        String domain = registrableDomain(host);
        if (!VALID_REGISTRABLE_DOMAIN.matcher(domain).matches()) return Optional.empty();
        return Optional.of(domain);
    }

    /**
     * @return the host with any {@code :port} suffix removed, or {@code null} when the
     *         input is an IPv6 literal (bracketed, or bare with 2+ colons) — this
     *         normalizer never has any use for one.
     */
    private static String stripPort(String text) {
        if (text.startsWith("[")) {
            return null; // bracketed form (optionally "[::1]:25565") is always IPv6
        }
        int firstColon = text.indexOf(':');
        if (firstColon < 0) return text;
        int lastColon = text.lastIndexOf(':');
        if (firstColon != lastColon) return null; // 2+ colons: bare IPv6, not host:port
        String portPart = text.substring(firstColon + 1);
        if (!PORT_SUFFIX.matcher(portPart).matches()) return null; // not a plausible port
        return text.substring(0, firstColon);
    }

    private static String registrableDomain(String host) {
        String[] labels = host.split("\\.");
        if (labels.length <= 2) return host;
        String lastTwo = labels[labels.length - 2] + "." + labels[labels.length - 1];
        int take = MULTI_PART_SUFFIXES.contains(lastTwo) ? 3 : 2;
        take = Math.min(take, labels.length);
        StringBuilder result = new StringBuilder();
        for (int i = labels.length - take; i < labels.length; i++) {
            if (result.length() > 0) result.append('.');
            result.append(labels[i]);
        }
        return result.toString();
    }
}
