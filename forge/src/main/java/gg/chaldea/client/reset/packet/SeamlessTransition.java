package gg.chaldea.client.reset.packet;

public class SeamlessTransition {

    public static volatile boolean active = false;

    public static void begin() {
        active = true;
    }

    public static void end() {
        active = false;
    }
}
