package com.victorfaurschou.fasterboats;

public final class FasterBoatsConfig {
    // volatile: written by the command thread, read by the client tick thread.
    public static volatile boolean enabled = true;

    private FasterBoatsConfig() {
    }
}
