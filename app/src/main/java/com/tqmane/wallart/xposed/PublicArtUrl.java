package com.tqmane.wallart.xposed;

import java.net.URI;

final class PublicArtUrl {
    private PublicArtUrl() {
    }

    static String allowlisted(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com")
                    || host.equals("gstatic.com") || host.endsWith(".gstatic.com") ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
