package com.toolhelper.crypto.aes;

public final class AesModels {
    private AesModels() {}

    public record CryptoRequest(String profile, String mode, Integer keyBits, String padding, String charset,
            String key, String keyFormat, String ivMode, String iv, String ivFormat, String aad,
            String aadFormat, String input, String inputFormat, String outputFormat,
            Boolean manualRiskConfirmed) {}

    public record RandomRequest(String kind, Integer keyBits, String format) {}
}
