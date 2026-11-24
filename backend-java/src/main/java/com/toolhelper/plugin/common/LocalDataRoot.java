package com.toolhelper.plugin.common;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.SecurePaths;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import org.springframework.stereotype.Component;

/** 服务级数据根和唯一文件锁。任务与插件安装共享此对象，不重复获取根锁。 */
@Component
public class LocalDataRoot implements AutoCloseable {
    private final Path root;
    private final FileChannel channel;
    private final FileLock lock;
    private volatile String recoveryProblem;

    public LocalDataRoot(ToolHelperProperties properties) throws IOException {
        root = properties.root();
        SecurePaths.createDirectories(root);
        SecurePaths.createDirectories(tasksRoot());
        Path file = root.resolve("service.lock");
        SecurePaths.checkAncestors(file);
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("已有服务管理该数据目录");
        } catch (IOException | OverlappingFileLockException error) {
            channel.close();
            throw new IOException("已有服务管理该数据目录或无法获取锁", error);
        }
    }

    public Path root() { return root; }
    public Path tasksRoot() { return root.resolve("tasks"); }
    public void recoveryRequired(String message) { recoveryProblem = message; }
    public String recoveryProblem() { return recoveryProblem; }
    @PreDestroy @Override public void close() throws IOException {
        if (lock.isValid()) lock.release();
        channel.close();
    }
}
