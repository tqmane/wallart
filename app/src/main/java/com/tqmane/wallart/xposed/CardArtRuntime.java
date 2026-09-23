package com.tqmane.wallart.xposed;

import android.content.Context;
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
import android.util.Log;
import android.widget.ImageView;

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
        onWalletActivityResumed(context);
    }

    static void onGooglePayActivityStopped() {
        googlePayDetailActive = false;
        if (isGooglePayContext(walletContext.get())) walletContext = new WeakReference<>(null);
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
            return result == null ? null : identity.withId(result.getString(CardStore.KEY_RESOLVED_ID));
        } catch (Throwable ignored) {
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
