package com.toolhelper.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonFiles {
    private final JsonMapper mapper;

    public JsonFiles(JsonMapper mapper) { this.mapper = mapper; }

    public <T> T read(Path path, Class<T> type) throws IOException {
        SecurePaths.checkAncestors(path);
        if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("元数据文件超限");
        return mapper.readValue(Files.readAllBytes(path), type);
    }

    /** 先持久化临时文件再原子替换；文件系统不支持时直接失败，不伪造事务保证。 */
    public void write(Path path, Object value) throws IOException {
        SecurePaths.createDirectories(path.getParent());
        SecurePaths.checkAncestors(path);
        byte[] bytes = mapper.writeValueAsBytes(value);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        SecurePaths.checkAncestors(temporary);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
