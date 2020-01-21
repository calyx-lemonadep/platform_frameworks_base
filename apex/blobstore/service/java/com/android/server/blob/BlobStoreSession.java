/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.blob;

import static android.app.blob.BlobStoreManager.COMMIT_RESULT_ERROR;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDWR;
import static android.system.OsConstants.SEEK_SET;

import static com.android.server.blob.BlobStoreConfig.TAG;

import android.annotation.BytesLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.blob.BlobHandle;
import android.app.blob.IBlobCommitCallback;
import android.app.blob.IBlobStoreSession;
import android.content.Context;
import android.os.Binder;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.RevocableFileDescriptor;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.blob.BlobStoreManagerService.SessionStateChangeListener;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;

/** TODO: add doc */
public class BlobStoreSession extends IBlobStoreSession.Stub {

    static final int STATE_OPENED = 1;
    static final int STATE_CLOSED = 0;
    static final int STATE_ABANDONED = 2;
    static final int STATE_COMMITTED = 3;
    static final int STATE_VERIFIED_VALID = 4;
    static final int STATE_VERIFIED_INVALID = 5;

    private final Object mSessionLock = new Object();

    private final Context mContext;
    private final SessionStateChangeListener mListener;

    public final BlobHandle blobHandle;
    public final long sessionId;
    public final int ownerUid;
    public final String ownerPackageName;

    // Do not access this directly, instead use getSessionFile().
    private File mSessionFile;

    @GuardedBy("mRevocableFds")
    private ArrayList<RevocableFileDescriptor> mRevocableFds = new ArrayList<>();

    @GuardedBy("mSessionLock")
    private int mState = STATE_CLOSED;

    @GuardedBy("mSessionLock")
    private final BlobAccessMode mBlobAccessMode = new BlobAccessMode();

    @GuardedBy("mSessionLock")
    private IBlobCommitCallback mBlobCommitCallback;

    BlobStoreSession(Context context, long sessionId, BlobHandle blobHandle,
            int ownerUid, String ownerPackageName, SessionStateChangeListener listener) {
        this.mContext = context;
        this.blobHandle = blobHandle;
        this.sessionId = sessionId;
        this.ownerUid = ownerUid;
        this.ownerPackageName = ownerPackageName;
        this.mListener = listener;
    }

    boolean hasAccess(int callingUid, String callingPackageName) {
        return ownerUid == callingUid && ownerPackageName.equals(callingPackageName);
    }

    void open() {
        synchronized (mSessionLock) {
            if (isFinalized()) {
                throw new IllegalStateException("Not allowed to open session with state: "
                        + stateToString(mState));
            }
            mState = STATE_OPENED;
        }
    }

    int getState() {
        synchronized (mSessionLock) {
            return mState;
        }
    }

    void sendCommitCallbackResult(int result) {
        synchronized (mSessionLock) {
            try {
                mBlobCommitCallback.onResult(result);
            } catch (RemoteException e) {
                Slog.d(TAG, "Error sending the callback result", e);
            }
            mBlobCommitCallback = null;
        }
    }

    BlobAccessMode getBlobAccessMode() {
        synchronized (mSessionLock) {
            return mBlobAccessMode;
        }
    }

    boolean isFinalized() {
        synchronized (mSessionLock) {
            return mState == STATE_COMMITTED || mState == STATE_ABANDONED;
        }
    }

