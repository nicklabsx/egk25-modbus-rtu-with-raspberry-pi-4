package org.example.egk25;

import java.util.Arrays;

final class Egk25Controller implements AutoCloseable {
    private final ModbusRtuClient rtu;
    private volatile boolean running = false;
    private Thread aliveThread;

    // ---- Firmware-5 relevante Bits ----
    private static final int BIT_FAST_STOP = 0;   // fail-safe low-active, für Betrieb = 1
    private static final int BIT_ACK       = 2;   // acknowledge (Puls)
    private static final int BIT_REPEAT    = 6;   // repeat command toggle
    private static final int BIT_ABS_POS   = 13;  // move to absolute position

    // ---- Statusbits (Firmware-5) ----
    private static final int ST_READY = 0;        // ready for operation
    private static final int ST_NOT_FEASIBLE = 3; // command not feasible
    private static final int ST_PROCESSED = 4;    // command successfully processed
    private static final int ST_CMD_TOGGLE = 5;   // command received toggle
    private static final int ST_WARNING = 6;
    private static final int ST_ERROR = 7;
    private static final int ST_POS_REACHED = 13; // position reached

    // Basis: Fast-Stop freigegeben
    private static final int CTRL_BASE      = (1 << BIT_FAST_STOP);                  // 0x00000001
    private static final int CTRL_ACK_PULSE = (1 << BIT_FAST_STOP) | (1 << BIT_ACK); // 0x00000005

    // ---- Demo-Zielwerte (in µm / µm/s) ----
    // EGK25 Hub pro Backe 26.5 mm -> ca. 26500 µm (Startwert)
    private static final int OPEN_POS_UM  = 26500; // ggf. anpassen/tauschen
    private static final int CLOSE_POS_UM = 0;     // ggf. anpassen/tauschen
    private static final int SPEED_UMS    = 40000; // 20 mm/s = 20000 µm/s

    // Zyklus-Sollwerte, die der Alive-Thread sendet
    private volatile int ctrl  = CTRL_BASE;
    private volatile int pos   = 0;
    private volatile int speed = 0;
    private volatile int force = 0;

    // für Bit6 toggle
    private boolean repeatToggle = false;

    Egk25Controller(ModbusRtuClient rtu) {
        this.rtu = rtu;
    }

    void startup() throws InterruptedException {
        System.out.println("=== STARTUP BEGIN (FW5) ===");

        // 1) Boot-Stabilisierung: immer CTRL_BASE senden (Fast-Stop freigeben)
        for (int i = 0; i < 10; i++) {
            sendOutputFrame(CTRL_BASE, 0, 0, 0);
            dumpSyncInput("BOOT base " + i);
            Thread.sleep(Egk25Config.CYCLE_MS);
        }

        // 2) ACK-Puls (Bit2) als Flanke, immer mit Bit0=1
        System.out.println("ACK pulse (0x00000005) ...");
        sendOutputFrame(CTRL_ACK_PULSE, 0, 0, 0);
        dumpSyncInput("after ACK=1");
        Thread.sleep(Egk25Config.CYCLE_MS);

        sendOutputFrame(CTRL_BASE, 0, 0, 0);
        dumpSyncInput("after ACK=0");
        Thread.sleep(Egk25Config.CYCLE_MS);

        // 3) Warten bis ready & kein error
        for (int i = 0; i < 30; i++) {
            long status = readStatusDword();
            boolean ready = bit(status, ST_READY);
            boolean err   = bit(status, ST_ERROR);
            System.out.printf("StatusDW=0x%08X [ready:%s err:%s]%n", status, ready ? "1" : "0", err ? "1" : "0");
            if (ready && !err) {
                System.out.println("=== STARTUP END (ready) ===");
                ctrl = CTRL_BASE;
                return;
            }
            Thread.sleep(Egk25Config.CYCLE_MS);
        }

        System.out.println("=== STARTUP END (timeout) ===");
        ctrl = CTRL_BASE;
    }

