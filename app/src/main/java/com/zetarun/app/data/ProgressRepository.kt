package com.zetarun.app.data

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import com.zetarun.app.BuildConfig
import com.zetarun.app.domain.SessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Local-first results with explicit account isolation and optional Firebase synchronization. */
class ProgressRepository(context: Context) {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("training-history-v1", Context.MODE_PRIVATE)
    private val ledger = LocalHistoryLedger(object : HistoryStorage {
        override fun get(key: String): String? = preferences.getString(key, null)
        override fun commit(updates: Map<String, String>): Boolean {
            val previous = updates.keys.associateWith { key -> preferences.getString(key, null) }
            val editor = preferences.edit()
            updates.forEach { (key, value) -> editor.putString(key, value) }
            if (editor.commit()) return true
            // SharedPreferences mutates its in-memory map before reporting a disk-write failure.
            // Restore that map too, so a failed migration/deletion cannot appear to have succeeded.
            val rollback = preferences.edit()
            previous.forEach { (key, value) ->
                if (value == null) rollback.remove(key) else rollback.putString(key, value)
            }
            rollback.commit()
            return false
        }
    })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localMutex = Mutex()
    private val syncMutex = Mutex()
    private val stateLock = Any()
    private val firebase = if (BuildConfig.FIREBASE_CONFIGURED) {
        runCatching {
            FirebaseApp.initializeApp(application)
            FirebaseAuth.getInstance()
        }.getOrNull()
    } else null
    private val firestore by lazy {
        FirebaseFirestore.getInstance().apply {
            // The app owns its account-separated offline cache. Do not retain a second cloud cache.
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        }
    }
    private val mutableHistory = MutableStateFlow<List<SessionRecord>>(emptyList())
    val history: StateFlow<List<SessionRecord>> = mutableHistory.asStateFlow()
    private val mutableAuth = MutableStateFlow(AuthStatus(configured = firebase != null))
    val auth: StateFlow<AuthStatus> = mutableAuth.asStateFlow()
    private val listener = FirebaseAuth.AuthStateListener {
        scope.launch {
            runSafely("Saved history could not be opened; its original data has been retained.") {
                localMutex.withLock { selectOwner(firebase?.currentUser?.uid) }
                if (firebase?.currentUser != null) sync()
            }
        }
    }

    init {
        if (firebase == null) {
            scope.launch {
                runSafely("Saved history could not be opened; its original data has been retained.") {
                    localMutex.withLock { selectOwner(null) }
                }
            }
        } else firebase.addAuthStateListener(listener)
    }

