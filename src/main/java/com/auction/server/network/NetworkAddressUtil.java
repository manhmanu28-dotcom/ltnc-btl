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
import java.util.HashSet;
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
     * và bỏ qua các card ảo nội bộ chỉ dùng cho máy ảo (VirtualBox Host-Only, VMware, WSL, Hyper-V, Docker).
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
                if (isHostOnlyVirtualAdapter(displayName)) {
                    continue;
                }
                boolean vpnAdapter = isVpnLanAdapter(displayName);

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
                    uniqueByIp.putIfAbsent(ip, new InterfaceAddressInfo(displayName, ip, vpnAdapter));
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
     * Trả về danh sách địa chỉ UDP broadcast cho các card mạng LAN/Wi-Fi và VPN đang hoạt động
     * (bỏ qua card ảo máy ảo như VirtualBox/VMware/WSL để tránh trùng lặp 1 máy chủ ra nhiều IP).
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
                String displayName = ni.getDisplayName() != null ? ni.getDisplayName() : ni.getName();
                if (isHostOnlyVirtualAdapter(displayName)) {
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

    /**
     * Chấm điểm ưu tiên cho một IP phản hồi từ LAN Discovery (số nhỏ hơn = ưu tiên cao hơn):
     * 0: IP LAN/Wi-Fi thực (192.168.x.x không phải 192.168.56.x, 10.x.x.x, 172.16-31.x.x)
     * 1: IP VPN LAN (Radmin 26.x, Tailscale 100.x, ZeroTier)
     * 2: IP khác không phải loopback/card ảo
     * 3: IP card ảo nội bộ (VirtualBox Host-Only 192.168.56.x, VMware, WSL, Hyper-V)
     * 4: Loopback 127.0.0.1
     */
    public static int ipReachabilityPriority(String ip) {
        if (ip == null || ip.isBlank() || "127.0.0.1".equals(ip) || "::1".equals(ip)) {
            return 4;
        }
        if (isKnownHostOnlyIp(ip)) {
            return 3;
        }
        if (ip.startsWith("26.") || ip.startsWith("100.")) {
            return 1;
        }
        if (ip.startsWith("192.168.") || ip.startsWith("10.") || isPrivate172Range(ip)) {
            return 0;
        }
        return 2;
    }

    private static boolean isKnownHostOnlyIp(String ip) {
        if (ip.startsWith("192.168.56.")) {
            // Dải mặc định của VirtualBox Host-Only Adapter
            return true;
        }
        return localHostOnlyVirtualIps().contains(ip);
    }

    private static Set<String> localHostOnlyVirtualIps() {
        Set<String> virtualIps = new HashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                String displayName = ni.getDisplayName() != null ? ni.getDisplayName() : ni.getName();
                if (!isHostOnlyVirtualAdapter(displayName)) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        virtualIps.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return virtualIps;
    }

    private static boolean isPrivate172Range(String ip) {
        if (!ip.startsWith("172.")) {
            return false;
        }
        String[] parts = ip.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) {
            return false;
        }
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

    /**
     * Card mạng ảo cục bộ dùng cho máy ảo/container (VirtualBox, VMware, Hyper-V, WSL, Docker) —
     * máy khác cùng Wi-Fi không thể kết nối vào các IP này.
     */
    public static boolean isHostOnlyVirtualAdapter(String displayName) {
        if (displayName == null) {
            return false;
        }
        String lower = displayName.toLowerCase(Locale.ROOT);
        return lower.contains("virtualbox")
                || lower.contains("vbox")
                || lower.contains("host-only")
                || lower.contains("vmware")
                || lower.contains("hyper-v")
                || lower.contains("vethernet")
                || lower.contains("wsl")
                || lower.contains("docker")
                || lower.contains("loopback")
                || lower.contains("npcap")
                || lower.contains("km-test");
    }

    private static boolean isVpnLanAdapter(String displayName) {
        if (displayName == null) {
            return false;
        }
        String lower = displayName.toLowerCase(Locale.ROOT);
        return lower.contains("radmin")
                || lower.contains("tailscale")
                || lower.contains("zerotier")
                || lower.contains("hamachi")
                || lower.contains("wireguard")
                || lower.contains("vpn")
                || lower.contains("virtual");
    }
}