    @Override
    @NonNull
    public ParcelFileDescriptor openWrite(@BytesLong long offsetBytes,
            @BytesLong long lengthBytes) {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to write in state: "
                        + stateToString(mState));
            }

            try {
                return openWriteLocked(offsetBytes, lengthBytes);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
    }

    @GuardedBy("mSessionLock")
    @NonNull
    private ParcelFileDescriptor openWriteLocked(@BytesLong long offsetBytes,
            @BytesLong long lengthBytes) throws IOException {
        // TODO: Add limit on active open sessions/writes/reads
        FileDescriptor fd = null;
        try {
            final File sessionFile = getSessionFile();
            if (sessionFile == null) {
                throw new IllegalStateException("Couldn't get the file for this session");
            }
            fd = Os.open(sessionFile.getPath(), O_CREAT | O_RDWR, 0600);
            if (offsetBytes > 0) {
                final long curOffset = Os.lseek(fd, offsetBytes, SEEK_SET);
                if (curOffset != offsetBytes) {
                    throw new IllegalStateException("Failed to seek " + offsetBytes
                            + "; curOffset=" + offsetBytes);
                }
            }
            if (lengthBytes > 0) {
                mContext.getSystemService(StorageManager.class).allocateBytes(fd, lengthBytes);
            }
        } catch (ErrnoException e) {
            e.rethrowAsIOException();
        }
        return createRevocableFdLocked(fd);
    }

    @Override
    @BytesLong
    public long getSize() {
        return 0;
    }

    @Override
    public void allowPackageAccess(@NonNull String packageName,
            @NonNull byte[] certificate) {
        assertCallerIsOwner();
        Preconditions.checkNotNull(packageName, "packageName must not be null");
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to change access type in state: "
                        + stateToString(mState));
            }
            mBlobAccessMode.allowPackageAccess(packageName, certificate);
        }
    }

    @Override
    public void allowSameSignatureAccess() {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to change access type in state: "
                        + stateToString(mState));
            }
            mBlobAccessMode.allowSameSignatureAccess();
        }
    }

    @Override
    public void allowPublicAccess() {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to change access type in state: "
                        + stateToString(mState));
            }
            mBlobAccessMode.allowPublicAccess();
        }
    }

    @Override
    public boolean isPackageAccessAllowed(@NonNull String packageName,
            @NonNull byte[] certificate) {
        assertCallerIsOwner();
        Preconditions.checkNotNull(packageName, "packageName must not be null");
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to get access type in state: "
                        + stateToString(mState));
            }
            return mBlobAccessMode.isPackageAccessAllowed(packageName, certificate);
        }
    }

    @Override
    public boolean isSameSignatureAccessAllowed() {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to get access type in state: "
                        + stateToString(mState));
            }
            return mBlobAccessMode.isSameSignatureAccessAllowed();
        }
    }

    @Override
    public boolean isPublicAccessAllowed() {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to get access type in state: "
                        + stateToString(mState));
            }
            return mBlobAccessMode.isPublicAccessAllowed();
        }
    }

    @Override
    public void close() {
        closeSession(STATE_CLOSED);
    }

    @Override
    public void abandon() {
        closeSession(STATE_ABANDONED);
    }

    @Override
    public void commit(IBlobCommitCallback callback) {
        synchronized (mSessionLock) {
            mBlobCommitCallback = callback;

            closeSession(STATE_COMMITTED);
        }
    }

    private void closeSession(int state) {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                if (state == STATE_CLOSED) {
                    // Just trying to close the session which is already deleted or abandoned,
                    // ignore.
                    return;
                } else {
                    throw new IllegalStateException("Not allowed to delete or abandon a session"
                            + " with state: " + stateToString(mState));
                }
            }

            mState = state;
            revokeAllFdsLocked();

            mListener.onStateChanged(this);
        }
    }

    void verifyBlobData() {
        byte[] actualDigest = null;
        try {
            actualDigest = FileUtils.digest(getSessionFile(), blobHandle.algorithm);
        } catch (IOException | NoSuchAlgorithmException e) {
            Slog.e(TAG, "Error computing the digest", e);
        }
        synchronized (mSessionLock) {
            if (actualDigest != null && Arrays.equals(actualDigest, blobHandle.digest)) {
                mState = STATE_VERIFIED_VALID;
                // Commit callback will be sent once the data is persisted.
            } else {
                mState = STATE_VERIFIED_INVALID;
                sendCommitCallbackResult(COMMIT_RESULT_ERROR);
            }
            mListener.onStateChanged(this);
        }
    }

    @GuardedBy("mSessionLock")
    private void revokeAllFdsLocked() {
        for (int i = mRevocableFds.size() - 1; i >= 0; --i) {
            mRevocableFds.get(i).revoke();
            mRevocableFds.remove(i);
        }
    }

    @GuardedBy("mSessionLock")
    @NonNull
    private ParcelFileDescriptor createRevocableFdLocked(FileDescriptor fd)
            throws IOException {
        final RevocableFileDescriptor revocableFd =
                new RevocableFileDescriptor(mContext, fd);
        synchronized (mRevocableFds) {
            mRevocableFds.add(revocableFd);
        }
        revocableFd.addOnCloseListener((e) -> {
            synchronized (mRevocableFds) {
                mRevocableFds.remove(revocableFd);
            }
        });
        return revocableFd.getRevocableFileDescriptor();
    }

    @Nullable
    File getSessionFile() {
        if (mSessionFile == null) {
            mSessionFile = BlobStoreConfig.prepareBlobFile(sessionId);
        }
        return mSessionFile;
    }

    @NonNull
    static String stateToString(int state) {
        switch (state) {
            case STATE_OPENED:
                return "<opened>";
            case STATE_CLOSED:
                return "<closed>";
            case STATE_ABANDONED:
                return "<abandoned>";
            case STATE_COMMITTED:
                return "<committed>";
            default:
                Slog.wtf(TAG, "Unknown state: " + state);
                return "<unknown>";
        }
    }

    private void assertCallerIsOwner() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != ownerUid) {
            throw new SecurityException(ownerUid + " is not the session owner");
        }
    }
}