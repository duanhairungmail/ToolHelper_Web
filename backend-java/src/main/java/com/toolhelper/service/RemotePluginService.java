package com.toolhelper.service;
import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.plugin.common.ManagedPluginService;
import com.toolhelper.plugin.electerm.*;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.plugin.electerm.PluginModels.*;

@Service
public class RemotePluginService extends ManagedPluginService {
    private final ProcessAdapter remoteProcesses;
    public RemotePluginService(InstallationStore store, ReleaseClient releases, ArchiveExtractor archives,
            ProcessAdapter processes, TaskCoordinator tasks, ToolHelperProperties properties, JsonMapper json) {
        super("electerm", properties.supportedVersions(), store, releases, archives, processes, tasks, properties, json);
        remoteProcesses = processes;
    }
    @Override protected ConnectionRequest normalizeConnection(ConnectionRequest request) { return ProcessAdapter.validate(request); }
    @Override protected Map<String, Object> dispatchConnection(Path executable, Path data, ConnectionRequest request) throws Exception {
        return remoteProcesses.connect(executable, data, request);
    }
}
