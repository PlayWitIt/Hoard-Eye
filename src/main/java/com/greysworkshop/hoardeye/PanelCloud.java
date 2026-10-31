package com.greysworkshop.hoardeye;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Backups in Google Drive and OneDrive, for the mods panel. Signing in happens in the browser (OAuth with PKCE, the
 * answer caught on a one-off local port), and only the sign-in that lets it keep its own files is asked for: Google
 * Drive's "files this app made" (into a "PlayWit backups" folder) and OneDrive's app folder (Apps/PlayWit). What it
 * keeps to stay signed in is in config/playwit/cloud.json, which is never put in a backup. Plain Java and Gson only,
 * so each PlayWit mod can carry the same copy.
 */
final class PanelCloud {
	private PanelCloud() {}

	/** PlayWit Creations' app registrations. Empty until registered: then the service shows as not set up yet. */
	private static final String GOOGLE_CLIENT_ID = "";
	/** Google's "desktop app" clients have a secret that, by Google's own rules, isn't secret in an installed app. */
	private static final String GOOGLE_CLIENT_SECRET = "";
	private static final String MICROSOFT_CLIENT_ID = "";

	enum Service {
		GOOGLE("Google Drive", "google"), ONEDRIVE("OneDrive", "onedrive");

		final String label, key;

		Service(String label, String key) {
			this.label = label;
			this.key = key;
		}
	}

	/** A backup in the cloud: newest first when listed. */
	record Remote(String id, String name, long size, long time) {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
	private static final String FOLDER = "PlayWit backups";
	/** Upload pieces: a multiple of both Google's 256 KiB and OneDrive's 320 KiB. */
	private static final int CHUNK = 320 * 1024 * 32;
	/** How long the browser sign-in may take. */
	private static final int SIGN_IN_SECONDS = 300;

	/** Opens a web page in the browser. The self-test swaps in one that answers by itself. */
	static Consumer<String> browser = url -> net.minecraft.util.Util.getOperatingSystem().open(URI.create(url));

	private static final Map<Service, String> accessTokens = new HashMap<>();
	private static final Map<Service, Long> accessUntil = new HashMap<>();

	// ---- Settings ----

	static boolean configured(Service s) {
		return !clientId(s).isEmpty();
	}

	private static String clientId(Service s) {
		String test = System.getProperty("playwit.cloud.client");
		if (test != null) return test;
		return s == Service.GOOGLE ? GOOGLE_CLIENT_ID : MICROSOFT_CLIENT_ID;
	}

	static boolean connected(Service s) {
		return !refreshToken(s).isEmpty();
	}

	/** Who it's signed in as ("name@example.com"), if known. */
	static String account(Service s) {
		JsonObject o = saved().getAsJsonObject(s.key);
		return o != null && o.has("account") ? o.get("account").getAsString() : "";
	}

	private static Path file() {
		return PanelData.dir().resolve("cloud.json");
	}

	private static synchronized JsonObject saved() {
		try {
			if (Files.exists(file())) return JsonParser.parseString(Files.readString(file())).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			LoggerFactory.getLogger("playwit").warn("Could not read {}", file(), e);
		}
		return new JsonObject();
	}

	private static synchronized void save(JsonObject o) throws IOException {
		Files.createDirectories(file().getParent());
		Path temp = file().resolveSibling("cloud.json.tmp");
		Files.writeString(temp, GSON.toJson(o));
		Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING);
	}

	private static String refreshToken(Service s) {
		JsonObject o = saved().getAsJsonObject(s.key);
		return o != null && o.has("refresh") ? o.get("refresh").getAsString() : "";
	}

	private static synchronized void remember(Service s, String key, String value) throws IOException {
		JsonObject all = saved();
		JsonObject o = all.has(s.key) ? all.getAsJsonObject(s.key) : new JsonObject();
		o.addProperty(key, value);
		all.add(s.key, o);
		save(all);
	}

	static synchronized void disconnect(Service s) throws IOException {
		JsonObject all = saved();
		all.remove(s.key);
		save(all);
		accessTokens.remove(s);
		accessUntil.remove(s);
	}

	// ---- Addresses (the self-test points them at a stand-in server) ----

	private static String at(String real) {
		String base = System.getProperty("playwit.cloud.base");
		if (base == null) return real;
		URI u = URI.create(real);
		return base + "/" + u.getHost() + u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
	}

	private static String authUrl(Service s) {
		return at(s == Service.GOOGLE ? "https://accounts.google.com/o/oauth2/v2/auth" : "https://login.microsoftonline.com/common/oauth2/v2.0/authorize");
	}

