package com.dragonmeow.nyanlex.hub;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerHostNormalizerTest {

    @Test
    void subdomainCollapsesToRegistrableDomain() {
        assertEquals(Optional.of("hypixel.net"), ServerHostNormalizer.normalize("mc.hypixel.net"));
        assertEquals(Optional.of("hypixel.net"), ServerHostNormalizer.normalize("Mc.Hypixel.NET"));
    }

    @Test
    void portIsStripped() {
        assertEquals(Optional.of("hypixel.net"), ServerHostNormalizer.normalize("mc.hypixel.net:25565"));
    }

    @Test
    void trailingDotIsStripped() {
        assertEquals(Optional.of("hypixel.net"), ServerHostNormalizer.normalize("mc.hypixel.net."));
    }

    @Test
    void multiPartSuffixKeepsThreeLabels() {
        assertEquals(Optional.of("example.co.uk"), ServerHostNormalizer.normalize("mc.example.co.uk"));
    }

    @Test
    void twoLabelDomainIsUnchanged() {
        assertEquals(Optional.of("hypixel.net"), ServerHostNormalizer.normalize("hypixel.net"));
    }

    @Test
    void ipv4AddressIsRejected() {
        assertTrue(ServerHostNormalizer.normalize("192.168.1.5").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("8.8.8.8:25565").isEmpty());
    }

    @Test
    void ipv6AddressIsRejected() {
        assertTrue(ServerHostNormalizer.normalize("::1").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("[::1]:25565").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("fe80::1234:5678").isEmpty());
    }

    @Test
    void localhostAndDotLocalAreRejected() {
        assertTrue(ServerHostNormalizer.normalize("localhost").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("LOCALHOST:25565").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("myserver.local").isEmpty());
    }

    @Test
    void singleLabelHostIsRejected() {
        assertTrue(ServerHostNormalizer.normalize("myserver").isEmpty());
    }

    @Test
    void blankOrNullIsRejected() {
        assertTrue(ServerHostNormalizer.normalize(null).isEmpty());
        assertTrue(ServerHostNormalizer.normalize("").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("   ").isEmpty());
    }

    // --- A3 (2026-10-01): the output must be a plain [a-z0-9.-] registrable domain, never
    // a value that can carry a path/traversal segment into HubPaths#serverPath's URL. ---

    @Test
    void embeddedPathIsRejectedNotPassedThrough() {
        // registrableDomain's naive split("\\.")/rejoin used to let a trailing "/x/y.ext"
        // ride along inside what looked like the last two labels.
        assertTrue(ServerHostNormalizer.normalize("evil.com/x/y.ext").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("evil.com/../admin").isEmpty());
    }

    @Test
    void bareSlashIsRejected() {
        assertTrue(ServerHostNormalizer.normalize("/").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("../").isEmpty());
    }

    @Test
    void percentEncodedDotTraversalIsRejected() {
        // "%2e" is never decoded by this normalizer; it must simply fail the output
        // whitelist like any other non-hostname character, not be treated as ".".
        assertTrue(ServerHostNormalizer.normalize("evil.com%2e%2e/admin").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("%2e%2e/evil.com").isEmpty());
    }

    @Test
    void outputIsAlwaysRestrictedToLowercaseAlnumDotHyphen() {
        assertEquals(Optional.of("hypixel.net"), ServerHostNormalizer.normalize("mc.hypixel.net"));
        assertTrue(ServerHostNormalizer.normalize("evil.com\\x").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("evil.com?x=1").isEmpty());
        assertTrue(ServerHostNormalizer.normalize("evil.com#frag").isEmpty());
    }
}
