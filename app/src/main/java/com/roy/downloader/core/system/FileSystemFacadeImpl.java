package com.roy.downloader.core.system;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.roy.downloader.core.exception.FileAlreadyExistsException;
import com.roy.downloader.core.utils.MimeTypeUtils;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

class FileSystemFacadeImpl implements FileSystemFacade {
    @SuppressWarnings("unused")
    private static final String TAG = FileSystemFacadeImpl.class.getSimpleName();

    private static final String EXTENSION_SEPARATOR = ".";
    /* The file copy buffer size (30 MB) */
    private static final long FILE_COPY_BUFFER_SIZE = 1024 * 1024 * 30;
    private static final int COPY_BUF_SIZE = 8024;

    private final SysCall sysCall;
    private final FsModuleResolver fsResolver;
    private final Context appContext;

    public FileSystemFacadeImpl(
            @NonNull SysCall sysCall,
            @NonNull FsModuleResolver fsResolver,
            @NonNull Context appContext
    ) {
        this.sysCall = sysCall;
        this.fsResolver = fsResolver;
        this.appContext = appContext;
    }

    /*
     * See http://man7.org/linux/man-pages/man2/lseek.2.html
     */

    @Override
    public void seek(@NonNull FileOutputStream fout, long offset) throws IOException {
        try {
            sysCall.lseek(fout.getFD(), offset);

        } catch (UnsupportedOperationException e) {
            fout.getChannel().position(offset);
        }
    }

    /*
     * See http://man7.org/linux/man-pages/man3/posix_fallocate.3.html
     */

    @Override
    public void allocate(@NonNull FileDescriptor fd, long length) throws IOException {
        sysCall.fallocate(fd, length);
    }

    @Override
    public void closeQuietly(Closeable closeable) {
        try {
            if (closeable != null)
                closeable.close();

        } catch (final IOException e) {
            /* Ignore */
        }
    }

    /*
     * If file with required name exists returns new filename in the following format:
     *
     *     base_name (count_number).extension
     *
     * otherwise returns original filename
     */

    public String makeFilename(@NonNull Uri dir,
                               @NonNull String desiredFileName) {
        while (true) {
            /* File doesn't exists, return */
            Uri filePath = getFileUri(dir, desiredFileName);
            if (filePath == null)
                return desiredFileName;

            FsModule fsModule = fsResolver.resolveFsByUri(filePath);
            String fileName = fsModule.getName(filePath);
            if (fileName == null)
                fileName = desiredFileName;

            int openBracketPos = fileName.lastIndexOf("(");
            int closeBracketPos = fileName.lastIndexOf(")");

            /* Try to parse the counter number and increment it for a new filename */
            int countNumber;
            if (openBracketPos > 0 && closeBracketPos > 0) {
                try {
                    countNumber = Integer.parseInt(fileName.substring(openBracketPos + 1, closeBracketPos));

                    desiredFileName = fileName.substring(0, openBracketPos + 1) +
                            ++countNumber + fileName.substring(closeBracketPos);
                    continue;

                } catch (NumberFormatException e) {
                    /* Ignore */
                }
            }

            /* Otherwise create a name with the initial value of the counter */
            countNumber = 1;
            int extensionPos = fileName.lastIndexOf(EXTENSION_SEPARATOR);
            String baseName = (extensionPos < 0 ? fileName : fileName.substring(0, extensionPos));

            StringBuilder sb = new StringBuilder(baseName + " (" + countNumber + ")");
            if (extensionPos > 0)
                sb.append(EXTENSION_SEPARATOR)
                        .append(getExtension(fileName));

            desiredFileName = sb.toString();
        }
    }

    /*
     * Returns Uri and name of moved file.
     */

