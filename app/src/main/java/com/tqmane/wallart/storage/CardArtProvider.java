package com.tqmane.wallart.storage;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;

public final class CardArtProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        requireCaller();
        if (CardStore.METHOD_RECORD.equals(method)) {
            CardStore.record(getContext(), value(extras, CardStore.KEY_ID), value(extras, CardStore.KEY_LABEL),
                    value(extras, CardStore.KEY_NETWORK), value(extras, CardStore.KEY_LAST_FOUR),
                    extras == null ? null : extras.getStringArray(CardStore.KEY_FINGERPRINTS));
            return Bundle.EMPTY;
        }
        if (CardStore.METHOD_RESOLVE_IDENTITY.equals(method)) {
            Bundle result = new Bundle();
            String id = CardStore.resolveIdentity(getContext(), value(extras, CardStore.KEY_ID),
                    extras == null ? null : extras.getStringArray(CardStore.KEY_FINGERPRINTS));
            if (id != null) result.putString(CardStore.KEY_RESOLVED_ID, id);
            return result;
        }
        if (CardStore.METHOD_RECORD_ORIGINAL.equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean(CardStore.KEY_STORED, CardStore.recordOriginalPreview(
                    getContext(), value(extras, CardStore.KEY_ID), extras == null ? null : extras.getByteArray(CardStore.KEY_PREVIEW)));
            return result;
        }
        if (CardStore.METHOD_RESOLVE.equals(method)) {
            CardStore.Artifact artifact = CardStore.artifact(getContext(), value(extras, CardStore.KEY_ID));
            Bundle result = new Bundle();
            if (artifact != null) {
                result.putString(CardStore.KEY_URI, artifact.uri.toString());
                result.putString(CardStore.KEY_FIT, artifact.fit);
                result.putLong(CardStore.KEY_REVISION, artifact.revision);
                result.putString(CardStore.KEY_FILE_NAME, artifact.file.getName());
            }
            String id = value(extras, CardStore.KEY_ID);
            if (CardStore.originalArtFile(getContext(), id).isFile()) {
                result.putString(CardStore.KEY_ORIGINAL_URI,
                        "content://" + CardStore.AUTHORITY + "/original/" + id);
            }
            return result;
        }
        throw new IllegalArgumentException("Unknown method");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireCaller();
        if (mode != null && (mode.contains("w") || mode.contains("a"))) throw new FileNotFoundException("Read-only provider");
        if (uri != null && !uri.getPathSegments().isEmpty() && "original".equals(uri.getPathSegments().get(0))) {
            File file = CardStore.originalArtFile(getContext(), uri.getLastPathSegment());
            if (!file.isFile()) throw new FileNotFoundException("Original artwork not captured");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        CardStore.Artifact artifact = CardStore.artifact(getContext(), uri == null ? null : uri.getLastPathSegment());
        if (artifact == null) throw new FileNotFoundException("Artwork not configured");
        return ParcelFileDescriptor.open(artifact.file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        requireCaller();
        if (uri != null && !uri.getPathSegments().isEmpty() && "original".equals(uri.getPathSegments().get(0))) {
            return CardStore.originalArtFile(getContext(), uri.getLastPathSegment()).isFile() ? "image/jpeg" : null;
        }
        CardStore.Artifact artifact = CardStore.artifact(getContext(), uri == null ? null : uri.getLastPathSegment());
        if (artifact == null) return null;
        String name = artifact.file.getName();
        return CardStore.mime(name.substring(name.lastIndexOf('.') + 1));
    }

    private static String value(Bundle extras, String key) {
        return extras == null ? null : extras.getString(key);
    }

    private void requireCaller() {
        if (Binder.getCallingUid() == Process.myUid()) return;
        String caller = getCallingPackage();
        if (!CardStore.WALLET_PACKAGE.equals(caller) && !CardStore.GOOGLE_PAY_PACKAGE.equals(caller)) {
            throw new SecurityException("WallArt provider is private to its app, Wallet, and the approved Pay UI");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
