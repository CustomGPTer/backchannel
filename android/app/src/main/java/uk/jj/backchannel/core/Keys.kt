package uk.jj.backchannel.core

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Creating this phone's keys, and packing them up to hand to a new contact. */
object Keys {
    const val K_IDENTITY = "self:identity"
    const val K_REG_ID = "self:regid"
    const val K_NAME = "self:name"
    const val K_ONION = "self:onion"
    const val K_ONION_KEY = "self:onionkey"
    private const val K_NEXT_ID = "self:nextkeyid"
    private const val SIGNED_ID = 1
    private const val DEVICE_ID = 1
    private const val MAX_ID = 0xFFFFFF

    fun address(onion: String) = SignalProtocolAddress(onion, DEVICE_ID)

    /** Makes the long-term identity key the first time the app is set up. */
    fun ensureIdentity(s: Storage, now: Long) {
        if (s.kvGet(K_IDENTITY) != null) return
        s.transaction {
            val identity = IdentityKeyPair.generate()
            s.kvPut(K_IDENTITY, identity.serialize())
            s.kvPut(K_REG_ID, KeyHelper.generateRegistrationId(false).toString().toByteArray())
            val signed = ECKeyPair.generate()
            val sig = identity.privateKey.calculateSignature(signed.publicKey.serialize())
            SignalStore(s).storeSignedPreKey(SIGNED_ID, SignedPreKeyRecord(SIGNED_ID, now, signed, sig))
        }
    }

    private fun nextId(s: Storage): Int {
        val cur = s.kvGet(K_NEXT_ID)?.let { String(it).toInt() } ?: 1
        val next = if (cur >= MAX_ID) 1 else cur + 1
        s.kvPut(K_NEXT_ID, next.toString().toByteArray())
        return cur
    }

    /**
     * Builds a fresh bundle of public keys for one new contact. The one-time keys in it are
     * made on the spot, kept until that contact's first message arrives, then destroyed.
     */
    fun makeBundle(s: Storage, store: SignalStore, now: Long): ByteArray {
        val identity = store.identityKeyPair
        val signed = store.loadSignedPreKey(SIGNED_ID)

        val preId = nextId(s)
        val pre = ECKeyPair.generate()
        store.storePreKey(preId, PreKeyRecord(preId, pre))

        val kyberId = nextId(s)
        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSig = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
        store.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, now, kyber, kyberSig))

        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(store.localRegistrationId)
        out.writeInt(preId)
        blob(out, pre.publicKey.serialize())
        out.writeInt(SIGNED_ID)
        blob(out, signed.keyPair.publicKey.serialize())
        blob(out, signed.signature)
        blob(out, identity.publicKey.serialize())
        out.writeInt(kyberId)
        blob(out, kyber.publicKey.serialize())
        blob(out, kyberSig)
        return bytes.toByteArray()
    }

    @Throws(IOException::class)
    fun parseBundle(raw: ByteArray): PreKeyBundle {
        try {
            val inp = DataInputStream(ByteArrayInputStream(raw))
            val regId = inp.readInt()
            val preId = inp.readInt()
            val pre = ECPublicKey(blob(inp))
            val signedId = inp.readInt()
            val signed = ECPublicKey(blob(inp))
            val signedSig = blob(inp)
            val identity = IdentityKey(blob(inp))
            val kyberId = inp.readInt()
            val kyber = KEMPublicKey(blob(inp))
            val kyberSig = blob(inp)
            return PreKeyBundle(regId, DEVICE_ID, preId, pre, signedId, signed, signedSig, identity, kyberId, kyber, kyberSig)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("bad key bundle", e)
        }
    }

    private fun blob(out: DataOutputStream, b: ByteArray) {
        out.writeShort(b.size)
        out.write(b)
    }

    private fun blob(inp: DataInputStream): ByteArray {
        val n = inp.readUnsignedShort()
        return ByteArray(n).also { inp.readFully(it) }
    }
}
