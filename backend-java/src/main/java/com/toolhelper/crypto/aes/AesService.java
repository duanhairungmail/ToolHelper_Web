package com.toolhelper.crypto.aes;

import com.toolhelper.crypto.aes.AesModels.CryptoRequest;
import com.toolhelper.crypto.aes.AesModels.RandomRequest;
import com.toolhelper.support.ApiException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static com.toolhelper.crypto.aes.AesCodec.invalid;

@Service
public final class AesService {
    private static final Logger LOG = LoggerFactory.getLogger(AesService.class);
    private static final int MAX_INPUT_BYTES = 1024 * 1024;
    private static final int MAX_AAD_BYTES = 64 * 1024;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BYTES = 16;
    private static final int AES_BLOCK_BYTES = 16;
    private static final BigInteger MAX_COUNTER = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
    private static final Set<Integer> KEY_BITS = Set.of(128, 192, 256);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Semaphore slots = new Semaphore(2);
    private final Map<String, List<Integer>> supportedKeyBits = new LinkedHashMap<>();

    public AesService() {
        for (String mode : List.of("CBC", "ECB", "CTR", "GCM")) {
            List<Integer> supported = new ArrayList<>();
            for (int bits : KEY_BITS) {
                try {
                    for (String padding : Set.of("CBC", "ECB").contains(mode) ? List.of("PKCS7", "NONE") : List.of("NONE")) {
                        byte[] key = new byte[bits / 8], iv = new byte[mode.equals("ECB") ? 0 : mode.equals("GCM") ? 12 : 16];
                        byte[] plain = new byte[16];
                        byte[] encrypted = crypt(mode, padding, true, key, iv, new byte[0], plain);
                        if (!Arrays.equals(plain, crypt(mode, padding, false, key, iv, new byte[0], encrypted)))
                            throw new GeneralSecurityException("AES self-test failed");
                    }
                    supported.add(bits);
                } catch (GeneralSecurityException | RuntimeException unavailable) {
                    LOG.warn("AES提供者能力自检失败 mode={} keyBits={}", mode, bits);
                }
            }
            if (!supported.isEmpty()) supportedKeyBits.put(mode, List.copyOf(supported));
        }
    }

