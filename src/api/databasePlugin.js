import { request } from '../utils/request.js'
import { createPluginApi } from './pluginClient.js'
export const createDatabasePluginApi = (transport = request) => createPluginApi('dbx', transport)
export const databasePluginApi = createDatabasePluginApi()
