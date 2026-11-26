package dev.vantage.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vantage.module.client.ClickGuiModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * Who else runs Vantage. Like Badlion's or Lunar's badge: the client tells our presence service "I am online on server X" and
 * asks who else is, then a small mark goes in front of those names (tab list, name tags, Shard Watch).
 *
 * What leaves the client: your UUID and the first 16 hex of sha256(server address), once a minute, over HTTPS. Proof of account
 * is Mojang's own server-join handshake (joinServer with a random id, the service asks hasJoined), so no Minecraft token ever
 * reaches our server. Off with the "Presence" toggle; then nothing is sent and no badge shows.
 *
 * Also on the title screen and in singleplayer once consent is given: the same ping with the word "menu" instead of a server hash,
 * so the service's total counts everybody who has the game open. The owner of Vantage (the service decides, by a hash of the UUID in
 * a file on the server) can list the accounts online: {@link #refreshOwner()}; everybody else gets a 403 once and never asks again
 * during that launch.
 */
public final class Presence {
    public static final String DEFAULT_URL = "https://presence.example.invalid/presence";
    private static final long PING_MS = 60_000, FIRST_DELAY_MS = 3000;
    /** The ping value off a server: the title screen, a singleplayer world, a LAN world you host. */
    public static final String MENU = "menu";
    private static final Identifier BADGE_FONT = Identifier.fromNamespaceAndPath("vantage", "badge");
    private static final Identifier BADGE_TEX = Identifier.fromNamespaceAndPath("vantage", "textures/font/badge.png");
    private static final Pattern UUID_RE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Vantage presence"); t.setDaemon(true); return t; });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    /** Rig only: lets tick() run against the rig's local service (without Mojang's join handshake, which the --test service does not check). */
    private static boolean rigTick() { return System.getProperty("vantage.presenceUrl") != null && Boolean.getBoolean("vantage.presenceTickRig"); }
    private static final SecureRandom RNG = new SecureRandom();

    private static volatile Set<UUID> users = Collections.emptySet();
    private static volatile String token;
    private static volatile String serverHash;
    /** What the next ping carries: the server hash, or "menu" off a server. */
    private static volatile String sendHash;
    private static volatile long nextAt, backoffMs = PING_MS;
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static long joinedAt;
    /** For the rig and the log line. */
    public static volatile int pings, decorated, tabHits, tagHits;
    public static volatile String lastError;
    /** Friends as the service knows them (mutual ones carry online + server hash), and who asked to be our friend. */
    public record Remote(UUID id, boolean mutual, boolean online, String server) {}
    private static volatile List<Remote> remote = Collections.emptyList();
    private static volatile Set<UUID> requests = Collections.emptySet();
    public static volatile long syncedAt, usersAt;
    public static volatile boolean friendsOk;
    private static volatile long lastRefresh;
    private static final Set<UUID> NO_NAME = ConcurrentHashMap.newKeySet();
    private static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Vantage names"); t.setDaemon(true); return t; });

    private Presence() {}

    public static void init() {
        PresenceConsent.applyAtStart();
        ClientTickEvents.END_CLIENT_TICK.register(Presence::tick);
    }

    private static ClickGuiModule gui() { return Modules.get(ClickGuiModule.class); }

    public static boolean enabled() {
        ClickGuiModule g = gui();
        return g != null && g.presence.on() && PresenceConsent.yes() && !Terms.off(); // no answer yet = nothing is sent
    }

    public static boolean badgeOn() {
        ClickGuiModule g = gui();
        return enabled() && g.presenceBadge.on() && !Panic.active();
    }

    /** The service base: the real one, or whatever -Dvantage.presenceUrl says (plain http allowed only then, for the rig). */
    static String base() {
        String p = System.getProperty("vantage.presenceUrl");
        return p != null && !p.isBlank() ? p.replaceAll("/+$", "") : DEFAULT_URL;
    }

    private static void tick(Minecraft mc) {
        if (Terms.off()) return; // the terms gate: no Vantage network at all
        if (System.getProperty("vantage.presenceUrl") != null && !rigTick()) return; // the rig drives the rounds itself and keeps its fake users
        if ((System.getProperty("vantage.ui") != null || Boolean.getBoolean("vantage.prodtest")) && System.getProperty("vantage.presenceUrl") == null) return; // the rig never talks to Mojang or the real service on its own (the menu ping would try the join handshake)
        // the first time it would send and nobody has answered: ask once (never in a local world, never over another screen)
        if (PresenceConsent.needsAsk() && !PresenceConsent.askedThisLaunch() && mc.player != null && mc.getCurrentServer() != null && !mc.isLocalServer()
                && mc.getConnection() != null && mc.screen == null) {
            PresenceConsent.markAsked();
            mc.setScreen(new dev.vantage.gui.PresenceAskScreen(null));
        }
        long now = System.currentTimeMillis();
        if (!enabled() || mc.getUser() == null) { // no consent (or switched off): nothing at all, also not in the menu
            if (!users.isEmpty()) users = Collections.emptySet();
            serverHash = null;
            sendHash = null;
            joinedAt = 0;
            return;
        }
        boolean onServer = mc.player != null && mc.getCurrentServer() != null && !mc.isLocalServer() && mc.getConnection() != null;
        String h = onServer ? hash(mc.getCurrentServer().ip) : MENU;
        if (!onServer) { if (!users.isEmpty()) users = Collections.emptySet(); serverHash = null; }
        if (!h.equals(sendHash)) { sendHash = h; if (onServer) { serverHash = h; users = Collections.emptySet(); } joinedAt = now; nextAt = now + FIRST_DELAY_MS; backoffMs = PING_MS; }
        if (now < nextAt || !busy.compareAndSet(false, true)) return;
        nextAt = now + backoffMs;
        String name = mc.getUser().getName(), tok = mc.getUser().getAccessToken();
        UUID me = mc.getUser().getProfileId();
        var session = mc.services().sessionService();
        NET.execute(() -> {
            try {
                if (token == null) {
                    String sid = randomHex(20);
                    if (!rigTick()) session.joinServer(me, tok, sid); // Mojang's handshake; the token stays between the client and Mojang
                    token = post("/auth", "{\"name\":\"" + name + "\",\"uuid\":\"" + me + "\",\"serverId\":\"" + sid + "\"}", null).get("token").getAsString();
                }
                round(h);
                backoffMs = PING_MS;
                lastError = null;
            } catch (Throwable t) {
                if (t.getMessage() != null && t.getMessage().contains("401")) token = null; // expired or rejected: handshake again next time
                backoffMs = Math.min(backoffMs * 2, 600_000);
                if (lastError == null) Vantage.LOG.info("Presence: {} (retry in {} s)", t.toString(), backoffMs / 1000);
                lastError = t.toString();
            } finally {
                busy.set(false);
            }
        });
    }

    /** One ping plus one fetch of who is online on this server hash. Needs a token. */
    private static void round(String h) throws Exception {
        post("/ping", "{\"token\":\"" + token + "\",\"server\":\"" + h + "\",\"consent\":" + PresenceConsent.VERSION + "}", null);
        pings++;
        if (MENU.equals(h)) { // nothing to list off a server; friends still sync
            try { syncFriends(); } catch (Exception e) { friendsOk = false; }
            return;
        }
        JsonObject o = get("/online?server=" + h, token);
        Set<UUID> set = new HashSet<>();
        for (var e : o.getAsJsonArray("uuids")) {
            Matcher m = UUID_RE.matcher(e.getAsString().toLowerCase());
            if (m.matches()) set.add(UUID.fromString(m.group()));
        }
        users = set;
        usersAt = System.currentTimeMillis();
        try { syncFriends(); } catch (Exception e) { friendsOk = false; } // an older service has no friends endpoints: the rest still works
    }

    /** Friends list from the service, plus names for the ones we can resolve (tab list, then Mojang's profile lookup). */
    private static void syncFriends() throws Exception {
        JsonObject o = get("/friends", token);
        List<Remote> l = new ArrayList<>();
        for (var e : o.getAsJsonArray("friends")) {
            JsonObject f = e.getAsJsonObject();
            UUID id = parse(f.get("uuid").getAsString());
            if (id == null) continue;
            String srv = f.get("server") == null || f.get("server").isJsonNull() ? null : f.get("server").getAsString();
            l.add(new Remote(id, f.get("mutual").getAsBoolean(), f.get("online").getAsBoolean(), srv));
        }
        Set<UUID> rq = new HashSet<>();
        for (var e : o.getAsJsonArray("requests")) { UUID id = parse(e.getAsString()); if (id != null) rq.add(id); }
        remote = l;
        requests = rq;
        friendsOk = true;
        syncedAt = System.currentTimeMillis();
        int budget = 20;
        // names: tab list first, then Mojang's profile lookup. A friend we never heard of (added on another device) joins the local
        // list once; one we already know is left alone, so a quick add then remove here is not undone by an older sync.
        for (Remote r : l) {
            if (Friends.nameOf(r.id()) != null) continue;
            String n = tabName(r.id());
            if (n != null) { Friends.remember(n, r.id()); Friends.adopt(n); }
            else if (budget-- > 0) lookupName(r.id(), true);
        }
        for (UUID id : rq) {
            if (Friends.nameOf(id) != null) continue;
            String n = tabName(id);
            if (n != null) Friends.remember(n, id);
            else if (budget-- > 0) lookupName(id, false);
        }
    }

    private static UUID parse(String s) {
        Matcher m = UUID_RE.matcher(s.toLowerCase());
        return m.matches() ? UUID.fromString(m.group()) : null;
    }

    private static void lookupName(UUID id, boolean friend) {
        if (!NO_NAME.add(id)) return;
        LOOKUP.execute(() -> {
            try {
                var p = Minecraft.getInstance().services().profileResolver().fetchById(id);
                if (p.isPresent() && p.get().name() != null) { Friends.remember(p.get().name(), id); if (friend) Friends.adopt(p.get().name()); NO_NAME.remove(id); }
            } catch (Throwable t) { /* offline or rate limited: the row is simply not shown yet */ }
        });
    }

    private static JsonObject post(String path, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("User-Agent", "Vantage/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return send(b.build());
    }

    private static JsonObject get(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(5)).header("User-Agent", "Vantage/1.0")
                .header("Authorization", "Bearer " + bearer).GET().build());
    }

    /** Accounts the service counts as online right now, over all servers; -1 while unknown. Only the total: the service gives out no names with it. */
    private static volatile int total = -1;
    private static volatile long totalAskedAt;

    public static int total() {
        return total;
    }

    /**
     * Asks the service for its total, at most once a minute, off the client thread. This is the public "health" address, which
     * needs no login and answers with one number. Called only by the Vantage Online view while it is switched on.
     */
    public static void refreshTotal() {
        if (Terms.off()) return;
        long now = System.currentTimeMillis();
        if (now - totalAskedAt < 60_000) return;
        totalAskedAt = now;
        NET.execute(() -> {
            try {
                JsonObject o = send(HttpRequest.newBuilder(URI.create(base() + "/health")).timeout(Duration.ofSeconds(5)).header("User-Agent", "Vantage/1.0").GET().build());
                total = o.has("online") ? o.get("online").getAsInt() : -1;
            } catch (Exception e) {
                total = -1; // unknown is shown as a dash; the next minute tries again
            }
        });
    }

    /** The result of {@link #sendFeedback}. */
    public enum FeedbackResult { SENT, EMPTY, TOO_MANY, OFFLINE, TERMS, FAILED }

    /**
     * Posts a feedback text to the presence service, off the calling thread; {@code done} runs on the network thread with the result.
     * Sends the text, the Vantage version and the Minecraft version and nothing else (no name, no UUID, no server). If a presence
     * token already exists it goes along as the bearer so the service can count three per hour for it (the service does not store it);
     * otherwise the request is anonymous and shares the service's anonymous limit. Never starts the account handshake itself and never logs the text.
     */
    public static void sendFeedback(String text, String vantageVersion, String mcVersion, java.util.function.Consumer<FeedbackResult> done) {
        String clean = Feedback.clean(text);
        if (clean.isEmpty()) { done.accept(FeedbackResult.EMPTY); return; }
        if (Terms.off()) { done.accept(FeedbackResult.TERMS); return; }
        if ((System.getProperty("vantage.ui") != null || Boolean.getBoolean("vantage.prodtest")) && System.getProperty("vantage.presenceUrl") == null) {
            done.accept(FeedbackResult.OFFLINE); // the rig never reaches the real service
            return;
        }
        String body = Feedback.body(clean, vantageVersion, mcVersion);
        String tok = token;
        FEEDBACK.execute(() -> {
            FeedbackResult r;
            try {
                try {
                    post("/feedback", body, tok);
                } catch (Status s) {
                    if (s.code != 401 || tok == null) throw s;
                    if (tok.equals(token)) token = null; // the session ran out: the next ping signs in again
                    post("/feedback", body, null);
                }
                r = FeedbackResult.SENT;
            } catch (Status s) {
                r = s.code == 429 ? FeedbackResult.TOO_MANY : s.code == 400 ? FeedbackResult.EMPTY : FeedbackResult.FAILED;
            } catch (java.io.IOException e) {
                r = e.getMessage() != null && e.getMessage().contains("terms") ? FeedbackResult.TERMS : FeedbackResult.OFFLINE;
            } catch (Exception e) {
                r = FeedbackResult.OFFLINE;
            }
            done.accept(r);
        });
    }

    private static final ExecutorService FEEDBACK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Vantage feedback"); t.setDaemon(true); return t; });

    /** Every request attempt this session (also failed ones), for the rig: proves nothing was sent before an answer. */
    public static volatile int netCalls;

    /** A non-200 answer; the message is the same text as before ("... answered 401"), the code is for callers that care. */
    static final class Status extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        final int code;
        Status(int code, String message) { super(message); this.code = code; }
    }

    private static JsonObject send(HttpRequest req) throws Exception {
        if (Terms.off()) throw new java.io.IOException("terms not confirmed"); // the one place every Presence request goes through
        netCalls++;
        if (!req.uri().getScheme().equals("https") && System.getProperty("vantage.presenceUrl") == null) throw new IllegalStateException("presence needs https");
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new Status(r.statusCode(), "presence " + req.uri().getPath() + " answered " + r.statusCode());
        if (r.body().length() > 65_536) throw new IllegalStateException("presence answer too large");
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    static String hash(String address) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(address.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "0000000000000000";
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---- what the rest of the client asks ----------------------------------------------------------------------------------

    public static boolean is(UUID id) {
        return id != null && badgeOn() && (users.contains(id) || (id.equals(selfId()) && serverHash != null));
    }

    private static UUID selfId() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() == null ? null : mc.getUser().getProfileId();
    }

    public static boolean isName(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || name == null) return false;
        PlayerInfo pi = mc.getConnection().getPlayerInfo(name);
        return pi != null && is(pi.getProfile().id());
    }

    /** The mark as text, for anywhere a Component is drawn: the badge glyph in the badge font, in the accent colour. */
    public static MutableComponent mark() {
        return Component.literal("\uE000 ").withStyle(s -> s.withFont(new FontDescription.Resource(BADGE_FONT)).withColor(Ui.ACCENT & 0xFFFFFF));
    }

    /** The name with the mark in front when this player runs Vantage, else the name untouched. */
    public static Component decorate(UUID id, Component name) {
        if (name == null || !is(id)) return name;
        decorated++;
        return Component.empty().append(mark()).append(name);
    }

    /** The mark drawn as an image for the HUD (design units). */
    public static void badge(GuiGraphics g, float x, float y, float size) {
        Ui.image(g, BADGE_TEX, x, y, size, size, Ui.ACCENT);
        decorated++;
    }

    public static int count() { return users.size(); }

    // ---- owner view ---------------------------------------------------------------------------------------------------------

    private static volatile boolean owner, ownerDenied;
    private static volatile List<OwnerList.Entry> all = Collections.emptyList();
    private static volatile long ownerAskedAt;
    /** Requests to /all made this launch (rig: proves a 403 is never asked again, and at most one a minute). */
    public static volatile int ownerCalls;
    private static final Map<UUID, String> OWNER_NAMES = new ConcurrentHashMap<>();
    private static final Set<UUID> LOOKED = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService NAMES = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "Vantage owner names"); t.setDaemon(true); return t; });
    private static long nextLookupAt;
    private static volatile Function<UUID, String> nameLookup = Presence::mojangName;

    /** True once the service answered /all with 200 for this account. A non-owner never gets here: the first 403 switches the whole feature off for the launch. */
    public static boolean isOwner() { return owner && !ownerDenied && enabled() && !Panic.active(); }

    /**
     * Asks the service who is online (owners only), at most once a minute, off the client thread, and only while Presence is on and we
     * hold a token. A 403 is remembered for the launch and nothing is ever asked again. Called by Vantage Online while it is enabled.
     */
    public static void refreshOwner() {
        if (ownerDenied || !enabled() || Panic.active()) return;
        String tok = token;
        long now = System.currentTimeMillis();
        if (tok == null || now - ownerAskedAt < 60_000) return;
        ownerAskedAt = now;
        ownerCalls++;
        NET.execute(() -> {
            try {
                var list = OwnerList.parse(get("/all", tok));
                all = list;
                owner = true;
            } catch (Status s) {
                if (s.code == 403) { ownerDenied = true; owner = false; all = Collections.emptyList(); }
                else if (s.code == 401) token = null;
            } catch (Throwable t) {
                // offline or a slow answer: keep what we have, the next minute asks again
            }
        });
    }

    /** The rows of the owner view: friends first, with names (tab list, friends, then Mojang's lookup in the background) or a short UUID. */
    public static List<OwnerList.Row> ownerRows() {
        return OwnerList.rows(all, serverHash, Presence::ownerName, Friends::is);
    }

    public static String ownerSummary() { return OwnerList.summary(ownerRows()); }

    private static String ownerName(UUID id) {
        UUID me = selfId();
        Minecraft mc = Minecraft.getInstance();
        if (id.equals(me) && mc.getUser() != null) return mc.getUser().getName();
        String n = nameOf(id);
        if (n == null) n = OWNER_NAMES.get(id);
        if (n == null) queueLookup(id);
        return n;
    }

    /** One Mojang lookup at most per second, on its own thread; one try per UUID per launch. Names stay in memory only. */
    private static void queueLookup(UUID id) {
        if (!LOOKED.add(id)) return;
        long delay;
        synchronized (NAMES) {
            long now = System.currentTimeMillis();
            long at = Math.max(now, nextLookupAt);
            nextLookupAt = at + 1000;
            delay = at - now;
        }
        NAMES.schedule(() -> {
            try {
                String n = nameLookup.apply(id);
                if (n != null && !n.isBlank()) OWNER_NAMES.put(id, n);
            } catch (Throwable t) { /* failure: the row keeps its short UUID */ }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static String mojangName(UUID id) {
        if (Terms.off()) return null;
        var p = Minecraft.getInstance().services().profileResolver().fetchById(id);
        return p.isPresent() ? p.get().name() : null;
    }

    // ---- friends on the service ----------------------------------------------------------------------------------------------

    public static List<Remote> remoteFriends() { return remote; }
    public static Set<UUID> requests() { return requests; }
    public static String serverHash() { return serverHash; }
    public static boolean synced() { return token != null && friendsOk; }

    /** A player's name when we can know it: the tab list, then names learnt before. Never null-checks to a raw UUID. */
    public static String nameOf(UUID id) {
        String n = tabName(id);
        return n != null ? n : Friends.nameOf(id);
    }

    private static String tabName(UUID id) {
        var c = Minecraft.getInstance().getConnection();
        PlayerInfo pi = c == null ? null : c.getPlayerInfo(id);
        return pi == null ? null : pi.getProfile().name();
    }

    public static UUID idOf(String name) {
        UUID id = Friends.idOf(name);
        if (id != null) return id;
        var c = Minecraft.getInstance().getConnection();
        PlayerInfo pi = c == null || name == null ? null : c.getPlayerInfoIgnoreCase(name);
        return pi == null ? null : pi.getProfile().id();
    }

    /** Vantage users on this server hash whose name we can resolve (never ourselves, never a bare UUID). Sorted by name. */
    public static List<UUID> onVantage() {
        List<UUID> out = new ArrayList<>();
        if (!enabled()) return out;
        UUID me = selfId();
        for (UUID u : users) if (!u.equals(me) && nameOf(u) != null) out.add(u);
        out.sort((a, b) -> nameOf(a).compareToIgnoreCase(nameOf(b)));
        return out;
    }

    /** A Vantage user here, with the distance in blocks when we can see them in the world (NaN = out of render distance). */
    public record Near(UUID id, String name, double dist) {
        public boolean far() { return Double.isNaN(dist); }
    }

    /** onVantage() with distances, nearest first (visible players), then the far ones by name. Reads entity positions only, no network. */
    public static List<Near> onVantageNear() {
        Minecraft mc = Minecraft.getInstance();
        List<Near> out = new ArrayList<>();
        for (UUID u : onVantage()) {
            double d = Double.NaN;
            if (mc.level != null && mc.player != null) {
                var p = mc.level.getPlayerByUUID(u);
                if (p != null && !p.isRemoved()) d = p.distanceTo(mc.player);
            }
            out.add(new Near(u, nameOf(u), d));
        }
        out.sort(java.util.Comparator.comparingDouble((Near x) -> x.far() ? Double.MAX_VALUE : x.dist()).thenComparing(Near::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The closest Vantage user who is not a friend yet, within max blocks, or null. */
    public static Near nearestNonFriend(double max) {
        for (Near n : onVantageNear()) if (!n.far() && n.dist() <= max && !Friends.is(n.name())) return n;
        return null;
    }

    /** Local friends whose UUID we know but the service does not have (offered for upload in the Friends screen). */
    public static List<String> notUploaded() {
        List<String> out = new ArrayList<>();
        if (!synced()) return out;
        Set<UUID> have = new HashSet<>();
        for (Remote r : remote) have.add(r.id());
        for (String n : Friends.list()) { UUID id = idOf(n); if (id != null && !have.contains(id)) out.add(n); }
        return out;
    }

    /** Friends.toggle hook: mirror an add or remove on the service when we are signed in there and know the UUID. */
    static void friendChanged(String name, boolean added) {
        UUID id = idOf(name);
        if (id == null) return;
        Friends.remember(name, id);
        friendCall(added ? "add" : "remove", id);
    }

    public static void addFriend(UUID id) {
        String n = nameOf(id);
        if (n == null) return;
        Friends.remember(n, id);
        if (!Friends.is(n)) Friends.adopt(n);
        friendCall("add", id);
    }

    public static void ignoreRequest(UUID id) { friendCall("ignore", id); }

    public static void uploadLocal() {
        int n = 0;
        for (String name : notUploaded()) { if (n++ >= 25) break; UUID id = idOf(name); if (id != null) friendCall("add", id); }
    }

    /** Deletes everything the service holds for this account. The local list stays. */
    public static void wipe() {
        if (token == null) return;
        NET.execute(() -> {
            try { post("/friends/wipe", "{}", token); remote = Collections.emptyList(); requests = Collections.emptySet(); } catch (Throwable t) { lastError = t.toString(); }
        });
    }

    private static void friendCall(String action, UUID id) {
        String tok = token;
        if (tok == null || !enabled()) return; // Presence off (or never agreed): friend changes stay on this device
        NET.execute(() -> {
            try {
                post("/friends/" + action, "{\"uuid\":\"" + id + "\"}", tok);
                syncFriends();
            } catch (Throwable t) { lastError = t.toString(); }
        });
    }

    /** Manual refresh: one presence round now (at most every 10 s, the service limits pings to 4 a minute). */
    public static boolean refresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < 10_000 || !enabled()) return false;
        lastRefresh = now;
        String h = serverHash != null ? serverHash : sendHash; // on a server both are the same; off one only sendHash (menu) is set
        if (token == null || h == null) { nextAt = 0; return true; }
        if (!busy.compareAndSet(false, true)) return true;
        NET.execute(() -> { try { round(h); } catch (Throwable t) { lastError = t.toString(); } finally { busy.set(false); } });
        return true;
    }

    // ---- rig -----------------------------------------------------------------------------------------------------------------

    /** Runs the handshake and one round against the presence URL property synchronously, pretending we stand on `server`. */
    public static boolean testRound(String name, UUID id, String server) {
        try {
            String sid = randomHex(20);
            token = post("/auth", "{\"name\":\"" + name + "\",\"uuid\":\"" + id + "\",\"serverId\":\"" + sid + "\"}", null).get("token").getAsString();
            serverHash = hash(server);
            round(serverHash);
            return true;
        } catch (Throwable t) {
            lastError = t.toString();
            Vantage.LOG.warn("Presence test round failed", t);
            return false;
        }
    }

    /** Rig: one friends sync now, on the calling thread's behalf. */
    public static void testSync() throws Exception { NET.submit(() -> { try { syncFriends(); } catch (Exception e) { lastError = e.toString(); } }).get(); }

    public static void testUnthrottle() { lastRefresh = 0; }

    /** Rig: forget the owner state, let the next refreshOwner() ask now, and replace Mojang's lookup (null = the real one). */
    public static void testOwnerReset(Function<UUID, String> lookup) {
        owner = false; ownerDenied = false; all = Collections.emptyList(); ownerAskedAt = 0; ownerCalls = 0;
        OWNER_NAMES.clear(); LOOKED.clear(); nextLookupAt = 0;
        nameLookup = lookup != null ? lookup : Presence::mojangName;
    }
    public static void testOwnerUnthrottle() { ownerAskedAt = 0; }
    public static boolean testOwnerDenied() { return ownerDenied; }
    public static void testServerHash(String address) { serverHash = address == null ? null : hash(address); }
    /** Rig: the hash the client would send for this address. */
    public static String testHash(String address) { return hash(address); }
    public static String testSendHash() { return sendHash; }
    public static void testResetSend() { sendHash = null; }

    public static void testReset() {
        token = null; users = Collections.emptySet(); serverHash = null;
        remote = Collections.emptyList(); requests = Collections.emptySet(); friendsOk = false; lastRefresh = 0;
    }

    /** Rig: a second client with its own account signs in and pings on `server`; returns its bearer token (null on failure). */
    public static String testFake(String name, UUID id, String server) {
        try {
            String t = post("/auth", "{\"name\":\"" + name + "\",\"uuid\":\"" + id + "\",\"serverId\":\"" + randomHex(20) + "\"}", null).get("token").getAsString();
            post("/ping", "{\"token\":\"" + t + "\",\"server\":\"" + (MENU.equals(server) ? MENU : hash(server)) + "\",\"consent\":" + PresenceConsent.VERSION + "}", null);
            return t;
        } catch (Throwable t) { lastError = t.toString(); return null; }
    }

    /** Rig: what that second client sees ("friends" JSON), and its friend calls (action add/remove/ignore/wipe). */
    public static JsonObject testFakeFriends(String token) throws Exception { return get("/friends", token); }
    public static void testFakeCall(String token, String action, UUID id) throws Exception { post("/friends/" + action, "{\"uuid\":\"" + id + "\"}", token); }

    /** Rig: wait for the async calls of this client to land. */
    public static void testDrain() throws Exception { NET.submit(() -> {}).get(); }
    public static String testToken() { return token; }
    public static void testSetToken(String t) { token = t; }

    /** Rig: pretend this player runs Vantage. */
    public static void testInject(UUID id) {
        Set<UUID> set = new HashSet<>(users);
        set.add(id);
        users = set;
        if (serverHash == null) serverHash = "0000000000000000";
    }
}
