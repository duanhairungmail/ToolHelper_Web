package com.toolhelper.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

public final class SecurePaths {
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private SecurePaths() {}

    /** 同时按 Windows 语义校验，不能让 Linux 上通过的测试放过盘符、ADS 和大小写冲突。 */
    public static Path archivePath(Path root, String name, int maxDepth) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.length() > 220) {
            throw unsafe("压缩包包含非受支持路径");
        }
        String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        String[] segments = clean.split("/", -1);
        if (segments.length > maxDepth) throw unsafe("压缩包路径层级超限");
        for (String segment : segments) {
            String base = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ") || RESERVED.contains(base)
                    || segment.chars().anyMatch(c -> c < 32 || "<>\"|?*".indexOf(c) >= 0)) {
                throw unsafe("压缩包包含不安全文件名");
            }
        }
        Path target = root.resolve(clean).normalize();
        requireInside(root, target);
        return target;
    }

    public static void requireInside(Path root, Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw unsafe("目录超出本服务管理范围");
        }
    }

    public static void checkAncestors(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) checkNode(current);
        }
    }

    public static void checkNode(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        // Windows JDK 的 isOther() 包含 reparse point（包括 junction）；不能查询不存在的 dos:reparsePoint 属性。
        if (attributes.isSymbolicLink() || attributes.isOther()) throw unsafe("拒绝符号链接、重解析点或特殊文件");
    }

    public static void createDirectories(Path path) throws IOException {
        checkAncestors(path);
        Files.createDirectories(path);
        checkAncestors(path);
    }

    public static void deleteTree(Path managedRoot, Path target) throws IOException {
        requireInside(managedRoot, target);
        if (managedRoot.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw unsafe("拒绝删除数据根目录");
        }
        checkAncestors(target);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                checkNode(dir);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkNode(file);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static String sha256(Path file) throws IOException {
        checkAncestors(file);
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("任务已中断");
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String hash(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public static ApiException unsafe(String message) { return new ApiException(422, "ARCHIVE_UNSAFE", message); }
}
