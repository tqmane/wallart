package com.tqmane.wallart.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.widget.ImageView;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;

import dalvik.system.DexFile;

final class WalletDiscovery {
    private WalletDiscovery() {
    }

    static final class Discovery {
        final List<Class<?>> classes;
        final List<Method> drawableRenderers;
        final List<Method> composeStackRenderers;
        final List<Method> composeTileRenderers;
        final List<Method> composeCardCandidates;
        final List<Method> composeImageRenderers;
        final Class<?> artworkModelBase;
        final Constructor<?> bitmapModelConstructor;

        Discovery(List<Class<?>> classes, List<Method> drawableRenderers, List<Method> composeStackRenderers,
                List<Method> composeTileRenderers,
                List<Method> composeCardCandidates, List<Method> composeImageRenderers,
                Class<?> artworkModelBase, Constructor<?> bitmapModelConstructor) {
            this.classes = classes;
            this.drawableRenderers = drawableRenderers;
            this.composeStackRenderers = composeStackRenderers;
            this.composeTileRenderers = composeTileRenderers;
            this.composeCardCandidates = composeCardCandidates;
            this.composeImageRenderers = composeImageRenderers;
            this.artworkModelBase = artworkModelBase;
            this.bitmapModelConstructor = bitmapModelConstructor;
        }
    }

    static Discovery discover(ClassLoader loader) {
        List<Class<?>> classes = loadClasses(loader);
        List<Method> imageRenderers = findComposeImageRenderers(classes);
        Class<?> modelBase = imageRenderers.size() == 1 ? imageRenderers.get(0).getParameterTypes()[0] : null;
        List<Method> cardCandidates = modelBase == null ? new ArrayList<>() : findComposeCardCandidates(classes, modelBase);
        List<Method> stackRenderers = imageRenderers.size() == 1
                ? findComposeStackRenderers(classes, imageRenderers.get(0).getParameterTypes()[2],
                        imageRenderers.get(0).getParameterTypes()[8])
                : new ArrayList<>();
        List<Method> tileRenderers = imageRenderers.size() == 1
                ? findComposeTileRenderers(classes, imageRenderers.get(0).getParameterTypes()[2],
                        imageRenderers.get(0).getParameterTypes()[8], modelBase)
                : new ArrayList<>();
        return new Discovery(classes, find(classes), stackRenderers, tileRenderers, cardCandidates,
                imageRenderers, modelBase, modelBase == null ? null : findBitmapModelConstructor(classes, modelBase));
    }

    static List<Method> findComposeCardRenderers(Discovery discovery, Class<?> cardStateType) {
        ArrayList<Method> result = new ArrayList<>();
        for (Method method : discovery.composeCardCandidates) {
            if (method.getParameterTypes()[1] == cardStateType) result.add(method);
        }
        return result;
    }

