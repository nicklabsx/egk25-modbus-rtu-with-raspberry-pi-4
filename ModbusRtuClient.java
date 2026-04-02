package org.example.egk25;

import com.fazecast.jSerialComm.SerialPort;

import java.util.Arrays;
/// MODBUS RTU KOMMUNIKATION ////
final class ModbusRtuClient implements AutoCloseable {
    private SerialPort port;
    private final Object ioLock = new Object();

    void open() {
        port = SerialPort.getCommPort(Egk25Config.PORT);
        int parity = Egk25Config.PARITY_EVEN ? SerialPort.EVEN_PARITY : SerialPort.NO_PARITY;

        port.setComPortParameters(Egk25Config.BAUD, 8, SerialPort.ONE_STOP_BIT, parity);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 600, 600);
        port.flushIOBuffers();

        if (!port.openPort()) {
            throw new IllegalStateException("Cannot open port: " + Egk25Config.PORT);
        }
    }

    @Override
    public void close() {
        if (port != null) port.closePort();
        port = null;
    }

    int[] readHoldingRegisters(int start, int qty) {
        if (qty < 1 || qty > 125) throw new IllegalArgumentException("qty out of range");

        byte[] req = new byte[8];
        req[0] = (byte) Egk25Config.SLAVE_ID;
        req[1] = 0x03;
        req[2] = (byte) (start >> 8);
        req[3] = (byte) (start);
        req[4] = (byte) (qty >> 8);
        req[5] = (byte) (qty);
        int crc = crc16(req, 0, 6);
        req[6] = (byte) (crc & 0xFF);
        req[7] = (byte) ((crc >> 8) & 0xFF);

        byte[] resp = transactRead(req, 0x03, qty);

        // resp layout: [slave][func][byteCount][data...][crcLo][crcHi]
        int byteCount = resp[2] & 0xFF;
        if (byteCount != 2 * qty) throw new RuntimeException("Unexpected byteCount=" + byteCount);

        int[] out = new int[qty];
        for (int i = 0; i < qty; i++) {
            int hi = resp[3 + 2 * i] & 0xFF;
            int lo = resp[4 + 2 * i] & 0xFF;
            out[i] = (hi << 8) | lo;
        }
        return out;
    }

    void writeMultipleRegisters(int start, int[] regs) {
        int qty = regs.length;
        if (qty < 1 || qty > 123) throw new IllegalArgumentException("qty out of range");

        byte[] req = new byte[9 + 2 * qty];
        req[0] = (byte) Egk25Config.SLAVE_ID;
        req[1] = 0x10;
        req[2] = (byte) (start >> 8);
        req[3] = (byte) (start);
        req[4] = (byte) (qty >> 8);
        req[5] = (byte) (qty);
        req[6] = (byte) (2 * qty);

        for (int i = 0; i < qty; i++) {
            int v = regs[i] & 0xFFFF;
            req[7 + 2 * i] = (byte) (v >> 8);
            req[8 + 2 * i] = (byte) (v);
        }

        int crc = crc16(req, 0, 7 + 2 * qty);
        req[7 + 2 * qty] = (byte) (crc & 0xFF);
        req[8 + 2 * qty] = (byte) ((crc >> 8) & 0xFF);

        byte[] resp = transactRead(req, 0x10, 0); // 0 => fixed response for 0x10

        // success response: [slave][0x10][addrHi][addrLo][qtyHi][qtyLo][crcLo][crcHi]
        // exception: [slave][0x90][excCode][crcLo][crcHi] handled in transactRead
        if ((resp[1] & 0xFF) != 0x10) throw new RuntimeException("Unexpected write response func=" + (resp[1] & 0xFF));
    }

    private byte[] transactRead(byte[] req, int expectedFunc, int qtyFor03) {
        synchronized (ioLock) {
            port.flushIOBuffers();

            int w = (int) port.writeBytes(req, req.length);
            if (w != req.length) throw new RuntimeException("Write incomplete: " + w + "/" + req.length);

            // Read first 3 bytes to detect exception vs normal
            byte[] hdr = readExact(3, 800);
            int slave = hdr[0] & 0xFF;
            int func = hdr[1] & 0xFF;

            if (slave != (Egk25Config.SLAVE_ID & 0xFF)) {
                throw new RuntimeException("Slave mismatch: " + slave);
            }

            // Exception response: func = expectedFunc | 0x80, total length 5
            if ((func & 0x80) != 0) {
                byte[] tail = readExact(2, 800);
                byte[] resp = concat(hdr, tail);
                if (!crcOk(resp)) throw new RuntimeException("Bad CRC (exc) resp=" + toHex(resp));
                int exc = hdr[2] & 0xFF;
                throw new RuntimeException("Modbus exception code: " + exc);
            }

            if (func != expectedFunc) throw new RuntimeException("Unexpected func: " + func);

            int remaining;
            if (expectedFunc == 0x03) {
                int byteCount = hdr[2] & 0xFF;
                remaining = byteCount + 2; // data + crc
            } else if (expectedFunc == 0x10) {
                remaining = 5; // addrHi addrLo qtyHi qtyLo crcLo crcHi => after 3 bytes, 5 remain
            } else {
                throw new RuntimeException("Unsupported func in transactRead");
            }

            byte[] rest = readExact(remaining, 800);
            byte[] resp = concat(hdr, rest);
            if (!crcOk(resp)) throw new RuntimeException("Bad CRC resp=" + toHex(resp));
            return resp;
        }
    }

    private byte[] readExact(int len, int timeoutMs) {
        byte[] buf = new byte[len];
        int got = 0;
        long end = System.currentTimeMillis() + timeoutMs;

        while (got < len && System.currentTimeMillis() < end) {
            int remaining = len - got;
            byte[] tmp = new byte[remaining];
            int r = (int) port.readBytes(tmp, remaining);
            if (r > 0) {
                System.arraycopy(tmp, 0, buf, got, r);
                got += r;
            }
        }
        if (got != len) {
            throw new RuntimeException("Read timeout " + got + "/" + len + " data=" + toHex(Arrays.copyOf(buf, got)));
        }
        return buf;
    }

    private static boolean crcOk(byte[] frame) {
        if (frame.length < 3) return false;
        int crc = crc16(frame, 0, frame.length - 2);
        int lo = frame[frame.length - 2] & 0xFF;
        int hi = frame[frame.length - 1] & 0xFF;
        return lo == (crc & 0xFF) && hi == ((crc >> 8) & 0xFF);
    }

    private static int crc16(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[off + i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                if ((crc & 1) != 0) crc = (crc >> 1) ^ 0xA001;
                else crc >>= 1;
            }
        }
        return crc & 0xFFFF;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02X ", v));
        return sb.toString().trim();
    }
}