    void startAliveLoop() {
        if (running) return;
        running = true;

        aliveThread = new Thread(() -> {
            while (running) {
                try {
                    // Alive: zyklisch senden, sonst drohen COMM/STOP Zustände
                    sendOutputFrame(ctrl, pos, speed, force);
                    rtu.readHoldingRegisters(Egk25Config.PLC_SYNC_INPUT_START, 8);
                    Thread.sleep(Egk25Config.CYCLE_MS);
                } catch (Exception e) {
                    System.out.println("Alive loop error: " + e.getMessage());
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
        }, "egk-alive");
        aliveThread.setDaemon(true);
        aliveThread.start();
    }

    // ---- Einfache Open/Close Kommandos ----

    void openGripper() throws InterruptedException {
        moveAbsolute(OPEN_POS_UM, SPEED_UMS);
    }

    void closeGripper() throws InterruptedException {
        moveAbsolute(CLOSE_POS_UM, SPEED_UMS);
    }

    private void moveAbsolute(int targetPosUm, int velUms) throws InterruptedException {
        // Status vor dem Kommando merken (Command-Received-Toggle)
        long statusBefore = readStatusDword();
        boolean cmdToggleBefore = bit(statusBefore, ST_CMD_TOGGLE);

        // Re-Trigger (Control repeat toggle) + Absolute Position Bit
        repeatToggle = !repeatToggle;

        int c = CTRL_BASE | (1 << BIT_ABS_POS);
        if (repeatToggle) c |= (1 << BIT_REPEAT);

        // Setpoints anlegen (werden im Alive-Loop zyklisch gesendet)
        this.pos = targetPosUm;
        this.speed = velUms;
        this.force = 0;

        // ctrl setzen (Alive sendet das dann zyklisch)
        this.ctrl = c;

        System.out.printf("ABS MOVE -> pos=%dµm vel=%dµm/s ctrl=0x%08X (cmdToggleBefore=%s)%n",
                targetPosUm, velUms, c, cmdToggleBefore ? "1" : "0");

        // 1) Warten bis Greifer das Kommando angenommen hat (Toggle muss umspringen)
        boolean accepted = waitForCommandAccepted(cmdToggleBefore, 1500);
        if (!accepted) {
            System.out.println("Command NOT accepted within timeout (toggle did not change).");
            Thread.sleep(200);
        }

        // 2) Danach auf Bewegung fertig warten (Position reached + processed)
        waitForPositionReached(8000);

        // Danach wieder Baseline (Alive läuft weiter)
        this.ctrl = CTRL_BASE;
    }

    private boolean waitForCommandAccepted(boolean cmdToggleBefore, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            long status = readStatusDword();
            boolean err = bit(status, ST_ERROR);
            boolean cmdToggleNow = bit(status, ST_CMD_TOGGLE);

            if (err) {
                System.out.printf("Accept aborted: error bit7 set. Status=0x%08X%n", status);
                return false;
            }
            if (cmdToggleNow != cmdToggleBefore) {
                System.out.printf("Command accepted: cmdToggle %s -> %s (Status=0x%08X)%n",
                        cmdToggleBefore ? "1" : "0", cmdToggleNow ? "1" : "0", status);
                return true;
            }
            Thread.sleep(Egk25Config.CYCLE_MS);
        }
        return false;
    }

    private void waitForPositionReached(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            long status = readStatusDword();

            boolean err   = bit(status, ST_ERROR);
            boolean done  = bit(status, ST_PROCESSED);
            boolean posOk = bit(status, ST_POS_REACHED);
            boolean notFeas = bit(status, ST_NOT_FEASIBLE);

            if (err) {
                System.out.printf("Move aborted: error bit7 set. Status=0x%08X%n", status);
                return;
            }
            if (notFeas) {
                System.out.printf("Move not feasible (bit3). Status=0x%08X%n", status);
                // nicht sofort return; manchmal geht es nach kurzer Zeit weg
            }
            if (done && posOk) {
                System.out.printf("Move done: Status=0x%08X%n", status);
                return;
            }

            Thread.sleep(Egk25Config.CYCLE_MS);
        }
        System.out.println("Move wait timeout.");
    }

    // ---- Helpers ----

    private static boolean bit(long v, int b) {
        return ((v >>> b) & 1L) == 1L;
    }

    private long readStatusDword() {
        int[] in = readSyncInput();
        return getDwordLE(in, 0);
    }

    @SuppressWarnings("unused")
    private long readDiagDword() {
        int[] in = readSyncInput();
        return getDwordLE(in, 6);
    }

    private int[] readSyncInput() {
        int[] in = rtu.readHoldingRegisters(Egk25Config.PLC_SYNC_INPUT_START, 8);
        if (Egk25Config.SWAP16) {
            for (int i = 0; i < in.length; i++) in[i] = swap16(in[i]);
        }
        return in;
    }

    private void dumpSyncInput(String tag) {
        try {
            int[] in = readSyncInput();
            System.out.println(tag + " IN=" + regsToHex(in));
        } catch (Exception e) {
            System.out.println(tag + " IN=<read failed> " + e.getMessage());
        }
    }

    private void sendOutputFrame(int ctrlDword, int posDword, int speedDword, int forceDword) {
        int[] regs = new int[8];
        putDwordLE(regs, 0, ctrlDword);
        putDwordLE(regs, 2, posDword);
        putDwordLE(regs, 4, speedDword);
        putDwordLE(regs, 6, forceDword);

        if (Egk25Config.SWAP16) {
            for (int i = 0; i < regs.length; i++) regs[i] = swap16(regs[i]);
        }

        rtu.writeMultipleRegisters(Egk25Config.PLC_SYNC_OUTPUT_START, regs);
    }

    private static void putDwordLE(int[] regs, int idx, int value) {
        regs[idx]     = value & 0xFFFF;
        regs[idx + 1] = (value >>> 16) & 0xFFFF;
    }

    private static long getDwordLE(int[] regs, int idx) {
        long lo = regs[idx] & 0xFFFFL;
        long hi = regs[idx + 1] & 0xFFFFL;
        return lo | (hi << 16);
    }

    private static int swap16(int v) {
        v &= 0xFFFF;
        return ((v & 0xFF) << 8) | ((v >>> 8) & 0xFF);
    }

    private static String regsToHex(int[] regs) {
        StringBuilder sb = new StringBuilder();
        for (int r : regs) sb.append(String.format("%04X ", r & 0xFFFF));
        return sb.toString().trim();
    }

    @Override
    public void close() {
        running = false;
        if (aliveThread != null) aliveThread.interrupt();
    }
}

