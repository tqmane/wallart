package com.tqmane.wallart;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A non-sensitive identity: only a hash of model fields is persisted. */
public final class CardIdentity {
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern CONTROL = Pattern.compile("[\\r\\n\\t]");

    public final String id;
    public final String displayName;
    public final String network;
    public final String lastFour;
    public final String[] lookupFingerprints;

    private CardIdentity(String id, String displayName, String network, String lastFour, String[] lookupFingerprints) {
        this.id = id;
        this.displayName = displayName;
        this.network = network;
        this.lastFour = lastFour;
        this.lookupFingerprints = lookupFingerprints;
    }

    public CardIdentity withId(String resolvedId) {
        return resolvedId == null ? null : new CardIdentity(resolvedId, displayName, network, lastFour, lookupFingerprints);
    }

    public CardIdentity withStableKeyParts(List<String> parts) {
        if (parts == null || parts.isEmpty()) return this;
        ArrayList<String> fingerprints = new ArrayList<>();
        Collections.addAll(fingerprints, lookupFingerprints);
        addStableKeyFingerprints(fingerprints, String.join("\u001f", parts));
        return new CardIdentity(id, displayName, network, lastFour, fingerprints.toArray(new String[0]));
    }

    public String gmsLinkKey() {
        ArrayList<String> fingerprints = new ArrayList<>();
        Collections.addAll(fingerprints, lookupFingerprints);
        Collections.sort(fingerprints);
        return sha256("gms-card-link\u001f" + id + "\u001f" + String.join("\u001f", fingerprints));
    }