    @Override
    public void moveFile(@NonNull Uri srcDir,
                         @NonNull String srcFileName,
                         @NonNull Uri destDir,
                         @NonNull String destFileName,
                         boolean replace) throws IOException, FileAlreadyExistsException {
        FsModule fsModule;
        Uri srcFileUri, destFileUri;

        fsModule = fsResolver.resolveFsByUri(srcDir);
        srcFileUri = fsModule.getFileUri(srcDir, srcFileName, false);
        if (srcFileUri == null)
            throw new FileNotFoundException("Source '" + srcFileName + "' from " + srcDir + " does not exists");

        fsModule = fsResolver.resolveFsByUri(destDir);
        destFileUri = fsModule.getFileUri(destDir, destFileName, replace);
        if (!replace && destFileUri != null)
            throw new FileAlreadyExistsException("Destination '" + destFileUri + "' already exists");

        destFileUri = createFile(destDir, destFileName, false);
        if (destFileUri == null)
            throw new IOException("Cannot create destination file '" + destFileName + "'");

        copyFile(srcFileUri, destFileUri, replace);
        deleteFile(srcFileUri);
    }

    /*
     * This caches the original file length, and throws an IOException
     * if the output file length is different from the current input file length.
     * So it may fail if the file changes size.
     * It may also fail with "IllegalArgumentException: Negative size" if the input file is truncated part way
     * through copying the data and the new file size is less than the current position.
     */

    public void copyFile(@NonNull Uri srcFile,
                         @NonNull Uri destFile,
                         boolean truncateDestFile) throws IOException {

        if (srcFile.equals(destFile))
            throw new IllegalArgumentException("Uri points to the same file");

        try (FileDescriptorWrapper wSrc = getFD(srcFile);
             FileDescriptorWrapper wDest = getFD(destFile)) {

            assert wSrc != null;
            try (FileInputStream fin = new FileInputStream(wSrc.open("r"))) {
                assert wDest != null;
                try (FileOutputStream fout = new FileOutputStream(wDest.open((truncateDestFile ? "rwt" : "rw")));
                     FileChannel input = fin.getChannel();
                     FileChannel output = fout.getChannel()) {
                    long size = input.size();
                    long pos = 0;
                    long count;
                    while (pos < size) {
                        long remain = size - pos;
                        count = Math.min(remain, FILE_COPY_BUFFER_SIZE);
                        long bytesCopied = output.transferFrom(input, pos, count);
                        if (bytesCopied == 0)
                            break;
                        pos += bytesCopied;
                    }

                    long srcLen = input.size();
                    long dstLen = output.size();
                    if (srcLen != dstLen)
                        throw new IOException("Failed to copy full contents from '" +
                                srcFile + "' to '" + destFile + "' Expected length: " + srcLen + " Actual: " + dstLen);
                }
            }
        }
    }

    @Override
    public FileDescriptorWrapper getFD(@NonNull Uri path) {
        FsModule fsModule = fsResolver.resolveFsByUri(path);

        return fsModule.openFD(path);
    }

    @Override
    public String getExtensionSeparator() {
        return EXTENSION_SEPARATOR;
    }

    @Override
    public String appendExtension(@NonNull String fileName, @NonNull String mimeType) {
        MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
        String extension = getExtension(fileName);

        if (TextUtils.isEmpty(extension)) {
            extension = mimeTypeMap.getExtensionFromMimeType(mimeType);
        } else {
            String m = mimeTypeMap.getMimeTypeFromExtension(extension);
            if (m == null || !m.equals(mimeType))
                extension = mimeTypeMap.getExtensionFromMimeType(mimeType);
        }

        if (extension != null && !fileName.endsWith(extension))
            fileName += getExtensionSeparator() + extension;

        return fileName;
    }

    /*
     * Return path to the standard Download directory.
     * If the directory doesn't exist, the function creates it automatically.
     */

    @Override
    @Nullable
    public String getDefaultDownloadPath() {
        String path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .getAbsolutePath();

        File dir = new File(path);
        if (dir.exists() && dir.isDirectory())
            return path;
        else
            return dir.mkdirs() ? path : null;
    }

    /*
     * Return the primary shared/external storage directory.
     */

