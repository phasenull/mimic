package dev.phasenull.mimic.bypass;

import java.util.List;

/** ViaVersion lookups shared by Mimic's id tables, through reflection (Via is optional at runtime). */
public final class ViaBridge {
	private ViaBridge() {}

	/** The mapping data of the first translation step (the server's own version), or null without Via. */
	public static Object firstMappingData() throws ReflectiveOperationException {
		List<Object> protocols = ServerStateTable.clientboundProtocols();
		for (Object protocol : protocols) {
			Object data = ServerStateTable.mappingData(protocol);
			if (data != null) {
				return data;
			}
		}
		return null;
	}

	/** The size of one of a mapping step's tables (e.g. "getBlockStateMappings"), 0 if it has none. */
	public static int size(Object mapping, String table) throws ReflectiveOperationException {
		Object mappings = ServerStateTable.method(mapping, table).invoke(mapping);
		return mappings == null ? 0 : (int) ServerStateTable.method(mappings, "size").invoke(mappings);
	}
}
