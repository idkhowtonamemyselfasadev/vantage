package dev.vantage.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vantage.core.Ui;
import dev.vantage.core.Vantage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.lwjgl.glfw.GLFW;

/** Multiplayer: the saved servers as rows with their ping, and Join, Direct connect, Add, Edit, Delete, Refresh. Vanilla's list and pinger underneath. */
public class VantageServersScreen extends Screen implements VantageScreen {
    private final Screen parent;
    private final MenuKit kit = new MenuKit();
    private final ServerStatusPinger pinger = new ServerStatusPinger();
    private ServerList servers;
    private ServerData editing;
    private int selected = -1, scroll;
    private long opened;
    /** One favicon texture per server ip, uploaded again whenever the icon bytes change (a ping brings new ones). */
    private final java.util.Map<String, FaviconTexture> icons = new java.util.HashMap<>();
    private final java.util.Map<String, byte[]> iconBytes = new java.util.HashMap<>();

    public VantageServersScreen(Screen parent) {
        super(Component.translatable("multiplayer.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        opened = System.currentTimeMillis();
        servers = new ServerList(minecraft);
        servers.load();
        if (selected < 0 && servers.size() > 0) selected = 0;
        pingAll();
    }

    public int count() { return servers == null ? 0 : servers.size(); }

    private void pingAll() {
        pinger.removeAll();
        for (int i = 0; i < servers.size(); i++) {
            ServerData d = servers.get(i);
            d.setState(ServerData.State.PINGING);
            d.motd = Component.translatable("multiplayer.status.pinging");
            Thread t = new Thread(() -> {
                try {
                    // like vanilla's list: the pinger fills the data in, the second callback is where the final state is set
                    pinger.pingServer(d, () -> {}, () -> d.setState(d.protocol == net.minecraft.SharedConstants.getCurrentVersion().protocolVersion()
                            ? ServerData.State.SUCCESSFUL : ServerData.State.INCOMPATIBLE), EventLoopGroupHolder.remote(minecraft.options.useNativeTransport()));
                } catch (java.net.UnknownHostException e) {
                    d.setState(ServerData.State.UNREACHABLE);
                    d.motd = Component.translatable("multiplayer.status.cannot_resolve");
                } catch (Exception e) {
                    d.setState(ServerData.State.UNREACHABLE);
                    d.motd = Component.translatable("multiplayer.status.cannot_connect");
                }
            }, "Vantage server ping");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void tick() {
        pinger.tick();
    }

    @Override
    public void removed() {
        pinger.removeAll();
        for (FaviconTexture f : icons.values()) f.close();
        icons.clear();
        iconBytes.clear();
    }

    /** The server's icon texture (vanilla's unknown-server picture until a ping brings one). */
    private Identifier icon(ServerData d) {
        FaviconTexture f = icons.computeIfAbsent(d.ip, ip -> FaviconTexture.forServer(minecraft.getTextureManager(), ip));
        byte[] now = d.getIconBytes();
        if (now != iconBytes.get(d.ip)) {
            iconBytes.put(d.ip, now);
            if (now == null) f.clear();
            else {
                try { f.upload(NativeImage.read(now)); } catch (Throwable t) { f.clear(); }
            }
        }
        return f.textureLocation();
    }

    private int visible() { return (int) ((MenuKit.H() - 100 - MenuKit.PAD) / (MenuKit.ROW + MenuKit.GAP)); }

    private static String status(ServerData d) {
        return switch (d.state()) {
            case INITIAL, PINGING -> "pinging";
            case UNREACHABLE -> "can't reach it";
            case INCOMPATIBLE -> "other version: " + d.version.getString();
            case SUCCESSFUL -> {
                String motd = d.motd == null ? "" : ChatFormatting.stripFormatting(d.motd.getString()).replace('\n', ' ').trim();
                yield motd.isEmpty() ? "online" : motd;
            }
        };
    }

    private static String right(ServerData d) {
        if (d.state() != ServerData.State.SUCCESSFUL) return null;
        String p = d.players == null ? "" : d.players.online() + "/" + d.players.max() + "  ·  ";
        return p + d.ping + " ms";
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (minecraft.level == null) renderPanorama(g, delta);
        g.fill(0, 0, width, height, 0x66050817);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        kit.begin(g, mx, my);
        Ui.alpha = Ui.ease(Math.min(1f, (System.currentTimeMillis() - opened) / 280f));
        int n = servers.size();
        kit.window(g, "Multiplayer", n == 0 ? "No servers saved yet" : n + " servers");
        float x = kit.x0(), y = kit.y0();
        float lw = MenuKit.W() - 2 * MenuKit.PAD - 220, lx = x + MenuKit.PAD, ly = y + 92;
        int vis = visible();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, n - vis)));
        for (int i = scroll; i < n && i < scroll + vis; i++) {
            ServerData d = servers.get(i);
            // the ip is the headline; the saved name only when it says something (vanilla's default "Minecraft Server" does not)
            String name = d.name == null || d.name.isBlank() || d.name.equals("Minecraft Server") ? "" : d.name + "  ·  ";
            kit.row(g, "server:" + i, icon(d), dev.vantage.core.Streamer.serverLabel(d.ip), name + status(d), right(d), lx, ly, lw, i == selected);
            ly += MenuKit.ROW + MenuKit.GAP;
        }
        if (n == 0) Ui.text(g, "Add one on the right, or connect directly.", lx, ly + 6, Ui.FAINT, 0.9f, false);
        if (n > vis) Ui.right(g, (scroll + 1) + "-" + Math.min(n, scroll + vis) + " of " + n + "  ·  scroll", lx + lw, y + MenuKit.H() - MenuKit.PAD - 10, Ui.FAINT, 0.78f, false);
        float bx = x + MenuKit.W() - MenuKit.PAD - 200, by = y + 92, bw = 200;
        boolean has = selected >= 0 && selected < n;
        kit.button(g, "join", "Join server", bx, by, bw, true, has);
        kit.button(g, "direct", "Direct connect", bx, by += 44, bw, false, true);
        kit.button(g, "add", "Add server", bx, by += 44, bw, false, true);
        kit.button(g, "edit", "Edit", bx, by += 56, bw / 2 - 4, false, has);
        kit.button(g, "delete", "Delete", bx + bw / 2 + 4, by, bw / 2 - 4, false, has);
        kit.button(g, "refresh", "Refresh", bx, by += 44, bw, false, true);
        kit.button(g, "back", "Back", bx, y + MenuKit.H() - MenuKit.PAD - MenuKit.BTN, bw, false, true);
        Ui.alpha = 1f;
        kit.end(g);
    }

    private ServerData sel() {
        return selected >= 0 && selected < servers.size() ? servers.get(selected) : null;
    }

    private void join(ServerData d) {
        ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(d.ip), d, false, null);
    }

