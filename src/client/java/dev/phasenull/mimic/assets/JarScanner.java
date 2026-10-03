package dev.phasenull.mimic.assets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Looks through a mod jar for things malware does and ordinary mods rarely need: starting programs,
 * loading code from bytes or the network, reading browser, Discord or launcher files, known malware
 * markers, native libraries. Classes are read as data with ASM (nothing is loaded or run), nested jars too.
 * <p>
 * A heuristic, not an antivirus: a clean report doesn't prove a jar is safe, and some findings (network
 * access, reflection) are common in legitimate mods. Severity says how unusual a finding is for a mod.
 */
public final class JarScanner {
	public enum Severity { HIGH, MEDIUM, LOW }

	/** One kind of finding, with where it was seen (up to a few classes). */
	public record Finding(Severity severity, String what, List<String> where) {}

	public record Report(String jar, int classes, int nestedJars, List<Finding> findings) {
		public long count(Severity severity) {
			return findings.stream().filter(f -> f.severity() == severity).count();
		}

		/** "2 high, 1 medium" or "nothing suspicious found". */
		public String summary() {
			List<String> parts = new ArrayList<>();
			for (Severity s : Severity.values()) {
				long n = count(s);
				if (n > 0) {
					parts.add(n + " " + s.name().toLowerCase(Locale.ROOT));
				}
			}
			return parts.isEmpty() ? "nothing suspicious found" : String.join(", ", parts);
		}
	}

	private static final int MAX_NESTING = 3;
	private static final int MAX_PLACES = 5;

	/** Method calls by owner and name (a null name matches any method of the owner). */
	private record Call(String owner, String name, Severity severity, String what) {}

	private static final List<Call> CALLS = List.of(
		new Call("java/lang/Runtime", "exec", Severity.HIGH, "Starts other programs (Runtime.exec)"),
		new Call("java/lang/ProcessBuilder", "start", Severity.HIGH, "Starts other programs (ProcessBuilder)"),
		new Call("java/lang/ClassLoader", "defineClass", Severity.HIGH, "Defines classes from raw bytes"),
		new Call("java/security/SecureClassLoader", "defineClass", Severity.HIGH, "Defines classes from raw bytes"),
		new Call("java/lang/invoke/MethodHandles$Lookup", "defineClass", Severity.HIGH, "Defines classes from raw bytes"),
		new Call("java/lang/invoke/MethodHandles$Lookup", "defineHiddenClass", Severity.HIGH, "Defines hidden classes from raw bytes"),
		new Call("sun/misc/Unsafe", "defineClass", Severity.HIGH, "Defines classes through Unsafe"),
		new Call("java/net/URLClassLoader", "<init>", Severity.HIGH, "Loads code from URLs or extra jars (URLClassLoader)"),
		new Call("java/lang/System", "load", Severity.MEDIUM, "Loads a native library from a path"),
		new Call("java/lang/System", "loadLibrary", Severity.MEDIUM, "Loads a native library"),
		new Call("java/util/Base64$Decoder", "decode", Severity.LOW, "Decodes Base64 (on its own common; with class loading a red flag)"),
		new Call("javax/crypto/Cipher", "doFinal", Severity.LOW, "Encrypts or decrypts data"),
		new Call("java/net/Socket", "<init>", Severity.MEDIUM, "Opens raw network sockets"),
		new Call("java/net/http/HttpClient", "send", Severity.LOW, "Makes HTTP requests"),
		new Call("java/net/URL", "openConnection", Severity.LOW, "Makes network connections (URL)"),
		new Call("java/net/URL", "openStream", Severity.LOW, "Downloads from URLs"),
		new Call("java/awt/Robot", null, Severity.MEDIUM, "Controls the mouse/keyboard or takes screenshots (Robot)"),
		new Call("java/awt/datatransfer/Clipboard", "getContents", Severity.MEDIUM, "Reads the clipboard"),
		new Call("com/sun/jna/Native", null, Severity.MEDIUM, "Calls native OS functions (JNA)"),
		new Call("java/lang/instrument/Instrumentation", null, Severity.MEDIUM, "Uses a Java agent to change other classes"));

