package com.toolhelper.crypto.aes;

import com.toolhelper.crypto.aes.AesModels.CryptoRequest;
import com.toolhelper.crypto.aes.AesModels.RandomRequest;
import com.toolhelper.support.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AesServiceTest {
    private final AesService service = new AesService();
    private static final String AES128 = "00000000000000000000000000000000";
    private static final String ZERO_IV = "00000000000000000000000000000000";
    private static final String BLOCK = "00000000000000000000000000000000";

    private CryptoRequest request(String mode, String padding, String key, int bits, String iv, String input, String inputFormat, String outputFormat) {
        return new CryptoRequest("STANDARD", mode, bits, padding, "UTF-8", key, "HEX",
                mode.equals("ECB") ? null : "MANUAL", iv, iv == null ? null : "HEX", "", "TEXT",
                input, inputFormat, outputFormat, true);
    }

    @Test void matchesNistGcmKnownAnswerAndAuthenticatesBeforeReturningPlaintext() {
        String nonce = "000000000000000000000000";
        var encrypt = request("GCM", "NONE", AES128, 128, nonce, BLOCK, "HEX", "HEX");
        var encrypted = service.encrypt(encrypt);
        assertEquals("0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf", encrypted.get("output"));
        assertEquals("AUTHENTICATED", encrypted.get("integrity"));
        var decrypt = request("GCM", "NONE", AES128, 128, nonce, (String) encrypted.get("output"), "HEX", "HEX");
        assertEquals(BLOCK, service.decrypt(decrypt).get("output"));
        var changed = request("GCM", "NONE", AES128, 128, nonce, "0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bdd0", "HEX", "HEX");
        ApiException failure = assertThrows(ApiException.class, () -> service.decrypt(changed));
        assertEquals("AES_DECRYPT_FAILED", failure.code());
        assertFalse(failure.getMessage().contains("tag"));
    }

    @Test void matchesNistCbcAndCtrVectors() {
        String key = "2b7e151628aed2a6abf7158809cf4f3c";
        String plain = "6bc1bee22e409f96e93d7e117393172a";
        String iv = "000102030405060708090a0b0c0d0e0f";
        assertEquals("7649abac8119b246cee98e9b12e9197d", service.encrypt(request("CBC", "NONE", key, 128, iv, plain, "HEX", "HEX")).get("output"));
        String counter = "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff";
        assertEquals("874d6191b620e3261bef6864990db6ce", service.encrypt(request("CTR", "NONE", key, 128, counter, plain, "HEX", "HEX")).get("output"));
    }

    @Test void validatesBytesStrictlyAndKeepsStandardKeysUnmodified() {
        var shortKey = request("GCM", "NONE", "00", 128, ZERO_IV, "", "HEX", "HEX");
        assertEquals("AES_INVALID_KEY_LENGTH", assertThrows(ApiException.class, () -> service.encrypt(shortKey)).code());
        var oddHex = request("CBC", "NONE", AES128, 128, ZERO_IV, "abc", "HEX", "HEX");
        assertEquals("AES_INVALID_ENCODING", assertThrows(ApiException.class, () -> service.encrypt(oddHex)).code());
        var badText = new CryptoRequest("STANDARD", "GCM", 128, "NONE", "US-ASCII", AES128, "HEX", "AUTO", null, null,
                "", "TEXT", "不支持的中文", "TEXT", "BASE64", false);
        assertEquals("AES_INVALID_ENCODING", assertThrows(ApiException.class, () -> service.encrypt(badText)).code());
    }

    @Test void supportsExplicitLegacyGcmNonceTagCiphertextLayout() {
        String nonce = ZERO_IV.substring(0, 24);
        String ciphertext = "0388dace60b6a392f328c2b971b2fe78";
        String tag = "ab6e47d42cec13bdf53a67b21257bddf";
        String legacy = nonce + tag + ciphertext;
        var request = new CryptoRequest("LEGACY_TOOLHELPER", "GCM", 128, "NONE", "UTF-8", AES128, "HEX",
                null, null, null, "", "TEXT", legacy, "HEX", "HEX", false);
        var result = service.decrypt(request);
        assertEquals(BLOCK, result.get("output"));
        assertEquals(nonce, result.get("ivHex"));
        assertEquals("AUTHENTICATED", result.get("integrity"));
    }

    @Test void rejectsCounterWrapAndInvalidRandomFormats() {
        String max = "ffffffffffffffffffffffffffffffff";
        var wrap = request("CTR", "NONE", AES128, 128, max, "0000000000000000000000000000000000", "HEX", "HEX");
        assertEquals("AES_INVALID_IV_LENGTH", assertThrows(ApiException.class, () -> service.encrypt(wrap)).code());
        assertThrows(ApiException.class, () -> service.random(new RandomRequest("KEY", 129, "HEX")));
        assertEquals(12, service.random(new RandomRequest("NONCE", null, "HEX")).get("bytes"));
    }
}