    suspend fun signIn(activity: Activity) {
        if (firebase == null || BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            mutableAuth.update {
                it.copy(error = "Google sign-in needs your Firebase configuration. Follow docs/FIREBASE.md. Your progress is saved on this device meanwhile.")
            }
            return
        }
        mutableAuth.update { it.copy(error = null, syncing = true) }
        try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build())
                .build()
            val response = CredentialManager.create(application).getCredential(activity, request)
            val credential = response.credential
            check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                "Google did not return a supported sign-in credential."
            }
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            firebase.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            withContext(Dispatchers.IO) {
                localMutex.withLock { selectOwner(firebase.currentUser?.uid) }
                sync()
            }
        } catch (_: NoCredentialException) {
            mutableAuth.update {
                it.copy(syncing = false, error = "No Google account is available for sign-in. Add a Google account in Android Settings, then try again. Local progress is safe.")
            }
        } catch (_: GetCredentialCancellationException) {
            mutableAuth.update { it.copy(syncing = false, error = null) }
        } catch (cancelled: CancellationException) {
            mutableAuth.update { it.copy(syncing = false) }
            throw cancelled
        } catch (_: Exception) {
            mutableAuth.update {
                it.copy(syncing = false, error = "Google sign-in failed. Check your connection, Firebase Google provider, Web client ID, and this build's SHA fingerprints. Local progress is safe.")
            }
        }
    }

    /** Immediately hide the signed-in account's cache, before the asynchronous auth callback. */
    fun signOut() {
        synchronized(stateLock) {
            firebase?.signOut()
            mutableHistory.value = emptyList()
            mutableAuth.value = AuthStatus(configured = firebase != null)
        }
        scope.launch {
            runSafely("Guest history could not be opened; its original data has been retained.") {
                localMutex.withLock { selectOwner(firebase?.currentUser?.uid) }
            }
        }
    }

    suspend fun record(record: SessionRecord) {
        // Bind ownership before dispatching IO; sign-out must not move an already submitted round
        // into the guest bucket (which could later migrate to a different Google account).
        val owner = firebase?.currentUser?.uid
        val generation = ledger.generation(owner)
        withContext(Dispatchers.IO) {
            runSafely("This round could not be saved to device storage. Free some storage and try again.") {
                localMutex.withLock {
                    if (ledger.accountDeletionRequested(owner)) {
                        if (firebase?.currentUser?.uid == owner) {
                            mutableAuth.update { it.copy(error = "Account deletion is pending. Sign in again and use Delete account to finish before saving new rounds.") }
                        }
                        return@withLock
                    }
                    selectOwner(owner)
                    ledger.append(owner, record, generation)?.let { showHistory(owner, it) }
                }
                if (firebase?.currentUser != null) scope.launch { sync() }
            }
        }
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val owner = firebase?.currentUser?.uid ?: return@withLock
            mutableAuth.update { it.copy(syncing = true, error = null) }
            try {
                withTimeout(20_000) {
                    if (localMutex.withLock { ledger.accountDeletionRequested(owner) } ||
                        firestore.collection("deletedAccounts").document(owner).get(Source.SERVER).await().exists()) {
                        localMutex.withLock { ledger.markAccountDeletion(owner) }
                        throw AccountDeletionPendingException()
                    }
                    val collection = firestore.collection("users").document(owner).collection("sessions")
                    flushPendingDeletes(owner)
                    val tombstones = firestore.collection("users").document(owner)
                        .collection("deletedSessions").get(Source.SERVER).await()
                    val deletedIds = tombstones.documents.map { it.id }.toSet()
                    localMutex.withLock {
                        requireCurrentOwner(owner)
                        ledger.applyRemoteDeletes(owner, deletedIds)
                        showHistory(owner, read(owner))
                    }
                    val snapshot = collection.get(Source.SERVER).await()
                    val remote = snapshot.documents.filterNot { it.id in deletedIds }.map { document ->
                        val fields = document.data ?: error("Missing cloud session")
                        require(fields["id"] == document.id) { "Cloud session ID mismatch" }
                        SessionCodec.fromMap(fields)
                    }
                    val local = localMutex.withLock {
                        requireCurrentOwner(owner)
                        selectOwner(owner)
                        read(owner)
                    }
                    val remoteIds = remote.map { it.id }.toSet()
                    // Rules read the account marker and one tombstone per result (batch rule-call limit 20).
                    local.filter { it.id !in remoteIds }.chunked(10).forEach { records ->
                        requireCurrentOwner(owner)
                        val batch = firestore.batch()
                        records.forEach { batch.set(collection.document(it.id), SessionCodec.toMap(it)) }
                        batch.commit().await()
                    }
                    localMutex.withLock {
                        requireCurrentOwner(owner)
                        // Include rounds saved while the network request was in flight.
                        val merged = SessionCodec.merge(read(owner), remote)
                        write(owner, merged)
                        showHistory(owner, read(owner))
                    }
                }
                mutableAuth.update { it.copy(syncing = false) }
            } catch (_: AccountDeletionPendingException) {
                mutableAuth.update { it.copy(syncing = false, error = "Account deletion is pending. Sign in again and use Delete account to finish.") }
            } catch (cancelled: CancellationException) {
                if (!kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.isActive) throw cancelled
                if (firebase?.currentUser?.uid == owner) {
                    mutableAuth.update { it.copy(syncing = false, error = "Cloud sync timed out. Progress remains saved on this device; retry when connected.") }
                }
            } catch (_: Exception) {
                if (firebase?.currentUser?.uid == owner) {
                    mutableAuth.update { it.copy(syncing = false, error = "Cloud sync failed. Progress remains saved on this device. Check your connection and Firestore rules, then retry sync.") }
                }
            }
        }
    }

    fun exportJson(): String = SessionCodec.encode(history.value)

    /** Deletion intent is durable; retries cannot upload a round already marked for deletion. */
    suspend fun deleteAllProgress(): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val owner = firebase?.currentUser?.uid
            try {
                mutableAuth.update { it.copy(syncing = true, error = null) }
                withTimeout(30_000) {
                    if (owner != null) {
                        deleteProgressLocked(owner)
                    } else localMutex.withLock {
                        check(firebase?.currentUser?.uid == null) { "Account changed during deletion" }
                        ledger.deleteGuestHistory()
                        showHistory(null, emptyList())
                    }
                }
                mutableAuth.update { it.copy(syncing = false) }
                true
            } catch (_: TimeoutCancellationException) {
                deletionError(owner)
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                deletionError(owner)
                false
            }
        }
    }

    /** Google account itself is untouched; this removes only the app's Firebase account/results. */
    suspend fun deleteAccount(): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val user = firebase?.currentUser ?: return@withLock false
            try {
                mutableAuth.update { it.copy(syncing = true, error = null) }
                withTimeout(30_000) {
                    localMutex.withLock { ledger.markAccountDeletion(user.uid) }
                    // Block late writes from other devices before enumerating and removing results.
                    firestore.collection("deletedAccounts").document(user.uid)
                        .set(mapOf("deleted" to true)).await()
                    deleteProgressLocked(user.uid)
                    requireCurrentOwner(user.uid)
                    user.delete().await()
                }
                localMutex.withLock { ledger.removeAccount(user.uid) }
                synchronized(stateLock) {
                    if (firebase?.currentUser == null) mutableAuth.value = AuthStatus(configured = true)
                }
                true
            } catch (_: TimeoutCancellationException) {
                mutableAuth.update { it.copy(syncing = false, error = "Account deletion timed out. New cloud uploads are blocked; reconnect and retry Delete account.") }
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: FirebaseAuthRecentLoginRequiredException) {
                mutableAuth.update { it.copy(syncing = false, error = "Your training progress was deleted. Sign out and sign in again, then retry Delete account to confirm account removal.") }
                false
            } catch (_: Exception) {
                mutableAuth.update { it.copy(syncing = false, error = "Account deletion did not complete. Reconnect, sign in again, and retry. Some training results may already have been deleted.") }
                false
            }
        }
    }

    fun clearError() = mutableAuth.update { it.copy(error = null) }

    fun close() {
        firebase?.removeAuthStateListener(listener)
        scope.cancel()
    }

    /** Called under syncMutex: capture intent once, then replay it until the server acknowledges it. */
    private suspend fun deleteProgressLocked(owner: String) {
        val collection = firestore.collection("users").document(owner).collection("sessions")
        val remoteIds = collection.get(Source.SERVER).await().documents.map { it.id }.toSet()
        localMutex.withLock {
            requireCurrentOwner(owner)
            ledger.markDeleted(owner, remoteIds + read(owner).map { it.id })
            showHistory(owner, read(owner))
        }
        flushPendingDeletes(owner)
    }

    private suspend fun flushPendingDeletes(owner: String) {
        val ids = localMutex.withLock { ledger.pendingDeletes(owner) }
        val account = firestore.collection("users").document(owner)
        // Each result consumes two batch writes: an immutable tombstone and deletion of the result.
        ids.chunked(200).forEach { page ->
            requireCurrentOwner(owner)
            val batch = firestore.batch()
            page.forEach { id ->
                batch.set(account.collection("deletedSessions").document(id), mapOf("deleted" to true))
                batch.delete(account.collection("sessions").document(id))
            }
            batch.commit().await()
            localMutex.withLock {
                ledger.applyRemoteDeletes(owner, page.toSet())
                showHistory(owner, read(owner))
            }
        }
    }

    private suspend fun deletionError(owner: String?) {
        if (firebase?.currentUser?.uid != owner) return
        val pending = try {
            localMutex.withLock { ledger.pendingDeletes(owner).isNotEmpty() }
        } catch (_: Exception) {
            false
        }
        mutableAuth.update {
            it.copy(syncing = false, error = if (pending) {
                "Cloud deletion is pending. These rounds stay hidden and deletion will retry on the next sync."
            } else {
                "Progress deletion could not reach the cloud. No new deletion was applied; reconnect and retry."
            })
        }
    }

    /** Called under localMutex. A single commit moves guest sessions exactly once. */
    private fun selectOwner(owner: String?) = synchronized(stateLock) {
        // Sign-out/account switching can happen while an IO callback was waiting for localMutex.
        if (firebase?.currentUser?.uid != owner) return@synchronized
        val selected = ledger.select(owner)
        mutableHistory.value = selected
        val user = firebase?.currentUser?.takeIf { it.uid == owner }
        mutableAuth.update {
            it.copy(userId = user?.uid, displayName = user?.displayName, email = user?.email, configured = firebase != null)
        }
    }

    private fun showHistory(owner: String?, records: List<SessionRecord>) = synchronized(stateLock) {
        if (firebase?.currentUser?.uid == owner) mutableHistory.value = records
    }

    private fun read(owner: String?): List<SessionRecord> = ledger.read(owner)

    private fun write(owner: String?, records: List<SessionRecord>) {
        ledger.write(owner, records)
    }

    private fun requireCurrentOwner(owner: String) {
        check(firebase?.currentUser?.uid == owner) { "Account changed during operation" }
    }

    private class AccountDeletionPendingException : IllegalStateException()

    private suspend fun runSafely(message: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableAuth.update { it.copy(error = message, syncing = false) }
        }
    }
}
