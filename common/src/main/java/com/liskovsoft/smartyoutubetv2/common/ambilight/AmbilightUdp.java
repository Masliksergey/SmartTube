package com.liskovsoft.smartyoutubetv2.common.ambilight;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Відправка кадрів на ESP по UDP. Викликати лише з фонового потоку.
 */
public final class AmbilightUdp {
    private final String mHost;
    private final int mPort;
    private DatagramSocket mSocket;
    private InetAddress mAddress;

    public AmbilightUdp(String host, int port) {
        mHost = host;
        mPort = port;
    }

    public void send(byte[] data) throws IOException {
        try {
            if (mSocket == null) {
                mAddress = InetAddress.getByName(mHost);
                mSocket = new DatagramSocket();
            }
            mSocket.send(new DatagramPacket(data, data.length, mAddress, mPort));
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    public void close() {
        if (mSocket != null) {
            mSocket.close();
            mSocket = null;
        }
    }
}
