package com.tqmane.wallart.storage;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class ImageStorage {
    private static final long MAX_BYTES = 32L * 1024L * 1024L;

    private ImageStorage() {
    }

    public static String saveCropped(Context context, Bitmap bitmap, String id) throws IOException {
        if (!CardStore.isValidId(id) || bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) throw new IOException("Invalid cropped image");
        File dir = CardStore.artDir(context);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create image directory");
        File temp = new File(dir, id + ".crop.tmp");
        boolean moved = false;
        try (FileOutputStream output = new FileOutputStream(temp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Cannot encode cropped image");
            output.flush();
            output.getFD().sync();
        } catch (Throwable error) {
            temp.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Cannot store cropped image", error);
        }
        if (temp.length() <= 0 || temp.length() > MAX_BYTES) {
            temp.delete();
            throw new IOException("Cropped image is too large");
        }
        File target = new File(dir, id + ".png");
        try {
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } catch (IOException error) {
            throw new IOException("Cannot store cropped image", error);
        } finally {
            if (!moved) temp.delete();
        }
        new File(dir, id + ".jpg").delete();
        new File(dir, id + ".webp").delete();
        return "png";
    }
}
