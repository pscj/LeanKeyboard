package com.liskovsoft.leankeyboard.addons.remoteinput;

import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

public final class RemoteInputAddressHelper {
    private static final String TAG = "RemoteInputAddress";

    private RemoteInputAddressHelper() {
    }

    public static String findLocalIpv4Address() {
        List<AddressCandidate> candidates = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            if (interfaces == null) {
                return null;
            }

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();

                if (!isUsableInterface(networkInterface)) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();

                    if (isUsableIpv4Address(address)) {
                        candidates.add(new AddressCandidate(
                                networkInterface.getName(),
                                address.getHostAddress(),
                                getInterfacePriority(networkInterface.getName())
                        ));
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "Unable to inspect local interfaces", e);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        Collections.sort(candidates, Comparator.comparingInt((AddressCandidate candidate) -> candidate.priority));

        return candidates.get(0).address;
    }

    static int getInterfacePriority(String interfaceName) {
        if (interfaceName == null) {
            return Integer.MAX_VALUE;
        }

        String normalizedName = interfaceName.toLowerCase();

        if (normalizedName.startsWith("wlan") || normalizedName.startsWith("wifi")) {
            return 0;
        }

        if (normalizedName.startsWith("eth") || normalizedName.startsWith("en")) {
            return 1;
        }

        return 2;
    }

    static boolean isUsableIpv4Address(InetAddress address) {
        return address instanceof Inet4Address &&
                !address.isAnyLocalAddress() &&
                !address.isLoopbackAddress() &&
                !address.isLinkLocalAddress() &&
                !address.isMulticastAddress();
    }

    private static boolean isUsableInterface(NetworkInterface networkInterface) throws SocketException {
        return networkInterface != null &&
                networkInterface.isUp() &&
                !networkInterface.isLoopback() &&
                !networkInterface.isVirtual();
    }

    private static final class AddressCandidate {
        private final String interfaceName;
        private final String address;
        private final int priority;

        private AddressCandidate(String interfaceName, String address, int priority) {
            this.interfaceName = interfaceName;
            this.address = address;
            this.priority = priority;
        }
    }
}