	/** Strings in a class (lower-cased match) and what they suggest. */
	private record Marker(String text, Severity severity, String what) {}

	private static final List<Marker> STRINGS = List.of(
		// Fractureiser and its relatives.
		new Marker("dev/neko/nekoclient", Severity.HIGH, "Known malware (Fractureiser)"),
		new Marker("dev.neko.nekoclient", Severity.HIGH, "Known malware (Fractureiser)"),
		new Marker("dev/neko/nekoinjector", Severity.HIGH, "Known malware (Fractureiser)"),
		new Marker("85.217.144.130", Severity.HIGH, "Known malware server address (Fractureiser)"),
		new Marker("107.189.3.101", Severity.HIGH, "Known malware server address (Fractureiser)"),
		new Marker("files-8ie.pages.dev", Severity.HIGH, "Known malware server address (Fractureiser)"),
		// Credential and session stealing.
		new Marker("local storage\\leveldb", Severity.HIGH, "Reads browser or Discord local storage (tokens)"),
		new Marker("local storage/leveldb", Severity.HIGH, "Reads browser or Discord local storage (tokens)"),
		new Marker("login data", Severity.HIGH, "Reads saved browser passwords"),
		new Marker("\\network\\cookies", Severity.HIGH, "Reads browser cookies"),
		new Marker("/network/cookies", Severity.HIGH, "Reads browser cookies"),
		new Marker("discord.com/api/webhooks", Severity.HIGH, "Sends data to a Discord webhook"),
		new Marker("discordapp.com/api/webhooks", Severity.HIGH, "Sends data to a Discord webhook"),
		new Marker("launcher_accounts", Severity.HIGH, "Reads Minecraft launcher accounts"),
		new Marker("launcher_profiles", Severity.MEDIUM, "Reads Minecraft launcher profiles"),
		new Marker("accesstoken", Severity.MEDIUM, "Mentions access tokens"),
		new Marker("exodus", Severity.MEDIUM, "Mentions a crypto wallet (Exodus)"),
		new Marker("metamask", Severity.MEDIUM, "Mentions a crypto wallet (MetaMask)"),
		new Marker("wallet.dat", Severity.HIGH, "Looks for crypto wallet files"),
		new Marker("api.telegram.org/bot", Severity.HIGH, "Sends data to a Telegram bot"),
		// Persistence and system changes.
		new Marker("\\microsoft\\windows\\start menu\\programs\\startup", Severity.HIGH, "Writes to the Windows startup folder"),
		new Marker("currentversion\\run", Severity.HIGH, "Adds a Windows autostart registry entry"),
		new Marker("schtasks", Severity.HIGH, "Creates scheduled tasks"),
		new Marker("powershell", Severity.MEDIUM, "Runs PowerShell"),
		new Marker("cmd.exe", Severity.MEDIUM, "Runs the Windows command prompt"),
		new Marker("/bin/sh", Severity.MEDIUM, "Runs a Unix shell"),
		new Marker("pastebin.com/raw", Severity.MEDIUM, "Downloads text from Pastebin (often used to fetch payloads)"),
		new Marker("ngrok.io", Severity.MEDIUM, "Connects to an ngrok tunnel"));

	private JarScanner() {}

	public static Report scan(Path jar) throws IOException {
		try (InputStream in = Files.newInputStream(jar)) {
			Scan scan = new Scan();
			scan.zip(in, "", 0);
			return scan.report(jar.getFileName().toString());
		}
	}

	public static void save(Report report, Path file) throws IOException {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Files.createDirectories(file.getParent());
		Files.writeString(file, gson.toJson(report));
	}

