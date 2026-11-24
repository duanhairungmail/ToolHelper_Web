import { request } from '../utils/request.js'
import { createPluginApi } from './pluginClient.js'
export const createRemotePluginApi = (transport = request) => createPluginApi('electerm', transport)
export const remotePluginApi = createRemotePluginApi()