    @Override
    public void act(String id) {
        if (id.startsWith("server:")) {
            int i = Integer.parseInt(id.substring(7));
            if (i == selected) act("join"); else selected = i;
            return;
        }
        ServerData d = sel();
        switch (id) {
            case "join" -> { if (d != null) join(d); }
            case "direct" -> {
                editing = new ServerData(I18n.get("selectServer.defaultName"), "", ServerData.Type.OTHER);
                minecraft.setScreen(new DirectJoinServerScreen(this, ok -> {
                    if (!ok) { minecraft.setScreen(this); return; }
                    ServerData known = servers.get(editing.ip);
                    if (known == null) { servers.add(editing, true); servers.save(); join(editing); } else join(known);
                }, editing));
            }
            case "add" -> {
                editing = new ServerData("", "", ServerData.Type.OTHER);
                minecraft.setScreen(new ManageServerScreen(this, Component.translatable("manageServer.add.title"), ok -> {
                    if (ok) { servers.add(editing, false); servers.save(); }
                    minecraft.setScreen(this);
                }, editing));
            }
            case "edit" -> {
                if (d == null) return;
                editing = new ServerData(d.name, d.ip, ServerData.Type.OTHER);
                minecraft.setScreen(new ManageServerScreen(this, Component.translatable("manageServer.edit.title"), ok -> {
                    if (ok) { d.copyFrom(editing); servers.save(); }
                    minecraft.setScreen(this);
                }, editing));
            }
            case "delete" -> {
                if (d == null) return;
                minecraft.setScreen(new ConfirmScreen(ok -> {
                    if (ok) { servers.remove(d); servers.save(); selected = -1; }
                    minecraft.setScreen(this);
                }, Component.translatable("selectServer.deleteQuestion"), Component.translatable("selectServer.deleteWarning", d.name),
                        Component.translatable("selectServer.deleteButton"), CommonComponents.GUI_CANCEL));
            }
            case "refresh" -> pingAll();
            case "back" -> Screens.back(parent);
            default -> Vantage.LOG.info("Servers screen: no action {}", id);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        String id = kit.hit(e.x(), e.y());
        if (id != null) { act(id); return true; }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, Math.min(Math.max(0, servers.size() - visible()), scroll - (int) Math.signum(v)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) { act("back"); return true; }
        if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) { act("join"); return true; }
        if (k == GLFW.GLFW_KEY_DOWN && selected < servers.size() - 1) { selected++; return true; }
        if (k == GLFW.GLFW_KEY_UP && selected > 0) { selected--; return true; }
        return false;
    }

    @Override
    public void onClose() {
        Screens.back(parent);
    }
}
