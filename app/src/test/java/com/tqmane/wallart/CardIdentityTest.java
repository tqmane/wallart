package com.tqmane.wallart;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class CardIdentityTest {
    @Test
    public void keepsOnlyMaskedLastFourAndStableHash() {
        CardIdentity first = CardIdentity.create(4, Arrays.asList("Visa •••• 1234", "1234567890123456"), "https://example/card?sig=one");
        CardIdentity second = CardIdentity.create(4, Arrays.asList("Visa •••• 1234", "1234567890123456"), "https://example/card?sig=two");
        CardIdentity different = CardIdentity.create(4, Arrays.asList("Visa •••• 5678", "9876543210985678"), "https://example/card");
        assertEquals(first.id, second.id);
        assertNotEquals(first.id, different.id);
        assertEquals("1234", first.lastFour);
        assertTrue(first.displayName.contains("1234"));
        assertTrue(!first.displayName.contains("1234567890123456"));
    }

    @Test
    public void stableKeyWinsWhenWalletLabelOrArtworkChanges() {
        CardIdentity first = CardIdentity.fromStableKey("instrument-opaque-key",
                Arrays.asList("Visa •••• 1234", "1234567890123456"), "https://example/one");
        CardIdentity second = CardIdentity.fromStableKey("instrument-opaque-key",
                Arrays.asList("Visa •••• 9876"), "https://example/two");
        assertEquals(first.id, second.id);
        assertEquals("1234", first.lastFour);
        assertTrue(!first.displayName.contains("1234567890123456"));
        assertNotEquals(first.id, CardIdentity.fromStableKey("another-key",
                Arrays.asList("Visa •••• 1234"), "https://example/one").id);
    }

    @Test
    public void opaqueStableKeysGetDistinctSafeLabels() {
        CardIdentity first = CardIdentity.fromStableKey("instrument-one", Arrays.<String>asList(), "https://example/one");
        CardIdentity second = CardIdentity.fromStableKey("instrument-two", Arrays.<String>asList(), "https://example/two");
        assertNotEquals(first.displayName, second.displayName);
        assertTrue(first.displayName.startsWith("Wallet card · "));
        assertEquals("Card", first.network);
        assertEquals(null, first.lastFour);
    }

    @Test
    public void recognizesTransitAndQuickPayLabels() {
        CardIdentity suica = CardIdentity.fromStableKey("suica-instrument", Arrays.asList("Suica"), null);
        CardIdentity quickPay = CardIdentity.fromStableKey("qp-instrument", Arrays.asList("QUICPay"), null);
        assertEquals("Suica", suica.network);
        assertEquals("Suica", suica.displayName);
        assertEquals("QUICPay", quickPay.network);
        assertEquals("QUICPay", quickPay.displayName);
    }

    @Test
    public void artworkUrlDisambiguatesCardsWithTheSameNetworkAndLastFour() {
        CardIdentity first = CardIdentity.create(4, Arrays.asList("Visa •••• 1234"), "https://issuer.example/card-a=w700?size=1");
        CardIdentity same = CardIdentity.create(4, Arrays.asList("Visa •••• 1234"), "https://issuer.example/card-a=w900?size=2");
        CardIdentity different = CardIdentity.create(4, Arrays.asList("Visa •••• 1234"), "https://issuer.example/card-b=w700");
        assertEquals(first.id, same.id);
        assertNotEquals(first.id, different.id);
    }

    @Test
    public void lookupFingerprintsMatchAcrossNetworkLabelsAndImageSizes() {
        CardIdentity home = CardIdentity.fromStableKey("wallet-home-key",
                Arrays.asList("QUICPay", "•••• 1234"), "https://issuer.example/card-a=w700?size=home");
        CardIdentity detail = CardIdentity.create(4, Arrays.asList("Visa •••• 1234"),
                "https://issuer.example/card-a=w900?size=detail");
        assertEquals(home.lookupFingerprints[0], detail.lookupFingerprints[0]);
    }
}