    public Map<String, Object> capabilities() {
        List<String> charsets = new ArrayList<>();
        for (String name : List.of("UTF-8", "UTF-16LE", "UTF-32LE", "US-ASCII", "GBK", "ISO-8859-1")) {
            try { if (Charset.isSupported(name)) charsets.add(name); } catch (IllegalArgumentException ignored) { }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modes", List.copyOf(supportedKeyBits.keySet())); result.put("modeKeyBits", Map.copyOf(supportedKeyBits));
        result.put("keyBits", KEY_BITS.stream().filter(bits -> supportedKeyBits.values().stream().anyMatch(list -> list.contains(bits))).toList());
        result.put("charsets", charsets); result.put("formats", List.of("TEXT", "HEX", "BASE64"));
        result.put("defaultMode", "GCM"); result.put("defaultKeyBits", 256); result.put("maxInputBytes", MAX_INPUT_BYTES);
        result.put("maxAadBytes", MAX_AAD_BYTES); result.put("gcmTagBits", 128); result.put("legacyDecrypt", true);
        result.put("roundTripPlaintextLimits", Map.of("GCM", MAX_INPUT_BYTES - GCM_TAG_BYTES, "PKCS7", MAX_INPUT_BYTES - 1, "NONE", MAX_INPUT_BYTES));
        result.put("gcmLayout", "CIPHERTEXT_THEN_TAG"); result.put("maxConcurrent", 2);
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> random(RandomRequest request) {
        if (request == null || request.kind() == null || request.format() == null)
            throw invalid("AES_INVALID_PARAMETERS", "请选择随机数据类型和输出格式");
        int size = switch (request.kind()) {
            case "KEY" -> {
                if (request.keyBits() == null || !KEY_BITS.contains(request.keyBits())) throw invalid("AES_INVALID_KEY_LENGTH", "密钥长度仅支持128、192或256位");
                yield request.keyBits() / 8;
            }
            case "IV", "CTR_COUNTER" -> 16;
            case "NONCE" -> GCM_NONCE_BYTES;
            default -> throw invalid("AES_INVALID_PARAMETERS", "随机数据类型无效");
        };
        if (!Set.of("HEX", "BASE64").contains(request.format())) throw invalid("AES_INVALID_PARAMETERS", "随机数据输出仅支持HEX或BASE64");
        byte[] bytes = new byte[size]; RANDOM.nextBytes(bytes);
        try { return Map.of("value", AesCodec.encode(bytes, request.format(), Charset.forName("UTF-8")), "format", request.format(), "bytes", size); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    public Map<String, Object> encrypt(CryptoRequest request) { return process(request, true); }
    public Map<String, Object> decrypt(CryptoRequest request) { return process(request, false); }

    private Map<String, Object> process(CryptoRequest request, boolean encrypt) {
        if (!slots.tryAcquire()) throw new ApiException(503, "AES_BUSY", "AES处理已达到并发上限，请稍后重试", true);
        long started = System.nanoTime(); String mode = request == null ? "unknown" : String.valueOf(request.mode());
        String profile = request == null ? "unknown" : String.valueOf(request.profile());
        byte[] key = null, iv = null, aad = null, input = null, output = null;
        List<String> warnings = new ArrayList<>();
        try {
            validate(request, encrypt);
            boolean legacy = "LEGACY_TOOLHELPER".equals(request.profile());
            Charset charset = AesCodec.charset(request.charset());
            if (request.key().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096
                    || request.iv() != null && request.iv().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
                throw invalid("AES_INVALID_PARAMETERS", "KEY/IV原始字段不得超过4 KiB");
            key = decodeCredential(request.key(), request.keyFormat(), charset, legacy);
            if (key.length == 0) throw invalid("AES_INVALID_KEY_LENGTH", "密钥不能为空");
            if (legacy) key = legacyAdjust(key, request.keyBits() / 8, "KEY", warnings);
            else if (key.length != request.keyBits() / 8) throw invalid("AES_INVALID_KEY_LENGTH", "密钥解码后必须恰为所选位数对应的字节数");

            input = AesCodec.decode(request.input(), request.inputFormat(), charset, legacy);
            if (input.length > MAX_INPUT_BYTES) throw new ApiException(413, "AES_INPUT_TOO_LARGE", "解码后的输入超过1 MiB");
            int sourceInputBytes = input.length;
            aad = request.aad() == null || request.aad().isEmpty() ? new byte[0]
                    : AesCodec.decode(request.aad(), request.aadFormat(), charset, false);
            if (aad.length > MAX_AAD_BYTES) throw new ApiException(413, "AES_INPUT_TOO_LARGE", "AAD超过64 KiB");
            if (!request.mode().equals("GCM") && aad.length != 0) throw invalid("AES_INVALID_PARAMETERS", "只有GCM模式支持AAD");

            boolean packedLegacyGcm = legacy && !encrypt && request.mode().equals("GCM");
            if (packedLegacyGcm) {
                if (input.length < GCM_NONCE_BYTES + GCM_TAG_BYTES) throw invalid("AES_BLOCK_ALIGNMENT_REQUIRED", "旧版GCM密文包至少包含12字节nonce和16字节tag");
                iv = Arrays.copyOfRange(input, 0, GCM_NONCE_BYTES);
                byte[] ciphertextAndTag = new byte[input.length - GCM_NONCE_BYTES];
                System.arraycopy(input, GCM_NONCE_BYTES + GCM_TAG_BYTES, ciphertextAndTag, 0, input.length - GCM_NONCE_BYTES - GCM_TAG_BYTES);
                System.arraycopy(input, GCM_NONCE_BYTES, ciphertextAndTag, ciphertextAndTag.length - GCM_TAG_BYTES, GCM_TAG_BYTES);
                Arrays.fill(input, (byte) 0); input = ciphertextAndTag;
                warnings.add("已从旧版密文包读取nonce，并按nonce||tag||ciphertext布局解析");
            } else {
                iv = resolveIv(request, encrypt, legacy, charset, warnings);
            }

            validateDataLength(request, encrypt, input.length, iv, legacy);
            if (request.mode().equals("CTR")) validateCounterRange(iv, input.length);
            if (!supportedKeyBits.getOrDefault(request.mode(), List.of()).contains(request.keyBits()))
                throw invalid("AES_UNSUPPORTED_CAPABILITY", "当前Java提供者未通过所选模式/密钥长度自检");
            output = crypt(request.mode(), request.padding(), encrypt, key, iv, aad, input);
            String rendered = AesCodec.encode(output, request.outputFormat(), charset);
            int outputBytes = output.length;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("output", rendered); result.put("outputFormat", request.outputFormat());
            result.put("mode", request.mode()); result.put("keyBits", request.keyBits());
            result.put("padding", request.padding()); result.put("profile", request.profile());
            result.put("inputBytes", sourceInputBytes); result.put("outputBytes", outputBytes);
            result.put("ivHex", iv.length == 0 ? null : HexFormat.of().formatHex(iv));
            result.put("tagHex", request.mode().equals("GCM") && !encrypt
                    ? HexFormat.of().formatHex(Arrays.copyOfRange(input, input.length - GCM_TAG_BYTES, input.length)) : null);
            result.put("integrity", request.mode().equals("GCM") ? "AUTHENTICATED" : "NOT_AUTHENTICATED");
            result.put("warnings", List.copyOf(warnings));
            LOG.info("AES操作完成 requestMode={} profile={} operation={} inputBytes={} outputBytes={} elapsedMs={}",
                    mode, profile, encrypt ? "ENCRYPT" : "DECRYPT", sourceInputBytes, outputBytes,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return result;
        } catch (AEADBadTagException failure) {
            throw decryptFailed();
        } catch (ApiException failure) {
            throw failure;
        } catch (GeneralSecurityException | RuntimeException failure) {
            if (!encrypt) throw decryptFailed();
            LOG.warn("AES加密失败 mode={} profile={} errorType={}", mode, profile, failure.getClass().getSimpleName());
            throw invalid("AES_UNSUPPORTED_CAPABILITY", "当前Java运行环境无法完成所选AES运算");
        } finally {
            wipe(key); wipe(iv); wipe(aad); wipe(input); wipe(output); slots.release();
        }
    }

    private static void validate(CryptoRequest request, boolean encrypt) {
        if (request == null || request.profile() == null || request.mode() == null || request.keyBits() == null
                || request.padding() == null || request.charset() == null || request.key() == null
                || request.keyFormat() == null || request.input() == null || request.inputFormat() == null || request.outputFormat() == null)
            throw invalid("AES_INVALID_PARAMETERS", "请求缺少必需参数");
        if (!Set.of("STANDARD", "LEGACY_TOOLHELPER").contains(request.profile()))
            throw invalid("AES_INVALID_PARAMETERS", "参数方案无效");
        if (encrypt && !request.profile().equals("STANDARD")) throw invalid("AES_INVALID_PARAMETERS", "旧ToolHelper兼容方案仅用于解密");
        if (!Set.of("CBC", "ECB", "CTR", "GCM").contains(request.mode())) throw invalid("AES_INVALID_PARAMETERS", "运算模式无效");
        if (!KEY_BITS.contains(request.keyBits())) throw invalid("AES_INVALID_KEY_LENGTH", "密钥长度仅支持128、192或256位");
        boolean block = Set.of("CBC", "ECB").contains(request.mode());
        if (block ? !Set.of("PKCS7", "NONE").contains(request.padding()) : !request.padding().equals("NONE"))
            throw invalid("AES_INVALID_PARAMETERS", "填充方式与运算模式不匹配");
        if (!Set.of("TEXT", "HEX", "BASE64").contains(request.keyFormat()) || !Set.of("HEX", "BASE64").contains(request.outputFormat()))
            throw invalid("AES_INVALID_PARAMETERS", "KEY或密文输出格式无效");
        if (encrypt && !Set.of("TEXT", "HEX", "BASE64").contains(request.inputFormat())) throw invalid("AES_INVALID_PARAMETERS", "加密输入格式无效");
        if (!encrypt && !Set.of("HEX", "BASE64").contains(request.inputFormat())) throw invalid("AES_INVALID_PARAMETERS", "解密密文只支持HEX或BASE64");
        if (!encrypt && request.outputFormat().equals("BASE64") && request.mode().equals("GCM")) { /* ciphertext output is also valid */ }
        if (!Set.of("HEX", "BASE64", "TEXT").contains(request.outputFormat())) throw invalid("AES_INVALID_PARAMETERS", "解密输出格式无效");
        boolean embeddedNonce = !encrypt && request.profile().equals("LEGACY_TOOLHELPER") && request.mode().equals("GCM");
        if (request.mode().equals("ECB") || embeddedNonce) {
            if (request.ivMode() != null || request.iv() != null || request.ivFormat() != null)
                throw invalid("AES_INVALID_PARAMETERS", "当前模式的IV在密文布局中确定，不能额外提供IV参数");
        } else {
            if (!Set.of("AUTO", "MANUAL").contains(request.ivMode())) throw invalid("AES_INVALID_PARAMETERS", "IV模式无效");
            if (request.ivMode().equals("AUTO") && (!encrypt || request.iv() != null || request.ivFormat() != null))
                throw invalid("AES_INVALID_PARAMETERS", "自动IV仅用于加密，且不接受外部IV");
            if (request.ivMode().equals("MANUAL") && (request.iv() == null || request.ivFormat() == null
                    || !Set.of("TEXT", "HEX", "BASE64").contains(request.ivFormat())))
                throw invalid("AES_INVALID_PARAMETERS", "手工IV缺少内容或格式");
            if (!encrypt && !request.ivMode().equals("MANUAL")) throw invalid("AES_INVALID_PARAMETERS", "解密必须提供实际使用的IV或nonce");
            if (encrypt && Set.of("CTR", "GCM").contains(request.mode()) && request.ivMode().equals("MANUAL")
                    && !Boolean.TRUE.equals(request.manualRiskConfirmed()))
                throw invalid("AES_INVALID_PARAMETERS", "手工nonce/CTR初始计数器须先确认复用风险");
        }
        if (request.mode().equals("GCM") && request.aad() != null && !request.aad().isEmpty() && request.aadFormat() == null)
            throw invalid("AES_INVALID_PARAMETERS", "请指定AAD格式");
        if (request.profile().equals("LEGACY_TOOLHELPER") && request.mode().equals("GCM") && request.aad() != null && !request.aad().isEmpty())
            throw invalid("AES_INVALID_PARAMETERS", "旧版GCM不支持AAD");
        if (!encrypt && request.mode().equals("GCM") && request.profile().equals("LEGACY_TOOLHELPER") && request.iv() != null && !request.iv().isEmpty())
            throw invalid("AES_INVALID_PARAMETERS", "旧版GCM nonce从密文包读取，不接受外部IV");
    }

    private static byte[] decodeCredential(String value, String format, Charset charset, boolean legacy) {
        if (legacy && "TEXT".equals(format)) value = value.trim();
        return AesCodec.decode(value, format, charset, legacy);
    }

    private static byte[] resolveIv(CryptoRequest request, boolean encrypt, boolean legacy, Charset charset, List<String> warnings) {
        if (request.mode().equals("ECB")) return new byte[0];
        if (encrypt && request.ivMode().equals("AUTO")) {
            byte[] value = new byte[request.mode().equals("GCM") ? GCM_NONCE_BYTES : AES_BLOCK_BYTES]; RANDOM.nextBytes(value); return value;
        }
        byte[] value = decodeCredential(request.iv(), request.ivFormat(), charset, legacy);
        if (legacy) return legacyAdjust(value, request.mode().equals("GCM") ? GCM_NONCE_BYTES : AES_BLOCK_BYTES, "IV", warnings);
        int expected = request.mode().equals("GCM") ? GCM_NONCE_BYTES : AES_BLOCK_BYTES;
        if (value.length != expected) { wipe(value); throw invalid("AES_INVALID_IV_LENGTH", "IV必须为16字节，GCM nonce必须为12字节"); }
        return value;
    }

    private static byte[] legacyAdjust(byte[] value, int size, String field, List<String> warnings) {
        if (value.length != size) warnings.add(field + (value.length < size ? "按旧版规则末尾补零" : "按旧版规则截断"));
        byte[] adjusted = Arrays.copyOf(value, size); wipe(value); return adjusted;
    }

    private static void validateDataLength(CryptoRequest request, boolean encrypt, int size, byte[] iv, boolean legacy) {
        if (request.mode().equals("GCM")) {
            if (!encrypt && size < GCM_TAG_BYTES) throw invalid("AES_BLOCK_ALIGNMENT_REQUIRED", "GCM密文至少需要16字节认证标签");
        } else if (Set.of("CBC", "ECB").contains(request.mode())) {
            if (!encrypt && (size == 0 || size % AES_BLOCK_BYTES != 0)) throw invalid("AES_BLOCK_ALIGNMENT_REQUIRED", "CBC/ECB密文必须为16字节的正整数倍");
            if (encrypt && request.padding().equals("NONE") && size % AES_BLOCK_BYTES != 0) throw invalid("AES_BLOCK_ALIGNMENT_REQUIRED", "无填充CBC/ECB明文长度必须为16字节的倍数");
        }
        if (encrypt) {
            long encryptedBytes = switch (request.mode()) {
                case "GCM" -> (long) size + GCM_TAG_BYTES;
                case "CBC", "ECB" -> request.padding().equals("PKCS7") ? (long) ((size / AES_BLOCK_BYTES) + 1) * AES_BLOCK_BYTES : size;
                default -> size;
            };
            if (encryptedBytes > MAX_INPUT_BYTES) throw new ApiException(413, "AES_INPUT_TOO_LARGE", "加密结果超过1 MiB密文额度，请减少明文长度");
        } else if (size > MAX_INPUT_BYTES) throw new ApiException(413, "AES_INPUT_TOO_LARGE", "解码后的密文超过1 MiB");
    }

    private static void validateCounterRange(byte[] iv, int inputBytes) {
        long blocks = (inputBytes + 15L) / 16L;
        if (blocks == 0) return;
        BigInteger start = new BigInteger(1, iv);
        if (start.add(BigInteger.valueOf(blocks - 1)).compareTo(MAX_COUNTER) > 0)
            throw invalid("AES_INVALID_IV_LENGTH", "CTR计数器范围会发生回绕，请更换初始计数器");
    }

    private static byte[] crypt(String mode, String configuredPadding, boolean encrypt, byte[] key, byte[] iv, byte[] aad, byte[] input)
            throws GeneralSecurityException {
        boolean gcm = mode.equals("GCM");
        String padding = configuredPadding.equals("PKCS7") ? "PKCS5Padding" : "NoPadding";
        String transformation = "AES/" + mode + "/" + (gcm || mode.equals("CTR") ? "NoPadding" : padding);
        Cipher cipher = Cipher.getInstance(transformation);
        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        if (gcm) cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
        else if (mode.equals("ECB")) cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, secretKey);
        else cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
        if (gcm && aad.length > 0) cipher.updateAAD(aad);
        byte[] result = cipher.doFinal(input);
        if (gcm && !encrypt && result.length > MAX_INPUT_BYTES) { wipe(result); throw new ApiException(413, "AES_INPUT_TOO_LARGE", "解密明文超过1 MiB"); }
        return result;
    }

    private static ApiException decryptFailed() { return new ApiException(400, "AES_DECRYPT_FAILED", "解密失败，请核对密钥、参数及密文完整性"); }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
}
