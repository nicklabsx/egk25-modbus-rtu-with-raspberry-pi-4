package org.example.egk25;
//// Hier wird die Konfiguration des EGK25 eingegeben
final class Egk25Config {
    // Parameter 
    static final String PORT = "/dev/ttyACM0";   // ggf. /dev/ttyUSB0
    static final int SLAVE_ID = 10;

    static final int BAUD = 115200;
    static final boolean PARITY_EVEN = true;     // 8E1
    static final int CYCLE_MS = 200;             // Alive-Zyklus

    // Register Startadressen (0-based holding registers)
    static final int PLC_SYNC_INPUT_START  = 0x003F; // lesen (8 regs)
    static final int PLC_SYNC_OUTPUT_START = 0x0047; // schreiben (8 regs)

    // Falls Werte “vertauscht” wirken: true setzen (Byte-Swap pro 16-bit Register)
    static final boolean SWAP16 = true;

    private Egk25Config() { }
}