	private static String tokenUrl(Service s) {
		return at(s == Service.GOOGLE ? "https://oauth2.googleapis.com/token" : "https://login.microsoftonline.com/common/oauth2/v2.0/token");
	}

	private static String scope(Service s) {
		return s == Service.GOOGLE ? "https://www.googleapis.com/auth/drive.file" : "Files.ReadWrite.AppFolder offline_access User.Read";
	}

	// ---- Signing in ----

	/**
	 * Signs in through the browser and keeps what's needed to stay signed in. Blocks until it's done (up to five
	 * minutes): call it off the game's thread. Throws with a message for the player if it didn't work.
	 */
	static void connect(Service s) throws IOException {
		if (!configured(s)) throw new IOException(s.label + " isn't set up in this version yet");
		String verifier = random(48), state = random(16);
		String challenge;
		try {
			challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
		// "localhost" can lead a browser to either loopback address: listen on both.
		try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); ServerSocket six = alsoOnSix(server.getLocalPort())) {
			String redirect = (s == Service.GOOGLE ? "http://127.0.0.1:" : "http://localhost:") + server.getLocalPort();
			Map<String, String> q = new LinkedHashMap<>();
			q.put("client_id", clientId(s));
			q.put("response_type", "code");
			q.put("redirect_uri", redirect);
			q.put("scope", scope(s));
			q.put("state", state);
			q.put("code_challenge", challenge);
			q.put("code_challenge_method", "S256");
			if (s == Service.GOOGLE) {
				q.put("access_type", "offline");
				q.put("prompt", "consent");
			} else {
				q.put("prompt", "select_account");
			}
			browser.accept(authUrl(s) + "?" + form(q));
			Map<String, String> answer = awaitRedirect(server, six, state);
			if (answer.containsKey("error")) throw new IOException("Sign-in was cancelled (" + answer.get("error") + ")");
			String code = answer.get("code");
			if (code == null) throw new IOException("The sign-in page didn't send a code back");

			Map<String, String> form = new LinkedHashMap<>();
			form.put("client_id", clientId(s));
			if (s == Service.GOOGLE && !GOOGLE_CLIENT_SECRET.isEmpty()) form.put("client_secret", GOOGLE_CLIENT_SECRET);
			form.put("grant_type", "authorization_code");
			form.put("code", code);
			form.put("redirect_uri", redirect);
			form.put("code_verifier", verifier);
			if (s == Service.ONEDRIVE) form.put("scope", scope(s));
			JsonObject token = postForm(tokenUrl(s), form);
			if (!token.has("refresh_token")) throw new IOException(s.label + " didn't allow staying signed in");
			remember(s, "refresh", token.get("refresh_token").getAsString());
			useToken(s, token);
			try {
				remember(s, "account", whoAmI(s));
			} catch (IOException | RuntimeException e) {
				// Only shown on the panel.
			}
		}
	}

	/** The same port on the IPv6 loopback, or null if this computer hasn't one (or it's taken). */
	private static ServerSocket alsoOnSix(int port) {
		try {
			return new ServerSocket(port, 4, InetAddress.getByName("::1"));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Waits for the browser to come back to the local port with the answer, and tells the browser it's done. */
	private static Map<String, String> awaitRedirect(ServerSocket server, ServerSocket six, String state) throws IOException {
		long deadline = System.currentTimeMillis() + SIGN_IN_SECONDS * 1000L;
		server.setSoTimeout(150);
		if (six != null) six.setSoTimeout(150);
		while (true) {
			if (System.currentTimeMillis() > deadline) throw new IOException("Sign-in took too long; try again");
			Socket accepted = accept(server);
			if (accepted == null && six != null) accepted = accept(six);
			if (accepted == null) continue;
			try (Socket socket = accepted) {
				socket.setSoTimeout(10_000);
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				String line = in.readLine();
				if (line == null) continue;
				// "GET /?code=...&state=... HTTP/1.1"
				String[] parts = line.split(" ");
				String target = parts.length > 1 ? parts[1] : "/";
				int qm = target.indexOf('?');
				Map<String, String> params = qm < 0 ? Map.of() : parseForm(target.substring(qm + 1));
				boolean ours = state.equals(params.get("state"));
				String body = ours && params.containsKey("code")
						? "<h2>Signed in</h2><p>You can close this tab and go back to Minecraft.</p>"
						: "<h2>Not signed in</h2><p>Go back to Minecraft and try again.</p>";
				byte[] html = ("<!doctype html><meta charset=utf-8><title>PlayWit</title><body style=\"font-family:sans-serif;margin:3em\">" + body).getBytes(StandardCharsets.UTF_8);
				OutputStream out = socket.getOutputStream();
				out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + html.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				out.write(html);
				out.flush();
				// A browser may ask for other things first (an icon): only the answer with our state counts.
				if (ours) return params;
			}
		}
	}

	private static Socket accept(ServerSocket server) throws IOException {
		try {
			return server.accept();
		} catch (SocketTimeoutException e) {
			return null;
		}
	}

	private static void useToken(Service s, JsonObject token) throws IOException {
		accessTokens.put(s, token.get("access_token").getAsString());
		long seconds = token.has("expires_in") ? token.get("expires_in").getAsLong() : 3600;
		accessUntil.put(s, System.currentTimeMillis() + (seconds - 60) * 1000);
		// Microsoft hands out a new one each time; Google keeps the first.
		if (token.has("refresh_token")) remember(s, "refresh", token.get("refresh_token").getAsString());
	}

	/** A current access token, fetched with the saved sign-in when the last one ran out. */
	private static synchronized String token(Service s) throws IOException {
		String t = accessTokens.get(s);
		if (t != null && System.currentTimeMillis() < accessUntil.getOrDefault(s, 0L)) return t;
		String refresh = refreshToken(s);
		if (refresh.isEmpty()) throw new IOException("Not signed in to " + s.label);
		Map<String, String> form = new LinkedHashMap<>();
		form.put("client_id", clientId(s));
		if (s == Service.GOOGLE && !GOOGLE_CLIENT_SECRET.isEmpty()) form.put("client_secret", GOOGLE_CLIENT_SECRET);
		form.put("grant_type", "refresh_token");
		form.put("refresh_token", refresh);
		if (s == Service.ONEDRIVE) form.put("scope", scope(s));
		JsonObject token;
		try {
			token = postForm(tokenUrl(s), form);
		} catch (Status e) {
			// Signed out elsewhere, or the access was taken back: sign in again.
			if (e.code == 400 || e.code == 401) {
				disconnect(s);
				throw new IOException(s.label + " signed you out; connect it again");
			}
			throw e;
		}
		useToken(s, token);
		return accessTokens.get(s);
	}

	private static String whoAmI(Service s) throws IOException {
		if (s == Service.GOOGLE) {
			JsonObject about = getJson(s, at("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)"));
			return about.getAsJsonObject("user").get("emailAddress").getAsString();
		}
		JsonObject me = getJson(s, at("https://graph.microsoft.com/v1.0/me?$select=userPrincipalName,mail"));
		return me.has("mail") && !me.get("mail").isJsonNull() ? me.get("mail").getAsString() : me.get("userPrincipalName").getAsString();
	}

	// ---- Backups ----

	/** Puts a backup in the cloud, under its own file name. */
	static void upload(Service s, Path zip) throws IOException {
		long size = Files.size(zip);
		String name = zip.getFileName().toString();
		String uploadUrl;
		if (s == Service.GOOGLE) {
			JsonObject meta = new JsonObject();
			meta.addProperty("name", name);
			JsonArray parents = new JsonArray();
			parents.add(googleFolder());
			meta.add("parents", parents);
			HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(at("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")))
					.header("Authorization", "Bearer " + token(s)).header("Content-Type", "application/json; charset=UTF-8")
					.header("X-Upload-Content-Type", "application/zip").header("X-Upload-Content-Length", Long.toString(size))
					.POST(HttpRequest.BodyPublishers.ofString(meta.toString())).build());
			uploadUrl = r.headers().firstValue("Location").orElseThrow(() -> new IOException("Google Drive didn't start the upload"));
		} else {
			JsonObject item = new JsonObject();
			JsonObject inner = new JsonObject();
			inner.addProperty("@microsoft.graph.conflictBehavior", "replace");
			item.add("item", inner);
			JsonObject session = postJson(s, at("https://graph.microsoft.com/v1.0/me/drive/special/approot:/" + encodePath(name) + ":/createUploadSession"), item);
			uploadUrl = session.get("uploadUrl").getAsString();
		}
		// In pieces, so a big backup (recordings, chat logs) goes up however large it is.
		try (RandomAccessFile in = new RandomAccessFile(zip.toFile(), "r")) {
			long sent = 0;
			byte[] buf = new byte[CHUNK];
			do {
				int n = (int) Math.min(CHUNK, size - sent);
				in.readFully(buf, 0, n);
				byte[] piece = n == CHUNK ? buf : java.util.Arrays.copyOf(buf, n);
				HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uploadUrl)).timeout(Duration.ofMinutes(5))
						.header("Content-Range", size == 0 ? "bytes */0" : "bytes " + sent + "-" + (sent + n - 1) + "/" + size)
						.PUT(HttpRequest.BodyPublishers.ofByteArray(piece));
				// OneDrive's upload address carries its own permission, and refuses one sent with it.
				if (s == Service.GOOGLE) b.header("Authorization", "Bearer " + token(s));
				HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
				int code = r.statusCode();
				sent += n;
				boolean more = code == 308 || code == 202;
				if (!more && code != 200 && code != 201) throw new Status(code, s.label + " stopped the upload (" + code + ")");
				if (!more) break;
			} while (sent < size);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Upload interrupted");
		}
	}

	/** The backups in the cloud, newest first. */
	static List<Remote> list(Service s) throws IOException {
		List<Remote> list = new ArrayList<>();
		if (s == Service.GOOGLE) {
			String q = "'" + googleFolder() + "' in parents and trashed=false";
			String page = null;
			do {
				JsonObject r = getJson(s, at("https://www.googleapis.com/drive/v3/files?q=" + enc(q)
						+ "&fields=" + enc("nextPageToken,files(id,name,size,createdTime)") + "&pageSize=200" + (page == null ? "" : "&pageToken=" + enc(page))));
				for (JsonElement e : r.getAsJsonArray("files")) {
					JsonObject f = e.getAsJsonObject();
					list.add(new Remote(f.get("id").getAsString(), f.get("name").getAsString(), f.has("size") ? f.get("size").getAsLong() : 0,
							time(f.get("createdTime"))));
				}
				page = r.has("nextPageToken") ? r.get("nextPageToken").getAsString() : null;
			} while (page != null);
		} else {
			String url = at("https://graph.microsoft.com/v1.0/me/drive/special/approot/children?$select=id,name,size,createdDateTime,file&$top=200");
			while (url != null) {
				JsonObject r = getJson(s, url);
				for (JsonElement e : r.getAsJsonArray("value")) {
					JsonObject f = e.getAsJsonObject();
					if (!f.has("file")) continue;
					list.add(new Remote(f.get("id").getAsString(), f.get("name").getAsString(), f.has("size") ? f.get("size").getAsLong() : 0,
							time(f.get("createdDateTime"))));
				}
				url = r.has("@odata.nextLink") ? at(r.get("@odata.nextLink").getAsString()) : null;
			}
		}
		list.removeIf(r -> !r.name().endsWith(".zip"));
		list.sort(Comparator.comparing(Remote::name).reversed());
		return list;
	}

	/** Downloads a backup to {@code to}. */
	static void download(Service s, Remote r, Path to) throws IOException {
		Files.createDirectories(to.getParent());
		Path temp = to.resolveSibling(to.getFileName() + ".part");
		try {
			HttpResponse<Path> resp;
			if (s == Service.GOOGLE) {
				resp = HTTP.send(HttpRequest.newBuilder(URI.create(at("https://www.googleapis.com/drive/v3/files/" + enc(r.id()) + "?alt=media")))
						.timeout(Duration.ofMinutes(30)).header("Authorization", "Bearer " + token(s)).build(), HttpResponse.BodyHandlers.ofFile(temp));
			} else {
				// OneDrive answers with where the file is, which needs no sign-in (and mustn't be sent one).
				HttpResponse<String> where = HTTP.send(HttpRequest.newBuilder(URI.create(at("https://graph.microsoft.com/v1.0/me/drive/items/" + enc(r.id()) + "/content")))
						.header("Authorization", "Bearer " + token(s)).build(), HttpResponse.BodyHandlers.ofString());
				String location = where.headers().firstValue("Location").orElseThrow(() -> new Status(where.statusCode(), "OneDrive didn't say where the file is (" + where.statusCode() + ")"));
				resp = HTTP.send(HttpRequest.newBuilder(URI.create(location)).timeout(Duration.ofMinutes(30)).build(), HttpResponse.BodyHandlers.ofFile(temp));
			}
			if (resp.statusCode() != 200) throw new Status(resp.statusCode(), s.label + " didn't send the file (" + resp.statusCode() + ")");
			Files.move(temp, to, StandardCopyOption.REPLACE_EXISTING);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrupted");
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	static void delete(Service s, Remote r) throws IOException {
		String url = s == Service.GOOGLE ? at("https://www.googleapis.com/drive/v3/files/" + enc(r.id())) : at("https://graph.microsoft.com/v1.0/me/drive/items/" + enc(r.id()));
		HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token(s)).DELETE().build());
		if (resp.statusCode() != 204 && resp.statusCode() != 200) throw new Status(resp.statusCode(), "Couldn't delete it (" + resp.statusCode() + ")");
	}

	/** Deletes all but the newest {@code keep} automatic backups. */
	static void prune(Service s, int keep) throws IOException {
		List<Remote> autos = list(s).stream().filter(r -> r.name().endsWith(PanelBackups.AUTO + ".zip")).toList();
		for (int i = keep; i < autos.size(); i++) delete(s, autos.get(i));
	}

	/** The "PlayWit backups" folder in Google Drive, made the first time. */
	private static String googleFolder() throws IOException {
		JsonObject o = saved().getAsJsonObject(Service.GOOGLE.key);
		if (o != null && o.has("folder")) {
			String id = o.get("folder").getAsString();
			try {
				JsonObject f = getJson(Service.GOOGLE, at("https://www.googleapis.com/drive/v3/files/" + enc(id) + "?fields=id,trashed"));
				if (!f.has("trashed") || !f.get("trashed").getAsBoolean()) return id;
			} catch (Status e) {
				if (e.code != 404) throw e;
			}
		}
		// Made by this app before (on another computer, say): use that one.
		String q = "name='" + FOLDER + "' and mimeType='application/vnd.google-apps.folder' and trashed=false";
		JsonArray found = getJson(Service.GOOGLE, at("https://www.googleapis.com/drive/v3/files?q=" + enc(q) + "&fields=" + enc("files(id)"))).getAsJsonArray("files");
		String id;
		if (!found.isEmpty()) {
			id = found.get(0).getAsJsonObject().get("id").getAsString();
		} else {
			JsonObject meta = new JsonObject();
			meta.addProperty("name", FOLDER);
			meta.addProperty("mimeType", "application/vnd.google-apps.folder");
			id = postJson(Service.GOOGLE, at("https://www.googleapis.com/drive/v3/files?fields=id"), meta).get("id").getAsString();
		}
		remember(Service.GOOGLE, "folder", id);
		return id;
	}

	// ---- HTTP ----

	/** A refusal from the server, with its status code. */
	static final class Status extends IOException {
		final int code;

		Status(int code, String message) {
			super(message);
			this.code = code;
		}
	}

	private static HttpResponse<String> send(HttpRequest request) throws IOException {
		try {
			return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted");
		}
	}

	private static JsonObject read(HttpResponse<String> r) throws IOException {
		if (r.statusCode() / 100 != 2) {
			String why = "";
			try {
				JsonObject err = JsonParser.parseString(r.body()).getAsJsonObject();
				JsonElement e = err.get("error_description") != null ? err.get("error_description") : err.get("error");
				why = e == null ? "" : e.isJsonObject() ? e.getAsJsonObject().get("message").getAsString() : e.getAsString();
			} catch (RuntimeException ignored) {
				// Not JSON: the code says enough.
			}
			throw new Status(r.statusCode(), "The server said no (" + r.statusCode() + (why.isEmpty() ? "" : ": " + why) + ")");
		}
		return r.body().isBlank() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject();
	}

	private static JsonObject postForm(String url, Map<String, String> form) throws IOException {
		return read(send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form(form))).build()));
	}

	private static JsonObject getJson(Service s, String url) throws IOException {
		return read(send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token(s)).GET().build()));
	}

	private static JsonObject postJson(Service s, String url, JsonObject body) throws IOException {
		return read(send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token(s))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()));
	}

	private static String form(Map<String, String> values) {
		StringBuilder b = new StringBuilder();
		for (Map.Entry<String, String> e : values.entrySet()) {
			if (b.length() > 0) b.append('&');
			b.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
		}
		return b.toString();
	}

	static Map<String, String> parseForm(String query) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			if (eq <= 0) continue;
			map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return map;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/** A file name for a OneDrive path: each part encoded, slashes kept. */
	private static String encodePath(String name) {
		return enc(name).replace("%2F", "/");
	}

	private static long time(JsonElement e) {
		try {
			return e == null ? 0 : Instant.parse(e.getAsString()).toEpochMilli();
		} catch (RuntimeException ex) {
			return 0;
		}
	}

	private static String random(int bytes) {
		byte[] b = new byte[bytes];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	/** For the self-test: forgets the access tokens, as a restart would. */
	static void forgetAccess() {
		accessTokens.clear();
		accessUntil.clear();
	}
}
