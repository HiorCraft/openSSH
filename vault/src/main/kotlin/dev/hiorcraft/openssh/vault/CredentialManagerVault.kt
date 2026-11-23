package dev.hiorcraft.openssh.vault

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Speichert Geheimnisse im Windows Credential Manager (Anmeldeinformationsverwaltung)
 * als generische Anmeldeinformationen mit dem Zielnamen `<namespace>:<key>`.
 * Windows verschlüsselt diese pro Benutzer mit DPAPI.
 */
class CredentialManagerVault(private val namespace: String = "OpenSSH") : SecretVault {

    private val prefix = "$namespace:"

    override fun put(key: String, secret: String) {
        val bytes = secret.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BLOB) throw VaultException("Geheimnis zu groß für den Credential Manager (${bytes.size} > $MAX_BLOB Byte)")
        val blob = Memory(maxOf(1, bytes.size).toLong()).apply { if (bytes.isNotEmpty()) write(0, bytes, 0, bytes.size) }
        val cred = Credential().apply {
            Type = CRED_TYPE_GENERIC
            TargetName = WString(prefix + key)
            UserName = WString(namespace)
            CredentialBlobSize = bytes.size
            CredentialBlob = if (bytes.isEmpty()) null else blob
            Persist = CRED_PERSIST_LOCAL_MACHINE
        }
        try {
            if (!api.CredWriteW(cred, 0)) throw VaultException("CredWrite fehlgeschlagen (Fehler ${Native.getLastError()})")
        } catch (e: LastErrorException) {
            throw VaultException("CredWrite fehlgeschlagen (Fehler ${e.errorCode})", e)
        } finally {
            blob.clear()
        }
    }

    override fun get(key: String): String? {
        val ref = PointerByReference()
        val ok = try {
            api.CredReadW(WString(prefix + key), CRED_TYPE_GENERIC, 0, ref)
        } catch (e: LastErrorException) {
            if (e.errorCode == ERROR_NOT_FOUND) return null
            throw VaultException("CredRead fehlgeschlagen (Fehler ${e.errorCode})", e)
        }
        if (!ok) return null
        val pointer = ref.value
        try {
            val cred = Credential(pointer)
            if (cred.CredentialBlobSize == 0 || cred.CredentialBlob == null) return ""
            return String(cred.CredentialBlob!!.getByteArray(0, cred.CredentialBlobSize), Charsets.UTF_8)
        } finally {
            api.CredFree(pointer)
        }
    }

    override fun delete(key: String) {
        try {
            api.CredDeleteW(WString(prefix + key), CRED_TYPE_GENERIC, 0)
        } catch (e: LastErrorException) {
            if (e.errorCode != ERROR_NOT_FOUND) throw VaultException("CredDelete fehlgeschlagen (Fehler ${e.errorCode})", e)
        }
    }

    override fun keys(): Set<String> {
        val count = IntByReference()
        val ref = PointerByReference()
        try {
            if (!api.CredEnumerateW(WString("$prefix*"), 0, count, ref)) return emptySet()
        } catch (e: LastErrorException) {
            if (e.errorCode == ERROR_NOT_FOUND) return emptySet()
            throw VaultException("CredEnumerate fehlgeschlagen (Fehler ${e.errorCode})", e)
        }
        val array = ref.value
        try {
            return array.getPointerArray(0, count.value)
                .map { Credential(it).TargetName.toString() }
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .toSet()
        } finally {
            api.CredFree(array)
        }
    }

    /** Entspricht `CREDENTIALW` aus wincred.h. */
    @Suppress("PropertyName")
    @Structure.FieldOrder(
        "Flags", "Type", "TargetName", "Comment", "LastWritten", "CredentialBlobSize", "CredentialBlob",
        "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName",
    )
    open class Credential() : Structure() {
        @JvmField var Flags: Int = 0
        @JvmField var Type: Int = 0
        @JvmField var TargetName: WString? = null
        @JvmField var Comment: WString? = null
        @JvmField var LastWritten: WinBase.FILETIME = WinBase.FILETIME()
        @JvmField var CredentialBlobSize: Int = 0
        @JvmField var CredentialBlob: Pointer? = null
        @JvmField var Persist: Int = 0
        @JvmField var AttributeCount: Int = 0
        @JvmField var Attributes: Pointer? = null
        @JvmField var TargetAlias: WString? = null
        @JvmField var UserName: WString? = null

        constructor(pointer: Pointer) : this() {
            useMemory(pointer)
            read()
        }
    }

    @Suppress("FunctionName")
    private interface CredApi : Library {
        @Throws(LastErrorException::class)
        fun CredWriteW(credential: Credential, flags: Int): Boolean

        @Throws(LastErrorException::class)
        fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean

        @Throws(LastErrorException::class)
        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean

        @Throws(LastErrorException::class)
        fun CredEnumerateW(filter: WString, flags: Int, count: IntByReference, credentials: PointerByReference): Boolean

        fun CredFree(buffer: Pointer)
    }

    companion object {
        /** CRED_MAX_CREDENTIAL_BLOB_SIZE = 5 * 512 */
        const val MAX_BLOB = 2560
        private const val CRED_TYPE_GENERIC = 1
        private const val CRED_PERSIST_LOCAL_MACHINE = 2
        private const val ERROR_NOT_FOUND = 1168

        private val api: CredApi by lazy { Native.load("Advapi32", CredApi::class.java) }
    }
}
