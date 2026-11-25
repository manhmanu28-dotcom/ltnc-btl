package com.auction.server.network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * NetworkAddressUtil: Tiện ích dò tìm địa chỉ IPv4 LAN/Wi-Fi, VPN và địa chỉ Broadcast trên máy hiện tại.
 * Phục vụ cả việc hiển thị địa chỉ kết nối trên Server và quét mạng LAN từ Client.
 */
public final class NetworkAddressUtil {

    public record InterfaceAddressInfo(String interfaceName, String hostAddress, boolean virtualAdapter) {
    }

    private NetworkAddressUtil() {
    }

    /**
     * Liệt kê các địa chỉ IPv4 khả dụng (Wi-Fi, Ethernet, Radmin VPN, Tailscale...)
     * và ưu tiên đặt địa chỉ LAN thực (Wi-Fi/Ethernet) lên trước.
     */
    public static List<InterfaceAddressInfo> listLanIpv4Addresses() {
        Map<String, InterfaceAddressInfo> uniqueByIp = new LinkedHashMap<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String displayName = ni.getDisplayName() != null ? ni.getDisplayName() : ni.getName();
                boolean virtual = isLikelyVirtualAdapter(displayName);

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!(addr instanceof Inet4Address)) {
                        continue;
                    }
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    uniqueByIp.putIfAbsent(ip, new InterfaceAddressInfo(displayName, ip, virtual));
                }
            }
        } catch (SocketException ignored) {
            return List.of();
        }

        List<InterfaceAddressInfo> result = new ArrayList<>(uniqueByIp.values());
        result.sort(Comparator.comparingInt(NetworkAddressUtil::priorityScore));
        return result;
    }

    /**
     * Trả về danh sách địa chỉ UDP broadcast cho mọi card mạng đang hoạt động
     * (luôn bao gồm 255.255.255.255 và 127.0.0.1 để hỗ trợ test trên cùng máy).
     */
    public static List<InetAddress> listBroadcastAddresses() {
        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ifaceAddr : ni.getInterfaceAddresses()) {
                    InetAddress addr = ifaceAddr.getAddress();
                    InetAddress broadcast = ifaceAddr.getBroadcast();
                    if (addr instanceof Inet4Address
                            && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()
                            && broadcast != null) {
                        targets.add(broadcast);
                    }
                }
            }
        } catch (SocketException ignored) {
            // Tiếp tục dùng địa chỉ broadcast mặc định bên dưới
        }

        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (UnknownHostException ignored) {
        }
        try {
            targets.add(InetAddress.getByName("127.0.0.1"));
        } catch (UnknownHostException ignored) {
        }
        return new ArrayList<>(targets);
    }

    public static String resolveLocalHostName() {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            if (hostName != null && !hostName.trim().isEmpty()) {
                return hostName.trim();
            }
        } catch (UnknownHostException ignored) {
        }
        return "Auction-Server";
    }

    private static int priorityScore(InterfaceAddressInfo info) {
        String name = info.interfaceName().toLowerCase(Locale.ROOT);
        String ip = info.hostAddress();
        if (!info.virtualAdapter() && (name.contains("wi-fi") || name.contains("wifi") || name.contains("wireless"))) {
            return 0;
        }
        if (!info.virtualAdapter() && (ip.startsWith("192.168.") || ip.startsWith("10."))) {
            return 1;
        }
        if (!info.virtualAdapter()) {
            return 2;
        }
        if (name.contains("radmin") || name.contains("tailscale") || name.contains("zerotier") || ip.startsWith("26.")) {
            return 3;
        }
        return 4;
    }

    private static boolean isLikelyVirtualAdapter(String displayName) {
        String lower = displayName.toLowerCase(Locale.ROOT);
        return lower.contains("virtual")
                || lower.contains("vmware")
                || lower.contains("hyper-v")
                || lower.contains("wsl")
                || lower.contains("vbox")
                || lower.contains("docker")
                || lower.contains("radmin")
                || lower.contains("tailscale")
                || lower.contains("zerotier")
                || lower.contains("vpn")
                || lower.contains("loopback");
    }
}
