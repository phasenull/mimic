package dev.phasenull.mimic.assets;

import dev.phasenull.mimic.MimicClient;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.properties.Property;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds each block's state properties by reading a mod jar's compiled classes as data (with ASM; nothing
 * from the jar is loaded or run). A block's properties are what its createBlockStateDefinition adds,
 * including its superclasses' (vanilla ones are read the same way from the game's own classes); a
 * property field is resolved from the static initializer that creates it. Block ids are matched to classes
 * from registration code: a registered name and the block class built next to it.
 * <p>
 * Heuristic: mods that build blocks or properties in unusual ways may be missed (the block then falls
 * back to its blockstate file or name).
 */
public final class BlockPropertyScanner {
	/** One property: its name and its values in the order the game enumerates them. */
	public record Prop(String name, List<String> values) {}

	private static final String BLOCK = "net/minecraft/world/level/block/Block";
	private static final String STATE_METHOD = "createBlockStateDefinition";
	private static final String BUILDER = "net/minecraft/world/level/block/state/StateDefinition$Builder";
	private static final String ID_PATTERN = "[a-z0-9_./-]+(:[a-z0-9_./-]+)?";

	private final Map<String, ClassNode> jarClasses = new HashMap<>();
	private final Map<String, ClassNode> otherClasses = new HashMap<>();
	private final Map<String, List<Prop>> propertiesByClass = new HashMap<>();

	private BlockPropertyScanner() {}

	/** Block id ("ns:path", or just "path" when the jar doesn't say) -> its properties. */
	public static Map<String, List<Prop>> scan(FileSystem jar, Set<String> namespaces) throws IOException {
		BlockPropertyScanner scanner = new BlockPropertyScanner();
		try (Stream<Path> files = Files.walk(jar.getPath("/"))) {
			for (Path file : (Iterable<Path>) files::iterator) {
				if (file.toString().endsWith(".class")) {
					ClassNode node = new ClassNode();
					new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
					scanner.jarClasses.put(node.name, node);
				}
			}
		}
		// Backport mods keep everything in the minecraft namespace.
		String namespace = namespaces.stream().filter(n -> !n.equals("minecraft")).findFirst().orElse("minecraft");
		Map<String, List<Prop>> result = new LinkedHashMap<>();
		scanner.registrations().forEach((name, blockClass) -> {
			String id = name.contains(":") || namespace == null ? name : namespace + ":" + name;
			result.putIfAbsent(id, scanner.propertiesOf(blockClass, new HashSet<>()));
		});
		MimicClient.LOGGER.info("[Assets] Read the block properties of {} blocks from the jar's classes", result.size());
		return result;
	}

	// --- Block id -> class -------------------------------------------------------------------------

