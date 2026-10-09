package dev.naominet.lazer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test-only SAF-like provider exposing seekable files and non-seekable pipe descriptors.
 *
 * <p>Android instantiates this provider in the test package's own process, whose classpath is the
 * androidTest APK alone, so it is written in Java and must not touch kotlin-stdlib.
 */
public final class AndroidDsdFixtureContentProvider extends ContentProvider {
    private static final String AUTHORITY = "dev.naominet.lazer.androidtest.dsd";

    private static final Set<String> FIXTURE_NAMES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("dsd64_test.dsf", "dff64_test.dff", "dst64_verbatim.dff", "malformed_test.dsf")));

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        String name = requestedFixtureName(uri);
        List<String> columns = projection == null
                ? Arrays.asList(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
                : Arrays.asList(projection);
        Object[] row = new Object[columns.size()];
        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index);
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row[index] = name;
            } else if (OpenableColumns.SIZE.equals(column)) {
                row[index] = -1L;
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns.toArray(new String[0]));
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = requestedFixtureName(uri);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? name : name.substring(dot + 1);
        if ("dsf".equals(extension)) return "audio/x-dsf";
        if ("dff".equals(extension)) return "audio/x-dff";
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("DSD test fixtures are read-only.");
        String name = fixtureName(uri);
        List<String> segments = uri.getPathSegments();
        String accessMode = segments.isEmpty() ? null : segments.get(0);
        if ("seekable".equals(accessMode)) return openSeekableFixture(name);
        if ("pipe".equals(accessMode)) {
            try {
                return openPipeFixture(name);
            } catch (IOException failure) {
                throw new FileNotFoundException(failure.getMessage());
            }
        }
        throw new FileNotFoundException("Unknown DSD fixture access mode.");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("The DSD fixture provider is read-only.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("The DSD fixture provider is read-only.");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("The DSD fixture provider is read-only.");
    }

    private String fixtureName(Uri uri) throws FileNotFoundException {
        String name = requestedFixtureName(uri);
        if (!FIXTURE_NAMES.contains(name)) throw new FileNotFoundException("Unknown DSD fixture.");
        return name;
    }

    private String requestedFixtureName(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (!AUTHORITY.equals(uri.getAuthority()) || segments.size() != 2) {
            throw new IllegalArgumentException("Invalid DSD fixture URI.");
        }
        return segments.get(1);
    }

    private ParcelFileDescriptor openSeekableFixture(String name) throws FileNotFoundException {
        Context appContext = getContext();
        if (appContext == null) throw new FileNotFoundException("Provider is not attached.");
        File file = new File(appContext.getCacheDir(), "dsd-content-fixture-" + name);
        try {
            copyAsset(appContext, name, file);
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException failure) {
            file.delete();
            throw new FileNotFoundException(failure.getMessage());
        }
    }

    private ParcelFileDescriptor openPipeFixture(String name) throws IOException {
        Context appContext = getContext();
        if (appContext == null) throw new FileNotFoundException("Provider is not attached.");
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        final ParcelFileDescriptor readEnd = pipe[0];
        final ParcelFileDescriptor writeEnd = pipe[1];
        Thread writer = new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                    new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                copyAsset(appContext, name, output);
            } catch (IOException failure) {
                closeDescriptor(writeEnd);
            }
        }, "lazer-dsd-test-provider");
        writer.setDaemon(true);
        writer.start();
        return readEnd;
    }

    private static void copyAsset(Context appContext, String name, File destination) throws IOException {
        try (OutputStream output = new FileOutputStream(destination)) {
            copyAsset(appContext, name, output);
        }
    }

    private static void copyAsset(Context appContext, String name, OutputStream output) throws IOException {
        try (InputStream input = appContext.getAssets().open(name)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.flush();
        }
    }

    private static void closeDescriptor(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }
}
