package com.alexvas.rtsp.srtp;

import android.util.Log;

import androidx.annotation.NonNull;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SRTP (RFC 3711) decryption for a single RTP track whose session keys were
 * negotiated out of band via SDES ("a=crypto:" line in the SDP, RFC 4568).
 * <p>
 * Only the two crypto suites cameras/NVRs commonly offer over SDES are
 * supported: AES_CM_128_HMAC_SHA1_80 and AES_CM_128_HMAC_SHA1_32 (AES-128
 * counter mode encryption with HMAC-SHA1 authentication, truncated to 80 or
 * 32 bits respectively). MKI is not supported.
 */
public final class SrtpCryptoContext {

    private static final String TAG = SrtpCryptoContext.class.getSimpleName();

    public static final String SUITE_AES_CM_128_HMAC_SHA1_80 = "AES_CM_128_HMAC_SHA1_80";
    public static final String SUITE_AES_CM_128_HMAC_SHA1_32 = "AES_CM_128_HMAC_SHA1_32";

    public static final int MASTER_KEY_LEN = 16;
    public static final int MASTER_SALT_LEN = 14;

    private static final int SESSION_KEY_LEN = 16;
    private static final int SESSION_SALT_LEN = 14;
    private static final int SESSION_AUTH_KEY_LEN = 20; // HMAC-SHA1 session auth key, n_a = 160 bit (RFC 3711 sec.5)

    private static final byte LABEL_RTP_ENCRYPTION = 0x00;
    private static final byte LABEL_RTP_AUTHENTICATION = 0x01;
    private static final byte LABEL_RTP_SALTING = 0x02;

    private final int authTagLength; // 10 bytes (80 bit) or 4 bytes (32 bit)
    private final byte[] sessionEncryptionKey;
    private final byte[] sessionSaltingKey;
    private final byte[] sessionAuthKey;

    private final Cipher cipher;
    private final Mac mac;

    // RTP-over-TCP interleaving guarantees in-order, lossless delivery, so a simple
    // "did the 16-bit sequence number wrap since the last packet" check is enough to
    // track the 32-bit rollover counter (RFC 3711 sec.3.3.1) - no reordering to handle.
    private final Map<Long, RolloverState> rolloverStateBySsrc = new HashMap<>();

    private static final class RolloverState {
        long roc = 0;
        int highestSeq;
    }

    public static boolean isSuiteSupported(@NonNull String cryptoSuite) {
        return SUITE_AES_CM_128_HMAC_SHA1_80.equals(cryptoSuite) || SUITE_AES_CM_128_HMAC_SHA1_32.equals(cryptoSuite);
    }

    public SrtpCryptoContext(@NonNull String cryptoSuite, @NonNull byte[] masterKey, @NonNull byte[] masterSalt) throws GeneralSecurityException {
        if (!isSuiteSupported(cryptoSuite))
            throw new GeneralSecurityException("Unsupported SRTP crypto suite '" + cryptoSuite + "'");
        if (masterKey.length != MASTER_KEY_LEN || masterSalt.length != MASTER_SALT_LEN)
            throw new GeneralSecurityException("Invalid SRTP master key/salt length");

        authTagLength = SUITE_AES_CM_128_HMAC_SHA1_80.equals(cryptoSuite) ? 10 : 4;

        cipher = Cipher.getInstance("AES/CTR/NoPadding");
        mac = Mac.getInstance("HmacSHA1");

        sessionEncryptionKey = deriveSessionKey(masterKey, masterSalt, LABEL_RTP_ENCRYPTION, SESSION_KEY_LEN);
        sessionAuthKey = deriveSessionKey(masterKey, masterSalt, LABEL_RTP_AUTHENTICATION, SESSION_AUTH_KEY_LEN);
        sessionSaltingKey = deriveSessionKey(masterKey, masterSalt, LABEL_RTP_SALTING, SESSION_SALT_LEN);
    }