    @Override
    @Nullable
    public String getUserDirPath() {
        String path = Environment.getExternalStorageDirectory().getAbsolutePath();

        File dir = new File(path);
        if (dir.exists() && dir.isDirectory())
            return path;
        else
            return dir.mkdirs() ? path : null;
    }

    @Override
    public boolean deleteFile(@NonNull Uri path) throws FileNotFoundException {
        FsModule fsModule = fsResolver.resolveFsByUri(path);

        return fsModule.delete(path);
    }

    /*
     * Returns a file (if exists) Uri by name from the pointed directory
     */

    @Override
    public Uri getFileUri(@NonNull Uri dir,
                          @NonNull String fileName) {
        FsModule fsModule = fsResolver.resolveFsByUri(dir);

        Uri path = null;
        try {
            path = fsModule.getFileUri(dir, fileName, false);

        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }

        return path;
    }

    /*
     * Returns a file (if exists) Uri by relative path (e.g foo/bar.txt)
     * from the pointed directory
     */

    @Override
    public Uri getFileUri(@NonNull String relativePath, @NonNull Uri dir) {
        var fsModule = fsResolver.resolveFsByUri(dir);
        Uri path = null;
        try {
            path = fsModule.getFileUri(relativePath, dir, false);

        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }

        return path;
    }

    /*
     * Returns Uri of created file.
     * Note: if replace == false, doesn't replace file if it exists and returns its Uri.
     */

    @Override
    public Uri createFile(@NonNull Uri dir,
                          @NonNull String fileName,
                          boolean replace) throws IOException {
        FsModule fsModule = fsResolver.resolveFsByUri(dir);
        try {
            Uri path = fsModule.getFileUri(dir, fileName, false);
            if (path != null) {
                if (!replace)
                    return path;
                else if (!fsModule.delete(path))
                    return null;
            }

            return fsModule.getFileUri(dir, fileName, true);

        } catch (SecurityException e) {
            throw new IOException(e);
        }
    }

    /*
     * Returns Uri of created file.
     * Note: if replace == false, doesn't replace file if it exists and returns its Uri.
     */

    @Override
    public Uri createFile(
            @NonNull String relativePath,
            @NonNull Uri dir,
            boolean replace
    ) throws IOException {
        var fsModule = fsResolver.resolveFsByUri(dir);
        try {
            var path = fsModule.getFileUri(relativePath, dir, false);
            if (path != null) {
                if (!replace) {
                    return path;
                } else if (!fsModule.delete(path)) {
                    return null;
                }
            }

            return fsModule.getFileUri(relativePath, dir, true);

        } catch (SecurityException e) {
            throw new IOException(e);
        }
    }

    /*
     * Return the number of bytes that are free on the file system
     * backing the given Uri
     */

    @Override
    public long getDirAvailableBytes(@NonNull Uri dir) {
        long availableBytes = -1;

        FsModule fsModule = fsResolver.resolveFsByUri(dir);
        try {
            availableBytes = fsModule.getDirAvailableBytes(dir);

        } catch (IllegalArgumentException | IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            return availableBytes;
        }

        return availableBytes;
    }

    @Override
    public String getExtension(String fileName) {
        if (fileName == null)
            return null;

        int extensionPos = fileName.lastIndexOf(EXTENSION_SEPARATOR);
        int lastSeparator = fileName.lastIndexOf(File.separator);
        int index = (lastSeparator > extensionPos ? -1 : extensionPos);

        if (index == -1)
            return "";
        else
            return fileName.substring(index + 1);
    }

    public String getNameWithoutExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName;
        String extension;
        do {
            extension = getExtension(name);
            var mimeType = MimeTypeUtils.getMimeTypeFromExtension(extension);
            if (mimeType == null) {
                break;
            } else {
                assert extension != null;
                var extensionPos = name.lastIndexOf(extension) - 1;
                if (extensionPos < 0 || extensionPos + 1 > name.length()) {
                    break;
                } else {
                    name = name.substring(0, extensionPos);
                }
            }
        } while (!TextUtils.isEmpty(extension));

