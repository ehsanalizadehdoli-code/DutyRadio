package com.example.dutyradio;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;

import java.util.Map;
import java.util.UUID;

public class RadioVoicePlugin implements VoicechatPlugin {

    private final DutyRadioPlugin plugin;

    public RadioVoicePlugin(DutyRadioPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getPluginId() {
        return "dutyradio";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    // این متد روی ترد صوتی اجرا میشه، پس فقط از کش (states) می‌خونیم و به Bukkit API دست نمیزنیم
    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection senderConn = event.getSenderConnection();
        if (senderConn == null || senderConn.getPlayer() == null) return;

        MicrophonePacket packet = event.getPacket();
        if (packet.isWhispering() && !plugin.isTransmitWhispers()) return;

        UUID senderId = senderConn.getPlayer().getUuid();
        DutyRadioPlugin.State sender = plugin.getStates().get(senderId);
        if (sender == null || sender.tx <= 0) return;

        VoicechatServerApi api = event.getVoicechat();
        StaticSoundPacket out = packet.staticSoundPacketBuilder().build();

        double skip = plugin.getNearbySkip();
        double skip2 = skip * skip;

        for (Map.Entry<UUID, DutyRadioPlugin.State> entry : plugin.getStates().entrySet()) {
            UUID rid = entry.getKey();
            if (rid.equals(senderId)) continue;

            DutyRadioPlugin.State receiver = entry.getValue();
            if (!receiver.rx.contains(sender.tx)) continue;

            if (skip > 0 && receiver.world.equals(sender.world)) {
                double dx = receiver.x - sender.x;
                double dy = receiver.y - sender.y;
                double dz = receiver.z - sender.z;
                if (dx * dx + dy * dy + dz * dz <= skip2) continue;
            }

            VoicechatConnection rc = api.getConnectionOf(rid);
            if (rc == null) continue;
            api.sendStaticSoundPacketTo(rc, out);
        }
    }
}
