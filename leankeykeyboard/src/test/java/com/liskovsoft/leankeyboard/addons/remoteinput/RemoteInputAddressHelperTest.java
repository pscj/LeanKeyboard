package com.liskovsoft.leankeyboard.addons.remoteinput;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteInputAddressHelperTest {
    @Test
    public void getInterfacePriority_prefersWifiThenEthernet() {
        assertEquals(0, RemoteInputAddressHelper.getInterfacePriority("wlan0"));
        assertEquals(1, RemoteInputAddressHelper.getInterfacePriority("eth0"));
        assertEquals(2, RemoteInputAddressHelper.getInterfacePriority("rmnet_data0"));
    }

    @Test
    public void isUsableIpv4Address_acceptsLanAddressOnly() throws Exception {
        assertTrue(RemoteInputAddressHelper.isUsableIpv4Address(InetAddress.getByName("192.168.1.20")));
        assertFalse(RemoteInputAddressHelper.isUsableIpv4Address(InetAddress.getByName("127.0.0.1")));
        assertFalse(RemoteInputAddressHelper.isUsableIpv4Address(InetAddress.getByName("169.254.12.34")));
    }
}