        return name;
    }

    /*
     * Check if given filename is valid for a FAT filesystem
     */

    @Override
    public boolean isValidFatFilename(String name) {
        return name != null && name.equals(buildValidFatFilename(name));
    }

    /*
     * Mutate the given filename to make it valid for a FAT filesystem,
     * replacing any invalid characters with "_"
     */

    @Override
    public String buildValidFatFilename(String name) {
        if (TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name))
            return "(invalid)";

        final StringBuilder res = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (isValidFatFilenameChar(c))
                res.append(c);
            else
                res.append('_');
        }
        /*
         * Even though vfat allows 255 UCS-2 chars, we might eventually write to
         * ext4 through a FUSE layer, so use that limit
         */
        trimFilename(res, 255);

        return res.toString();
    }

    private boolean isValidFatFilenameChar(char c) {
        if (c <= 0x1f)
            return false;
        return switch (c) {
            case '"', '*', '/', ':', '<', '>', '?', '\\', '|', 0x7F -> false;
            default -> true;
        };
    }

    private void trimFilename(StringBuilder res, int maxBytes) {
        byte[] raw = res.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > maxBytes) {
            maxBytes -= 3;
            while (raw.length > maxBytes) {
                res.deleteCharAt(res.length() / 2);
                raw = res.toString().getBytes(StandardCharsets.UTF_8);
            }
            res.insert(res.length() / 2, "...");
        }
    }

    /*
     * Returns path if the directory belongs to the filesystem,
     * otherwise returns SAF name
     */

    @Override
    public String getDirName(@NonNull Uri dir) {
        FsModule fsModule = fsResolver.resolveFsByUri(dir);

        return fsModule.getDirName(dir);
    }

    @Override
    public long getFileSize(@NonNull Uri filePath) {
        FsModule fsModule = fsResolver.resolveFsByUri(filePath);

        return fsModule.getFileSize(filePath);
    }

    @Override
    public void truncate(@NonNull Uri filePath, long newSize) throws IOException {
        try (FileDescriptorWrapper w = getFD(filePath)) {
            assert w != null;
            FileDescriptor fd = w.open("rw");
            try (FileChannel chan = new FileOutputStream(fd).getChannel()) {
                chan.truncate(newSize);
            }
        }
    }

    @Override
    public void takePermissions(@NonNull Uri path) {
        FsModule fsModule = fsResolver.resolveFsByUri(path);

        fsModule.takePermissions(path);
    }

    @Override
    public String getDirPath(@NonNull Uri dir) {
        FsModule fsModule = fsResolver.resolveFsByUri(dir);

        return fsModule.getDirPath(dir);
    }

    @Override
    public boolean exists(@NonNull Uri filePath) {
        FsModule fsModule = fsResolver.resolveFsByUri(filePath);

        return fsModule.exists(filePath);
    }

    @Override
    public boolean mkdirs(@NonNull Uri dir, @NonNull String relativePath) {
        var fsModule = fsResolver.resolveFsByUri(dir);

        return fsModule.mkdirs(dir, relativePath);
    }

    @Override
    public File createTmpFile(String suffix) throws IOException {
        return File.createTempFile(UUID.randomUUID().toString(), suffix, appContext.getCacheDir());
    }

    /*
     * Copies the content of a InputStream into an OutputStream.
     * Uses a default buffer size of 8024 bytes.
     */
    @Override
    public long copy(final InputStream input, final OutputStream output) throws IOException {
        return copy(input, output, COPY_BUF_SIZE);
    }

    /*
     * Copies the content of a InputStream into an OutputStream
     */
    @Override
    public long copy(final InputStream input, final OutputStream output, final int buffersize) throws IOException {
        if (buffersize < 1) {
            throw new IllegalArgumentException("Buffer size must be bigger than 0");
        }
        var buffer = new byte[buffersize];
        int n;
        long count = 0;
        while ((n = input.read(buffer)) != -1) {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }
}
