package com.toolhelper.kylin;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** 仅在项目数据根下持久化模块记录；拒绝符号链接和意外覆盖。 */
public final class KylinFiles {
    private KylinFiles() {}
    public static Path directory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Kylin data path is a symbolic link");
            if (!Files.exists(cursor)) Files.createDirectory(cursor);
            if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Kylin data path is not a directory");
        }
        return absolute;
    }
    public static byte[] read(Path path, int maximum) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maximum)
            throw new IOException("Kylin file is missing, linked or oversized");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Kylin file is oversized");
            return bytes;
        }
    }
    public static void writeNew(Path path, byte[] bytes) throws IOException {
        directory(path.getParent());
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