	public static Report load(Path file) {
		try {
			return Files.exists(file) ? new Gson().fromJson(Files.readString(file), Report.class) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static final class Scan {
		private final Map<String, Severity> severities = new LinkedHashMap<>();
		private final Map<String, List<String>> places = new TreeMap<>();
		private int classes;
		private int nestedJars;
		private int shortNames;

		void add(Severity severity, String what, String where) {
			severities.merge(what, severity, (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
			List<String> list = places.computeIfAbsent(what, k -> new ArrayList<>());
			if (list.size() < MAX_PLACES && !list.contains(where)) {
				list.add(where);
			}
		}

		void zip(InputStream in, String prefix, int depth) throws IOException {
			ZipInputStream zip = new ZipInputStream(in);
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				String lower = name.toLowerCase(Locale.ROOT);
				if (entry.isDirectory()) {
					continue;
				}
				if (lower.endsWith(".class")) {
					classes++;
					classFile(zip.readAllBytes(), prefix + name);
				} else if (lower.endsWith(".jar") && depth < MAX_NESTING) {
					nestedJars++;
					zip(new ByteArrayInputStream(zip.readAllBytes()), prefix + name + "!/", depth + 1);
				} else if (lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib") || lower.endsWith(".exe")) {
					add(Severity.MEDIUM, "Contains native code or programs (" + lower.substring(lower.lastIndexOf('.')) + ")", prefix + name);
				} else if (lower.endsWith(".bat") || lower.endsWith(".ps1") || lower.endsWith(".vbs") || lower.endsWith(".sh")) {
					add(Severity.MEDIUM, "Contains scripts (" + lower.substring(lower.lastIndexOf('.')) + ")", prefix + name);
				}
			}
		}

		void classFile(byte[] bytes, String path) {
			String simple = path.substring(path.lastIndexOf('/') + 1).replace(".class", "");
			if (simple.length() <= 2 && !simple.startsWith("package-info")) {
				shortNames++;
			}
			try {
				new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
					private String className = path;

					@Override
					public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
						className = name.replace('/', '.');
						if ("java/net/URLClassLoader".equals(superName) || "java/lang/ClassLoader".equals(superName)) {
							add(Severity.MEDIUM, "Has its own class loader", className);
						}
					}

					@Override
					public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
						return new MethodVisitor(Opcodes.ASM9) {
							@Override
							public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
								for (Call call : CALLS) {
									if (call.owner().equals(owner) && (call.name() == null || call.name().equals(method))) {
										add(call.severity(), call.what(), className);
									}
								}
							}

							@Override
							public void visitLdcInsn(Object value) {
								if (value instanceof String text) {
									string(text, className);
								}
							}

							@Override
							public void visitInvokeDynamicInsn(String n, String d, Handle bootstrap, Object... args) {
								for (Object arg : args) {
									if (arg instanceof String text) {
										string(text, className);
									}
								}
							}
						};
					}
				}, ClassReader.SKIP_FRAMES);
			} catch (RuntimeException e) {
				add(Severity.LOW, "Has classes that couldn't be read (damaged or deliberately malformed)", path);
			}
		}

		void string(String text, String where) {
			String lower = text.toLowerCase(Locale.ROOT);
			for (Marker marker : STRINGS) {
				if (lower.contains(marker.text())) {
					add(marker.severity(), marker.what(), where);
				}
			}
		}

		Report report(String jar) {
			if (classes > 20 && shortNames * 3 > classes) {
				add(Severity.LOW, "Many classes have 1-2 letter names (obfuscated)", shortNames + " of " + classes + " classes");
			}
			// Raw bytes turned into classes, together with decoding or downloads, is the usual loader pattern.
			if (severities.containsKey("Defines classes from raw bytes")
					&& (severities.containsKey("Decodes Base64 (on its own common; with class loading a red flag)")
					|| severities.containsKey("Downloads from URLs") || severities.containsKey("Makes HTTP requests"))) {
				add(Severity.HIGH, "Loads code it decoded or downloaded (a common malware loader pattern)", "whole jar");
			}
			List<Finding> findings = new ArrayList<>();
			severities.forEach((what, severity) -> findings.add(new Finding(severity, what, List.copyOf(places.get(what)))));
			findings.sort(Comparator.comparing(Finding::severity));
			return new Report(jar, classes, nestedJars, findings);
		}
	}
}