    /**
     * Verifies the packet's authentication tag and decrypts its payload in place.
     *
     * @param rawHeader   the 12-byte fixed RTP header exactly as received on the wire
     *                    (no CSRC list, RTP header extension excluded)
     * @param data        buffer holding the SRTP payload: ciphertext immediately followed
     *                    by the authentication tag, starting at offset 0
     * @param payloadSize             number of valid bytes in {@code data} (header extension,
     *                                if any, + ciphertext + auth tag)
     * @param ssrc                    RTP SSRC from the header
     * @param seq                     RTP sequence number from the header (0-65535)
     * @param headerExtensionLength   number of bytes at the start of {@code data} occupied by
     *                                the RTP header extension (0 if the packet has none). Per
     *                                RFC 3711 sec.3.1, the header extension is authenticated
     *                                like the fixed header but, unlike the rest of the payload,
     *                                is NOT part of the encrypted portion, so it must be
     *                                excluded from decryption while still covered by the tag.
     * @return length of the decrypted payload (header extension + plaintext) with the auth tag
     *         stripped, or -1 if the packet failed authentication or was too short
     */
    public synchronized int decryptAndVerify(
            @NonNull byte[] rawHeader,
            @NonNull byte[] data,
            int payloadSize,
            long ssrc,
            int seq,
            int headerExtensionLength) {
        int cipherTextLen = payloadSize - authTagLength;
        if (cipherTextLen < headerExtensionLength)
            return -1;

        long roc = updateRolloverCounter(ssrc, seq);

        try {
            mac.init(new SecretKeySpec(sessionAuthKey, "HmacSHA1"));
            mac.update(rawHeader);
            mac.update(data, 0, cipherTextLen);
            mac.update(new byte[]{(byte) (roc >>> 24), (byte) (roc >>> 16), (byte) (roc >>> 8), (byte) roc});
            byte[] computedTag = mac.doFinal();
            for (int i = 0; i < authTagLength; i++) {
                if (computedTag[i] != data[cipherTextLen + i]) {
                    Log.w(TAG, "SRTP authentication failed, ssrc=" + ssrc + ", seq=" + seq);
                    return -1;
                }
            }
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "SRTP authentication error", e);
            return -1;
        }

        try {
            byte[] iv = buildPacketIv(ssrc, roc, seq);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sessionEncryptionKey, "AES"), new IvParameterSpec(iv));
            // Counter position 0 corresponds to the first byte of the actual RTP payload,
            // i.e. right after the (unencrypted) header extension - not to byte 0 of `data`.
            cipher.doFinal(data, headerExtensionLength, cipherTextLen - headerExtensionLength, data, headerExtensionLength);
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "SRTP decryption error", e);
            return -1;
        }

        return cipherTextLen;
    }

    private long updateRolloverCounter(long ssrc, int seq) {
        RolloverState state = rolloverStateBySsrc.get(ssrc);
        if (state == null) {
            state = new RolloverState();
            state.highestSeq = seq;
            rolloverStateBySsrc.put(ssrc, state);
            return state.roc;
        }
        int delta = seq - state.highestSeq;
        if (delta < -32768) {
            // Sequence number wrapped past 65535 back to (near) 0.
            state.roc++;
            state.highestSeq = seq;
        } else if (delta > 32768) {
            // A stray packet from just before the last wrap arrived; don't advance ROC.
            return state.roc - 1;
        } else if (seq > state.highestSeq) {
            state.highestSeq = seq;
        }
        return state.roc;
    }

    // RFC 3711 sec.4.1.1: IV = (k_s * 2^16) XOR (SSRC * 2^64) XOR (index * 2^16),
    // where index = (ROC << 16) | SEQ.
    @NonNull
    private byte[] buildPacketIv(long ssrc, long roc, int seq) {
        byte[] iv = new byte[16];
        System.arraycopy(sessionSaltingKey, 0, iv, 0, SESSION_SALT_LEN);

        iv[4] ^= (byte) (ssrc >>> 24);
        iv[5] ^= (byte) (ssrc >>> 16);
        iv[6] ^= (byte) (ssrc >>> 8);
        iv[7] ^= (byte) ssrc;

        iv[8] ^= (byte) (roc >>> 24);
        iv[9] ^= (byte) (roc >>> 16);
        iv[10] ^= (byte) (roc >>> 8);
        iv[11] ^= (byte) roc;
        iv[12] ^= (byte) (seq >>> 8);
        iv[13] ^= (byte) seq;

        return iv;
    }

    // RFC 3711 sec.4.3: derive a session key using AES-CM as a PRF.
    // x = (0x0000000 || label || 0x000000000000) XOR master_salt, i.e. master_salt with
    // "label" xored into byte 7 (key derivation rate is always 0, so r is always 0 too).
    // The session key is the first `outputLength` bytes of AES-CM(master_key, IV = x || 0x0000).
    @NonNull
    private static byte[] deriveSessionKey(
            @NonNull byte[] masterKey,
            @NonNull byte[] masterSalt,
            byte label,
            int outputLength)
    throws GeneralSecurityException {
        byte[] x = new byte[16];
        System.arraycopy(masterSalt, 0, x, 0, MASTER_SALT_LEN);
        x[7] ^= label;

        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new IvParameterSpec(x));
        byte[] zeros = new byte[((outputLength + 15) / 16) * 16];
        byte[] keystream = cipher.doFinal(zeros);
        byte[] result = new byte[outputLength];
        System.arraycopy(keystream, 0, result, 0, outputLength);
        return result;
    }
}
