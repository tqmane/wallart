package com.tqmane.wallart.xposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.tqmane.wallart.CardIdentity;
import com.tqmane.wallart.FitMode;
import com.tqmane.wallart.storage.BitmapDecoder;
import com.tqmane.wallart.storage.CardStore;

import java.util.Collections;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CardArtRuntime {
    private static final String TAG = "WallArt";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WallArt-card-art");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService ORIGINAL_FETCH_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "WallArt-original-preview");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<ImageView, ImageView.ScaleType> ORIGINAL_SCALE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Drawable> GOOGLE_PAY_OVERLAYS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Activity> GOOGLE_PAY_LINK_PROMPTED =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static final Map<Drawable, CardIdentity> ART_IDENTITIES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, CachedArt> DRAWABLE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_LOOKUP = new ConcurrentHashMap<>();
    private static final Set<String> LOOKUP_IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Paint DRAW_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final long LOOKUP_INTERVAL_MS = 1500;
    private static final Set<String> IDENTITY_WARNED = ConcurrentHashMap.newKeySet();
    private static final Set<String> COMPOSE_DEBUG_EVENTS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> LAST_RECORDED = new ConcurrentHashMap<>();
    private static final Map<String, ComposeArt> COMPOSE_ART_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ComposeEntry> COMPOSE_BY_ART_URL = new ConcurrentHashMap<>();
    private static final Set<String> AMBIGUOUS_ART_URLS = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, ComposeEntry> COMPOSE_BY_ART_RESOURCE = new ConcurrentHashMap<>();
    private static final Set<Integer> AMBIGUOUS_ART_RESOURCES = ConcurrentHashMap.newKeySet();
    private static final Set<String> ORIGINAL_CAPTURE_PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<String> ORIGINAL_CAPTURED = ConcurrentHashMap.newKeySet();
    private static final Set<String> ORIGINAL_FETCH_ATTEMPTED = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<ComposeScope> COMPOSE_SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<ComposeEntry> COMPOSE_CARD = new ThreadLocal<>();
    private static volatile WeakReference<Context> walletContext = new WeakReference<>(null);
    private static volatile WeakReference<Activity> googlePayActivity = new WeakReference<>(null);
    private static volatile boolean googlePayDetailActive;

    private static final class CachedArt {
        final Bitmap bitmap;
        final String fit;
        final long revision;

        CachedArt(Bitmap bitmap, String fit, long revision) {
            this.bitmap = bitmap;
            this.fit = fit;
            this.revision = revision;
        }
    }

    static final class ComposeScope {
        final ComposeScope previous;
        final IdentityHashMap<Object, ComposeEntry> cards;
        final Class<?> cardStateType;
        final Class<?> artworkModelBase;

        ComposeScope(ComposeScope previous, IdentityHashMap<Object, ComposeEntry> cards,
                Class<?> cardStateType, Class<?> artworkModelBase) {
            this.previous = previous;
            this.cards = cards;
            this.cardStateType = cardStateType;
            this.artworkModelBase = artworkModelBase;
        }
    }

    private static final class ComposeEntry {
        final CardIdentity identity;
        final Set<String> artworkUrls;
        final String stableKey;
        final Object sourceModel;
        final int sourceResourceId;

        ComposeEntry(CardIdentity identity, Set<String> artworkUrls, String stableKey, Object sourceModel,
                int sourceResourceId) {
            this.identity = identity;
            this.artworkUrls = artworkUrls;
            this.stableKey = stableKey;
            this.sourceModel = sourceModel;
            this.sourceResourceId = sourceResourceId;
        }

        boolean matches(Object model, Set<String> urls, int resourceId) {
            if (sourceModel == model || (sourceResourceId != 0 && sourceResourceId == resourceId)) return true;
            for (String url : urls) if (artworkUrls.contains(url)) return true;
            return false;
        }
    }

    private static final class ComposeArt {
        final long revision;
        final long checkedAt;
        final Bitmap bitmap;

        ComposeArt(long revision, long checkedAt, Bitmap bitmap) {
            this.revision = revision;
            this.checkedAt = checkedAt;
            this.bitmap = bitmap;
        }
    }

    private CardArtRuntime() {
    }

    static void onWalletActivityResumed(Context context) {
        walletContext = new WeakReference<>(context.getApplicationContext());
        LAST_LOOKUP.clear();
        synchronized (COMPOSE_ART_CACHE) {
            for (Map.Entry<String, ComposeArt> entry : COMPOSE_ART_CACHE.entrySet()) {
                ComposeArt art = entry.getValue();
                COMPOSE_ART_CACHE.put(entry.getKey(), new ComposeArt(art.revision, 0L, art.bitmap));
            }
        }
    }

    static void onGooglePayActivityStarted(Context context) {
        googlePayDetailActive = true;
        if (context instanceof Activity) googlePayActivity = new WeakReference<>((Activity) context);
        onWalletActivityResumed(context);
    }

    static void onGooglePayActivityResumed(Activity activity) {
        onGooglePayActivityStarted(activity);
        logGooglePayIntentKeys(activity);
        scheduleGooglePayArtwork(activity, 0, false);
    }

    static void onGooglePayTapActivityResumed(Activity activity) {
        onGooglePayActivityStarted(activity);
        scheduleGooglePayArtwork(activity, 0, true);
    }

    private static void logGooglePayIntentKeys(Activity activity) {
        if (!com.tqmane.wallart.BuildConfig.DEBUG || !COMPOSE_DEBUG_EVENTS.add("gms-intent-keys")) return;
        try {
            Bundle extras = activity.getIntent().getExtras();
            if (extras == null) {
                Log.i(TAG, "GMS PayActivity has no intent extras");
                return;
            }
            ArrayList<String> keys = new ArrayList<>();
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                String type = value == null ? "null" : value.getClass().getSimpleName();
                if (value instanceof byte[]) {
                    type += "[" + ((byte[]) value).length + " bytes]";
                }
                keys.add(key + ":" + type);
                if (keys.size() == 16) break;
            }
            Log.i(TAG, "GMS PayActivity intent extra keys=" + keys);
        } catch (Throwable ignored) {
            Log.i(TAG, "GMS PayActivity intent extras unavailable");
        }
    }

    private static void scheduleGooglePayArtwork(Activity activity, int attempt, boolean tapConfirmation) {
        if (!googlePayDetailActive || googlePayActivity.get() != activity
                || activity.isFinishing() || activity.isDestroyed()) return;
        View decor = activity.getWindow().getDecorView();
        View artView = findCardArtworkView(decor, decor.getWidth(), decor.getHeight());
        if (artView == null) {
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG, "GMS detail card-art view scan missed; retrying");
            if (attempt < 8) decor.postDelayed(() -> scheduleGooglePayArtwork(activity, attempt + 1, tapConfirmation), 250L);
            else if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG, "GMS detail card-art view not found");
            return;
        }
        int[] artLocation = new int[2];
        artView.getLocationOnScreen(artLocation);
        int artWidth = artView.getWidth();
        int artHeight = artView.getHeight();
        List<String> labels = new ArrayList<>();
        collectCardLabels(decor, artLocation[0], artLocation[1],
                artLocation[0] + artWidth, artLocation[1] + artHeight, labels);
        CardIdentity candidate = CardIdentity.fromStableKey(null, labels, null);
        if (candidate == null) {
            if (attempt < 8) decor.postDelayed(() -> scheduleGooglePayArtwork(activity, attempt + 1, tapConfirmation), 250L);
            else if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG, "GMS detail card label not found");
            return;
        }
        if (com.tqmane.wallart.BuildConfig.DEBUG && COMPOSE_DEBUG_EVENTS.add("gms-identity-candidate")) {
            Log.i(TAG, "GMS identity candidate network=" + candidate.network + ", hasLastFour="
                    + (candidate.lastFour != null) + ", labels=" + labels.size()
                    + ", fingerprints=" + candidate.lookupFingerprints.length);
        }

        EXECUTOR.execute(() -> {
            try {
                CardIdentity identity = resolveGooglePayIdentity(activity, candidate, labels, !tapConfirmation);
                if (identity == null) {
                    if (attempt < 4) retryGooglePayArtwork(activity, attempt, tapConfirmation);
                    else if (!tapConfirmation) {
                        if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG,
                                "GMS detail card did not match a saved Wallet card");
                        offerManualGooglePayLink(activity, candidate);
                    }
                    return;
                }
                clearPendingSelection(activity);
                Bundle resolved = resolve(activity, identity.id);
                if (resolved == null || !resolved.containsKey(CardStore.KEY_URI)) {
                    if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG,
                            "No custom artwork configured for this GMS card");
                    return;
                }
                Bitmap source = BitmapDecoder.decode(activity.getContentResolver(),
                        Uri.parse(resolved.getString(CardStore.KEY_URI)));
                if (source == null) return;
                Bitmap fitted = fitComposeArt(source, resolved.getString(CardStore.KEY_FIT, FitMode.FIT_CENTER));
                source.recycle();
                RoundedBitmapDrawable overlay = RoundedBitmapDrawableFactory.create(activity.getResources(), fitted);
                overlay.setAntiAlias(true);
                overlay.setCornerRadius(artHeight * 0.065f);
                overlay.setBounds(0, 0, artWidth, artHeight);
                artView.post(() -> {
                    if (!googlePayDetailActive || googlePayActivity.get() != activity || activity.isFinishing()) return;
                    synchronized (GOOGLE_PAY_OVERLAYS) {
                        Drawable previous = GOOGLE_PAY_OVERLAYS.put(artView, overlay);
                        if (previous != null) artView.getOverlay().remove(previous);
                        artView.getOverlay().add(overlay);
                    }
                    if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG,
                            "Custom artwork applied to GMS detail card");
                });
            } catch (Throwable error) {
                if (com.tqmane.wallart.BuildConfig.DEBUG) Log.w(TAG, "GMS detail artwork overlay skipped", error);
            }
        });
    }

    private static CardIdentity resolveGooglePayIdentity(Activity activity, CardIdentity candidate, List<String> labels,
            boolean allowIntentParsing) {
        CardIdentity identity = resolveExistingIdentity(activity, candidate);
        if (identity != null) return identity;
        String gmsLinkId = candidate.gmsLinkKey();
        try {
            Bundle values = new Bundle();
            values.putString(CardStore.KEY_GMS_ID, gmsLinkId);
            Bundle result = activity.getContentResolver().call(Uri.parse("content://" + CardStore.AUTHORITY),
                    CardStore.METHOD_RESOLVE_GMS_LINK, null, values);
            String cardId = result == null ? null : result.getString(CardStore.KEY_RESOLVED_ID);
            if (CardStore.isValidId(cardId)) {
                if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG, "GMS detail matched a manually linked Wallet card");
                return candidate.withId(cardId);
            }
        } catch (Throwable ignored) {
        }
        if (!allowIntentParsing) return resolvePendingSelection(activity, candidate, true);
        Bundle extras = activity.getIntent().getExtras();
        if (extras == null) return null;
        int checked = 0;
        for (String key : extras.keySet()) {
            Object extra;
            try {
                extra = extras.get(key);
            } catch (Throwable ignored) {
                continue;
            }
            if (extra instanceof byte[]) {
                byte[] bytes = (byte[]) extra;
                identity = resolvePayIntentArgs(activity, bytes);
                if (identity != null) return identity;
                Set<String> values = new LinkedHashSet<>(ProtoStrings.extract(bytes));
                values.addAll(ProtoStrings.extractUtf16LittleEndian(bytes));
                values.addAll(asciiTokens(bytes));
                int candidateCount = 0;
                for (String value : values) {
                    identity = resolveExtraString(activity, value, labels);
                    if (identity != null) return identity;
                    if (++candidateCount == 24) break;
                }
                for (String compositeKey : stableKeyCombinations(new ArrayList<>(values))) {
                    identity = resolveStoredStableKey(activity, compositeKey);
                    if (identity != null) return identity;
                }
                continue;
            }
            String normalizedKey = key.toLowerCase(java.util.Locale.ROOT);
            if (!(normalizedKey.contains("card") || normalizedKey.contains("pass")
                    || normalizedKey.contains("instrument") || normalizedKey.contains("object")
                    || normalizedKey.contains("wallet") || normalizedKey.contains("payment"))) continue;
            String value = extra instanceof String ? (String) extra : null;
            if (value == null || value.isEmpty() || value.length() > 256 || value.startsWith("http")) continue;
            identity = resolveExtraString(activity, value, labels);
            if (identity != null) return identity;
            if (++checked == 8) break;
        }
        return resolvePendingSelection(activity, candidate, false);
    }

    private static CardIdentity resolvePendingSelection(Activity activity, CardIdentity candidate,
            boolean requireNetworkMatch) {
        try {
            Bundle values = null;
            if (requireNetworkMatch) {
                if ("Card".equals(candidate.network)) return null;
                values = new Bundle();
                values.putString(CardStore.KEY_NETWORK, candidate.network);
            }
            Bundle pending = activity.getContentResolver().call(Uri.parse("content://" + CardStore.AUTHORITY),
                    CardStore.METHOD_RESOLVE_PENDING_SELECTION, null, values);
            String id = pending == null ? null : pending.getString(CardStore.KEY_RESOLVED_ID);
            if (CardStore.isValidId(id)) {
                if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG,
                        "GMS card matched the recent Wallet card selection");
                return candidate.withId(id);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void offerManualGooglePayLink(Activity activity, CardIdentity candidate) {
        synchronized (GOOGLE_PAY_LINK_PROMPTED) {
            if (!GOOGLE_PAY_LINK_PROMPTED.add(activity)) return;
        }
        boolean stored = false;
        try {
            Bundle values = new Bundle();
            values.putString(CardStore.KEY_GMS_ID, candidate.gmsLinkKey());
            values.putString(CardStore.KEY_LABEL, candidate.displayName);
            Bundle result = activity.getContentResolver().call(Uri.parse("content://" + CardStore.AUTHORITY),
                    CardStore.METHOD_SET_PENDING_GMS_LINK, null, values);
            stored = result != null && result.getBoolean(CardStore.KEY_STORED);
        } catch (Throwable ignored) {
        }
        if (!stored) {
            synchronized (GOOGLE_PAY_LINK_PROMPTED) {
                GOOGLE_PAY_LINK_PROMPTED.remove(activity);
            }
            return;
        }
        activity.runOnUiThread(() -> {
            if (!googlePayDetailActive || googlePayActivity.get() != activity
                    || activity.isFinishing() || activity.isDestroyed()) return;
            boolean japanese = "ja".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
            try {
                new AlertDialog.Builder(activity)
                        .setTitle(japanese ? "WallArtカードを関連付けますか？" : "Link this card in WallArt?")
                        .setMessage(japanese
                                ? candidate.displayName + " の券面画像を、WallArtに登録したカードから選べます。決済情報は変更しません。"
                                : "Choose which WallArt card's artwork should appear for " + candidate.displayName
                                        + ". Payment details are not changed.")
                        .setNegativeButton(japanese ? "あとで" : "Not now", null)
                        .setPositiveButton(japanese ? "WallArtで選ぶ" : "Choose in WallArt", (dialog, which) -> {
                            Intent intent = new Intent().setComponent(new ComponentName(
                                    com.tqmane.wallart.BuildConfig.APPLICATION_ID,
                                    com.tqmane.wallart.BuildConfig.APPLICATION_ID + ".MainActivity"));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            intent.putExtra(CardStore.EXTRA_OPEN_GMS_LINK, true);
                            activity.startActivity(intent);
                        })
                        .show();
            } catch (Throwable error) {
                if (com.tqmane.wallart.BuildConfig.DEBUG) Log.w(TAG, "Could not show the card-link prompt", error);
            }
        });
    }

    private static CardIdentity resolvePayIntentArgs(Activity activity, byte[] bytes) {
        Parcel parcel = Parcel.obtain();
        try {
            Class<?> argsType = Class.forName("com.google.android.gms.pay.PayIntentArgs", false,
                    activity.getClassLoader());
            Object creatorValue = argsType.getField("CREATOR").get(null);
            if (!(creatorValue instanceof Parcelable.Creator)) return null;
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            Object args = ((Parcelable.Creator<?>) creatorValue).createFromParcel(parcel);
            ArrayList<String> ids = new ArrayList<>();
            for (Field field : argsType.getDeclaredFields()) {
                if (!"com.google.android.gms.pay.FopDetailIntentArgs".equals(field.getType().getName())) continue;
                field.setAccessible(true);
                Object detail = field.get(args);
                if (detail == null) continue;
                for (Field idField : detail.getClass().getDeclaredFields()) {
                    if (idField.getType() != String.class) continue;
                    idField.setAccessible(true);
                    Object value = idField.get(detail);
                    if (value instanceof String && !((String) value).isEmpty()) ids.add((String) value);
                }
            }
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG, "GMS FOP detail ID fields=" + ids.size());
            for (String id : ids) {
                CardIdentity identity = resolveStoredStableKey(activity, id);
                if (identity != null) return identity;
            }
            for (String composite : stableKeyCombinations(ids)) {
                CardIdentity identity = resolveStoredStableKey(activity, composite);
                if (identity != null) return identity;
            }
        } catch (Throwable error) {
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG,
                    "GMS PayIntentArgs parse skipped: " + error.getClass().getSimpleName());
        } finally {
            parcel.recycle();
        }
        return null;
    }

    private static List<String> stableKeyCombinations(List<String> values) {
        ArrayList<String> sorted = new ArrayList<>(new LinkedHashSet<>(values));
        Collections.sort(sorted);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (sorted.size() > 1) result.add(String.join("\u001f", sorted));
        for (int i = 0; i < sorted.size() && result.size() < 24; i++) {
            for (int j = i + 1; j < sorted.size() && result.size() < 24; j++) {
                result.add(sorted.get(i) + "\u001f" + sorted.get(j));
            }
        }
        return new ArrayList<>(result);
    }

    private static CardIdentity resolveStoredStableKey(Activity activity, String stableKey) {
        return resolveExistingIdentity(activity,
                CardIdentity.fromStableKey(stableKey, Collections.<String>emptyList(), null));
    }

    private static CardIdentity resolveExtraString(Activity activity, String value, List<String> labels) {
        if (value == null || value.isEmpty() || value.length() > 256
                || value.regionMatches(true, 0, "http", 0, 4)) return null;
        CardIdentity identity = resolveExistingIdentity(activity,
                CardIdentity.fromStableKey(value, Collections.<String>emptyList(), null));
        if (identity != null) return identity;
        long letters = value.codePoints().filter(Character::isLetter).count();
        if (value.length() < 12 || letters < 6 || value.contains("@")
                || value.toLowerCase(java.util.Locale.ROOT).contains("com.google.")
                || value.matches(".*\\d{8,}.*")) return null;
        ArrayList<String> candidates = new ArrayList<>(labels);
        candidates.add(value);
        return resolveExistingIdentity(activity, CardIdentity.fromStableKey(null, candidates, null));
    }

    private static List<String> asciiTokens(byte[] bytes) {
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (byte item : bytes) {
            int value = item & 0xff;
            boolean allowed = value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                    || value >= '0' && value <= '9' || value == '_' || value == '-' || value == '.';
            if (allowed) {
                if (token.length() < 128) token.append((char) value);
            } else if (token.length() >= 4) {
                tokens.add(token.toString());
                token.setLength(0);
                if (tokens.size() == 32) return tokens;
            } else {
                token.setLength(0);
            }
        }
        if (token.length() >= 4 && tokens.size() < 32) tokens.add(token.toString());
        return tokens;
    }

    private static void retryGooglePayArtwork(Activity activity, int attempt, boolean tapConfirmation) {
        View decor = activity.getWindow().getDecorView();
        decor.postDelayed(() -> scheduleGooglePayArtwork(activity, attempt + 1, tapConfirmation), 250L);
    }

    static void onGooglePayActivityStopped() {
        googlePayDetailActive = false;
        Activity activity = googlePayActivity.get();
        if (activity != null) {
            synchronized (GOOGLE_PAY_OVERLAYS) {
                for (Map.Entry<View, Drawable> entry : GOOGLE_PAY_OVERLAYS.entrySet()) {
                    entry.getKey().getOverlay().remove(entry.getValue());
                }
                GOOGLE_PAY_OVERLAYS.clear();
            }
            clearPendingSelection(activity);
            synchronized (GOOGLE_PAY_LINK_PROMPTED) {
                GOOGLE_PAY_LINK_PROMPTED.remove(activity);
            }
        }
        googlePayActivity = new WeakReference<>(null);
        if (isGooglePayContext(walletContext.get())) walletContext = new WeakReference<>(null);
    }

    static void captureWalletCardSelection(Activity activity, float x, float y) {
        if (activity == null || !CardStore.WALLET_PACKAGE.equals(activity.getPackageName())) return;
        clearPendingSelection(activity);
        AccessibilityNodeInfo root = activity.getWindow().getDecorView().createAccessibilityNodeInfo();
        if (root == null || !sealAccessibilityNode(root)) {
            if (root != null) root.recycle();
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG, "Wallet tap accessibility root unavailable");
            return;
        }
        AccessibilityNodeInfo[] clicked = {null};
        int[] area = {Integer.MAX_VALUE};
        findClickableNodeAt(root, (int) x, (int) y, 0, area, clicked);
        int rootChildren = root.getChildCount();
        root.recycle();
        ArrayList<String> labels = new ArrayList<>();
        if (clicked[0] != null) {
            collectAccessibilityLabels(clicked[0], labels, 0);
            clicked[0].recycle();
        } else {
            int[] providers = {0, 0};
            collectAccessibleTapLabels(activity.getWindow().getDecorView(), (int) x, (int) y,
                    labels, 0, providers);
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG,
                    "Wallet tap semantics: children=" + rootChildren + ", providers=" + providers[0]
                            + ", textSearchHits=" + providers[1] + ", labels=" + labels.size());
        }
        CardIdentity candidate = CardIdentity.fromStableKey(null, labels, null);
        if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG,
                "Wallet clickable card candidate=" + (candidate == null ? "none" : candidate.network)
                        + ", labels=" + labels.size());
        CardIdentity identity = resolveWalletIdentity(activity, candidate);
        if (identity == null) {
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG, "Wallet click did not match a saved card");
            return;
        }
        try {
            Bundle values = new Bundle();
            values.putString(CardStore.KEY_ID, identity.id);
            Bundle result = activity.getContentResolver().call(Uri.parse("content://" + CardStore.AUTHORITY),
                    CardStore.METHOD_SET_PENDING_SELECTION, null, values);
            if (com.tqmane.wallart.BuildConfig.DEBUG && result != null
                    && result.getBoolean(CardStore.KEY_STORED)) {
                Log.i(TAG, "Wallet card selection linked for Pay details: " + identity.network);
            } else if (com.tqmane.wallart.BuildConfig.DEBUG) {
                Log.i(TAG, "Wallet card selection has no custom artwork");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void findClickableNodeAt(AccessibilityNodeInfo node, int x, int y, int depth,
            int[] bestArea, AccessibilityNodeInfo[] best) {
        if (node == null || depth > 16 || !sealAccessibilityNode(node)) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int area = bounds.width() * bounds.height();
        if (node.isClickable() && area >= 80_000 && bounds.contains(x, y) && area < bestArea[0]) {
            if (best[0] != null) best[0].recycle();
            best[0] = AccessibilityNodeInfo.obtain(node);
            bestArea[0] = area;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            findClickableNodeAt(child, x, y, depth + 1, bestArea, best);
            child.recycle();
        }
    }

    private static void collectAccessibilityLabels(AccessibilityNodeInfo node, List<String> labels, int depth) {
        if (node == null || depth > 12 || labels.size() >= 24 || !sealAccessibilityNode(node)) return;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (text != null && text.length() > 0 && text.length() <= 128) labels.add(text.toString());
        if (description != null && description.length() > 0 && description.length() <= 128) {
            labels.add(description.toString());
        }
        for (int i = 0; i < node.getChildCount() && labels.size() < 24; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collectAccessibilityLabels(child, labels, depth + 1);
            child.recycle();
        }
    }

    private static void collectAccessibleTapLabels(View view, int x, int y, List<String> labels,
            int depth, int[] providers) {
        if (depth > 20 || labels.size() >= 24 || !view.isShown()) return;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int centerX = location[0] + view.getWidth() / 2;
        int centerY = location[1] + view.getHeight() / 2;
        boolean nearTap = Math.abs(centerX - x) <= 280 && Math.abs(centerY - y) <= 260;
        if (nearTap && view instanceof TextView) addAccessibilityLabel(((TextView) view).getText(), labels);
        if (nearTap) addAccessibilityLabel(view.getContentDescription(), labels);

        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null && providers[0] < 4 && view.getWidth() > 160 && view.getHeight() > 120) {
            providers[0]++;
            for (String cardType : new String[] {"QUICPay", "Suica"}) {
                try {
                    List<AccessibilityNodeInfo> matches = provider.findAccessibilityNodeInfosByText(
                            cardType, View.NO_ID);
                    if (matches == null) continue;
                    for (AccessibilityNodeInfo match : matches) {
                        if (match == null) continue;
                        if (sealAccessibilityNode(match)) {
                            Rect bounds = new Rect();
                            match.getBoundsInScreen(bounds);
                            if (Math.abs(bounds.centerX() - x) <= 320 && Math.abs(bounds.centerY() - y) <= 340) {
                                addAccessibilityLabel(cardType, labels);
                                providers[1]++;
                            }
                        }
                        match.recycle();
                    }
                } catch (Throwable ignored) {
                }
            }
            for (int id = View.NO_ID; id < 128 && labels.size() < 24; id++) {
                AccessibilityNodeInfo node;
                try {
                    node = provider.createAccessibilityNodeInfo(id);
                } catch (Throwable ignored) {
                    continue;
                }
                if (node == null) continue;
                if (sealAccessibilityNode(node)) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (Math.abs(bounds.centerX() - x) <= 280 && Math.abs(bounds.centerY() - y) <= 260) {
                        addAccessibilityLabel(node.getText(), labels);
                        addAccessibilityLabel(node.getContentDescription(), labels);
                    }
                }
                node.recycle();
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && labels.size() < 24; i++) {
                collectAccessibleTapLabels(group.getChildAt(i), x, y, labels, depth + 1, providers);
            }
        }
    }

    private static void addAccessibilityLabel(CharSequence value, List<String> labels) {
        if (value != null && value.length() > 0 && value.length() <= 128 && !labels.contains(value.toString())) {
            labels.add(value.toString());
        }
    }

    private static boolean sealAccessibilityNode(AccessibilityNodeInfo node) {
        try {
            Method method = AccessibilityNodeInfo.class.getDeclaredMethod("setSealed", boolean.class);
            method.setAccessible(true);
            method.invoke(node, true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static CardIdentity resolveWalletIdentity(Context context, CardIdentity candidate) {
        if (candidate == null) return null;
        try {
            Bundle values = new Bundle();
            values.putString(CardStore.KEY_ID, candidate.id);
            values.putStringArray(CardStore.KEY_FINGERPRINTS, candidate.lookupFingerprints);
            Bundle result = context.getContentResolver().call(Uri.parse("content://" + CardStore.AUTHORITY),
                    CardStore.METHOD_RESOLVE_IDENTITY, null, values);
            return result == null ? null : candidate.withId(result.getString(CardStore.KEY_RESOLVED_ID));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void clearPendingSelection(Context context) {
        try {
            context.getContentResolver().call(Uri.parse("content://" + CardStore.AUTHORITY),
                    CardStore.METHOD_CLEAR_PENDING_SELECTION, null, null);
        } catch (Throwable ignored) {
        }
    }

    private static View findCardArtworkView(View view, int screenWidth, int screenHeight) {
        View best = null;
        if ((isComposeView(view) || view instanceof ImageView) && view.isShown()
                && view.getWidth() >= screenWidth * 0.7f
                && view.getHeight() >= screenHeight * 0.2f && view.getHeight() <= screenHeight * 0.4f) {
            float ratio = (float) view.getWidth() / view.getHeight();
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            if (ratio >= 1.5f && ratio <= 1.85f && location[1] < screenHeight * 0.55f) {
                best = view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View candidate = findCardArtworkView(group.getChildAt(i), screenWidth, screenHeight);
                if (candidate != null && (best == null
                        || candidate.getWidth() * candidate.getHeight() > best.getWidth() * best.getHeight())) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static boolean isComposeView(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if ("androidx.compose.ui.platform.ComposeView".equals(type.getName())) return true;
        }
        return false;
    }

    private static void collectCardLabels(View view, int artLeft, int artTop, int artRight, int artBottom,
            List<String> labels) {
        if (!view.isShown()) return;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0 && text.length() <= 128
                    && location[1] + view.getHeight() <= artTop) labels.add(text.toString());
        }
        CharSequence description = view.getContentDescription();
        int artWidth = artRight - artLeft;
        int artHeight = artBottom - artTop;
        if (description != null && description.length() > 0 && description.length() <= 128
                && view.getWidth() >= artWidth * 0.8f && view.getWidth() <= artWidth * 1.25f
                && view.getHeight() >= artHeight * 0.8f && view.getHeight() <= artHeight * 1.25f
                && location[0] < artRight && location[0] + view.getWidth() > artLeft
                && location[1] < artBottom && location[1] + view.getHeight() > artTop) {
            labels.add(description.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectCardLabels(group.getChildAt(i), artLeft, artTop, artRight, artBottom, labels);
            }
        }
    }

    private static boolean isGooglePayContext(Context context) {
        return context != null && CardStore.GOOGLE_PAY_PACKAGE.equals(context.getPackageName());
    }

    private static CardIdentity resolveExistingIdentity(Context context, CardIdentity identity) {
        if (identity == null || !isGooglePayContext(context)) return identity;
        if (!googlePayDetailActive) return null;
        try {
            Bundle values = new Bundle();
            values.putString(CardStore.KEY_ID, identity.id);
            values.putStringArray(CardStore.KEY_FINGERPRINTS, identity.lookupFingerprints);
            Bundle result = context.getContentResolver().call(Uri.parse("content://" + CardStore.AUTHORITY),
                    CardStore.METHOD_RESOLVE_IDENTITY, null, values);
            String resolvedId = result == null ? null : result.getString(CardStore.KEY_RESOLVED_ID);
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG, resolvedId == null
                    ? "GMS card identity did not match a saved Wallet card"
                    : "GMS card identity matched a saved Wallet card");
            debugOnce(resolvedId == null ? "gms-identity-unmatched" : "gms-identity-matched",
                    resolvedId == null ? "GMS card did not match a saved Wallet card"
                            : "GMS card matched a saved Wallet card");
            return identity.withId(resolvedId);
        } catch (Throwable ignored) {
            debugOnce("gms-identity-error", "GMS card identity lookup failed");
            return null;
        }
    }

    static Context walletContext() {
        return walletContext.get();
    }

    static ComposeScope enterComposeState(Object state, Class<?> artworkModelBase, Context context) {
        ComposeScope previous = COMPOSE_SCOPE.get();
        IdentityHashMap<Object, ComposeEntry> cards = new IdentityHashMap<>();
        Class<?> cardStateType = null;
        if (isGooglePayContext(context) && !googlePayDetailActive) {
            ComposeScope inactive = new ComposeScope(previous, cards, null, artworkModelBase);
            COMPOSE_SCOPE.set(inactive);
            return inactive;
        }
        List<?> rows = state == null ? null : listField(state);
        if (rows != null) {
            for (Object row : rows) {
                if (row == null) continue;
                Object cardState = null;
                Object stableKey = null;
                for (Field field : fields(row.getClass())) {
                    try {
                        Object value = field.get(row);
                        if (value != null && artworkField(value.getClass(), artworkModelBase) != null) {
                            cardState = value;
                        } else if (value != null) {
                            stableKey = value;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (cardState == null) continue;

                Field artField = artworkField(cardState.getClass(), artworkModelBase);
                Object model = read(artField, cardState);
                Set<String> urls = artworkUrls(model);
                List<String> labels = cardLabels(cardState, context);
                String stable = stableKey(stableKey);
                String primaryUrl = urls.isEmpty() ? null : urls.iterator().next();
                CardIdentity identity = CardIdentity.fromStableKey(stable, labels, primaryUrl);
                if (identity != null) identity = identity.withStableKeyParts(opaqueKeyParts(row, cardState, stableKey));
                identity = resolveExistingIdentity(context, identity);
                if (identity == null) continue;

                ComposeEntry entry = new ComposeEntry(identity, urls, stable, model,
                        artworkResourceId(model, context));
                cards.put(cardState, entry);
                indexComposeEntry(entry);
                if (cardStateType == null) cardStateType = cardState.getClass();
                if (context != null) recordComposeCard(context, identity, publicPreviewUrl(model));
            }
        }
        if (!cards.isEmpty()) debugOnce("compose-state", "Compose card state entries=" + cards.size());
        ComposeScope current = new ComposeScope(previous, cards, cardStateType, artworkModelBase);
        COMPOSE_SCOPE.set(current);
        return current;
    }

    static void leaveComposeState(ComposeScope scope) {
        if (scope == null || scope.previous == null) COMPOSE_SCOPE.remove();
        else COMPOSE_SCOPE.set(scope.previous);
    }

    static Object enterComposeTileState(Object state, Class<?> artworkModelBase, Context context) {
        ComposeEntry previous = COMPOSE_CARD.get();
        if (isGooglePayContext(context) && !googlePayDetailActive) {
            COMPOSE_CARD.remove();
            return previous;
        }
        Object model = nestedArtworkModel(state, artworkModelBase);
        if (model == null || context == null) {
            COMPOSE_CARD.remove();
            return previous;
        }

        Set<String> urls = artworkUrls(model);
        List<String> labels = cardLabels(state, context);
        String resourceName = artworkResourceName(model, context);
        String primaryUrl = urls.isEmpty() ? null : urls.iterator().next();
        String type = favoriteTileType(labels, urls, resourceName);
        if (type == null) {
            COMPOSE_CARD.remove();
            return previous;
        }
        labels.removeIf(label -> label.contains("選択") || label.contains("有効にする"));
        if (!labels.contains(type)) labels.add(0, type);

        String stable = "Suica".equals(type)
                ? "wallet-favorite-suica:" + (resourceName != null ? resourceName
                        : primaryUrl != null ? normalizeArtworkUrl(primaryUrl) : String.join("\u001f", labels))
                : stableKey(state);
        if (stable.isEmpty()) stable = "wallet-favorite:" + type + ":" + String.join("\u001f", labels);
        CardIdentity identity = CardIdentity.fromStableKey(stable, labels, primaryUrl);
        identity = enrichComposeIdentity(model, identity);
        if (identity != null) identity = identity.withStableKeyParts(opaqueKeyParts(state, model));
        identity = resolveExistingIdentity(context, identity);
        if (identity == null) {
            COMPOSE_CARD.remove();
            return previous;
        }

        ComposeEntry entry = new ComposeEntry(identity, urls, stable, model, artworkResourceId(model, context));
        indexComposeEntry(entry);
        COMPOSE_CARD.set(entry);
        recordComposeCard(context, identity, publicPreviewUrl(model));
        captureOriginalResourcePreview(model, identity, context, resourceName);
        debugOnce("favorite-tile-" + type, "Wallet favorite tile artwork detected: " + type);
        return previous;
    }

    static Object enterComposeCard(Object cardState) {
        ComposeEntry previous = COMPOSE_CARD.get();
        ComposeScope scope = COMPOSE_SCOPE.get();
        ComposeEntry current = scope == null ? null : scope.cards.get(cardState);
        if (current == null) COMPOSE_CARD.remove();
        else {
            COMPOSE_CARD.set(current);
            debugOnce("compose-card-" + current.identity.id, "Compose card context matched " + shortId(current.identity.id));
        }
        return previous;
    }

    static void leaveComposeCard(Object previous) {
        if (previous instanceof ComposeEntry) COMPOSE_CARD.set((ComposeEntry) previous);
        else COMPOSE_CARD.remove();
    }

    static Object customComposeArtwork(Object originalModel, Constructor<?> bitmapModelConstructor) {
        ComposeEntry entry = COMPOSE_CARD.get();
        Context context = walletContext.get();
        debugOnce("compose-image", "Compose image renderer reached");
        if (isGooglePayContext(context) && !googlePayDetailActive) return originalModel;
        Set<String> urls = artworkUrls(originalModel);
        int resourceId = artworkResourceId(originalModel, context);
        if (originalModel == null || bitmapModelConstructor == null) return originalModel;
        if (entry == null || !entry.matches(originalModel, urls, resourceId)) {
            entry = composeEntryForArt(urls, resourceId);
        }
        if (entry == null) {
            debugOnce("compose-image-no-card", "Compose image did not match a detected card-art model");
            return originalModel;
        }
        if (context == null) return originalModel;
        try {
            ComposeArt art = composeArt(context, entry.identity);
            if (art == null || art.bitmap == null) return originalModel;
            return bitmapModelConstructor.newInstance(art.bitmap);
        } catch (Throwable error) {
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.w(TAG, "Compose artwork replacement skipped", error);
            return originalModel;
        }
    }

    private static ComposeArt composeArt(Context context, CardIdentity identity) {
        long now = System.currentTimeMillis();
        ComposeArt cached = COMPOSE_ART_CACHE.get(identity.id);
        if (cached != null && now - cached.checkedAt < LOOKUP_INTERVAL_MS) return cached;
        synchronized (COMPOSE_ART_CACHE) {
            cached = COMPOSE_ART_CACHE.get(identity.id);
            now = System.currentTimeMillis();
            if (cached != null && now - cached.checkedAt < LOOKUP_INTERVAL_MS) return cached;
            Bundle resolved = resolve(context, identity.id);
            if (resolved == null || !resolved.containsKey(CardStore.KEY_URI)) {
                debugOnce("compose-no-custom-" + identity.id,
                        "No custom image configured for card " + shortId(identity.id));
                ComposeArt noCustomArt = new ComposeArt(0L, now, null);
                COMPOSE_ART_CACHE.put(identity.id, noCustomArt);
                return noCustomArt;
            }
            long revision = resolved.getLong(CardStore.KEY_REVISION, 0L);
            if (cached != null && cached.revision == revision && cached.bitmap != null) {
                ComposeArt refreshed = new ComposeArt(revision, now, cached.bitmap);
                COMPOSE_ART_CACHE.put(identity.id, refreshed);
                return refreshed;
            }
            Bitmap decoded = BitmapDecoder.decode(context.getContentResolver(),
                    Uri.parse(resolved.getString(CardStore.KEY_URI)));
            if (decoded == null) {
                ComposeArt failed = new ComposeArt(revision, now, null);
                COMPOSE_ART_CACHE.put(identity.id, failed);
                return failed;
            }
            Bitmap bitmap = fitComposeArt(decoded, resolved.getString(CardStore.KEY_FIT, FitMode.FIT_CENTER));
            decoded.recycle();
            ComposeArt loaded = new ComposeArt(revision, now, bitmap);
            COMPOSE_ART_CACHE.put(identity.id, loaded);
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.d(TAG, "Replacing card art: " + identity.id);
            return loaded;
        }
    }

    private static Bitmap fitComposeArt(Bitmap source, String fit) {
        final int width = 700;
        final int height = 440;
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        float sx = (float) width / source.getWidth();
        float sy = (float) height / source.getHeight();
        float scale = FitMode.CENTER_CROP.equals(fit) ? Math.max(sx, sy)
                : FitMode.CENTER_INSIDE.equals(fit) ? Math.min(1f, Math.min(sx, sy))
                : Math.min(sx, sy);
        float drawWidth = source.getWidth() * scale;
        float drawHeight = source.getHeight() * scale;
        RectF destination = new RectF((width - drawWidth) / 2f, (height - drawHeight) / 2f,
                (width + drawWidth) / 2f, (height + drawHeight) / 2f);
        new Canvas(output).drawBitmap(source, null, destination, DRAW_PAINT);
        return output;
    }

    private static List<?> listField(Object receiver) {
        if (receiver == null) return null;
        List<?> result = null;
        for (Field field : fields(receiver.getClass())) {
            if (!List.class.isAssignableFrom(field.getType())) continue;
            if (result != null) return null;
            Object value = read(field, receiver);
            if (!(value instanceof List)) return null;
            result = (List<?>) value;
        }
        return result;
    }

    private static List<String> opaqueKeyParts(Object... roots) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        for (Object root : roots) collectOpaqueKeyParts(root, 0, values, visited);
        return new ArrayList<>(values);
    }

    private static void collectOpaqueKeyParts(Object value, int depth, Set<String> values,
            IdentityHashMap<Object, Boolean> visited) {
        if (value == null || depth > 6 || values.size() >= 32 || visited.size() >= 128) return;
        if (value instanceof String) {
            String part = (String) value;
            if (part.length() >= 12 && part.length() <= 128 && !part.contains(" ")
                    && !part.startsWith("http") && !part.startsWith("com.google.")) {
                boolean letters = part.codePoints().anyMatch(Character::isLetter);
                boolean digits = part.codePoints().anyMatch(Character::isDigit);
                int digitRun = 0;
                boolean longNumber = false;
                for (int i = 0; i < part.length(); i++) {
                    digitRun = Character.isDigit(part.charAt(i)) ? digitRun + 1 : 0;
                    if (digitRun >= 8) {
                        longNumber = true;
                        break;
                    }
                }
                if (letters && digits && !longNumber) values.add(part);
            }
            return;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || type.isArray() || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof Iterable || value instanceof Map
                || type.getName().startsWith("android.") || type.getName().startsWith("androidx.")
                || type.getName().startsWith("java.") || type.getName().startsWith("kotlin.")) return;
        if (visited.put(value, Boolean.TRUE) != null) return;
        for (Field field : fields(type)) {
            Object nested = read(field, value);
            if (nested != null) collectOpaqueKeyParts(nested, depth + 1, values, visited);
            if (values.size() >= 32 || visited.size() >= 128) return;
        }
    }

    private static Field artworkField(Class<?> type, Class<?> artworkModelBase) {
        if (artworkModelBase == null) return null;
        Field result = null;
        for (Field field : fields(type)) {
            if (!artworkModelBase.isAssignableFrom(field.getType())) continue;
            if (result != null) return null;
            result = field;
        }
        return result;
    }

    private static Set<String> artworkUrls(Object model) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (model == null) return urls;
        for (Field field : fields(model.getClass())) {
            Object value = read(field, model);
            String url = value instanceof Uri ? value.toString() : value instanceof String ? (String) value : null;
            if (url != null && url.startsWith("http")) {
                urls.add(normalizeArtworkUrl(url));
            }
        }
        return urls;
    }

    private static void indexComposeEntry(ComposeEntry entry) {
        for (String url : entry.artworkUrls) {
            if (AMBIGUOUS_ART_URLS.contains(url)) continue;
            ComposeEntry previous = COMPOSE_BY_ART_URL.putIfAbsent(url, entry);
            if (previous != null && !previous.identity.id.equals(entry.identity.id)) {
                COMPOSE_BY_ART_URL.remove(url, previous);
                AMBIGUOUS_ART_URLS.add(url);
            }
        }
        int resourceId = entry.sourceResourceId;
        if (resourceId != 0 && !AMBIGUOUS_ART_RESOURCES.contains(resourceId)) {
            ComposeEntry previous = COMPOSE_BY_ART_RESOURCE.putIfAbsent(resourceId, entry);
            if (previous != null && !previous.identity.id.equals(entry.identity.id)) {
                COMPOSE_BY_ART_RESOURCE.remove(resourceId, previous);
                AMBIGUOUS_ART_RESOURCES.add(resourceId);
            }
        }
    }

    private static ComposeEntry composeEntryForArt(Set<String> urls, int resourceId) {
        for (String url : urls) {
            if (!AMBIGUOUS_ART_URLS.contains(url)) {
                ComposeEntry entry = COMPOSE_BY_ART_URL.get(url);
                if (entry != null) return entry;
            }
        }
        return resourceId == 0 || AMBIGUOUS_ART_RESOURCES.contains(resourceId)
                ? null : COMPOSE_BY_ART_RESOURCE.get(resourceId);
    }

    private static CardIdentity enrichComposeIdentity(Object model, CardIdentity fallback) {
        if (fallback == null) return null;
        for (String url : artworkUrls(model)) {
            if (AMBIGUOUS_ART_URLS.contains(url)) continue;
            ComposeEntry entry = COMPOSE_BY_ART_URL.get(url);
            if (entry == null || entry.stableKey.isEmpty()) continue;
            ArrayList<String> labels = new ArrayList<>();
            labels.add(fallback.displayName);
            labels.add(fallback.network);
            if (fallback.lastFour != null) labels.add(fallback.lastFour);
            return CardIdentity.fromStableKey(entry.stableKey, labels, url);
        }
        return fallback;
    }

    private static String normalizeArtworkUrl(String url) {
        int end = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return url.substring(0, end).replaceFirst("=w\\d+$", "");
    }

    private static void debugOnce(String key, String message) {
        if (com.tqmane.wallart.BuildConfig.DEBUG && COMPOSE_DEBUG_EVENTS.add(key)) Log.d(TAG, message);
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static List<String> cardLabels(Object cardState, Context context) {
        ArrayList<String> result = new ArrayList<>();
        if (context == null) return result;
        for (Field field : fields(cardState.getClass())) {
            Object value = read(field, cardState);
            addResolvedText(value, context, result);
            if (value == null || artworkUrls(value).size() > 0) continue;
            for (Field nested : fields(value.getClass())) addResolvedText(read(nested, value), context, result);
        }
        return result;
    }

    private static void addResolvedText(Object value, Context context, List<String> output) {
        if (value == null) return;
        if (value instanceof CharSequence) {
            addDisplayLabel(value.toString(), output);
            return;
        }
        for (Method method : value.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 1 || !Context.class.isAssignableFrom(parameters[0])
                    || !CharSequence.class.isAssignableFrom(method.getReturnType())) continue;
            try {
                Object text = method.invoke(value, context);
                if (text instanceof CharSequence) addDisplayLabel(text.toString(), output);
            } catch (Throwable ignored) {
            }
            return;
        }
    }

    private static void addDisplayLabel(String value, List<String> output) {
        String text = value.trim();
        if (text.isEmpty() || text.length() > 80 || text.startsWith("http")) return;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        boolean known = lower.contains("visa") || lower.contains("mastercard")
                || lower.contains("amex") || lower.contains("american express")
                || lower.contains("suica") || lower.contains("quicpay")
                || lower.contains("quickpay") || lower.contains("quick pay");
        boolean readable = text.chars().anyMatch(Character::isWhitespace)
                || text.codePoints().anyMatch(codePoint -> codePoint > 127 && Character.isLetter(codePoint));
        if (known || readable) output.add(text);
    }

    private static String stableKey(Object key) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        appendStableKey(key, parts, 0);
        ArrayList<String> sorted = new ArrayList<>(parts);
        Collections.sort(sorted);
        return String.join("\u001f", sorted);
    }

    private static void appendStableKey(Object key, Set<String> parts, int depth) {
        if (key == null || depth > 2) return;
        if (key instanceof CharSequence) {
            String value = key.toString();
            if (!value.isEmpty()) parts.add(value);
            return;
        }
        if (key.getClass().isPrimitive() || key instanceof Number || key instanceof Boolean || key instanceof Enum<?>) {
            parts.add(key.toString());
            return;
        }
        for (Field field : fields(key.getClass())) {
            if (field.getType() == String.class || CharSequence.class.isAssignableFrom(field.getType())) {
                Object value = read(field, key);
                if (value instanceof CharSequence && ((CharSequence) value).length() > 0) parts.add(value.toString());
            } else if (depth < 2 && !field.getType().isPrimitive()) {
                appendStableKey(read(field, key), parts, depth + 1);
            }
        }
    }

    private static List<Field> fields(Class<?> type) {
        ArrayList<Field> result = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) result.add(field);
            }
        }
        return result;
    }

    private static Object nestedArtworkModel(Object state, Class<?> artworkModelBase) {
        if (state == null || artworkModelBase == null) return null;
        for (Field field : fields(state.getClass())) {
            Object value = read(field, state);
            if (value == null) continue;
            if (artworkModelBase.isAssignableFrom(value.getClass())) return value;
            for (Field nested : fields(value.getClass())) {
                if (!artworkModelBase.isAssignableFrom(nested.getType())) continue;
                Object model = read(nested, value);
                if (model != null) return model;
            }
        }
        return null;
    }

    private static String artworkResourceName(Object model, Context context) {
        int id = artworkResourceId(model, context);
        if (id == 0) return null;
        try {
            String name = context.getResources().getResourceEntryName(id).toLowerCase(java.util.Locale.ROOT);
            return name.contains("suica") || name.contains("quicpay") || name.contains("quickpay") ? name : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int artworkResourceId(Object model, Context context) {
        if (model == null || context == null) return 0;
        for (Field field : fields(model.getClass())) {
            if (field.getType() != int.class) continue;
            Object value = read(field, model);
            if (!(value instanceof Integer)) continue;
            try {
                int id = (Integer) value;
                if ("drawable".equals(context.getResources().getResourceTypeName(id))
                        && context.getPackageName().equals(context.getResources().getResourcePackageName(id))) return id;
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static String favoriteTileType(List<String> labels, Set<String> urls, String resourceName) {
        StringBuilder text = new StringBuilder(resourceName == null ? "" : resourceName.toLowerCase(java.util.Locale.ROOT));
        for (String label : labels) text.append(' ').append(label.toLowerCase(java.util.Locale.ROOT));
        for (String url : urls) text.append(' ').append(url.toLowerCase(java.util.Locale.ROOT));
        String value = text.toString();
        if (value.contains("suica")) return "Suica";
        if (value.contains("quicpay") || value.contains("quickpay") || value.contains("quick pay")) return "QUICPay";
        return null;
    }

    private static void captureOriginalResourcePreview(Object model, CardIdentity identity, Context context, String resourceName) {
        if (model == null || identity == null || context == null || resourceName == null
                || ORIGINAL_CAPTURED.contains(identity.id)) return;
        for (Field field : fields(model.getClass())) {
            if (field.getType() != int.class) continue;
            Object value = read(field, model);
            if (!(value instanceof Integer)) continue;
            try {
                int id = (Integer) value;
                if (!"drawable".equals(context.getResources().getResourceTypeName(id))
                        || !context.getPackageName().equals(context.getResources().getResourcePackageName(id))
                        || !resourceName.equalsIgnoreCase(context.getResources().getResourceEntryName(id))) continue;
                captureDrawablePreview(context.getDrawable(id), identity, context);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object read(Field field, Object receiver) {
        if (field == null || receiver == null) return null;
        try {
            field.setAccessible(true);
            return field.get(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void recordComposeCard(Context context, CardIdentity identity, String previewUrl) {
        if (isGooglePayContext(context)) return;
        long now = System.currentTimeMillis();
        Long last = LAST_RECORDED.get(identity.id);
        if (last != null && now - last < LOOKUP_INTERVAL_MS) return;
        LAST_RECORDED.put(identity.id, now);
        EXECUTOR.execute(() -> {
            record(context, identity);
            if (previewUrl != null) fetchOriginalPreview(context, identity, previewUrl);
            if (com.tqmane.wallart.BuildConfig.DEBUG) {
                Log.d(TAG, "Card discovered: " + identity.network + " •••• "
                        + (identity.lastFour == null ? "----" : identity.lastFour));
            }
        });
    }

    private static String publicPreviewUrl(Object model) {
        if (model == null) return null;
        String candidate = null;
        for (Field field : fields(model.getClass())) {
            Object value = read(field, model);
            String url = value instanceof Uri ? value.toString() : value instanceof String ? (String) value : null;
            if (value instanceof String && value != null && !((String) value).isEmpty()
                    && !((String) value).startsWith("https://")) return null;
            if (url == null || url.isEmpty()) continue;
            if (PublicArtUrl.allowlisted(url) == null) return null;
            if (candidate == null) candidate = url;
        }
        return candidate;
    }

    private static void fetchOriginalPreview(Context context, CardIdentity identity, String url) {
        if (ORIGINAL_CAPTURED.contains(identity.id) || !ORIGINAL_CAPTURE_PENDING.add(identity.id)) return;
        if (!ORIGINAL_FETCH_ATTEMPTED.add(identity.id)) {
            ORIGINAL_CAPTURE_PENDING.remove(identity.id);
            return;
        }
        try {
            Bundle existing = resolve(context, identity.id);
            if (existing != null && existing.containsKey(CardStore.KEY_ORIGINAL_URI)) {
                ORIGINAL_CAPTURED.add(identity.id);
                ORIGINAL_CAPTURE_PENDING.remove(identity.id);
                return;
            }
            ORIGINAL_FETCH_EXECUTOR.execute(() -> {
                Bitmap decoded = null;
                Bitmap preview = null;
                boolean saved = false;
                try {
                    byte[] bytes = downloadPublicPreview(url);
                    decoded = BitmapDecoder.decodeBytes(bytes);
                    if (decoded != null) {
                        preview = fitComposeArt(decoded, FitMode.FIT_CENTER);
                        saved = storeOriginalPreview(context, identity.id, preview);
                    }
                } catch (Throwable error) {
                    if (com.tqmane.wallart.BuildConfig.DEBUG) Log.w(TAG, "Original artwork preview unavailable", error);
                } finally {
                    if (decoded != null) decoded.recycle();
                    if (preview != null) preview.recycle();
                    ORIGINAL_CAPTURE_PENDING.remove(identity.id);
                    if (saved) {
                        ORIGINAL_CAPTURED.add(identity.id);
                        if (com.tqmane.wallart.BuildConfig.DEBUG) {
                            Log.d(TAG, "Original artwork preview loaded from public card art for " + shortId(identity.id));
                        }
                    }
                }
            });
        } catch (Throwable error) {
            ORIGINAL_CAPTURE_PENDING.remove(identity.id);
        }
    }

    private static byte[] downloadPublicPreview(String initialUrl) throws Exception {
        String currentUrl = initialUrl;
        for (int redirect = 0; redirect <= 2; redirect++) {
            String safeUrl = PublicArtUrl.allowlisted(currentUrl);
            if (safeUrl == null) return null;
            HttpURLConnection connection = (HttpURLConnection) new URL(safeUrl).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setUseCaches(true);
            connection.setInstanceFollowRedirects(false);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirect == 2) return null;
                    currentUrl = new URL(new URL(safeUrl), location).toString();
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) return null;
                String contentType = connection.getContentType();
                if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return null;
                int contentLength = connection.getContentLength();
                if (contentLength > 2 * 1024 * 1024) return null;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > 2 * 1024 * 1024) return null;
                        output.write(buffer, 0, read);
                    }
                    return output.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        return null;
    }

    static void afterRender(java.util.List<Object> args) {
        if (args == null || args.size() != 3 || !(args.get(1) instanceof ImageView)) return;
        Context context = ((ImageView) args.get(1)).getContext();
        if (isGooglePayContext(context) && !googlePayDetailActive) return;
        CardIdentity identity = enrichComposeIdentity(args.get(2), CardIdentity.fromModel(args.get(2)));
        identity = resolveExistingIdentity(context, identity);
        if (identity != null && args.get(0) instanceof Drawable) {
            ART_IDENTITIES.put((Drawable) args.get(0), identity);
        }
        enqueue((ImageView) args.get(1), args.get(0), identity);
    }

    static boolean drawCustom(Canvas canvas, Drawable walletDrawable) {
        CardIdentity identity = ART_IDENTITIES.get(walletDrawable);
        if (identity == null) identity = CardIdentity.fromModel(walletDrawable);
        Context context = artworkContext(walletDrawable);
        if (isGooglePayContext(context) && !googlePayDetailActive) return false;
        identity = resolveExistingIdentity(context, identity);
        if (identity == null || context == null) {
            if (com.tqmane.wallart.BuildConfig.DEBUG && IDENTITY_WARNED.add("unresolved")) {
                Log.w(TAG, "Card-art Drawable has no usable non-sensitive identity or context");
            }
            return false;
        }

        captureOriginalPreview(walletDrawable, identity, context);
        refresh(context, walletDrawable, identity);
        CachedArt art = DRAWABLE_CACHE.get(identity.id);
        if (art == null) return false;
        Rect bounds = walletDrawable.getBounds();
        if (bounds.isEmpty()) return false;

        float width = bounds.width();
        float height = bounds.height();
        float sx = width / art.bitmap.getWidth();
        float sy = height / art.bitmap.getHeight();
        float scale = FitMode.CENTER_CROP.equals(art.fit) ? Math.max(sx, sy)
                : FitMode.CENTER_INSIDE.equals(art.fit) ? Math.min(1f, Math.min(sx, sy))
                : Math.min(sx, sy);
        float drawWidth = art.bitmap.getWidth() * scale;
        float drawHeight = art.bitmap.getHeight() * scale;
        RectF destination = new RectF(
                bounds.centerX() - drawWidth / 2f,
                bounds.centerY() - drawHeight / 2f,
                bounds.centerX() + drawWidth / 2f,
                bounds.centerY() + drawHeight / 2f);
        int saveCount = canvas.save();
        float radius = width * (1f / 28f);
        Path clip = new Path();
        clip.addRoundRect(new RectF(bounds), radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawBitmap(art.bitmap, null, destination, DRAW_PAINT);
        canvas.restoreToCount(saveCount);
        return true;
    }

    private static void captureOriginalPreview(Drawable walletDrawable, CardIdentity identity, Context context) {
        captureDrawablePreview(originalArtwork(walletDrawable), identity, context);
    }

    private static void captureDrawablePreview(Drawable source, CardIdentity identity, Context context) {
        if (source == null || identity == null || context == null || ORIGINAL_CAPTURED.contains(identity.id)
                || !ORIGINAL_CAPTURE_PENDING.add(identity.id)) return;
        Bitmap snapshot = null;
        boolean queued = false;
        try {
            Drawable.ConstantState state = source.getConstantState();
            if (state == null) return;
            Drawable copy = state.newDrawable(context.getResources()).mutate();
            if (copy == source) return;
            int width = 700;
            int height = 440;
            snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int sourceWidth = copy.getIntrinsicWidth();
            int sourceHeight = copy.getIntrinsicHeight();
            float scale = sourceWidth > 0 && sourceHeight > 0
                    ? Math.min(width / (float) sourceWidth, height / (float) sourceHeight) : 0f;
            int drawWidth = scale > 0f ? Math.max(1, Math.round(sourceWidth * scale)) : width;
            int drawHeight = scale > 0f ? Math.max(1, Math.round(sourceHeight * scale)) : height;
            copy.setBounds((width - drawWidth) / 2, (height - drawHeight) / 2,
                    (width + drawWidth) / 2, (height + drawHeight) / 2);
            Canvas previewCanvas = new Canvas(snapshot);
            previewCanvas.drawColor(Color.WHITE);
            copy.draw(previewCanvas);
            Bitmap captured = snapshot;
            snapshot = null;
            Context appContext = context.getApplicationContext();
            EXECUTOR.execute(() -> {
                boolean saved = storeOriginalPreview(appContext, identity.id, captured);
                captured.recycle();
                ORIGINAL_CAPTURE_PENDING.remove(identity.id);
                if (saved) {
                    ORIGINAL_CAPTURED.add(identity.id);
                    if (com.tqmane.wallart.BuildConfig.DEBUG) {
                        Log.d(TAG, "Original artwork preview captured for " + shortId(identity.id));
                    }
                }
            });
            queued = true;
        } catch (Throwable error) {
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.w(TAG, "Original artwork preview skipped", error);
        } finally {
            if (snapshot != null) snapshot.recycle();
            if (!queued) ORIGINAL_CAPTURE_PENDING.remove(identity.id);
        }
    }

    private static boolean storeOriginalPreview(Context context, String id, Bitmap bitmap) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) return false;
            byte[] bytes = output.toByteArray();
            if (bytes.length == 0 || bytes.length > 512 * 1024) return false;
            Bundle values = new Bundle();
            values.putString(CardStore.KEY_ID, id);
            values.putByteArray(CardStore.KEY_PREVIEW, bytes);
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + CardStore.AUTHORITY),
                    CardStore.METHOD_RECORD_ORIGINAL, null, values);
            return result != null && result.getBoolean(CardStore.KEY_STORED, false);
        } catch (Throwable error) {
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.w(TAG, "Original artwork preview storage skipped", error);
            return false;
        }
    }

    private static Drawable originalArtwork(Drawable walletDrawable) {
        Drawable result = null;
        for (Field field : fields(walletDrawable.getClass())) {
            if (!Drawable.class.isAssignableFrom(field.getType())) continue;
            Object value = read(field, walletDrawable);
            if (!(value instanceof Drawable) || value == walletDrawable) continue;
            Drawable candidate = (Drawable) value;
            if (candidate.getIntrinsicWidth() <= 0 || candidate.getIntrinsicHeight() <= 0) continue;
            if (result != null) return null;
            result = candidate;
        }
        return result;
    }

    private static void enqueue(ImageView imageView, Object walletDrawable, CardIdentity identity) {
        if (walletDrawable == null || identity == null) return;
        Context context = imageView.getContext();
        EXECUTOR.execute(() -> {
            try {
                if (!isGooglePayContext(context)) record(context, identity);
                if (com.tqmane.wallart.BuildConfig.DEBUG) {
                    Log.d(TAG, "Card discovered: " + identity.network + " •••• "
                            + (identity.lastFour == null ? "----" : identity.lastFour));
                }
                Bundle resolved = resolve(context, identity.id);
                if (resolved == null || !resolved.containsKey(CardStore.KEY_URI)) {
                    imageView.post(() -> restore(imageView, walletDrawable));
                    return;
                }
                Uri uri = Uri.parse(resolved.getString(CardStore.KEY_URI));
                Bitmap bitmap = BitmapDecoder.decode(context.getContentResolver(), uri);
                if (bitmap == null) {
                    imageView.post(() -> restore(imageView, walletDrawable));
                    return;
                }
                String fit = resolved.getString(CardStore.KEY_FIT, FitMode.FIT_CENTER);
                imageView.post(() -> apply(imageView, walletDrawable, bitmap, fit, identity.id));
            } catch (Throwable error) {
                if (com.tqmane.wallart.BuildConfig.DEBUG) Log.w(TAG, "Artwork lookup failed", error);
                imageView.post(() -> restore(imageView, walletDrawable));
            }
        });
    }

    private static void record(Context context, CardIdentity identity) {
        try {
            Bundle values = new Bundle();
            values.putString(CardStore.KEY_ID, identity.id);
            values.putString(CardStore.KEY_LABEL, identity.displayName);
            values.putString(CardStore.KEY_NETWORK, identity.network);
            values.putString(CardStore.KEY_LAST_FOUR, identity.lastFour);
            values.putStringArray(CardStore.KEY_FINGERPRINTS, identity.lookupFingerprints);
            context.getContentResolver().call(Uri.parse("content://" + CardStore.AUTHORITY), CardStore.METHOD_RECORD, null, values);
        } catch (Throwable ignored) {
            // Settings is optional; Wallet keeps its original renderer.
        }
    }

    private static Bundle resolve(Context context, String id) {
        Bundle values = new Bundle();
        values.putString(CardStore.KEY_ID, id);
        return context.getContentResolver().call(Uri.parse("content://" + CardStore.AUTHORITY), CardStore.METHOD_RESOLVE, null, values);
    }

    private static void apply(ImageView imageView, Object walletDrawable, Bitmap bitmap, String fit, String id) {
        if (imageView.getDrawable() != walletDrawable) return;
        synchronized (ORIGINAL_SCALE) {
            if (!ORIGINAL_SCALE.containsKey(imageView)) ORIGINAL_SCALE.put(imageView, imageView.getScaleType());
        }
        Drawable replacement = new BitmapDrawable(imageView.getResources(), bitmap);
        imageView.setScaleType(FitMode.scaleType(fit));
        imageView.setImageDrawable(replacement);
        if (com.tqmane.wallart.BuildConfig.DEBUG) Log.d(TAG, "Replacing card art: " + id);
    }

    private static void restore(ImageView imageView, Object walletDrawable) {
        if (imageView.getDrawable() != walletDrawable) return;
        ImageView.ScaleType original = ORIGINAL_SCALE.remove(imageView);
        if (original != null) imageView.setScaleType(original);
    }

    private static void refresh(Context context, Drawable drawable, CardIdentity identity) {
        long now = System.currentTimeMillis();
        Long checkedAt = LAST_LOOKUP.get(identity.id);
        if ((checkedAt != null && now - checkedAt < LOOKUP_INTERVAL_MS)
                || !LOOKUP_IN_FLIGHT.add(identity.id)) return;
        LAST_LOOKUP.put(identity.id, now);
        EXECUTOR.execute(() -> {
            try {
                if (!isGooglePayContext(context)) record(context, identity);
                Bundle resolved = resolve(context, identity.id);
                if (resolved == null || !resolved.containsKey(CardStore.KEY_URI)) {
                    if (DRAWABLE_CACHE.remove(identity.id) != null) drawable.invalidateSelf();
                    return;
                }
                long revision = resolved.getLong(CardStore.KEY_REVISION, 0L);
                CachedArt current = DRAWABLE_CACHE.get(identity.id);
                if (current != null && current.revision == revision) return;
                Uri uri = Uri.parse(resolved.getString(CardStore.KEY_URI));
                Bitmap bitmap = BitmapDecoder.decode(context.getContentResolver(), uri);
                if (bitmap == null) return;
                DRAWABLE_CACHE.put(identity.id, new CachedArt(
                        bitmap, resolved.getString(CardStore.KEY_FIT, FitMode.FIT_CENTER), revision));
                drawable.invalidateSelf();
                if (com.tqmane.wallart.BuildConfig.DEBUG) {
                    Log.d(TAG, "Replacing card art: " + identity.network + " •••• "
                            + (identity.lastFour == null ? "----" : identity.lastFour));
                }
            } catch (Throwable error) {
                if (com.tqmane.wallart.BuildConfig.DEBUG) Log.w(TAG, "Artwork lookup failed", error);
            } finally {
                LOOKUP_IN_FLIGHT.remove(identity.id);
            }
        });
    }

    private static Context artworkContext(Drawable drawable) {
        try {
            for (java.lang.reflect.Field field : drawable.getClass().getDeclaredFields()) {
                if (Context.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (Context) field.get(drawable);
                }
            }
        } catch (Throwable error) {
            if (com.tqmane.wallart.BuildConfig.DEBUG) Log.w(TAG, "Artwork context unavailable", error);
        }
        return null;
    }
}
