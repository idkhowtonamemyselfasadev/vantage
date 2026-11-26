package dev.vantage.module.donut;

import dev.vantage.core.Category;
import dev.vantage.core.Chat;
import dev.vantage.core.Module;
import dev.vantage.core.util.Cooldown;
import dev.vantage.setting.BoolSetting;
import dev.vantage.setting.NumberSetting;
import dev.vantage.setting.TextSetting;
import net.minecraft.network.chat.Component;

/**
 * Falling into the void: sends your escape command once (/rtp by default) and, if you are still falling a few seconds later,
 * leaves the server so the death never happens. Only below the trigger height and only while actually falling.
 */
public class VoidRescue extends Module {
    public final NumberSetting below = add(new NumberSetting("Below Y", "Trigger height; the Overworld floor is -64", -75, -200, 0, 1));
    public final TextSetting command = add(new TextSetting("Command", "Sent once when you fall past the height", "/rtp"));
    public final BoolSetting leave = add(new BoolSetting("Leave if it fails", "Disconnect when you are still falling after the wait", true));
    public final NumberSetting leaveAfter = add(new NumberSetting("Wait", "Seconds between the command and leaving", 4, 1, 15, 0.5));

    private long firstSeen;
    private final Cooldown sendGate = Cooldown.wall(2000);
    /** For the rig. */
    public static int sends, leaves;

    public VoidRescue() {
        super("Void Rescue", "Falling into the void: /rtp, and leave the game if that fails", Category.DONUT);
        onByDefault();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) { firstSeen = 0; return; }
        boolean falling = mc.player.getY() < below.get() && mc.player.getDeltaMovement().y < -0.5 && !mc.player.isSpectator();
        if (!falling) { firstSeen = 0; return; }
        long now = System.currentTimeMillis();
        if (firstSeen == 0) {
            firstSeen = now;
            String c = command.text().trim();
            if (c.startsWith("/")) c = c.substring(1);
            if (!c.isEmpty() && sendGate.tryUse()) {
                dev.vantage.core.command.ServerCommands.send(this, c);
                sends++;
                Chat.info("Void: sent /" + c);
            }
            return;
        }
        if (leave.on() && now - firstSeen > leaveAfter.get() * 1000) {
            leaves++;
            Chat.info("Void: still falling, leaving the server");
            firstSeen = 0;
            mc.disconnectFromWorld(Component.literal("Vantage: left before the void got you"));
        }
    }
}