    static Method findCardArtLoaderMethod(Method renderer) {
        try {
            Class<?> owner = renderer.getDeclaringClass();
            Class<?> drawable = renderer.getParameterTypes()[0];
            Class<?> model = renderer.getParameterTypes()[2];
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getReturnType() == void.class && parameters.length == 4
                        && parameters[0] == View.class && parameters[1] == drawable
                        && parameters[2].isInterface() && parameters[3] == model) {
                    return method;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static List<Method> find(List<Class<?>> classes) {
        LinkedHashSet<Method> result = new LinkedHashSet<>();
        for (Class<?> candidate : classes) {
            try {
                if (!isWalletArtDrawable(candidate)) continue;
                for (Class<?> owner : classes) {
                    try {
                        result.addAll(findMethods(owner, candidate, null));
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<>(result);
    }

    private static List<Method> findComposeStackRenderers(List<Class<?>> classes, Class<?> modifierType, Class<?> composerType) {
        LinkedHashSet<Method> result = new LinkedHashSet<>();
        for (Class<?> owner : classes) {
            try {
                for (Method method : owner.getDeclaredMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class && p.length == 4
                            && isCardStackState(p[0]) && p[1] == modifierType && p[2] == composerType && p[3] == int.class) {
                        result.add(method);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<>(result);
    }

    private static List<Method> findComposeTileRenderers(List<Class<?>> classes, Class<?> modifierType,
            Class<?> composerType, Class<?> artworkModelBase) {
        LinkedHashSet<Method> result = new LinkedHashSet<>();
        if (artworkModelBase == null) return new ArrayList<>(result);
        for (Class<?> owner : classes) {
            try {
                for (Method method : owner.getDeclaredMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                            || p.length != 4 || p[1] != modifierType || p[2] != composerType || p[3] != int.class
                            || !isFavoriteTileStateBase(classes, p[0], artworkModelBase)) continue;
                    result.add(method);
                }
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<>(result);
    }

    private static boolean isFavoriteTileStateBase(List<Class<?>> classes, Class<?> type, Class<?> artworkModelBase) {
        if (type == null || !Modifier.isAbstract(type.getModifiers()) || type.isInterface()
                || !hasArtworkSubtype(classes, type, artworkModelBase)) return false;
        int abstractMethods = 0, intResults = 0, interfaceResults = 0, otherResults = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) || method.getParameterCount() != 0) continue;
            abstractMethods++;
            Class<?> result = method.getReturnType();
            if (result == int.class) intResults++;
            else if (result.isInterface()) interfaceResults++;
            else otherResults++;
        }
        return abstractMethods == 4 && intResults == 1 && interfaceResults >= 1 && otherResults >= 1;
    }

    private static boolean hasArtworkSubtype(List<Class<?>> classes, Class<?> baseType, Class<?> artworkModelBase) {
        if (baseType == null) return false;
        for (Class<?> candidate : classes) {
            try {
                if (candidate == baseType || !baseType.isAssignableFrom(candidate)
                        || Modifier.isAbstract(candidate.getModifiers())) continue;
                for (Field field : candidate.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (artworkModelBase.isAssignableFrom(field.getType())) return true;
                    for (Field nested : field.getType().getDeclaredFields()) {
                        if (!Modifier.isStatic(nested.getModifiers())
                                && artworkModelBase.isAssignableFrom(nested.getType())) return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean isCardStackState(Class<?> type) {
        try {
            int lists = 0, ints = 0, text = 0, callbacks = 0, instanceFields = 0;
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                instanceFields++;
                Class<?> fieldType = field.getType();
                if (List.class.isAssignableFrom(fieldType)) lists++;
                if (fieldType == int.class) ints++;
                if (fieldType.isInterface()) callbacks++;
                if (hasContextTextMethod(fieldType)) text++;
            }
            return instanceFields == 4 && lists == 1 && ints == 1 && (callbacks == 1 || text == 1);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasContextTextMethod(Class<?> type) {
        try {
            for (Method method : type.getMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (CharSequence.class.isAssignableFrom(method.getReturnType()) && p.length == 1
                        && Context.class.isAssignableFrom(p[0])) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static List<Method> findComposeImageRenderers(List<Class<?>> classes) {
        ArrayList<Method> result = new ArrayList<>();
        for (Class<?> owner : classes) {
            try {
                for (Method method : owner.getDeclaredMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class && p.length == 11
                            && p[0].getSuperclass() == Object.class && p[0].getDeclaredFields().length == 0
                            && p[1] == String.class && p[2].isInterface() && p[5] == float.class
                            && p[8].isInterface() && p[9] == int.class && p[10] == int.class
                            && hasBitmapArtworkFamily(classes, p[0])) result.add(method);
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static List<Method> findComposeCardCandidates(List<Class<?>> classes, Class<?> artworkModelBase) {
        ArrayList<Method> result = new ArrayList<>();
        for (Class<?> owner : classes) {
            try {
                for (Method method : owner.getDeclaredMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class || p.length != 5
                            || !p[0].isInterface() || !p[2].isInterface() || p[3] != int.class || p[4] != int.class
                            || artworkField(p[1], artworkModelBase) == null) continue;
                    result.add(method);
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static Field artworkField(Class<?> type, Class<?> artworkModelBase) {
        Field result = null;
        for (Field field : type.getDeclaredFields()) {
            if (!artworkModelBase.isAssignableFrom(field.getType())) continue;
            if (result != null) return null;
            result = field;
        }
        return result;
    }

    private static boolean hasBitmapArtworkFamily(List<Class<?>> classes, Class<?> base) {
        boolean bitmap = false, url = false;
        for (Class<?> candidate : classes) {
            try {
                if (candidate.getSuperclass() != base) continue;
                for (Field field : candidate.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType() == Bitmap.class) bitmap = true;
                    if (field.getType() == String.class) url = true;
                }
            } catch (Throwable ignored) {
            }
        }
        return bitmap && url;
    }

    private static Constructor<?> findBitmapModelConstructor(List<Class<?>> classes, Class<?> base) {
        ArrayList<Constructor<?>> result = new ArrayList<>();
        for (Class<?> candidate : classes) {
            try {
                if (candidate.getSuperclass() != base) continue;
                int bitmapFields = 0, instanceFields = 0;
                for (Field field : candidate.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    instanceFields++;
                    if (field.getType() == Bitmap.class) bitmapFields++;
                }
                if (bitmapFields != 1 || instanceFields != 1) continue;
                for (Constructor<?> constructor : candidate.getDeclaredConstructors()) {
                    if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0] == Bitmap.class) {
                        result.add(constructor);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return result.size() == 1 ? result.get(0) : null;
    }

    private static List<Method> findMethods(Class<?> owner, Class<?> drawable, Class<?> expectedModel) {
        ArrayList<Method> result = new ArrayList<>();
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() != void.class || parameters.length != 3
                    || parameters[0] != drawable || parameters[1] != ImageView.class) continue;
            Class<?> model = parameters[2];
            if ((expectedModel == null || model == expectedModel) && isCardArtModel(model)) result.add(method);
        }
        return result;
    }

    private static boolean isWalletArtDrawable(Class<?> type) {
        if (!Drawable.class.isAssignableFrom(type) || type == Drawable.class) return false;
        int textPaints = 0, strings = 0, drawables = 0, contexts = 0, booleans = 0, ints = 0;
        for (Field field : type.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            if (fieldType == TextPaint.class) textPaints++;
            if (fieldType == String.class) strings++;
            if (Drawable.class.isAssignableFrom(fieldType)) drawables++;
            if (fieldType == Context.class) contexts++;
            if (fieldType == boolean.class) booleans++;
            if (fieldType == int.class) ints++;
        }
        return textPaints >= 2 && strings >= 4 && drawables >= 1 && contexts >= 1
                && booleans >= 1 && ints >= 3 && hasIntMethod(type, "getIntrinsicWidth")
                && hasIntMethod(type, "getIntrinsicHeight");
    }

    private static boolean isCardArtModel(Class<?> type) {
        if (type.isPrimitive()) return false;
        int strings = 0, ints = 0;
        boolean uri = false;
        for (Field field : type.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            if (fieldType == String.class) strings++;
            if (fieldType == int.class) ints++;
            if ("android.net.Uri".equals(fieldType.getName())) uri = true;
        }
        return strings >= 4 && ints >= 2 && uri;
    }

    private static boolean hasIntMethod(Class<?> type, String name) {
        try {
            return type.getDeclaredMethod(name).getReturnType() == int.class;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ArrayList<Class<?>> loadClasses(ClassLoader loader) {
        ArrayList<Class<?>> result = new ArrayList<>();
        List<String> names = classNames(loader);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String name : names) if (name.startsWith("defpackage.")) ordered.add(name);
        ordered.addAll(names);
        for (String name : ordered) {
            if (name.startsWith("android.") || name.startsWith("java.") || name.startsWith("javax.")) continue;
            try {
                result.add(Class.forName(name, false, loader));
            } catch (Throwable ignored) {
                // One broken/deferred class must not disable the original app.
            }
        }
        return result;
    }

    private static List<String> classNames(ClassLoader loader) {
        ArrayList<String> result = new ArrayList<>();
        try {
            Object pathList = field(loader, "pathList");
            Object[] elements = (Object[]) field(pathList, "dexElements");
            for (Object element : elements) {
                DexFile dexFile = (DexFile) fieldOfType(element, DexFile.class);
                if (dexFile == null) continue;
                Enumeration<String> entries = dexFile.entries();
                while (entries.hasMoreElements()) result.add(entries.nextElement());
            }
        } catch (Throwable ignored) {
            // The version-specific fallback handles this case.
        }
        return result;
    }

    private static Object field(Object object, String name) throws Exception {
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object fieldOfType(Object object, Class<?> expected) throws Exception {
        Class<?> type = object.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (expected.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field.get(object);
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
