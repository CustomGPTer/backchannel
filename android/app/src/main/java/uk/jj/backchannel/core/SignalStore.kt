package uk.jj.backchannel.core

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * Where the Signal library keeps its keys and sessions. Everything is handed to [Storage]
 * as plain bytes, so on the phone it all lands in the encrypted database.
 */
class SignalStore(private val s: Storage) : SignalProtocolStore {

    // --- our own identity ---

    override fun getIdentityKeyPair(): IdentityKeyPair =
        IdentityKeyPair(s.kvGet(Keys.K_IDENTITY) ?: error("no identity"))

    override fun getLocalRegistrationId(): Int =
        String(s.kvGet(Keys.K_REG_ID) ?: error("no registration id")).toInt()

    // --- other people's identities ---

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = ID + address.name
        val existing = s.kvGet(key)
        val fresh = identityKey.serialize()
        s.kvPut(key, fresh)
        return if (existing != null && !existing.contentEquals(fresh)) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    /**
     * A contact's identity key is fixed the moment they are added. If a different key ever
     * turns up for the same address, it is refused.
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val existing = s.kvGet(ID + address.name) ?: return true
        return existing.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        s.kvGet(ID + address.name)?.let { IdentityKey(it) }

    fun pinIdentity(onion: String, identityKey: ByteArray) = s.kvPut(ID + onion, identityKey)

    /** Throws away every session, keeping contacts and their identity keys. Used after restoring a backup. */
    fun dropAllSessions() {
        for (k in s.kvKeys(SESSION)) s.kvDelete(k)
    }

    fun forget(onion: String) {
        s.kvDelete(ID + onion)
        s.kvDelete(SESSION + onion)
    }

    // --- one-time prekeys ---

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        PreKeyRecord(s.kvGet(PRE + preKeyId) ?: throw InvalidKeyIdException("no prekey $preKeyId"))

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = s.kvPut(PRE + preKeyId, record.serialize())

    override fun containsPreKey(preKeyId: Int): Boolean = s.kvGet(PRE + preKeyId) != null

    override fun removePreKey(preKeyId: Int) = s.kvDelete(PRE + preKeyId)

    // --- sessions ---

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        s.kvGet(SESSION + address.name)?.let { SessionRecord(it) }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw NoSessionException(it, "no session") }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        s.kvPut(SESSION + address.name, record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean = s.kvGet(SESSION + address.name) != null

    override fun deleteSession(address: SignalProtocolAddress) = s.kvDelete(SESSION + address.name)

    override fun deleteAllSessions(name: String) = s.kvDelete(SESSION + name)

    // --- signed prekeys ---

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        SignedPreKeyRecord(s.kvGet(SIGNED + signedPreKeyId) ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId"))

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        s.kvKeys(SIGNED).mapNotNull { k -> s.kvGet(k)?.let { SignedPreKeyRecord(it) } }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        s.kvPut(SIGNED + signedPreKeyId, record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = s.kvGet(SIGNED + signedPreKeyId) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) = s.kvDelete(SIGNED + signedPreKeyId)

    // --- post-quantum (Kyber) prekeys: each one is used once and then destroyed ---

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        KyberPreKeyRecord(s.kvGet(KYBER + kyberPreKeyId) ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId"))

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        s.kvKeys(KYBER).mapNotNull { k -> s.kvGet(k)?.let { KyberPreKeyRecord(it) } }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        s.kvPut(KYBER + kyberPreKeyId, record.serialize())

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = s.kvGet(KYBER + kyberPreKeyId) != null

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) =
        s.kvDelete(KYBER + kyberPreKeyId)

    // --- group keys: not used (one-to-one chats only) ---

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) = Unit

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? = null

    private companion object {
        const val ID = "id:"
        const val SESSION = "ss:"
        const val PRE = "pk:"
        const val SIGNED = "sk:"
        const val KYBER = "kk:"
    }
}
