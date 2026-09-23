package com.tqmane.wallart.xposed;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class PublicArtUrlTest {
    @Test
    public void onlyAllowsUncredentialedHttpsGoogleImageHosts() {
        String publicUrl = "https://lh3.googleusercontent.com/card-art=w700";
        assertEquals(publicUrl, PublicArtUrl.allowlisted(publicUrl));
        assertEquals("https://fonts.gstatic.com/card-art", PublicArtUrl.allowlisted("https://fonts.gstatic.com/card-art"));
        assertNull(PublicArtUrl.allowlisted("http://lh3.googleusercontent.com/card-art"));
        assertNull(PublicArtUrl.allowlisted("https://lh3.googleusercontent.com/card-art?token=secret"));
        assertNull(PublicArtUrl.allowlisted("https://user@lh3.googleusercontent.com/card-art"));
        assertNull(PublicArtUrl.allowlisted("https://example.com/card-art"));
        assertNull(PublicArtUrl.allowlisted("https://lh3.googleusercontent.com:8443/card-art"));
    }
}