	/** Registered names and the block class built for each, from every method of the jar. */
	private Map<String, String> registrations() {
		Map<String, String> found = new LinkedHashMap<>();
		for (ClassNode node : jarClasses.values()) {
			// Block classes build themselves (codecs: CODEC = simpleCodec(X::new)), they don't register blocks.
			if (isBlock(node.name)) {
				continue;
			}
			for (MethodNode method : node.methods) {
				String lastString = null;
				String name = null;
				String blockClass = null;
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s && !s.isEmpty() && s.matches(ID_PATTERN)) {
						lastString = s;
					} else if (insn instanceof FieldInsnNode key && insn.getOpcode() == Opcodes.GETSTATIC && jarClasses.containsKey(key.owner)
							&& (key.desc.endsWith("/ResourceKey;") || key.desc.endsWith("/Identifier;") || key.desc.equals("Ljava/lang/String;"))) {
						// An id kept in a constant: ComputerCraftBlockIds.SPEAKER = key("speaker").
						String constant = constantString(key.owner, key.name);
						if (constant != null) {
							lastString = constant;
						}
					} else if (blockClass == null) {
						// The name is the last string before the block is built: register(MOD_ID, "name", X::new).
						blockClass = constructedBlock(node, insn);
						if (blockClass != null) {
							name = lastString;
						}
					}
					// A statement ends where the registered value is stored, returned or dropped.
					int op = insn.getOpcode();
					if (op == Opcodes.PUTSTATIC || op == Opcodes.PUTFIELD || op == Opcodes.ARETURN || op == Opcodes.POP) {
						// No string: the field it's stored in usually carries the name (WIRED_MODEM_FULL).
						if (name == null && blockClass != null && insn instanceof FieldInsnNode stored) {
							name = stored.name.toLowerCase(Locale.ROOT);
						}
						if (name != null && blockClass != null) {
							found.putIfAbsent(name, blockClass);
						}
						lastString = null;
						name = null;
						blockClass = null;
					}
				}
			}
		}
		return found;
	}

	/** The id string a static constant is built from (the last id-like string before it's stored). */
	private String constantString(String owner, String field) {
		MethodNode clinit = method(jarClasses.get(owner), "<clinit>", "()V");
		if (clinit == null) {
			return null;
		}
		String last = null;
		for (AbstractInsnNode insn : clinit.instructions) {
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s && !s.isEmpty() && s.matches(ID_PATTERN)) {
				last = s;
			} else if (insn instanceof FieldInsnNode put && insn.getOpcode() == Opcodes.PUTSTATIC) {
				if (put.owner.equals(owner) && put.name.equals(field)) {
					return last;
				}
				last = null;
			}
		}
		return null;
	}

	/** The block class an instruction builds: "new X", "X::new", or a lambda in the same class doing either. */
	private String constructedBlock(ClassNode owner, AbstractInsnNode insn) {
		if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.NEW && isBlock(type.desc)) {
			return type.desc;
		}
		if (insn instanceof InvokeDynamicInsnNode indy) {
			for (Object arg : indy.bsmArgs) {
				if (arg instanceof Handle handle) {
					if (handle.getName().equals("<init>") && isBlock(handle.getOwner())) {
						return handle.getOwner();
					}
					if (handle.getOwner().equals(owner.name)) {
						MethodNode lambda = method(owner, handle.getName(), handle.getDesc());
						if (lambda != null) {
							for (AbstractInsnNode inner : lambda.instructions) {
								if (inner instanceof TypeInsnNode t && inner.getOpcode() == Opcodes.NEW && isBlock(t.desc)) {
									return t.desc;
								}
							}
						}
					}
				}
			}
		}
		return null;
	}

	private boolean isBlock(String className) {
		for (int depth = 0; className != null && depth < 20; depth++) {
			if (className.equals(BLOCK)) {
				return true;
			}
			ClassNode node = load(className);
			className = node == null ? null : node.superName;
		}
		return false;
	}

	// --- Class -> properties -----------------------------------------------------------------------

	private List<Prop> propertiesOf(String className, Set<String> seen) {
		if (propertiesByClass.containsKey(className)) {
			return propertiesByClass.get(className);
		}
		List<Prop> props = new ArrayList<>();
		ClassNode node = load(className);
		if (node != null && seen.add(className)) {
			MethodNode method = method(node, STATE_METHOD, "(L" + BUILDER + ";)V");
			if (method == null) {
				if (node.superName != null && !node.superName.equals(BLOCK)) {
					props.addAll(propertiesOf(node.superName, seen));
				}
			} else {
				collect(node, method, props, seen, 0);
			}
		}
		propertiesByClass.put(className, props);
		return props;
	}

	/** Properties a state-definition method adds: fields it reads, its super call, and helpers it calls. */
	private void collect(ClassNode node, MethodNode method, List<Prop> props, Set<String> seen, int depth) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && insn.getOpcode() == Opcodes.GETSTATIC && field.desc.endsWith("Property;")) {
				Prop prop = resolveField(field.owner, field.name, 0);
				if (prop != null && props.stream().noneMatch(p -> p.name().equals(prop.name()))) {
					props.add(prop);
				}
			} else if (insn instanceof MethodInsnNode call) {
				if (call.name.equals(STATE_METHOD) && insn.getOpcode() == Opcodes.INVOKESPECIAL) {
					for (Prop prop : propertiesOf(call.owner, seen)) {
						if (props.stream().noneMatch(p -> p.name().equals(prop.name()))) {
							props.add(prop);
						}
					}
				} else if (depth < 2 && call.owner.equals(node.name) && call.desc.contains(BUILDER)) {
					MethodNode helper = method(node, call.name, call.desc);
					if (helper != null && helper != method) {
						collect(node, helper, props, seen, depth + 1);
					}
				}
			}
		}
	}

	/** A static Property field: read from the game when it's a loaded class, else from its static initializer. */
	private Prop resolveField(String owner, String name, int depth) {
		if (depth > 4) {
			return null;
		}
		if (!jarClasses.containsKey(owner)) {
			return runtimeField(owner, name);
		}
		ClassNode node = jarClasses.get(owner);
		// Read through a subclass: the field is declared further up (SpeakerBlock.FACING).
		if (node.fields.stream().noneMatch(f -> f.name.equals(name))) {
			return node.superName == null ? null : resolveField(node.superName, name, depth + 1);
		}
		MethodNode clinit = method(node, "<clinit>", "()V");
		if (clinit == null) {
			return null;
		}
		for (AbstractInsnNode insn : clinit.instructions) {
			if (insn instanceof FieldInsnNode put && insn.getOpcode() == Opcodes.PUTSTATIC && put.owner.equals(owner) && put.name.equals(name)) {
				return fromCreation(put.getPrevious(), depth);
			}
		}
		return null;
	}

	/** The property built by the instructions ending at {@code last} (a create(...) call or another field). */
	private Prop fromCreation(AbstractInsnNode last, int depth) {
		if (last instanceof FieldInsnNode alias && last.getOpcode() == Opcodes.GETSTATIC) {
			return resolveField(alias.owner, alias.name, depth + 1);
		}
		if (!(last instanceof MethodInsnNode call) || !call.name.equals("create")) {
			return null;
		}
		List<Object> args = new ArrayList<>();
		for (AbstractInsnNode insn = call.getPrevious(); insn != null && args.size() < 6; insn = insn.getPrevious()) {
			Object constant = constant(insn);
			if (constant != null) {
				args.add(0, constant);
			}
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
				break;
			}
		}
		if (args.isEmpty() || !(args.getFirst() instanceof String propName)) {
			return null;
		}
		if (call.owner.endsWith("BooleanProperty")) {
			return new Prop(propName, List.of("true", "false"));
		}
		if (call.owner.endsWith("IntegerProperty") && args.size() >= 3 && args.get(1) instanceof Integer min && args.get(2) instanceof Integer max) {
			List<String> values = new ArrayList<>();
			for (int i = min; i <= max; i++) {
				values.add(Integer.toString(i));
			}
			return new Prop(propName, values);
		}
		if (call.owner.endsWith("EnumProperty") && args.size() >= 2 && args.get(1) instanceof Type enumType) {
			List<String> values = enumValues(enumType.getInternalName());
			return values.isEmpty() ? null : new Prop(propName, values);
		}
		return null;
	}

	private static Object constant(AbstractInsnNode insn) {
		if (insn instanceof LdcInsnNode ldc) {
			return ldc.cst;
		}
		if (insn instanceof IntInsnNode push && (insn.getOpcode() == Opcodes.BIPUSH || insn.getOpcode() == Opcodes.SIPUSH)) {
			return push.operand;
		}
		int op = insn.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
			return op - Opcodes.ICONST_0;
		}
		return null;
	}

	/** Enum constants, as the property names them: serialized names for game enums, lowercase names otherwise. */
	private List<String> enumValues(String enumClass) {
		List<String> values = new ArrayList<>();
		ClassNode node = jarClasses.get(enumClass);
		if (node != null) {
			// Constants in declaration order; a constant's name is the second string passed to its constructor
			// (the first is the Java name), as StringRepresentable enums do, else its lowercase Java name.
			Set<String> constants = new HashSet<>();
			for (FieldNode field : node.fields) {
				if ((field.access & Opcodes.ACC_ENUM) != 0) {
					constants.add(field.name);
				}
			}
			MethodNode clinit = method(node, "<clinit>", "()V");
			List<String> strings = new ArrayList<>();
			if (clinit != null) {
				for (AbstractInsnNode insn : clinit.instructions) {
					if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String str) {
						strings.add(str);
					} else if (insn instanceof FieldInsnNode put && insn.getOpcode() == Opcodes.PUTSTATIC && constants.contains(put.name)) {
						values.add(strings.size() >= 2 ? strings.get(1) : put.name.toLowerCase(Locale.ROOT));
						strings.clear();
					}
				}
			}
			if (values.size() != constants.size()) {
				values.clear();
				for (FieldNode field : node.fields) {
					if ((field.access & Opcodes.ACC_ENUM) != 0) {
						values.add(field.name.toLowerCase(Locale.ROOT));
					}
				}
			}
			return values;
		}
		try {
			Class<?> type = Class.forName(enumClass.replace('/', '.'), false, BlockPropertyScanner.class.getClassLoader());
			for (Object constant : type.getEnumConstants()) {
				values.add(constant instanceof StringRepresentable s ? s.getSerializedName() : ((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			MimicClient.LOGGER.debug("[Assets] Unknown enum {}", enumClass, e);
		}
		return values;
	}

	/** A property field of a class the game has loaded (vanilla or a library), read by reflection. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Prop runtimeField(String owner, String name) {
		try {
			Field field = null;
			for (Class<?> type = Class.forName(owner.replace('/', '.'), false, BlockPropertyScanner.class.getClassLoader());
					type != null && field == null; type = type.getSuperclass()) {
				try {
					field = type.getDeclaredField(name);
				} catch (NoSuchFieldException e) {
					// Declared further up.
				}
			}
			if (field == null) {
				return null;
			}
			field.setAccessible(true);
			if (field.get(null) instanceof Property property) {
				List<String> values = new ArrayList<>();
				for (Object value : property.getPossibleValues()) {
					values.add(property.getName((Comparable) value));
				}
				return new Prop(property.getName(), values);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			MimicClient.LOGGER.debug("[Assets] Unknown property field {}.{}", owner, name, e);
		}
		return null;
	}

	private ClassNode load(String className) {
		ClassNode node = jarClasses.get(className);
		if (node != null || otherClasses.containsKey(className)) {
			return node != null ? node : otherClasses.get(className);
		}
		try (InputStream in = BlockPropertyScanner.class.getClassLoader().getResourceAsStream(className + ".class")) {
			if (in != null) {
				node = new ClassNode();
				new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			}
		} catch (IOException e) {
			node = null;
		}
		otherClasses.put(className, node);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) {
				return method;
			}
		}
		return null;
	}
}