    public static CardIdentity fromModel(Object model) {
        if (model == null) return null;
        try {
            ArrayList<String> strings = new ArrayList<>();
            String artworkUri = "";
            int networkCode = 1000;
            for (Field field : model.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Class<?> type = field.getType();
                if (type == String.class) {
                    String value = (String) field.get(model);
                    if (value != null && !value.isEmpty()) strings.add(value);
                } else if (type == int.class && networkCode == 1000) {
                    networkCode = field.getInt(model);
                } else if ("android.net.Uri".equals(type.getName())) {
                    Object value = field.get(model);
                    artworkUri = value == null ? "" : value.toString();
                }
            }
            if (strings.isEmpty() && artworkUri.isEmpty()) return null;
            return create(networkCode, strings, artworkUri);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static CardIdentity fromStableKey(String stableKey, List<String> labels, String artworkUri) {
        if (stableKey == null || stableKey.isEmpty()) return create(1000, labels, artworkUri);
        return create(stableKey, 1000, labels, artworkUri);
    }

    static CardIdentity create(int networkCode, List<String> rawStrings, String artworkUri) {
        return create(null, networkCode, rawStrings, artworkUri);
    }

    private static CardIdentity create(String stableKey, int networkCode, List<String> rawStrings, String artworkUri) {
        ArrayList<String> strings = new ArrayList<>();
        for (String value : rawStrings) {
            if (value != null && !value.isEmpty()) strings.add(value);
        }
        if (strings.isEmpty() && (artworkUri == null || artworkUri.isEmpty())
                && (stableKey == null || stableKey.isEmpty())) return null;
        String lastFour = findLastFour(strings);
        String network = networkName(networkCode, strings);
        String label = mask(selectLabel(strings), lastFour);
        ArrayList<String> canonical = new ArrayList<>(strings);
        Collections.sort(canonical);
        String stableArtwork = stableUri(artworkUri);
        String identityMaterial = stableKey != null
                ? "wallet-stable\u001f" + stableKey
                : lastFour != null
                ? network + "\u001f" + lastFour + (stableArtwork.isEmpty() ? "" : "\u001f" + stableArtwork)
                : network + "\u001f" + (canonical.isEmpty()
                        ? stableArtwork
                        : String.join("\u001f", canonical));
        String id = sha256(identityMaterial);
        if (label.isEmpty()) {
            String alias = id.replaceAll("[0-9]", "");
            if (alias.length() > 6) alias = alias.substring(0, 6);
            String fallback = stableKey == null ? "Google Wallet card"
                    : "Wallet card · " + alias.toUpperCase(Locale.ROOT);
            label = fallback;
        }
        return new CardIdentity(id, label, network, lastFour,
                lookupFingerprints(network, lastFour, stableArtwork, stableKey, strings, label));
    }

    private static String[] lookupFingerprints(String network, String lastFour, String artUri,
            String stableKey, List<String> labels, String label) {
        ArrayList<String> result = new ArrayList<>(8);
        if (!artUri.isEmpty()) {
            if (lastFour != null) result.add(sha256("art-url-last4\u001f" + artUri + "\u001f" + lastFour));
            result.add(sha256("art-url\u001f" + artUri));
        }
        if (lastFour != null) result.add(sha256("network-last4\u001f" + network + "\u001f" + lastFour));
        if ("Suica".equals(network) || "QUICPay".equals(network)) {
            result.add(sha256("wallet-card-type\u001f" + network));
        }
        addStableKeyFingerprints(result, stableKey);
        addLabelFingerprint(result, label);
        for (String value : labels) {
            if (result.size() >= 12) break;
            addLabelFingerprint(result, value);
        }
        return result.toArray(new String[0]);
    }

    private static void addStableKeyFingerprints(List<String> fingerprints, String stableKey) {
        if (stableKey == null) return;
        int added = 0;
        for (String part : stableKey.split("\u001f")) {
            if (part.length() < 12 || part.length() > 128 || part.startsWith("http")
                    || part.chars().anyMatch(Character::isWhitespace)) continue;
            boolean hasLetter = part.codePoints().anyMatch(Character::isLetter);
            boolean hasDigit = part.codePoints().anyMatch(Character::isDigit);
            if (!hasLetter || !hasDigit) continue;
            Matcher digits = DIGITS.matcher(part);
            boolean looksLikeNumber = false;
            while (digits.find()) if (digits.group().length() >= 8) looksLikeNumber = true;
            if (looksLikeNumber) continue;
            String fingerprint = sha256("wallet-stable-component\u001f" + part);
            if (!fingerprints.contains(fingerprint)) {
                fingerprints.add(fingerprint);
                if (++added == 16) break;
            }
        }
    }

    private static void addLabelFingerprint(List<String> fingerprints, String value) {
        if (value == null || value.isEmpty() || value.length() > 128 || value.startsWith("http")
                || value.codePoints().noneMatch(Character::isLetter)) return;
        Matcher digits = DIGITS.matcher(value);
        while (digits.find()) if (digits.group().length() >= 8) return;
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        if (!normalized.isEmpty()) {
            String fingerprint = sha256("card-label\u001f" + normalized);
            if (!fingerprints.contains(fingerprint)) fingerprints.add(fingerprint);
        }
    }

    static String findLastFour(List<String> values) {
        for (String value : values) {
            Matcher matcher = DIGITS.matcher(value);
            String last = null;
            while (matcher.find()) {
                String digits = matcher.group();
                if (digits.length() >= 4) last = digits.substring(digits.length() - 4);
            }
            if (last != null) return last;
        }
        return null;
    }

    private static String selectLabel(List<String> values) {
        String selected = "";
        int best = Integer.MIN_VALUE;
        for (String value : values) {
            if (value == null || value.isEmpty() || value.startsWith("http")) continue;
            int score = Math.min(value.length(), 80);
            boolean hasLetters = value.codePoints().anyMatch(Character::isLetter);
            if (hasLetters) score += 1000;
            if (hasLetters && (value.indexOf('•') >= 0 || value.indexOf('*') >= 0)) score += 1000;
            if (hasLetters && findLastFour(Collections.singletonList(value)) != null) score += 250;
            String lower = value.trim().toLowerCase(Locale.ROOT);
            if (lower.equals("suica") || lower.equals("quicpay") || lower.equals("quickpay")
                    || lower.equals("quick pay")) score += 2000;
            if (score > best) {
                best = score;
                selected = value;
            }
        }
        return selected;
    }

    private static String mask(String value, String lastFour) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        Matcher matcher = DIGITS.matcher(value);
        int end = 0;
        while (matcher.find()) {
            result.append(value, end, matcher.start());
            String digits = matcher.group();
            result.append(digits.length() > 4 ? "•••• " + digits.substring(digits.length() - 4) : digits);
            end = matcher.end();
        }
        result.append(value, end, value.length());
        String cleaned = CONTROL.matcher(result.toString()).replaceAll(" ").trim();
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80);
        return cleaned.isEmpty() && lastFour != null ? "•••• " + lastFour : cleaned;
    }

    private static String networkName(int code, List<String> values) {
        switch (code) {
            case 1: return "American Express";
            case 2: return "Discover";
            case 3: return "Mastercard";
            case 4: return "Visa";
            case 5: return "Interac";
            case 6: return "eftpos";
            case 7: return "Maestro";
            case 8: return "Elo";
            default:
                String text = String.join(" ", values).toLowerCase(Locale.ROOT);
                if (text.contains("visa")) return "Visa";
                if (text.contains("mastercard")) return "Mastercard";
                if (text.contains("amex") || text.contains("american express")) return "American Express";
                if (text.contains("suica")) return "Suica";
                if (text.contains("quicpay") || text.contains("quickpay") || text.contains("quick pay")) return "QUICPay";
                return "Card";
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String stableUri(String value) {
        if (value == null) return "";
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int end = value.length();
        if (query >= 0) end = query;
        if (fragment >= 0) end = Math.min(end, fragment);
        return value.substring(0, end).replaceFirst("=w\\d+$", "");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
