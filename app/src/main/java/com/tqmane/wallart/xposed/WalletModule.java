package com.tqmane.wallart.xposed;

import android.app.Application;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.ImageView;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class WalletModule extends XposedModule {
    private static final String TAG = "WallArt";
    private static final String GMS_CARD_DETAIL_ACTION =
            "com.google.android.gms.pay.secard.view.detail.VIEW_SE_MFI_PREPAID_CARD_DETAIL";
    private static final String GMS_FOP_DETAIL_ACTION = "com.google.android.gms.pay.fops.VIEW_FOP";
    private static final String GMS_UI_PROCESS = com.tqmane.wallart.storage.CardStore.GOOGLE_PAY_PACKAGE + ".ui";
    private static final String GMS_TAP_ACTIVITY_CLASS = "com.google.android.gms.tapandpay.tap.TapActivity";
    private static final String GMS_TAP_ACTION = "com.google.android.gms.tapandpay.tap.TAP_EVENT";
    private final Set<ClassLoader> hookedClassLoaders = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Method> composeCardHooks = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Method> composeImageHooks = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Method> composeStackHooks = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Method> composeTileHooks = Collections.newSetFromMap(new WeakHashMap<>());
    private final AtomicBoolean attachHookInstalled = new AtomicBoolean();
    private final AtomicBoolean activityCreateHookInstalled = new AtomicBoolean();
    private final AtomicBoolean activityHookInstalled = new AtomicBoolean();
    private final AtomicBoolean activityPauseHookInstalled = new AtomicBoolean();
    private final AtomicBoolean walletTouchHookInstalled = new AtomicBoolean();
    private final AtomicBoolean drawableDrawSeen = new AtomicBoolean();

    static {
        if (com.tqmane.wallart.BuildConfig.DEBUG) Log.i(TAG, "Entry initialized (API 102)");
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Log.i(TAG, "Module loaded in " + param.getProcessName());
        log(Log.INFO, TAG, "Loaded in " + param.getProcessName());
        if (isWalletProcess(param.getProcessName())) {
            ClassLoader loader = walletClassLoader();
            if (loader != null) installFor(loader);
            hookActivityResumeFallback();
            hookWalletSelectionFallback();
            if (!hasHooks()) hookApplicationAttach();
        } else if (isGooglePlayServicesProcess(param.getProcessName())) {
            hookActivityCreateFallback();
            hookActivityResumeFallback();
            hookActivityPauseFallback();
        }
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        Log.i(TAG, "Package loaded: " + param.getPackageName() + " loader="
                + System.identityHashCode(param.getDefaultClassLoader()));
        if (com.tqmane.wallart.storage.CardStore.WALLET_PACKAGE.equals(param.getPackageName())) {
            hookActivityResumeFallback();
            hookWalletSelectionFallback();
            installFor(param.getDefaultClassLoader());
        } else if (com.tqmane.wallart.storage.CardStore.GOOGLE_PAY_PACKAGE.equals(param.getPackageName())
                && isGooglePlayServicesUiProcess()) {
            hookActivityCreateFallback();
            hookActivityResumeFallback();
            hookActivityPauseFallback();
        } else if (isWalletProcess(null)) {
            ClassLoader loader = walletClassLoader();
            if (loader != null) installFor(loader);
            if (!hasHooks()) hookActivityResumeFallback();
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        Log.i(TAG, "Package ready: " + param.getPackageName() + " loader="
                + System.identityHashCode(param.getClassLoader()));
        if (com.tqmane.wallart.storage.CardStore.WALLET_PACKAGE.equals(param.getPackageName())) {
            hookActivityResumeFallback();
            hookWalletSelectionFallback();
            installFor(param.getClassLoader());
        } else if (com.tqmane.wallart.storage.CardStore.GOOGLE_PAY_PACKAGE.equals(param.getPackageName())
                && isGooglePlayServicesUiProcess()) {
            hookActivityCreateFallback();
            hookActivityResumeFallback();
            hookActivityPauseFallback();
        } else if (isWalletProcess(null)) {
            ClassLoader loader = walletClassLoader();
            if (loader != null) installFor(loader);
            if (!hasHooks()) hookActivityResumeFallback();
        }
    }

    private boolean isWalletProcess(String processName) {
        if (processName != null && processName.startsWith(com.tqmane.wallart.storage.CardStore.WALLET_PACKAGE)) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return com.tqmane.wallart.storage.CardStore.WALLET_PACKAGE.equals(Application.getProcessName());
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean isGooglePlayServicesProcess(String processName) {
        return GMS_UI_PROCESS.equals(processName);
    }

    private boolean isGooglePlayServicesUiProcess() {
        if (Build.VERSION.SDK_INT < 28) return false;
        try {
            return isGooglePlayServicesProcess(Application.getProcessName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isGooglePayDetailActivity(Activity activity) {
        if (activity == null
                || !com.tqmane.wallart.storage.CardStore.GOOGLE_PAY_PACKAGE.equals(activity.getPackageName())
                || !activity.getClass().getName().endsWith(".pay.main.PayActivity")) return false;
        String action = activity.getIntent() == null ? null : activity.getIntent().getAction();
        return GMS_CARD_DETAIL_ACTION.equals(action) || GMS_FOP_DETAIL_ACTION.equals(action);
    }

    private boolean isGooglePayTapConfirmationActivity(Activity activity) {
        return activity != null
                && com.tqmane.wallart.storage.CardStore.GOOGLE_PAY_PACKAGE.equals(activity.getPackageName())
                && GMS_TAP_ACTIVITY_CLASS.equals(activity.getClass().getName())
                && activity.getIntent() != null
                && GMS_TAP_ACTION.equals(activity.getIntent().getAction());
    }

    private boolean isGooglePayArtworkActivity(Activity activity) {
        return isGooglePayDetailActivity(activity) || isGooglePayTapConfirmationActivity(activity);
    }

    private void hookActivityCreateFallback() {
        if (!activityCreateHookInstalled.compareAndSet(false, true)) return;
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", android.os.Bundle.class);
            onCreate.setAccessible(true);
            hook(onCreate)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("google-pay-detail-activity-create")
                    .intercept(chain -> {
                        Object receiver = chain.getThisObject();
                        Activity activity = receiver instanceof Activity ? (Activity) receiver : null;
                        boolean target = isGooglePayArtworkActivity(activity);
                        if (target) {
                            log(Log.INFO, TAG, isGooglePayTapConfirmationActivity(activity)
                                    ? "GMS tap confirmation activity entered" : "GMS PayActivity entered");
                            CardArtRuntime.onGooglePayActivityStarted(activity);
                        }
                        Object result = chain.proceed();
                        return result;
                    });
            Log.i(TAG, "Pay detail activity creation fallback installed");
        } catch (Throwable error) {
            activityCreateHookInstalled.set(false);
            Log.w(TAG, "Pay detail activity creation fallback unavailable", error);
        }
    }

    private ClassLoader walletClassLoader() {
        try {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (isWalletClassLoader(contextLoader)) return contextLoader;

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method current = activityThreadClass.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object activityThread = current.invoke(null);
            if (activityThread == null) return null;

            Object initial = readField(activityThread, "mInitialApplication");
            if (initial instanceof Application
                    && com.tqmane.wallart.storage.CardStore.WALLET_PACKAGE.equals(((Application) initial).getPackageName())) {
                ClassLoader loader = ((Application) initial).getClassLoader();
                if (isWalletClassLoader(loader)) return loader;
            }

            Object packages = readField(activityThread, "mPackages");
            if (packages instanceof Map) {
                Object reference = ((Map<?, ?>) packages).get(com.tqmane.wallart.storage.CardStore.WALLET_PACKAGE);
                Object loadedApk = reference instanceof WeakReference ? ((WeakReference<?>) reference).get() : null;
                if (loadedApk != null) {
                    ClassLoader loader = (ClassLoader) readField(loadedApk, "mClassLoader");
                    if (isWalletClassLoader(loader)) return loader;
                    loader = (ClassLoader) readField(loadedApk, "mDefaultClassLoader");
                    if (isWalletClassLoader(loader)) return loader;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Cannot resolve Wallet classloader", error);
        }
        return null;
    }

    private boolean isWalletClassLoader(ClassLoader loader) {
        if (loader == null) return false;
        try {
            Class<?> carousel = Class.forName(
                    "com.google.android.apps.wallet.home.cardcarousel.CardCarousel", false, loader);
            return carousel.getClassLoader() == loader;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object readField(Object receiver, String name) throws Exception {
        Class<?> type = receiver.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private void hookApplicationAttach() {
        if (!attachHookInstalled.compareAndSet(false, true)) return;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallet-application-attach")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Context context = (Context) chain.getArg(0);
                            if (com.tqmane.wallart.storage.CardStore.WALLET_PACKAGE.equals(context.getPackageName())) {
                                CardArtRuntime.onWalletActivityResumed(context);
                                installFor(context.getClassLoader());
                            }
                        } catch (Throwable error) {
                            Log.w(TAG, "Application attach fallback failed", error);
                        }
                        return result;
                    });
            Log.i(TAG, "Application attach fallback installed");
        } catch (Throwable error) {
            attachHookInstalled.set(false);
            Log.e(TAG, "Cannot install Application attach fallback", error);
        }
    }

    private void hookActivityResumeFallback() {
        if (!activityHookInstalled.compareAndSet(false, true)) return;
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            onResume.setAccessible(true);
            hook(onResume)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallet-activity-resume")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object receiver = chain.getThisObject();
                            if (receiver instanceof Activity) {
                                Activity activity = (Activity) receiver;
                                if (com.tqmane.wallart.storage.CardStore.WALLET_PACKAGE.equals(activity.getPackageName())) {
                                    CardArtRuntime.onWalletActivityResumed(activity);
                                    installFor(activity.getClassLoader());
                                } else if (isGooglePayTapConfirmationActivity(activity)) {
                                    log(Log.INFO, TAG, "GMS tap confirmation activity resumed");
                                    CardArtRuntime.onGooglePayTapActivityResumed(activity);
                                } else if (isGooglePayDetailActivity(activity)) {
                                    log(Log.INFO, TAG, "GMS PayActivity resumed");
                                    CardArtRuntime.onGooglePayActivityResumed(activity);
                                }
                            }
                        } catch (Throwable error) {
                            Log.w(TAG, "Activity resume fallback failed", error);
                        }
                        return result;
                    });
            Log.i(TAG, "Activity resume fallback installed");
        } catch (Throwable error) {
            activityHookInstalled.set(false);
            Log.e(TAG, "Cannot install Activity resume fallback", error);
        }
    }

    private void hookWalletSelectionFallback() {
        if (!walletTouchHookInstalled.compareAndSet(false, true)) return;
        try {
            Method dispatchTouchEvent = Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            dispatchTouchEvent.setAccessible(true);
            hook(dispatchTouchEvent)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallet-card-selection-bridge")
                    .intercept(chain -> {
                        Object receiver = chain.getThisObject();
                        Object argument = chain.getArg(0);
                        if (receiver instanceof Activity && argument instanceof MotionEvent) {
                            Activity activity = (Activity) receiver;
                            MotionEvent event = (MotionEvent) argument;
                            if (com.tqmane.wallart.storage.CardStore.WALLET_PACKAGE.equals(activity.getPackageName())
                                    && event.getActionMasked() == MotionEvent.ACTION_UP) {
                                try {
                                    CardArtRuntime.captureWalletCardSelection(activity, event.getRawX(), event.getRawY());
                                } catch (Throwable error) {
                                    Log.w(TAG, "Wallet card selection capture skipped", error);
                                }
                            }
                        }
                        return chain.proceed();
                    });
            Log.i(TAG, "Wallet card-selection bridge installed");
        } catch (Throwable error) {
            walletTouchHookInstalled.set(false);
            Log.w(TAG, "Wallet card-selection bridge unavailable", error);
        }
    }

    private void hookActivityPauseFallback() {
        if (!activityPauseHookInstalled.compareAndSet(false, true)) return;
        try {
            Method onPause = Activity.class.getDeclaredMethod("onPause");
            onPause.setAccessible(true);
            hook(onPause)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("google-pay-detail-activity-pause")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object receiver = chain.getThisObject();
                            if (receiver instanceof Activity && isGooglePayArtworkActivity((Activity) receiver)) {
                                CardArtRuntime.onGooglePayActivityStopped();
                            }
                        } catch (Throwable error) {
                            Log.w(TAG, "Pay detail activity cleanup skipped", error);
                        }
                        return result;
                    });
            Log.i(TAG, "Pay detail activity pause fallback installed");
        } catch (Throwable error) {
            activityPauseHookInstalled.set(false);
            Log.w(TAG, "Pay detail activity pause fallback unavailable", error);
        }
    }

    private void installFor(ClassLoader classLoader) {
        if (classLoader == null) return;
        synchronized (hookedClassLoaders) {
            if (hookedClassLoaders.contains(classLoader)) return;
        }
        try {
            Log.i(TAG, "Searching card art renderer in " + classLoader);
            log(Log.INFO, TAG, "Searching card art renderer...");
            WalletDiscovery.Discovery discovery = WalletDiscovery.discover(classLoader);
            if (discovery.drawableRenderers.isEmpty() && discovery.composeStackRenderers.isEmpty()
                    && discovery.composeTileRenderers.isEmpty()) {
                String message = "Card-art renderers not found; original app behavior is unchanged";
                Log.w(TAG, message);
                log(Log.WARN, TAG, message);
                return;
            }
            for (Method method : discovery.drawableRenderers) {
                try {
                    method.setAccessible(true);
                    hook(method)
                            .setPriority(XposedInterface.PRIORITY_DEFAULT)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("wallet-card-art-" + method.toGenericString().hashCode())
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                try {
                                    CardArtRuntime.afterRender(chain.getArgs());
                                } catch (Throwable error) {
                                    log(Log.WARN, TAG, "Card art replacement skipped", error);
                                }
                                return result;
                            });
                } catch (Throwable error) {
                    Log.w(TAG, "Legacy card-art renderer hook skipped", error);
                }
            }
            Set<Method> imageLoaders = new LinkedHashSet<>();
            Set<Class<?>> drawableTypes = new LinkedHashSet<>();
            for (Method renderer : discovery.drawableRenderers) {
                Method loader = WalletDiscovery.findCardArtLoaderMethod(renderer);
                if (loader != null && imageLoaders.add(loader)) hookArtLoader(loader);
                Class<?> drawableType = renderer.getParameterTypes()[0];
                if (drawableTypes.add(drawableType)) hookDrawableDraw(drawableType);
            }
            for (Method method : discovery.composeImageRenderers) {
                if (discovery.bitmapModelConstructor != null) hookComposeImage(method, discovery.bitmapModelConstructor);
            }
            for (Method method : discovery.composeStackRenderers) hookComposeStack(method, discovery);
            for (Method method : discovery.composeTileRenderers) hookComposeTile(method, discovery);
            synchronized (hookedClassLoaders) {
                hookedClassLoaders.add(classLoader);
            }
            Log.i(TAG, "Hook installed: " + discovery.drawableRenderers.size() + " legacy renderer(s), "
                    + discovery.composeStackRenderers.size() + " Compose card stack(s), "
                    + discovery.composeTileRenderers.size() + " Compose favorite-tile renderer(s), "
                    + discovery.composeImageRenderers.size() + " Compose image renderer(s)");
            log(Log.INFO, TAG, "Hook installed for verified Wallet card-art paths");
        } catch (Throwable error) {
            Log.e(TAG, "Hook installation failed; original Wallet behavior is unchanged", error);
            log(Log.ERROR, TAG, "Hook installation failed; original Wallet behavior is unchanged", error);
        }
    }

    private void hookComposeStack(Method method, WalletDiscovery.Discovery discovery) {
        synchronized (composeStackHooks) {
            if (composeStackHooks.contains(method)) return;
            composeStackHooks.add(method);
        }
        try {
            method.setAccessible(true);
            hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallet-compose-card-stack-" + method.toGenericString().hashCode())
                    .intercept(chain -> {
                        CardArtRuntime.ComposeScope scope = CardArtRuntime.enterComposeState(
                                chain.getArg(0), discovery.artworkModelBase, CardArtRuntime.walletContext());
                        try {
                            if (scope.cardStateType != null) installComposeCardHooks(discovery, scope.cardStateType);
                            return chain.proceed();
                        } finally {
                            CardArtRuntime.leaveComposeState(scope);
                        }
                    });
            Log.i(TAG, "Compose card-stack renderer discovered by signature");
        } catch (Throwable error) {
            synchronized (composeStackHooks) {
                composeStackHooks.remove(method);
            }
            Log.w(TAG, "Compose card-stack hook unavailable; original renderer remains", error);
        }
    }

    private void hookComposeTile(Method method, WalletDiscovery.Discovery discovery) {
        synchronized (composeTileHooks) {
            if (composeTileHooks.contains(method)) return;
            composeTileHooks.add(method);
        }
        try {
            method.setAccessible(true);
            hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallet-compose-favorite-tile-" + method.toGenericString().hashCode())
                    .intercept(chain -> {
                        Object previous = CardArtRuntime.enterComposeTileState(
                                chain.getArg(0), discovery.artworkModelBase, CardArtRuntime.walletContext());
                        try {
                            return chain.proceed();
                        } finally {
                            CardArtRuntime.leaveComposeCard(previous);
                        }
                    });
            Log.i(TAG, "Compose favorite-tile renderer discovered by signature");
        } catch (Throwable error) {
            synchronized (composeTileHooks) {
                composeTileHooks.remove(method);
            }
            Log.w(TAG, "Compose favorite-tile hook unavailable; original renderer remains", error);
        }
    }

    private void installComposeCardHooks(WalletDiscovery.Discovery discovery, Class<?> cardStateType) {
        List<Method> methods = WalletDiscovery.findComposeCardRenderers(discovery, cardStateType);
        if (methods.isEmpty()) return;
        for (Method method : methods) {
            synchronized (composeCardHooks) {
                if (!composeCardHooks.add(method)) continue;
            }
            try {
                method.setAccessible(true);
                hook(method)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("wallet-compose-card-" + method.toGenericString().hashCode())
                        .intercept(chain -> {
                            Object previous = CardArtRuntime.enterComposeCard(chain.getArg(1));
                            try {
                                return chain.proceed();
                            } finally {
                                CardArtRuntime.leaveComposeCard(previous);
                            }
                        });
            } catch (Throwable error) {
                synchronized (composeCardHooks) {
                    composeCardHooks.remove(method);
                }
                Log.w(TAG, "Compose card context hook skipped; original renderer remains", error);
            }
        }
        Log.i(TAG, "Compose card context hook installed for " + methods.size() + " structural candidate(s)");
    }

    private void hookComposeImage(Method method, java.lang.reflect.Constructor<?> bitmapModelConstructor) {
        synchronized (composeImageHooks) {
            if (!composeImageHooks.add(method)) return;
        }
        try {
            method.setAccessible(true);
            hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallet-compose-card-art-" + method.toGenericString().hashCode())
                    .intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        Object replacement = CardArtRuntime.customComposeArtwork(args[0], bitmapModelConstructor);
                        if (replacement == args[0]) return chain.proceed();
                        args[0] = replacement;
                        return chain.proceed(args);
                    });
            Log.i(TAG, "Compose image renderer discovered by signature");
        } catch (Throwable error) {
            synchronized (composeImageHooks) {
                composeImageHooks.remove(method);
            }
            Log.w(TAG, "Compose image hook unavailable; original image renderer remains", error);
        }
    }

    private boolean hasHooks() {
        synchronized (hookedClassLoaders) {
            return !hookedClassLoaders.isEmpty();
        }
    }

    private void hookArtLoader(Method method) {
        try {
            method.setAccessible(true);
            hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallet-card-art-loader-" + method.toGenericString().hashCode())
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object[] args = chain.getArgs().toArray();
                            if (args.length == 4 && args[0] instanceof ImageView) {
                                if (com.tqmane.wallart.BuildConfig.DEBUG) Log.d(TAG, "Card-art loader invoked");
                                CardArtRuntime.afterRender(Arrays.asList(args[1], args[0], args[3]));
                            }
                        } catch (Throwable error) {
                            Log.w(TAG, "Card-art loader fallback skipped", error);
                        }
                        return result;
                    });
            Log.i(TAG, "Card-art loader fallback installed: " + method.toGenericString());
        } catch (Throwable error) {
            Log.w(TAG, "Card-art loader fallback unavailable", error);
        }
    }

    private void hookDrawableDraw(Class<?> artworkDrawableClass) {
        try {
            Method draw = artworkDrawableClass.getDeclaredMethod("draw", Canvas.class);
            hook(draw)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wallet-card-art-draw-fallback-" + draw.toGenericString().hashCode())
                    .intercept(chain -> {
                        Object drawable = chain.getThisObject();
                        Canvas canvas = (Canvas) chain.getArg(0);
                        if (drawable != null && artworkDrawableClass.isInstance(drawable)) {
                            if (com.tqmane.wallart.BuildConfig.DEBUG && drawableDrawSeen.compareAndSet(false, true)) {
                                Log.d(TAG, "Wallet card-art Drawable.draw reached");
                            }
                            if (CardArtRuntime.drawCustom(canvas, (Drawable) drawable)) return null;
                        }
                        return chain.proceed();
                    });
            Log.i(TAG, "Wallet card-art Drawable fallback installed");
        } catch (Throwable error) {
            Log.w(TAG, "Filtered ImageView fallback unavailable", error);
        }
    }
}
