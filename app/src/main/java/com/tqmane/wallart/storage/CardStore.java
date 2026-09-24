package com.tqmane.wallart.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.tqmane.wallart.BuildConfig;
import com.tqmane.wallart.FitMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class CardStore {
    public static final String WALLET_PACKAGE = "com.google.android.apps.walletnfcrel";
    public static final String GOOGLE_PAY_PACKAGE = "com.google.android.gms";
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".provider";
    public static final String METHOD_RECORD = "record_card";
    public static final String METHOD_RESOLVE = "resolve_art";
    public static final String METHOD_RESOLVE_IDENTITY = "resolve_identity";
    public static final String METHOD_SET_PENDING_SELECTION = "set_pending_selection";
    public static final String METHOD_RESOLVE_PENDING_SELECTION = "resolve_pending_selection";
    public static final String METHOD_CLEAR_PENDING_SELECTION = "clear_pending_selection";
    public static final String METHOD_SET_PENDING_GMS_LINK = "set_pending_gms_link";
    public static final String METHOD_RESOLVE_GMS_LINK = "resolve_gms_link";
    public static final String EXTRA_OPEN_GMS_LINK = "com.tqmane.wallart.OPEN_GMS_LINK";
    public static final String METHOD_RECORD_ORIGINAL = "record_original_preview";
    public static final String KEY_ID = "id";
    public static final String KEY_LABEL = "label";
    public static final String KEY_NETWORK = "network";
    public static final String KEY_LAST_FOUR = "last_four";
    public static final String KEY_URI = "uri";
    public static final String KEY_FIT = "fit";
    public static final String KEY_REVISION = "revision";
    public static final String KEY_FILE_NAME = "file_name";
    public static final String KEY_ORIGINAL_URI = "original_uri";
    public static final String KEY_FINGERPRINTS = "fingerprints";
    public static final String KEY_RESOLVED_ID = "resolved_id";
    public static final String KEY_PREVIEW = "preview";
    public static final String KEY_STORED = "stored";
    public static final String KEY_GMS_ID = "gms_id";

    private static final String PREFS = "cards";
    private static final String IDS = "ids";
    private static final String PENDING_ID = "pending_detail_card_id";
    private static final String PENDING_AT = "pending_detail_card_at";
    private static final String PENDING_GMS_ID = "pending_gms_link_id";
    private static final String PENDING_GMS_LABEL = "pending_gms_link_label";
    private static final String PENDING_GMS_AT = "pending_gms_link_at";
    private static final String GMS_LINK_PREFIX = "gms_link.";
    private static final String PREFIX = "card.";
    private static final String[] EXTENSIONS = {"png", "jpg", "webp"};
    private static final int MAX_PREVIEW_BYTES = 512 * 1024;
    private static final long PENDING_SELECTION_TIMEOUT_MS = 15_000L;
    private static final long PENDING_GMS_LINK_TIMEOUT_MS = 30L * 60L * 1000L;

    private CardStore() {
    }

    public static final class Card {
        public final String id;
        public final String label;
        public final String network;
        public final String lastFour;
        public final String extension;
        public final String fit;
        public final Uri originalArtUri;

        Card(String id, String label, String network, String lastFour, String extension, String fit, Uri originalArtUri) {
            this.id = id;
            this.label = label;
            this.network = network;
            this.lastFour = lastFour;
            this.extension = extension;
            this.fit = fit;
            this.originalArtUri = originalArtUri;
        }

        public boolean hasCustomArt() {
            return extension != null;
        }
    }

    public static final class Artifact {
        public final File file;
        public final Uri uri;
        public final String fit;
        public final long revision;

        Artifact(File file, String fit, long revision) {
            this.file = file;
            this.uri = Uri.parse("content://" + AUTHORITY + "/art/" + file.getName().substring(0, file.getName().lastIndexOf('.')));
            this.fit = fit;
            this.revision = revision;
        }
    }

    public static final class PendingGmsLink {
        public final String id;
        public final String label;
        public final String linkedCardId;

        PendingGmsLink(String id, String label, String linkedCardId) {
            this.id = id;
            this.label = label;
            this.linkedCardId = linkedCardId;
        }
    }

    public static void record(Context context, String id, String label, String network, String lastFour,
            String[] fingerprints) {
        if (!isValidId(id)) return;
        SharedPreferences prefs = prefs(context);
        Set<String> ids = new HashSet<>(prefs.getStringSet(IDS, Collections.<String>emptySet()));
        ids.add(id);
        String nextLabel = cleanLabel(label);
        String previousLabel = prefs.getString(key(id, "label"), "");
        if (isGenericLabel(nextLabel) && !isGenericLabel(previousLabel)) nextLabel = previousLabel;
        String nextNetwork = clean(network, 32);
        String previousNetwork = prefs.getString(key(id, "network"), "");
        if ((nextNetwork.isEmpty() || "Card".equalsIgnoreCase(nextNetwork))
                && !previousNetwork.isEmpty() && !"Card".equalsIgnoreCase(previousNetwork)) nextNetwork = previousNetwork;
        String nextLastFour = validLastFour(lastFour) ? lastFour : prefs.getString(key(id, "last_four"), "");
        Set<String> storedFingerprints = new HashSet<>(prefs.getStringSet(key(id, "fingerprints"), Collections.<String>emptySet()));
        if (fingerprints != null) {
            for (String fingerprint : fingerprints) if (isValidId(fingerprint)) storedFingerprints.add(fingerprint);
        }
        prefs.edit().putStringSet(IDS, ids)
                .putString(key(id, "label"), nextLabel)
                .putString(key(id, "network"), nextNetwork)
                .putString(key(id, "last_four"), validLastFour(nextLastFour) ? nextLastFour : "")
                .putStringSet(key(id, "fingerprints"), storedFingerprints)
                .apply();
    }

    public static String resolveIdentity(Context context, String candidateId, String[] fingerprints) {
        SharedPreferences prefs = prefs(context);
        Set<String> ids = prefs.getStringSet(IDS, Collections.<String>emptySet());
        if (isValidId(candidateId) && ids.contains(candidateId)) return candidateId;
        if (fingerprints == null) return null;
        for (String fingerprint : fingerprints) {
            if (!isValidId(fingerprint)) continue;
            String match = null;
            boolean ambiguous = false;
            for (String id : ids) {
                if (!prefs.getStringSet(key(id, "fingerprints"), Collections.<String>emptySet()).contains(fingerprint)) continue;
                if (match != null && !match.equals(id)) {
                    ambiguous = true;
                    break;
                }
                match = id;
            }
            if (!ambiguous && match != null) return match;
        }
        return null;
    }

    public static boolean setPendingSelection(Context context, String id) {
        SharedPreferences prefs = prefs(context);
        Card card = read(context, prefs, id);
        if (card == null || !card.hasCustomArt()) return false;
        prefs.edit().putString(PENDING_ID, id).putLong(PENDING_AT, System.currentTimeMillis()).apply();
        return true;
    }

    public static String pendingSelection(Context context) {
        SharedPreferences prefs = prefs(context);
        String id = prefs.getString(PENDING_ID, null);
        if (id == null) return null;
        long age = System.currentTimeMillis() - prefs.getLong(PENDING_AT, 0L);
        Card card = read(context, prefs, id);
        if (age < 0 || age > PENDING_SELECTION_TIMEOUT_MS || card == null || !card.hasCustomArt()) {
            clearPendingSelection(context);
            return null;
        }
        return id;
    }

    public static void clearPendingSelection(Context context) {
        prefs(context).edit().remove(PENDING_ID).remove(PENDING_AT).apply();
    }

    public static boolean setPendingGmsLink(Context context, String id, String label) {
        if (!isValidId(id)) return false;
        boolean hasCustomArt = false;
        for (Card card : list(context)) {
            if (card.hasCustomArt()) {
                hasCustomArt = true;
                break;
            }
        }
        if (!hasCustomArt) return false;
        prefs(context).edit().putString(PENDING_GMS_ID, id)
                .putString(PENDING_GMS_LABEL, cleanLabel(label))
                .putLong(PENDING_GMS_AT, System.currentTimeMillis()).apply();
        return true;
    }

    public static PendingGmsLink pendingGmsLink(Context context) {
        SharedPreferences prefs = prefs(context);
        String id = prefs.getString(PENDING_GMS_ID, null);
        long age = System.currentTimeMillis() - prefs.getLong(PENDING_GMS_AT, 0L);
        if (!isValidId(id) || age < 0 || age > PENDING_GMS_LINK_TIMEOUT_MS) {
            prefs.edit().remove(PENDING_GMS_ID).remove(PENDING_GMS_LABEL).remove(PENDING_GMS_AT).apply();
            return null;
        }
        return new PendingGmsLink(id, fallback(prefs.getString(PENDING_GMS_LABEL, ""), "Google Pay card"),
                resolveGmsLink(context, id));
    }

    public static boolean linkGmsCard(Context context, String gmsId, String cardId) {
        Card card = read(context, cardId);
        if (!isValidId(gmsId) || card == null || !card.hasCustomArt()) return false;
        prefs(context).edit().putString(GMS_LINK_PREFIX + gmsId, cardId).apply();
        return true;
    }

    public static String resolveGmsLink(Context context, String gmsId) {
        if (!isValidId(gmsId)) return null;
        SharedPreferences prefs = prefs(context);
        String cardId = prefs.getString(GMS_LINK_PREFIX + gmsId, null);
        Card card = read(context, cardId);
        return card != null && card.hasCustomArt() ? cardId : null;
    }

    public static List<Card> list(Context context) {
        SharedPreferences prefs = prefs(context);
        ArrayList<String> ids = new ArrayList<>(prefs.getStringSet(IDS, Collections.<String>emptySet()));
        Collections.sort(ids);
        ArrayList<Card> cards = new ArrayList<>(ids.size());
        for (String id : ids) {
            Card card = read(context, prefs, id);
            if (card != null) cards.add(card);
        }
        Collections.sort(cards, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return cards;
    }

    public static Card read(Context context, String id) {
        return read(context, prefs(context), id);
    }

    private static Card read(Context context, SharedPreferences prefs, String id) {
        if (!isValidId(id) || !prefs.getStringSet(IDS, Collections.<String>emptySet()).contains(id)) return null;
        String extension = normalizeExtension(prefs.getString(key(id, "extension"), ""));
        if (extension != null && !new File(artDir(context), id + "." + extension).isFile()) extension = null;
        String storedLastFour = prefs.getString(key(id, "last_four"), "");
        return new Card(id,
                fallback(prefs.getString(key(id, "label"), ""), "Google Wallet card"),
                fallback(prefs.getString(key(id, "network"), ""), "Card"),
                validLastFour(storedLastFour) ? storedLastFour : null,
                extension,
                normalizeFit(prefs.getString(key(id, "fit"), FitMode.FIT_CENTER)),
                originalArtFile(context, id).isFile() ? originalArtUri(id) : null);
    }

    public static synchronized boolean recordOriginalPreview(Context context, String id, byte[] bytes) {
        if (!isValidId(id) || bytes == null || bytes.length < 4 || bytes.length > MAX_PREVIEW_BYTES
                || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8) return false;
        if (originalArtFile(context, id).isFile()) return true;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 1400 || bounds.outHeight > 880
                || bounds.outWidth * 10 < bounds.outHeight * 13 || bounds.outWidth * 10 > bounds.outHeight * 19) return false;
        File dir = originalArtDir(context);
        if (!dir.exists() && !dir.mkdirs()) return false;
        File temp = new File(dir, id + ".jpg.tmp");
        File target = originalArtFile(context, id);
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        } catch (IOException error) {
            temp.delete();
            return false;
        }
        try {
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException error) {
            temp.delete();
            return false;
        }
    }

    public static void setFit(Context context, String id, String fit) {
        if (isValidId(id) && FitMode.isValid(fit)) prefs(context).edit().putString(key(id, "fit"), fit).apply();
    }

    public static void setCustom(Context context, String id, String extension) {
        if (!isValidId(id) || normalizeExtension(extension) == null) return;
        String normalized = normalizeExtension(extension);
        File dir = artDir(context);
        if (!dir.exists()) dir.mkdirs();
        for (String old : EXTENSIONS) if (!old.equals(normalized)) new File(dir, id + "." + old).delete();
        SharedPreferences prefs = prefs(context);
        long revision = Math.max(System.currentTimeMillis(), prefs.getLong(key(id, "revision"), 0L) + 1L);
        prefs.edit().putString(key(id, "extension"), normalized).putLong(key(id, "revision"), revision).apply();
    }

    public static void reset(Context context, String id) {
        if (!isValidId(id)) return;
        for (String extension : EXTENSIONS) new File(artDir(context), id + "." + extension).delete();
        prefs(context).edit().remove(key(id, "extension")).remove(key(id, "revision")).apply();
    }

    public static Artifact artifact(Context context, String id) {
        if (!isValidId(id)) return null;
        SharedPreferences prefs = prefs(context);
        String extension = normalizeExtension(prefs.getString(key(id, "extension"), ""));
        if (extension == null) return null;
        File file = new File(artDir(context), id + "." + extension);
        return file.isFile() && file.length() > 0
                ? new Artifact(file, normalizeFit(prefs.getString(key(id, "fit"), FitMode.FIT_CENTER)), prefs.getLong(key(id, "revision"), 0L))
                : null;
    }

    public static File artDir(Context context) {
        return new File(context.getFilesDir(), "card_art");
    }

    public static File originalArtDir(Context context) {
        return new File(context.getFilesDir(), "original_art");
    }

    public static File originalArtFile(Context context, String id) {
        return new File(originalArtDir(context), isValidId(id) ? id + ".jpg" : "invalid");
    }

    private static Uri originalArtUri(String id) {
        return Uri.parse("content://" + AUTHORITY + "/original/" + id);
    }

    public static boolean isValidId(String id) {
        if (id == null || id.length() != 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    public static String mime(String extension) {
        if ("png".equals(extension)) return "image/png";
        if ("webp".equals(extension)) return "image/webp";
        return "image/jpeg";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String id, String suffix) {
        return PREFIX + id + "." + suffix;
    }

    private static String cleanLabel(String value) {
        String result = clean(value, 80).replaceAll("\\d{5,}", "••••");
        return result.isEmpty() ? "Google Wallet card" : result;
    }

    private static boolean isGenericLabel(String value) {
        return value == null || value.isEmpty() || "Google Wallet card".equals(value)
                || value.startsWith("Wallet card · ");
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String result = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return result.length() > max ? result.substring(0, max) : result;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static boolean validLastFour(String value) {
        return value != null && value.matches("\\d{4}");
    }

    private static String normalizeExtension(String value) {
        if (value == null) return null;
        String result = value.toLowerCase(Locale.ROOT);
        return "png".equals(result) || "jpg".equals(result) || "webp".equals(result) ? result : null;
    }

    private static String normalizeFit(String value) {
        return FitMode.isValid(value) ? value : FitMode.FIT_CENTER;
    }
}
