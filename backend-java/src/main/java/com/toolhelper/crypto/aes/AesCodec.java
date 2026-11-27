package com.toolhelper.crypto.aes;

import com.toolhelper.support.ApiException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

final class AesCodec {
    private static final Pattern HEX_SEPARATORS = Pattern.compile("[ \\t\\r\\n]");
    private static final Pattern LEGACY_HEX_SEPARATORS = Pattern.compile("[ \\t\\r\\n-]");
    private static final Set<String> CHARSETS = Set.of("UTF-8", "UTF-16LE", "UTF-32LE", "US-ASCII", "GBK", "ISO-8859-1");

    private AesCodec() {}

    static Charset charset(String name) {
        if (name == null || !CHARSETS.contains(name)) throw invalid("AES_INVALID_PARAMETERS", "请选择支持的文本编码");
        try {
            Charset charset = Charset.forName(name);
            if (!charset.canEncode()) throw invalid("AES_UNSUPPORTED_CAPABILITY", "当前运行环境不支持所选文本编码");
            return charset;
        } catch (UnsupportedCharsetException | IllegalCharsetNameException failure) {
            throw invalid("AES_UNSUPPORTED_CAPABILITY", "当前运行环境不支持所选文本编码");
        }
    }

    static byte[] encodeText(String value, Charset charset) {
        try {
            ByteBuffer buffer = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (CharacterCodingException failure) {
            throw invalid("AES_INVALID_ENCODING", "文本包含当前编码无法表示的字符");
        }
    }

    static String decodeText(byte[] bytes, Charset charset) {
        try { return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw invalid("AES_TEXT_DECODE_FAILED", "结果无法按所选文本编码无损解码，请改用 HEX 或 BASE64"); }
    }

    static byte[] decode(String value, String format, Charset charset, boolean legacyHex) {
        if (value == null) throw invalid("AES_INVALID_PARAMETERS", "缺少待解码内容");
        if (format == null) throw invalid("AES_INVALID_PARAMETERS", "缺少数据格式");
        try {
            return switch (format) {
                case "TEXT" -> encodeText(value, charset);
                case "HEX" -> decodeHex(value, legacyHex);
                case "BASE64" -> decodeBase64(value);
                default -> throw invalid("AES_INVALID_ENCODING", "数据格式仅支持 TEXT、HEX 或 BASE64");
            };
        } catch (IllegalArgumentException failure) {
            throw invalid("AES_INVALID_ENCODING", "HEX 或 BASE64 编码不合法");
        }
    }

    private static byte[] decodeHex(String value, boolean legacy) {
        String text = value;
        if (legacy) text = text.trim();
        if (text.startsWith("0x") || text.startsWith("0X")) text = text.substring(2);
        text = (legacy ? LEGACY_HEX_SEPARATORS : HEX_SEPARATORS).matcher(text).replaceAll("");
        if ((text.length() & 1) != 0 || !text.matches("[0-9a-fA-F]*")) throw invalid("AES_INVALID_ENCODING", "HEX必须由成对的十六进制字符组成");
        return HexFormat.of().parseHex(text);
    }

    private static byte[] decodeBase64(String value) {
        String text = HEX_SEPARATORS.matcher(value).replaceAll("");
        byte[] bytes = Base64.getDecoder().decode(text);
        if (!Base64.getEncoder().encodeToString(bytes).equals(text)) throw invalid("AES_INVALID_ENCODING", "BASE64必须使用规范的基本字母表及padding");
        return bytes;
    }

    static String encode(byte[] bytes, String format, Charset charset) {
        if (format == null) throw invalid("AES_INVALID_PARAMETERS", "缺少输出格式");
        return switch (format) {
            case "HEX" -> HexFormat.of().formatHex(bytes);
            case "BASE64" -> Base64.getEncoder().encodeToString(bytes);
            case "TEXT" -> decodeText(bytes, charset);
            default -> throw invalid("AES_INVALID_PARAMETERS", "输出格式仅支持 TEXT、HEX 或 BASE64");
        };
    }

    static ApiException invalid(String code, String message) { return new ApiException(400, code, message); }
}
